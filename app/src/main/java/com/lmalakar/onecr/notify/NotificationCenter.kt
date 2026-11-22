package com.lmalakar.onecr.notify

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.lmalakar.onecr.core.model.PermissionSnapshot
import com.lmalakar.onecr.data.files.AppSettingsStore
import com.lmalakar.onecr.data.files.EventLogEntry
import com.lmalakar.onecr.privilege.api.PrivilegeHealth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Reads the real OS permission state into a [PermissionSnapshot].
 * Called on activity resume and after every grant action; the snapshot is
 * published as a StateFlow so every screen recomputes reactively.
 */
object PermissionReader {

    fun read(context: Context): PermissionSnapshot = PermissionSnapshot(
        phoneGranted = isGranted(context, android.Manifest.permission.READ_PHONE_STATE),
        callLogGranted = isGranted(context, android.Manifest.permission.READ_CALL_LOG),
        notificationsGranted = Build.VERSION.SDK_INT < 33 ||
            isGranted(context, android.Manifest.permission.POST_NOTIFICATIONS),
        notificationListenerEnabled = notificationListenerEnabled(context),
        batteryExempt = batteryIgnored(context),
    )

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun notificationListenerEnabled(context: Context): Boolean = runCatching {
        Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.split(':')
            ?.any { it.startsWith(context.packageName) }
    }.getOrDefault(false) == true

    private fun batteryIgnored(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}

/**
 * App-lifetime notification center state (v0.2.2).
 *
 * Combines the live flows (permissions, bridge health, detection running,
 * event log) into a [NotificationModel] for the top-bar badge and the
 * Notifications page. A rebuild tick forces re-composition after
 * [markAllSeen] / [refresh] even when no input flow changed.
 */
class NotificationCenter(
    private val settings: AppSettingsStore,
    private val health: StateFlow<PrivilegeHealth>,
    private val events: StateFlow<List<EventLogEntry>>,
    permissions: StateFlow<PermissionSnapshot>,
    running: StateFlow<Boolean>,
    private val showBridge: () -> Boolean,
    scope: CoroutineScope,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {

    private val rebuildTick = MutableStateFlow(0)

    /** Read-only view of the permission snapshot flow (rebroadcast for screens). */
    val permissionSnapshot: StateFlow<PermissionSnapshot> = permissions

    private val initialValue: NotificationModel = NotificationItems.build(
        permissions = permissions.value,
        bridgeState = health.value.state,
        bridgeDetail = health.value.detail,
        bridgeConfigured = showBridge(),
        detectionRunning = running.value,
        events = events.value,
        lastSeenMillis = settings.notificationLastSeen,
        nowMillis = nowMillis(),
    )

    val model: StateFlow<NotificationModel> =
        combine(permissions, health, events, running, rebuildTick) { perms, healthValue, eventList, isRunning, _ ->
            NotificationItems.build(
                permissions = perms,
                bridgeState = healthValue.state,
                bridgeDetail = healthValue.detail,
                bridgeConfigured = showBridge(),
                detectionRunning = isRunning,
                events = eventList,
                lastSeenMillis = settings.notificationLastSeen,
                nowMillis = nowMillis(),
            )
        }.stateIn(scope, SharingStarted.Eagerly, initialValue)

    /** Recompute on demand (activity resume, after grant actions). */
    fun refresh() {
        rebuildTick.value++
    }

    /** Called when the user opens the notification center — clears unread activity. */
    fun markAllSeen() {
        settings.notificationLastSeen = nowMillis()
        rebuildTick.value++
    }
}
