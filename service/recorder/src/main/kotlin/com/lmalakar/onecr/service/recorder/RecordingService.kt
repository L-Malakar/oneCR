package com.lmalakar.onecr.service.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service showing the "Recording" notification with a live timer
 * and a Stop action while a shell capture session runs (M3).
 *
 * Uses the specialUse FGS type (like the detection service): the app process
 * itself never opens the microphone — the capture runs in the privileged shell
 * process — so the microphone-typed FGS restrictions do not apply here, and
 * the type becomes microphone/mediaProjection only when the in-app VoIP mix
 * lands in the next M3 step.
 */
class RecordingService : Service() {

    private var engine: RecordingEngine? = null
    private var stateJob: Job? = null
    private var sawRecording = false
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        engine = RecorderServiceHost.engineProvider?.invoke()
        createChannel()
        // A failed startForeground must stop the service or Android kills the
        // process (ForegroundServiceDidNotStartInTime) — same guard as detection.
        val foregroundOk = runCatching { startInForeground() }.isSuccess
        if (!foregroundOk || engine == null) {
            stopSelf()
            return
        }

        stateJob = serviceScope.launch {
            engine?.state?.collect { state ->
                when (state) {
                    is RecordingEngine.UiState.Recording -> {
                        sawRecording = true
                        updateNotification(describe(state))
                    }

                    is RecordingEngine.UiState.Finalizing -> updateNotification("Saving recording…")
                    is RecordingEngine.UiState.Idle -> if (sawRecording) stopSelf()
                    is RecordingEngine.UiState.Failed -> if (sawRecording) stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            RecorderContract.ACTION_STOP_RECORDING -> engine?.stop()
        }
        // NOT_STICKY: a restarted-but-empty recorder service would show a stale
        // notification; the engine re-starts it on the next real session.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stateJob?.cancel()
        serviceScope.cancel()
        engine = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------

    private fun startInForeground() {
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
    }

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
        state.direction == com.lmalakar.onecr.core.model.CallDirection.INCOMING -> "Recording incoming call"
        state.direction == com.lmalakar.onecr.core.model.CallDirection.OUTGOING -> "Recording outgoing call"
        state.trigger == RecordingJournal.TRIGGER_AUTO -> "Recording call (${state.source.name.lowercase()})"
        else -> "Recording (${state.source.name.lowercase()})"
    }
}
