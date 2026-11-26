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
import com.lmalakar.onecr.update.UpdateCoordinator

/**
 * v0.2.4: the host activity is now an AppCompatActivity carrying a DayNight
 * theme, and the platform night mode is synced to the in-app theme setting.
 * Before this release the Compose palette followed the theme switch, but the
 * window behind it (the setup wizard draws no background of its own), the
 * system bars and platform dialogs kept the OS day/night configuration —
 * which is why pinning Dark still showed light surfaces on some screens.
 *
 * v0.3.3: singleTask + the show-update-page intent extra, so a tap on the
 * update notification (or its Update action) brings this activity forward
 * on the update page instead of stacking a second copy. The update check
 * runs on every app open; if the user just came back from the "Install
 * unknown apps" screen with the grant, the APK download they asked for
 * continues automatically (see UpdateCoordinator.onAppResumed).
 */
class MainActivity : AppCompatActivity() {

    private var handledIntentHash: Int = 0

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

        // v0.3.3: check GitHub for a newer release on every app open.
        container.updateCoordinator.checkOnLaunch()
        handleUpdateIntent(intent)

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask: the notification tap lands here while we are alive.
        handleUpdateIntent(intent)
    }

    private fun handleUpdateIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(UpdateCoordinator.EXTRA_SHOW_UPDATE_PAGE, false) == true) {
            val hash = intent.hashCode()
            if (hash != handledIntentHash) {
                handledIntentHash = hash
                (application as OneCRApplication).container.updateCoordinator.openFromNotification()
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
        // v0.3.3: back from the "Install unknown apps" grant screen with the
        // grant in hand → the requested download continues on its own.
        container.updateCoordinator.onAppResumed()
    }
}
