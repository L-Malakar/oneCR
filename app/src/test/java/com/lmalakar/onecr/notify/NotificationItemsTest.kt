package com.lmalakar.onecr.notify

import com.lmalakar.onecr.core.model.PermissionSnapshot
import com.lmalakar.onecr.data.files.EventLogEntry
import com.lmalakar.onecr.privilege.api.PrivilegeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the notification-center feed composition and unread-badge math (v0.2.2).
 */
class NotificationItemsTest {

    private val allGranted = PermissionSnapshot(
        phoneGranted = true,
        callLogGranted = true,
        notificationsGranted = true,
        notificationListenerEnabled = true,
        batteryExempt = true,
    )

    @Test
    fun `all set and running produces no needs and no errors`() {
        val model = NotificationItems.build(
            permissions = allGranted,
            bridgeState = PrivilegeState.OK,
            bridgeDetail = "Connected",
            bridgeConfigured = true,
            detectionRunning = true,
            events = emptyList(),
            lastSeenMillis = 0,
            nowMillis = 100,
        )
        assertTrue(model.needs.isEmpty())
        assertTrue(model.errors.isEmpty())
        assertEquals(0, model.badgeCount)
    }

    @Test
    fun `fresh install lists every missing permission as an action item`() {
        val model = NotificationItems.build(
            permissions = PermissionSnapshot.NONE,
            bridgeState = PrivilegeState.NOT_CONFIGURED,
            bridgeDetail = "Shizuku not installed",
            bridgeConfigured = false, // fresh install: bridge is optional, not nagged
            detectionRunning = false,
            events = emptyList(),
            lastSeenMillis = 0,
            nowMillis = 100,
        )
        val ids = model.needs.map { it.id }
        // detection off + 5 permissions; bridge NOT configured → not included
        assertEquals(
            setOf("detection.off", "perm.phone", "perm.call_log", "perm.notifications",
                "perm.listener", "perm.battery"),
            ids.toSet(),
        )
        assertEquals(6, model.badgeCount)
        model.needs.forEach { assertNotNull(it.actionLabel) }
    }

    @Test
    fun `bridge nagged only when configured or degraded`() {
        val notConfigured = NotificationItems.build(
            permissions = allGranted,
            bridgeState = PrivilegeState.NOT_CONFIGURED,
            bridgeDetail = "",
            bridgeConfigured = false,
            detectionRunning = true,
            events = emptyList(),
            lastSeenMillis = 0,
            nowMillis = 100,
        )
        assertTrue(notConfigured.needs.isEmpty())

        val configuredButAbsent = NotificationItems.build(
            permissions = allGranted,
            bridgeState = PrivilegeState.NOT_CONFIGURED,
            bridgeDetail = "",
            bridgeConfigured = true,
            detectionRunning = true,
            events = emptyList(),
            lastSeenMillis = 0,
            nowMillis = 100,
        )
        assertEquals(listOf("bridge.state"), configuredButAbsent.needs.map { it.id })

        val degraded = NotificationItems.build(
            permissions = allGranted,
            bridgeState = PrivilegeState.PAIRING_LOST,
            bridgeDetail = "Shizuku is not running",
            bridgeConfigured = false,
            detectionRunning = true,
            events = emptyList(),
            lastSeenMillis = 0,
            nowMillis = 100,
        )
        assertEquals(listOf("bridge.state"), degraded.needs.map { it.id })
        assertEquals("Open Shizuku", degraded.needs.first().actionLabel)
    }

    @Test
    fun `error events surface as error items`() {
        val model = NotificationItems.build(
            permissions = allGranted,
            bridgeState = PrivilegeState.OK,
            bridgeDetail = "",
            bridgeConfigured = true,
            detectionRunning = true,
            events = listOf(
                EventLogEntry(100, EventLogEntry.KIND_CALL, "Incoming call answered …1234"),
                EventLogEntry(20, EventLogEntry.KIND_ERROR, "Foreground service failed to start: boom"),
            ),
            lastSeenMillis = 100, // the call event was seen; the error is new
            nowMillis = 100,
        )
        assertEquals(1, model.errors.size)
        assertEquals("Foreground service failed to start: boom", model.errors.first().message)
        // Badge counts the error once (errors are never double-counted as activity):
        assertEquals(1, model.badgeCount)
    }

    @Test
    fun `badge counts only activity newer than last seen`() {
        val events = listOf(
            EventLogEntry(100, EventLogEntry.KIND_CALL, "Call one"),
            EventLogEntry(200, EventLogEntry.KIND_VOIP, "Call two"),
        )
        val unseen = NotificationItems.build(
            permissions = allGranted,
            bridgeState = PrivilegeState.OK,
            bridgeDetail = "",
            bridgeConfigured = true,
            detectionRunning = true,
            events = events,
            lastSeenMillis = 50,
            nowMillis = 300,
        )
        assertEquals(2, unseen.badgeCount)

        val seen = NotificationItems.build(
            permissions = allGranted,
            bridgeState = PrivilegeState.OK,
            bridgeDetail = "",
            bridgeConfigured = true,
            detectionRunning = true,
            events = events,
            lastSeenMillis = 200, // everything seen
            nowMillis = 300,
        )
        assertEquals(0, seen.badgeCount)
        // Logs still listed, just not counted as unread:
        assertEquals(2, seen.logs.size)
    }

    @Test
    fun `activity feed is newest first`() {
        val model = NotificationItems.build(
            permissions = allGranted,
            bridgeState = PrivilegeState.OK,
            bridgeDetail = "",
            bridgeConfigured = true,
            detectionRunning = true,
            events = listOf(
                EventLogEntry(100, EventLogEntry.KIND_CALL, "Old"),
                EventLogEntry(200, EventLogEntry.KIND_CALL, "New"),
            ),
            lastSeenMillis = 0,
            nowMillis = 300,
        )
        assertEquals(listOf("New", "Old"), model.logs.map { it.title })
        assertFalse(model.isEmpty)
    }
}
