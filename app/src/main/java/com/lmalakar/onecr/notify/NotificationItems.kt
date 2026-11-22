package com.lmalakar.onecr.notify

import com.lmalakar.onecr.core.model.PermissionSnapshot
import com.lmalakar.onecr.data.files.EventLogEntry
import com.lmalakar.onecr.privilege.api.PrivilegeState

/**
 * The v0.2.2 in-app notification center model: one place that shows everything
 * the app needs (missing permissions, bridge repair, detection off), every
 * error the app hit, and the recent activity log — as a single feed.
 *
 * Pure Kotlin (no Android imports) so the feed composition and the unread-badge
 * math are unit-testable; [NotificationCenter] wires it to live flows.
 */
enum class NotificationSeverity { NEEDS_ATTENTION, ERROR, LOG }

data class NotificationItem(
    val id: String,
    val severity: NotificationSeverity,
    val title: String,
    val message: String,
    /** Label for the action button; null = informational, no action. */
    val actionLabel: String? = null,
    /** Event kind for LOG items ("call", "voip", …); null otherwise. */
    val kind: String? = null,
    /** Event timestamp for LOG/ERROR items; 0 for derived (needs-attention) items. */
    val timestampMillis: Long = 0L,
)

data class NotificationModel(
    val items: List<NotificationItem>,
    /** Unread count for the top-bar badge: all needs + errors, plus activity
     *  recorded after the user last opened the center. 0 = nothing to show. */
    val badgeCount: Int,
) {
    val needs: List<NotificationItem> get() = items.filter { it.severity == NotificationSeverity.NEEDS_ATTENTION }
    val errors: List<NotificationItem> get() = items.filter { it.severity == NotificationSeverity.ERROR }
    val logs: List<NotificationItem> get() = items.filter { it.severity == NotificationSeverity.LOG }

    val isEmpty: Boolean get() = items.isEmpty()
}

object NotificationItems {

