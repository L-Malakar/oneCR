package com.lmalakar.onecr

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.lmalakar.onecr.core.model.ThemeMode

/**
 * Maps the in-app [ThemeMode] preference onto the platform night mode
 * (v0.2.4). With this sync in place the *whole* stack follows the setting —
 * the Compose palette (via [OneCRTheme] in MainActivity), the window
 * background behind the setup wizard, the system bars and every platform
 * dialog — instead of only the Compose layer following the OS setting.
 */
fun applyPlatformNightMode(mode: ThemeMode) {
    AppCompatDelegate.setDefaultNightMode(
        when (mode) {
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        },
    )
}

class OneCRApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Apply the persisted theme before the first activity renders so a
        // Dark-pinned app no longer flashes its light window background.
        applyPlatformNightMode(container.settings.themeMode)
    }
}
