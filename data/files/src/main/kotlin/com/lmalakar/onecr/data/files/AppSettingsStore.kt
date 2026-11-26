package com.lmalakar.onecr.data.files

import android.content.Context
import com.lmalakar.onecr.core.model.ThemeMode

/**
 * Tiny key-value settings store (SharedPreferences) for app-level flags.
 * Not a settings UI — just persisted booleans/strings the app needs across runs.
 */
class AppSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("onecr_settings", Context.MODE_PRIVATE)

    /** True once the user completed the onboarding wizard (gates the main UI). */
    var onboardingCompleted: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_DONE, value).apply()

    /** Privilege mode chosen during onboarding ("SHIZUKU"; embedded ADB joins in v0.3.0). */
    var privilegeMode: String?
        get() = prefs.getString(KEY_PRIVILEGE_MODE, null)
        set(value) = prefs.edit().putString(KEY_PRIVILEGE_MODE, value).apply()

    /** Start the detection service automatically on app launch (after onboarding). */
    var detectionAutostart: Boolean
        get() = prefs.getBoolean(KEY_DETECTION_AUTOSTART, false)
        set(value) = prefs.edit().putBoolean(KEY_DETECTION_AUTOSTART, value).apply()

    /** Theme preference; SYSTEM (follow the OS) is the default. */
    var themeMode: ThemeMode
        get() = ThemeMode.fromStorageKey(prefs.getString(KEY_THEME_MODE, null))
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value.storageKey).apply()

    /**
     * Master motion switch (v0.2.6). ON by default: transitions, crossfades
     * and idle pulses run everywhere. Turning it off makes the UI swap
     * instantly — the state lives here so it survives restarts.
     */
    var animationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_ANIMATIONS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ANIMATIONS_ENABLED, value).apply()

    /**
     * Timestamp (epoch millis) the in-app notification center was last opened.
     * Events newer than this count towards the top-bar unread badge (v0.2.2).
     */
    var notificationLastSeen: Long
        get() = prefs.getLong(KEY_NOTIFICATION_LAST_SEEN, 0L)
        set(value) = prefs.edit().putLong(KEY_NOTIFICATION_LAST_SEEN, value).apply()

    private companion object {
        const val KEY_ONBOARDING_DONE = "onboarding_completed"
        const val KEY_PRIVILEGE_MODE = "privilege_mode"
        const val KEY_DETECTION_AUTOSTART = "detection_autostart"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_ANIMATIONS_ENABLED = "animations_enabled"
        const val KEY_NOTIFICATION_LAST_SEEN = "notification_last_seen"
    }
}

