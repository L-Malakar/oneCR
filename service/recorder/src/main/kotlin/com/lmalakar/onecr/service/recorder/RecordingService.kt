package com.lmalakar.onecr.service.recorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lmalakar.onecr.core.model.CallDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service backing the recorder (M3).
 *
 * Two contracts on the same notification (v0.3.7):
 *  - shell sessions (cellular capture / Record-now): the app process itself
 *    never opens the microphone — the privileged capture process does — so the
 *    service runs as `specialUse`, as since v0.3.6;
 *  - armed VoIP capture: the service must run with the `mediaProjection`
 *    foreground type BEFORE the engine claims the projection (Android 14
 *    requirement), plus `microphone` when RECORD_AUDIO is granted. It parks on
 *    an "armed" notice and stays up across VoIP sessions until disarmed.
 */
class RecordingService : Service() {

    private var engine: RecordingEngine? = null
    private var stateJob: Job? = null
    private var armedJob: Job? = null
    private var sawRecording = false
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        engine = RecorderServiceHost.engineProvider?.invoke()
        createChannel()
        // startForeground happens in onStartCommand: the intent action decides
        // the foreground type (specialUse vs mediaProjection|microphone).

        stateJob = serviceScope.launch {
            engine?.state?.collect { state ->
                when (state) {
                    is RecordingEngine.UiState.Recording -> {
                        sawRecording = true
                        updateNotification(describe(state))
                    }

                    is RecordingEngine.UiState.Finalizing -> updateNotification("Saving recording…")
                    is RecordingEngine.UiState.Idle -> onIdleState()
                    is RecordingEngine.UiState.Failed -> if (sawRecording) stopSelf()
                }
            }
        }
        armedJob = serviceScope.launch {
            engine?.voipArmed?.collect { armed ->
                if (armed && engine?.state?.value is RecordingEngine.UiState.Idle) {
                    updateNotification(ARMED_NOTICE)
                }
                if (!armed && engine?.state?.value !is RecordingEngine.UiState.Recording) {
                    // Disarmed outside a session — nothing left to guard.
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            RecorderContract.ACTION_ARM_VOIP -> {
                if (!startForegroundForVoip() || engine == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                // Foreground contract satisfied — the engine may now claim the
                // projection (Android 14 requires this order).
                engine?.onServiceForegroundReady()
            }

            RecorderContract.ACTION_STOP_RECORDING -> {
                if (!startForegroundForShell() || engine == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                engine?.stop()
            }

            else -> {
                if (!startForegroundForShell() || engine == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        // NOT_STICKY: a restarted-but-empty recorder service would show a stale
        // notification; the engine re-starts it on the next real session.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stateJob?.cancel()
        armedJob?.cancel()
        serviceScope.cancel()
        engine = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------

    private fun onIdleState() {
        if (engine?.voipArmed?.value == true) {
            // Still armed: park on the armed notice, ready for the next call.
            sawRecording = false
            updateNotification(ARMED_NOTICE)
        } else if (sawRecording) {
            stopSelf()
        }
    }

    private fun startForegroundForShell(): Boolean = runCatching {
        val notification = buildNotification("Preparing capture…")
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this,
                RecorderContract.NOTIFICATION_ID_RECORDING,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(RecorderContract.NOTIFICATION_ID_RECORDING, notification)
        }
        true
    }.getOrDefault(false)

    /**
     * The armed-VoIP foreground contract: mediaProjection (the consent the
     * user just gave) plus microphone when RECORD_AUDIO is granted. Starting
     * with a type the app cannot back up throws — hence the permission check.
     */
    private fun startForegroundForVoip(): Boolean = runCatching {
        val notification = buildNotification(ARMED_NOTICE)
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            ServiceCompat.startForeground(
                this,
                RecorderContract.NOTIFICATION_ID_RECORDING,
                notification,
                type,
            )
        } else {
            startForeground(RecorderContract.NOTIFICATION_ID_RECORDING, notification)
        }
        true
    }.getOrDefault(false)

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { nm.notify(RecorderContract.NOTIFICATION_ID_RECORDING, buildNotification(text)) }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, RecordingService::class.java).setAction(RecorderContract.ACTION_STOP_RECORDING),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, RecorderContract.NOTIFICATION_CHANNEL_RECORDING)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("oneCR — recording")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setUsesChronometer(true)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                RecorderContract.NOTIFICATION_CHANNEL_RECORDING,
                "Recording",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows while oneCR is recording a call, with a Stop button."
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    private fun describe(state: RecordingEngine.UiState.Recording): String = when {
        state.direction == CallDirection.INCOMING -> "Recording incoming call"
        state.direction == CallDirection.OUTGOING -> "Recording outgoing call"
        state.trigger == RecorderContract.TRIGGER_AUTO ->
            "Recording call (${state.sourceLabel.lowercase()})"
        else -> "Recording (${state.sourceLabel.lowercase()})"
    }

    private companion object {
        const val ARMED_NOTICE = "VoIP capture armed — internet calls will be recorded"
    }
}
