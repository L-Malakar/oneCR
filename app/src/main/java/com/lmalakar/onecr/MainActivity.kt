package com.lmalakar.onecr

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import com.lmalakar.onecr.core.designsystem.theme.OneCRTheme
import com.lmalakar.onecr.service.detection.CallDetectionService
import com.lmalakar.onecr.service.detection.DetectionContract
import com.lmalakar.onecr.ui.OneCRApp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as OneCRApplication).container

        // Auto-start detection after onboarding when the user opted in.
        if (container.settings.onboardingCompleted && container.settings.detectionAutostart) {
            runCatching {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, CallDetectionService::class.java)
                        .setAction(DetectionContract.ACTION_START),
                )
            }
        }

        setContent {
            OneCRTheme {
                OneCRApp(container = container)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from Shizuku / settings: refresh the bridge health right away.
        (application as OneCRApplication).container.privilegeMonitor.refreshAsync()
    }
}
