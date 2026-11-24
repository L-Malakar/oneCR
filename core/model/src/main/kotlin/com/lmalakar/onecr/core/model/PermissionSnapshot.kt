package com.lmalakar.onecr.core.model

/**
 * Point-in-time snapshot of every permission oneCR uses in this release.
 *
 * Pure data (no Android imports) so UI modules and the notification center can
 * observe it reactively; the app module produces it from the real OS state and
 * re-publishes it on resume and after every grant action.
 */
data class PermissionSnapshot(
    /** READ_PHONE_STATE — cellular call detection. */
    val phoneGranted: Boolean = false,
    /** READ_CALL_LOG — resolve numbers and contact names after calls. */
    val callLogGranted: Boolean = false,
    /**
     * POST_NOTIFICATIONS — visible detection notification. Always true below
     * Android 13, where the runtime permission does not exist.
     */
    val notificationsGranted: Boolean = false,
    /** Notification listener access — VoIP call detection (WhatsApp etc.). */
    val notificationListenerEnabled: Boolean = false,
    /** Battery optimization exemption — survive OEM battery savers. */
    val batteryExempt: Boolean = false,
) {
    /** Number of optional-but-recommended grants currently missing (0–5). */
    val missingCount: Int
        get() = listOf(
            phoneGranted,
            callLogGranted,
            notificationsGranted,
            notificationListenerEnabled,
            batteryExempt,
        ).count { !it }

    val allGranted: Boolean get() = missingCount == 0

    companion object {
        /** Fresh-install state: nothing granted yet. */
        val NONE = PermissionSnapshot()
    }
}
