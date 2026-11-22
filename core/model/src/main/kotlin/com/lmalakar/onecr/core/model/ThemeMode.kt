package com.lmalakar.onecr.core.model

/**
 * App theme preference.
 *
 * SYSTEM follows the OS dark-mode setting (the default); LIGHT and DARK pin the
 * palette regardless of the system setting. Persisted as its [storageKey] by
 * [com.lmalakar.onecr.data.files.AppSettingsStore].
 */
enum class ThemeMode(val storageKey: String, val label: String) {
    SYSTEM("SYSTEM", "System default"),
    LIGHT("LIGHT", "Light"),
    DARK("DARK", "Dark");

    companion object {
        /** Decodes a persisted value; unknown values fall back to SYSTEM. */
        fun fromStorageKey(raw: String?): ThemeMode =
            entries.firstOrNull { it.storageKey == raw } ?: SYSTEM
    }
}
