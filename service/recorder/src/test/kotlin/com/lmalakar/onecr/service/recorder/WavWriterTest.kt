package com.lmalakar.onecr.service.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `header plus appended data equals file size`() {
        val file = tmp.newFile("call.wav")
        val writer = WavWriter(file, sampleRateHz = 16_000)
        val pcm = ByteArray(1_000) { it.toByte() }
        writer.append(pcm, pcm.size)
        writer.finish()

        assertEquals(44 + 1_000, file.length())
        assertEquals(44 + 1_000, writer.writtenBytes)
    }

    @Test
    fun `finish patches the RIFF and data size fields`() {
        val file = tmp.newFile("call.wav")
        val writer = WavWriter(file, sampleRateHz = 16_000, channels = 1, bitsPerSample = 16)
        val pcm = ByteArray(2_048)
        writer.append(pcm, pcm.size)
        writer.finish()

        val bytes = file.readBytes()
        // RIFF magic + WAVE + fmt + data markers exist
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.ISO_8859_1))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.ISO_8859_1))
        assertEquals("data", String(bytes, 36, 4, Charsets.ISO_8859_1))
        // RIFF chunk size at offset 4 (LE) = 36 + dataBytes
        val riffSize = readIntLe(bytes, 4)
        assertEquals(36 + 2_048, riffSize)
        // data size at offset 40 (LE)
        val dataSize = readIntLe(bytes, 40)
        assertEquals(2_048, dataSize)
    }

    @Test
    fun `finish is idempotent`() {
        val file = tmp.newFile("call.wav")
        val writer = WavWriter(file, 16_000)
        writer.append(ByteArray(10), 10)
        writer.finish()
        val afterFirst = file.length()
        writer.finish()
        assertEquals(afterFirst, file.length())
    }

    @Test
    fun `mono 16k header carries the real format`() {
        val file = tmp.newFile("call.wav")
        val writer = WavWriter(file, 16_000)
        writer.finish()

        val bytes = file.readBytes()
        // fmt chunk: PCM (1) at offset 20 (LE short), channels at 22, rate at 24 (LE int)
        assertEquals(1, readShortLe(bytes, 20))
        assertEquals(1, readShortLe(bytes, 22))
        assertEquals(16_000, readIntLe(bytes, 24))
        // byte rate = rate * channels * bits / 8 = 32000
        assertEquals(32_000, readIntLe(bytes, 28))
    }

    @Test
    fun `appends after finish are ignored safely`() {
        val file = tmp.newFile("call.wav")
        val writer = WavWriter(file, 16_000)
        writer.append(ByteArray(100), 100)
        writer.finish()
        // A late pump byte after close must not crash or resurrect the file
        writer.append(ByteArray(50), 50)
        assertEquals(44 + 100, file.length())
    }

    @Test
    fun `creates missing parent directories`() {
        val dir = tmp.newFolder("recordings", "id-1", "raw")
        val file = java.io.File(dir, "call.wav")
        val writer = WavWriter(file, 16_000)
        writer.append(ByteArray(8), 8)
        writer.finish()
        assertTrue(file.exists())
    }

    private fun readIntLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readShortLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}
