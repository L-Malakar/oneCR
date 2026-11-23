package com.lmalakar.onecr.service.detection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Stable string contracts for the detection foreground service.
 */
object DetectionContract {
    const val ACTION_START = "com.lmalakar.onecr.action.DETECTION_START"
    const val ACTION_STOP = "com.lmalakar.onecr.action.DETECTION_STOP"

    const val NOTIFICATION_CHANNEL = "detection"
    const val NOTIFICATION_ID = 42
}

/**
 * Bridge the service uses to reach the app's single [DetectionEngine].
 * :app sets [engineProvider] at process start (AppContainer); the service module
 * must not depend on the app module, hence the inversion. Hilt can replace this
 * hook when the graph grows.
 */
object DetectionServiceHost {
    var engineProvider: (() -> DetectionEngine)? = null
}

/**
 * Foreground service keeping call detection alive while the app is closed
 * (Android 14+ kills background listeners otherwise). Uses the specialUse FGS
 * type with an honest sub-type — the proper phoneCall/microphone types arrive
 * with the recorder service in M3.
 */
class CallDetectionService : Service() {

    private var engine: DetectionEngine? = null
    private var stateJob: Job? = null
    private val serviceScope = CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val detection = DetectionServiceHost.engineProvider?.invoke()
        if (detection == null) {
            stopSelf()
            return
        }
        engine = detection
        createChannel()
        // A failed startForeground must stop the service or Android kills the
        // process (ForegroundServiceDidNotStartInTime). The failure is logged
        // as an error event so the notification center can show it (v0.2.2).
        val foregroundOk = runCatching { startInForeground() }
            .onFailure { e -> detection.logError("Foreground service failed to start: ${e.message}") }
            .isSuccess
        if (!foregroundOk) {
            stopSelf()
            return
        }
        runCatching { detection.start() }
            .onFailure { e -> detection.logError("Detection engine failed to start: ${e.message}") }

        stateJob = serviceScope.launch {
            detection.callState.collect { state ->
                updateNotification(describe(state))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            DetectionContract.ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // START_STICKY: if the system ever kills the watcher process, it is
        // restarted with a null intent and onCreate re-asserts the foreground
        // notification and the engine — the 24×7 watch resumes on its own.
        return START_STICKY
    }

    /**
     * v0.2.6 — the fix for "the 'Watching for calls' notification disappears
     * when I close the app". Swiping the app from Recents (or closing it from
     * a launcher) removes the task but must NOT remove the watcher: the
     * foreground service stays alive, the engine keeps listening, and the
     * notification is re-asserted because some OEM task managers clear the
     * notification together with the task.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        engine?.logService("App closed — detection keeps running in the background")
        runCatching { startInForeground() }
            .onFailure { e ->
                engine?.logError("Could not re-assert the watcher notification: ${e.message}")
            }
    }

    override fun onDestroy() {
        stateJob?.cancel()
        engine?.stop()
        serviceScope.cancel()
        engine = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------

    private fun startInForeground() {
        val notification = buildNotification("Watching for calls")
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this,
                DetectionContract.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(DetectionContract.NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { nm.notify(DetectionContract.NOTIFICATION_ID, buildNotification(text)) }
            .onFailure { e -> engine?.logError("Could not update the detection notification: ${e.message}") }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, DetectionContract.NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_detection)
            .setContentTitle("oneCR — call detection")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                DetectionContract.NOTIFICATION_CHANNEL,
                "Call detection",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows that oneCR is watching for calls so detection survives in the background."
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    private fun describe(state: LiveCallState): String = when (state) {
        is LiveCallState.Idle -> "Watching for calls"
        is LiveCallState.CellularRinging -> "Incoming call…"
        is LiveCallState.CellularActive -> "Cellular call in progress"
        is LiveCallState.VoipActive -> "VoIP call in progress (${state.appLabel ?: state.packageName})"
    }
}
