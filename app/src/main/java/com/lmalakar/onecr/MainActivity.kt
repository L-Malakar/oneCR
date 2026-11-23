package com.lmalakar.onecr

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.lmalakar.onecr.core.designsystem.motion.LocalMotionEnabled
import com.lmalakar.onecr.core.designsystem.theme.OneCRTheme
import com.lmalakar.onecr.core.model.ThemeMode
import com.lmalakar.onecr.service.detection.CallDetectionService
import com.lmalakar.onecr.service.detection.DetectionContract
import com.lmalakar.onecr.ui.OneCRApp

/**
 * v0.2.4: the host activity is now an AppCompatActivity carrying a DayNight
 * theme, and the platform night mode is synced to the in-app theme setting.
 * Before this release the Compose palette followed the theme switch, but the
 * window behind it (the setup wizard draws no background of its own), the
 * system bars and platform dialogs kept the OS day/night configuration —
 * which is why pinning Dark still showed light surfaces on some screens.
 */
class MainActivity : AppCompatActivity() {

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
            // v0.2.1 theme setting: SYSTEM follows the OS, LIGHT/DARK are pinned.
            // Reading container.themeMode here recomposes the whole tree on change.
            val themeMode by container.themeMode
            // v0.2.6 motion setting: provided once here so every transition,
            // crossfade and pulse in the app honors the Animations switch.
            val animationsEnabled by container.animationsEnabled
            OneCRTheme(
                darkTheme = when (themeMode) {
                    ThemeMode.DARK -> true
                    ThemeMode.LIGHT -> false
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                },
            ) {
                CompositionLocalProvider(LocalMotionEnabled provides animationsEnabled) {
                    OneCRApp(container = container)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val container = (application as OneCRApplication).container
        // Returning from Shizuku / system permission screens: re-read the OS state
        // and refresh the bridge health + notification center right away.
        container.refreshPermissions()
        container.notificationCenter.refresh()
        container.resumeTick.intValue++
        container.privilegeMonitor.refreshAsync()
    }
}
