package com.lmalakar.onecr.service.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
 * The M3 capture foreground service (microphone type, v0.3.3).
 *
 * Runs while a capture session is live: Android shows the mic privacy chip
 * because the service declares `foregroundServiceType="microphone"`, and the
 * persistent notification carries a Stop action. The service does NOT own the
 * audio — [RecordingEngine] does; this is the visible, system-required shell
 * around it. It stops itself when the engine returns to Idle.
 */
class RecordingService : Service() {

    private var engine: RecordingEngine? = null
    private var stateJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        engine = RecorderHost.engineProvider?.invoke() ?: run {
            stopSelf()
            return
        }
        createChannel()
        startInForeground("Preparing capture…")

        stateJob = serviceScope.launch {
            engine?.state?.collect { state ->
                when (state) {
                    is RecordingEngine.UiState.Idle -> stopSelf()
                    is RecordingEngine.UiState.Recording ->
                        updateNotification(
                            "Recording via ${state.source.name.lowercase().replace('_', ' ')} — " +
                                describeTrigger(state.trigger),
                        )

                    is RecordingEngine.UiState.Finalizing ->
                        updateNotification("Saving recording…")

                    is RecordingEngine.UiState.Failed -> stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            RecorderContract.ACTION_STOP_RECORDING -> {
                engine?.stop()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // START_STICKY: a killed process restarts the service; the engine hook
        // re-asserts the notification (the pump loop keeps writing meanwhile).
        return START_STICKY
    }

    override fun onDestroy() {
        stateJob?.cancel()
        serviceScope.cancel()
        engine = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------

    private fun describeTrigger(trigger: String): String = when (trigger) {
        RecordingJournal.TRIGGER_AUTO -> "auto capture"
        else -> "manual capture"
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, RecorderContract.NOTIFICATION_CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_recording)
            .setContentTitle("Recording call audio")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(openAppIntent())
            .addAction(
                0,
                "Stop",
                android.app.PendingIntent.getService(
                    this,
                    1,
                    Intent(this, RecordingService::class.java)
                        .setAction(RecorderContract.ACTION_STOP_RECORDING),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    private fun openAppIntent(): android.app.PendingIntent {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
        return android.app.PendingIntent.getActivity(
            this,
            2,
            launch,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun startInForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(
                this,
                RecorderContract.NOTIFICATION_ID_RECORDING,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(RecorderContract.NOTIFICATION_ID_RECORDING, notification)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(RecorderContract.NOTIFICATION_ID_RECORDING, buildNotification(text))
    }

    private fun createChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            RecorderContract.NOTIFICATION_CHANNEL_RECORDING,
            "Recording",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while oneCR records call audio"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
