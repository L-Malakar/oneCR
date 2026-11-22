package com.lmalakar.onecr.service.detection

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.app.Notification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide bus between the [VoipNotificationListener] (bound by the system)
 * and the [DetectionEngine]. Same process, so a simple singleton StateFlow is enough.
 */
object VoipNotificationBus {
    private val _activeCallPackages = MutableStateFlow<Set<String>>(emptySet())
    val activeCallPackages: StateFlow<Set<String>> = _activeCallPackages.asStateFlow()

    fun update(packages: Set<String>) {
        _activeCallPackages.value = packages
    }
}

/**
 * NotificationListenerService feeding the VoIP heuristic input (1):
 * "an ongoing CATEGORY_CALL notification from an allowlisted package exists".
 *
 * Privacy: allowlist-only (see [VoipPackages]); contents are NEVER read — only the
 * category flag, ongoing flag and package name are inspected. The user must grant
 * notification access in system settings (onboarding deep-links there).
 */
class VoipNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        recompute()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        recompute()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        recompute()
    }

    private fun recompute() {
        val active = runCatching { activeNotifications }.getOrNull().orEmpty()
        val callPackages = active
            .filter { sbn ->
                sbn.isOngoing &&
                    sbn.packageName != this.packageName &&
                    VoipPackages.isWatched(sbn.packageName) &&
                    sbn.notification?.category == Notification.CATEGORY_CALL
            }
            .map { it.packageName }
            .toSet()
        VoipNotificationBus.update(callPackages)
    }
}
