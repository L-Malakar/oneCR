package com.lmalakar.onecr.service.detection

/**
 * VoIP packages oneCR watches for call notifications (mirrors PRIVACY.md —
 * a fixed allowlist, no QUERY_ALL_PACKAGES, nothing outside this list is read).
 */
object VoipPackages {

    val ALLOWLIST: Set<String> = setOf(
        "com.whatsapp",
        "com.facebook.orca", // Messenger
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.thoughtcrime.securesms", // Signal
        "com.viber.voip",
        "com.skype.raider",
        "com.google.android.apps.tachyon", // Google Meet / Duo
        "us.zoom.videomeetings",
        "com.discord",
    )

    fun isWatched(packageName: String): Boolean = packageName in ALLOWLIST
}
