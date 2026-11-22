package com.lmalakar.onecr.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EventLogStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = EventLogStore(tmp.newFolder())

    @Test
    fun `append and load round-trips entries oldest first`() {
        val store = store()
        store.append(EventLogEntry(1_000, EventLogEntry.KIND_CALL, "Incoming call answered …7890"))
        store.append(EventLogEntry(2_000, EventLogEntry.KIND_VOIP, "VoIP call started — WhatsApp"))
        store.append(EventLogEntry(3_000, EventLogEntry.KIND_SERVICE, "Detection stopped"))

        val loaded = store.loadRecent()
        assertEquals(3, loaded.size)
        assertEquals(1_000, loaded[0].timestampMillis)
        assertEquals(EventLogEntry.KIND_CALL, loaded[0].kind)
        assertEquals("Incoming call answered …7890", loaded[0].message)
        assertEquals("VoIP call started — WhatsApp", loaded[1].message)
    }

    @Test
    fun `quotes and newlines survive the round-trip`() {
        val store = store()
        val tricky = "line1\nline2 with \"quotes\" and \\ backslash"
        store.append(EventLogEntry(42, EventLogEntry.KIND_DEBUG, tricky))

        assertEquals(tricky, store.loadRecent().single().message)
    }

    @Test
    fun `loadRecent honors the limit with newest entries`() {
        val store = store()
        repeat(10) { i -> store.append(EventLogEntry(i.toLong(), "k$i", "m$i")) }

        val loaded = store.loadRecent(limit = 3)
        assertEquals(3, loaded.size)
        assertEquals(7L, loaded[0].timestampMillis)
        assertEquals(9L, loaded[2].timestampMillis)
    }

    @Test
    fun `malformed lines are skipped not fatal`() {
        val dir = tmp.newFolder()
        val store = EventLogStore(dir)
        store.append(EventLogEntry(1, "a", "ok"))
        // Corrupt the file behind the store's back.
        dir.resolve(StorageLayout.EVENT_LOG_FILE).appendText("garbage\n{\"broken\n")
        store.append(EventLogEntry(2, "b", "still ok"))

        val loaded = store.loadRecent()
        assertEquals(2, loaded.size)
        assertTrue(loaded.all { it.message.endsWith("ok") })
    }

    @Test
    fun `clear wipes the log`() {
        val store = store()
        store.append(EventLogEntry(1, "a", "hello"))
        store.clear()
        assertTrue(store.loadRecent().isEmpty())
    }

    @Test
    fun `trim keeps only the newest lines past the cap`() {
        val dir = tmp.newFolder()
        val store = EventLogStore(dir, maxLines = 10, keepLines = 5)
        repeat(12) { i -> store.append(EventLogEntry(i.toLong(), "k", "m$i")) }

        // Appends ts 0–9 fill the cap; append ts 10 triggers a trim to the newest 5
        // (ts 6–10); append ts 11 lands after that without another trim.
        val loaded = store.loadRecent()
        assertEquals(6, loaded.size)
        assertEquals(6L, loaded.first().timestampMillis)
        assertEquals(11L, loaded.last().timestampMillis)
    }
}