    /**
     * Builds the feed.
     *
     * @param permissions            current permission snapshot (from the app module).
     * @param bridgeState            privilege bridge state.
     * @param bridgeDetail           human detail from the health monitor.
     * @param bridgeConfigured       true once the user set up the bridge in onboarding;
     *                               without it, NOT_CONFIGURED is not nagged about
     *                               (the bridge is optional for detection).
     * @param detectionRunning       whether the detection service/engine is running.
     * @param events                 recent event log entries, oldest first.
     * @param lastSeenMillis         when the user last opened the notification center.
     * @param nowMillis              current time (for the unread math).
     */
    fun build(
        permissions: PermissionSnapshot,
        bridgeState: PrivilegeState,
        bridgeDetail: String,
        bridgeConfigured: Boolean,
        detectionRunning: Boolean,
        events: List<EventLogEntry>,
        lastSeenMillis: Long,
        nowMillis: Long,
    ): NotificationModel {
        val items = buildList {
            // ---- Needs attention ------------------------------------------------
            if (!detectionRunning) {
                add(
                    NotificationItem(
                        id = "detection.off",
                        severity = NotificationSeverity.NEEDS_ATTENTION,
                        title = "Call detection is off",
                        message = "Start detection so oneCR notices calls while it is in the background.",
                        actionLabel = "Start detection",
                    ),
                )
            }
            if (!permissions.phoneGranted) {
                add(
                    needPermission(
                        "perm.phone", "Phone permission missing",
                        "oneCR cannot see when cellular calls start or end.",
                        "Allow phone access",
                    ),
                )
            }
            if (!permissions.callLogGranted) {
                add(
                    needPermission(
                        "perm.call_log", "Call log permission missing",
                        "Numbers and contact names cannot be resolved after a call ends.",
                        "Allow call log access",
                    ),
                )
            }
            if (!permissions.notificationsGranted) {
                add(
                    needPermission(
                        "perm.notifications", "Notifications blocked",
                        "Without it the persistent detection notification stays hidden (Android 13+).",
                        "Allow notifications",
                    ),
                )
            }
            if (!permissions.notificationListenerEnabled) {
                add(
                    needPermission(
                        "perm.listener", "Notification access off",
                        "WhatsApp and other VoIP calls cannot be detected without it.",
                        "Open settings",
                    ),
                )
            }
            if (!permissions.batteryExempt) {
                add(
                    needPermission(
                        "perm.battery", "Battery optimization is on",
                        "OEM battery savers may pause detection in the middle of a call.",
                        "Exempt oneCR",
                    ),
                )
            }

            // ---- Privilege bridge ------------------------------------------------
            // Optional for detection (required for capture in M3): only nag when the
            // user actually set it up, or when a working bridge degraded.
            val bridgeDegraded = bridgeState != PrivilegeState.OK && bridgeState != PrivilegeState.NOT_CONFIGURED
            if (bridgeDegraded || (bridgeConfigured && bridgeState == PrivilegeState.NOT_CONFIGURED)) {
                val (title, action) = when (bridgeState) {
                    PrivilegeState.NOT_CONFIGURED -> "Privilege bridge not set up" to "Set up bridge"
                    PrivilegeState.NEEDS_PERMISSION -> "Shizuku permission pending" to "Grant permission"
                    PrivilegeState.PAIRING_LOST -> "Shizuku is not running" to "Open Shizuku"
                    PrivilegeState.SERVICE_DEAD -> "Privilege service died" to "Re-run setup"
                    PrivilegeState.DEV_OPTIONS_OFF -> "Developer options are off" to "Re-run setup"
                    PrivilegeState.UNSUPPORTED -> "Bridge unsupported on this device" to null
                    else -> "Privilege bridge" to null
                }
                add(
                    NotificationItem(
                        id = "bridge.state",
                        severity = NotificationSeverity.NEEDS_ATTENTION,
                        title = title,
                        message = bridgeDetail.ifBlank {
                            "Needed for recording (M3); detection works without it."
                        },
                        actionLabel = action,
                    ),
                )
            }

            // ---- Errors ----------------------------------------------------------
            events.filter { it.kind == EventLogEntry.KIND_ERROR }
                .takeLast(MAX_ERRORS)
                .forEach { entry ->
                    add(
                        NotificationItem(
                            id = "error.${entry.timestampMillis}",
                            severity = NotificationSeverity.ERROR,
                            title = "Error",
                            message = entry.message,
                            kind = entry.kind,
                            timestampMillis = entry.timestampMillis,
                        ),
                    )
                }

            // ---- Activity log ----------------------------------------------------
            events.takeLast(MAX_LOGS).asReversed().forEach { entry ->
                add(
                    NotificationItem(
                        id = "log.${entry.timestampMillis}.${entry.kind}",
                        severity = NotificationSeverity.LOG,
                        title = entry.message,
                        message = entry.message,
                        kind = entry.kind,
                        timestampMillis = entry.timestampMillis,
                    ),
                )
            }
        }

        // Unread activity = non-error events recorded after the last open; error
        // events are already counted once via the ERROR items above.
        val unreadActivity = events.count {
            it.kind != EventLogEntry.KIND_ERROR &&
                it.timestampMillis > lastSeenMillis && it.timestampMillis <= nowMillis
        }
        val badge = items.count { it.severity == NotificationSeverity.NEEDS_ATTENTION } +
            items.count { it.severity == NotificationSeverity.ERROR } +
            unreadActivity

        return NotificationModel(items = items, badgeCount = badge)
    }

    // ------------------------------------------------------------------

    private fun needPermission(id: String, title: String, message: String, action: String) =
        NotificationItem(
            id = id,
            severity = NotificationSeverity.NEEDS_ATTENTION,
            title = title,
            message = message,
            actionLabel = action,
        )

    private const val MAX_ERRORS = 10
    private const val MAX_LOGS = 30
}
