package com.lmalakar.onecr.service.recorder

import com.lmalakar.onecr.data.files.StorageLayout
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingJournalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `save and load round-trip preserves every field`() {
        val dir = tmp.newFolder("rec-1")
        val journal = RecordingJournal(
            recordingId = "rec-1",
            startedAtMillis = 1_000L,
            direction = "INCOMING",
            phone = "…1234",
            trigger = RecordingJournal.TRIGGER_AUTO,
            captureSource = "VOICE_CALL",
            sampleRateHz = 16_000,
            wavFile = "call.wav",
        )
        journal.save(dir)

        val loaded = RecordingJournal.load(dir)
        assertNotNull(loaded)
        assertEquals(journal, loaded)
    }

    @Test
    fun `garbled journal parses to null`() {
        val dir = tmp.newFolder("rec-2")
        File(dir, StorageLayout.JOURNAL_FILE).writeText("this is not a journal\nat all\n")
        assertNull(RecordingJournal.load(dir))
    }

    @Test
    fun `missing journal file loads to null`() {
        assertNull(RecordingJournal.load(tmp.newFolder("rec-empty")))
    }

    @Test
    fun `sweep recovers an orphaned recording journal`() {
        val root = tmp.newFolder("recordings")
        val dir = File(root, "rec-orphan")
        dir.mkdirs()
        // A killed process: journal says recording, WAV has placeholder header + 3s of PCM.
        RecordingJournal(
            recordingId = "rec-orphan",
            startedAtMillis = 10_000L,
            status = RecordingJournal.STATUS_RECORDING,
            captureSource = "VOICE_CALL",
            sampleRateHz = 16_000,
            wavFile = "call.wav",
        ).save(dir)
        val wav = File(dir, "call.wav")
        wav.writeBytes(ByteArray(44 + 16_000 * 2 * 3)) // 3 seconds of mono s16

        val recovered = RecordingJournal.sweepOrphans(root, timeNowMillis = 99_999L)
        assertEquals(1, recovered)

        val patched = RecordingJournal.load(dir)!!
        assertEquals(RecordingJournal.STATUS_RECOVERED, patched.status)
        assertEquals(16_000L * 2 * 3, patched.sizeBytes)
        assertEquals(3_000L, patched.durationMs)
        assertEquals(99_999L, patched.endedAtMillis)
    }

    @Test
    fun `sweep leaves finalized and failed journals alone`() {
        val root = tmp.newFolder("recordings")
        for ((name, status) in listOf("a" to RecordingJournal.STATUS_FINALIZED, "b" to RecordingJournal.STATUS_FAILED)) {
            val dir = File(root, name)
            dir.mkdirs()
            RecordingJournal(
                recordingId = name,
                startedAtMillis = 0,
                status = status,
            ).save(dir)
        }
        assertEquals(0, RecordingJournal.sweepOrphans(root, 1_000L))
    }

    @Test
    fun `sweep on a missing root is a no-op`() {
        assertEquals(0, RecordingJournal.sweepOrphans(File(tmp.root, "does-not-exist"), 0L))
    }
}
