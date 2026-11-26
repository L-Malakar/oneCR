package com.lmalakar.onecr.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WavWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun frame(channels: Int, frames: Int) = ByteArray(frames * channels * 2) { (it * 31).toByte() }

    @Test
    fun `header sizes are patched after finalize`() {
        val f = tmp.newFile("a.wav")
        val pcm = frame(channels = 1, frames = 100) // 200 bytes
        WavWriter.create(f, sampleRate = 44_100, channels = 1).use { w ->
            w.writePcm(pcm)
            w.finalize()
        }
        val bytes = f.readBytes()
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        // RIFF size = 36 + data
        assertEquals(36 + 200, le32(bytes, 4))
        assertEquals(200, le32(bytes, 40))
        assertEquals(44 + 200, f.length())
    }

    @Test
    fun `finalize is idempotent and close finalizes automatically`() {
        val f = tmp.newFile("b.wav")
        val w = WavWriter.create(f, sampleRate = 16_000, channels = 2)
        w.writePcm(frame(channels = 2, frames = 50)) // 200 bytes
        w.finalize()
        w.finalize() // second call must not corrupt
        w.close()
        assertEquals(200, le32(f.readBytes(), 40))

        val f2 = tmp.newFile("c.wav")
        WavWriter.create(f2, sampleRate = 16_000, channels = 1).use { it.writePcm(frame(1, 10)) }
        assertEquals(20, le32(f2.readBytes(), 40))
    }

    @Test
    fun `unfinalized crash signature is zero data size`() {
        val f = tmp.newFile("crash.wav")
        val w = WavWriter.create(f, sampleRate = 48_000, channels = 1)
        w.writePcm(frame(1, 64))
        // simulate a crash: no finalize, just abandon (close a copy of state)
        // — we cannot close without finalizing, so read the file raw.
        val bytes = f.readBytes()
        assertEquals(0, le32(bytes, 40))
        val info = WavWriter.readInfo(f)
        assertNotNull(info)
        assertFalse(info!!.finalized)
        assertEquals(0, info.dataBytes)
    }

    @Test
    fun `readInfo round-trips format facts`() {
        val f = tmp.newFile("d.wav")
        WavWriter.create(f, sampleRate = 44_100, channels = 2).use { w ->
            w.writePcm(frame(2, 128))
        }
        val info = WavWriter.readInfo(f)
        assertNotNull(info)
        assertEquals(44_100, info!!.sampleRate)
        assertEquals(2, info.channels)
        assertEquals(16, info.bitsPerSample)
        assertEquals(512, info.dataBytes)
        assertTrue(info.finalized)
    }

    @Test
    fun `readInfo rejects non-wav and truncated files`() {
        val notWav = tmp.newFile("x.txt").apply { writeText("hello world this is not audio") }
        assertNull(WavWriter.readInfo(notWav))
        val short = tmp.newFile("y.wav").apply { writeBytes(ByteArray(20)) }
        assertNull(WavWriter.readInfo(short))
        assertNull(WavWriter.readInfo(File(tmp.root, "missing.wav")))
    }

    @Test
    fun `partial frames are rejected`() {
        val f = tmp.newFile("e.wav")
        WavWriter.create(f, sampleRate = 44_100, channels = 2).use { w ->
            val thrown = runCatching { w.writePcm(ByteArray(7)) }
            assertTrue(thrown.isFailure)
            // whole frames still pass
            w.writePcm(ByteArray(8))
        }
        assertEquals(8, le32(f.readBytes(), 40))
    }

    @Test
    fun `invalid formats are rejected at create`() {
        assertTrue(runCatching { WavWriter.create(tmp.newFile(), 1, 1) }.isFailure)
        assertTrue(runCatching { WavWriter.create(tmp.newFile(), 44_100, 0) }.isFailure)
    }

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
}
