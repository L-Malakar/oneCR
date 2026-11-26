package com.lmalakar.onecr.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecordingJournalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000_000L

    /** Lazy: @Rule folders exist only inside test methods. */
    private val root: File by lazy { tmp.newFolder("recordings") }
    private val journal: RecordingJournal by lazy { RecordingJournal(root, clock = { now }) }

    private fun started(id: String = "r1") = RecordingSession(
        id = id,
        direction = "incoming",
        phone = "+91 98765 43210",
        source = "voice_call",
        sampleRate = 44_100,
        channels = 2,
        startedAt = now,
    )

    @Test
    fun `start and finalize round-trip through the journal`() {
        val s = started("call-1")
        journal.start(s)

        val loaded = journal.load("call-1")
        assertNotNull(loaded)
        assertEquals("incoming", loaded!!.direction)
        assertEquals("+91 98765 43210", loaded.phone)
        assertEquals("voice_call", loaded.source)
        assertEquals(44_100, loaded.sampleRate)
        assertEquals(2, loaded.channels)
        assertEquals(RecordingJournal.STATE_STARTED, loaded.state)
        assertNull(loaded.endedAt)

        // write the final file, then finalize against it
        val finalFile = File(root, "call-1/final/audio.wav")
        finalFile.parentFile!!.mkdirs()
        finalFile.writeBytes(ByteArray(1_024))
        val done = journal.finalize(
            "call-1",
            endedAt = now + 60_000,
            finalPath = "call-1/final/audio.wav",
            finalBytes = 1_024,
        )
        assertNotNull(done)
        assertEquals(RecordingJournal.STATE_FINALIZED, done!!.state)
        assertEquals(now + 60_000, done.endedAt)
        assertEquals("call-1/final/audio.wav", done.finalPath)
        assertEquals(listOf(done), journal.list())
    }

    @Test
    fun `finalize refuses a vanished final file`() {
        journal.start(started("call-2"))
        val thrown = runCatching {
            journal.finalize("call-2", finalPath = "call-2/final/audio.wav", finalBytes = 10)
        }
        assertTrue(thrown.isFailure)
        assertEquals(RecordingJournal.STATE_STARTED, journal.load("call-2")!!.state)
    }

    @Test
    fun `sweep orphans a stale started session and never deletes files`() {
        val raw = File(root, "old/raw/downlink.wav")
        raw.parentFile!!.mkdirs()
        raw.writeBytes(ByteArray(64))
        journal.start(started("old"))

        now += RecordingJournal.DEFAULT_STALE_MS + 1
        val report = journal.sweepOrphans()

        assertEquals(1, report.orphanedCount)
        assertEquals("stale-started", report.orphaned.single().second)
        val after = journal.load("old")!!
        assertEquals(RecordingJournal.STATE_ORPHANED, after.state)
        assertEquals("stale-started", after.orphanReason)
        // the sweep marked, not deleted
        assertTrue(raw.isFile)

        // a second sweep does not double-report
        assertEquals(0, journal.sweepOrphans().orphanedCount)
    }

    @Test
    fun `sweep flags audio without a journal and finalized sessions missing audio`() {
        // dir with audio but no journal
        File(root, "ghost/raw").mkdirs()
        File(root, "ghost/raw/uplink.wav").writeBytes(ByteArray(32))
        // journal says finalized but the file is gone
        journal.start(started("gone"))
        val goneFinal = File(root, "gone/final/audio.wav")
        goneFinal.parentFile!!.mkdirs()
        goneFinal.writeBytes(ByteArray(16))
        journal.finalize("gone", finalPath = "gone/final/audio.wav", finalBytes = 16)
        goneFinal.delete()

        val report = journal.sweepOrphans()
        val reasons = report.orphaned.map { it.second }.sorted()
        assertEquals(listOf("final-missing", "journal-missing"), reasons)
    }

    @Test
    fun `empty recording dirs and fresh sessions are left alone`() {
        File(root, "empty").mkdirs()
        journal.start(started("fresh"))
        val report = journal.sweepOrphans()
        assertEquals(0, report.orphanedCount)
        assertEquals(2, report.scanned)
    }

    @Test
    fun `phone numbers with quotes and slashes survive the round-trip`() {
        val weird = started("w").copy(phone = "save\"me\\now")
        journal.start(weird)
        assertEquals("save\"me\\now", journal.load("w")!!.phone)
    }

    @Test
    fun `load of an unknown take is null, not a crash`() {
        assertNull(journal.load("nope"))
    }
}
