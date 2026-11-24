package com.lmalakar.onecr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.lmalakar.onecr.service.detection.CallDetectionService
import com.lmalakar.onecr.service.detection.DetectionContract

/**
 * v0.2.6 — the other half of the "24×7 watcher" promise. When the phone
 * reboots, Android drops every service; this receiver restarts the call
 * watcher so the "Watching for calls" notification (and detection with it)
 * is back within seconds of boot, without the user opening the app.
 *
 * It only acts when the user opted in during onboarding (wizard completed +
 * autostart enabled); BOOT_COMPLETED is a protected system broadcast, so no
 * third-party app can fake it, and it is on the platform's allowlist of
 * valid foreground-service start triggers.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val container = (context.applicationContext as? OneCRApplication)?.container ?: return
        if (container.settings.onboardingCompleted && container.settings.detectionAutostart) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, CallDetectionService::class.java)
                        .setAction(DetectionContract.ACTION_START),
                )
            }
        }
    }
}
