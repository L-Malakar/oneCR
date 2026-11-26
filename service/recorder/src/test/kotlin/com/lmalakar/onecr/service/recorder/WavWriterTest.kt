package com.lmalakar.onecr.service.recorder

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun riffSize(file: File): Int = RandomAccessFile(file, "r").use { raf ->
        raf.seek(4)
        littleEndian(readBytes(raf, 4))
    }

    private fun dataSize(file: File): Int = RandomAccessFile(file, "r").use { raf ->
        raf.seek(40)
        littleEndian(readBytes(raf, 4))
    }

    private fun readBytes(raf: RandomAccessFile, count: Int): ByteArray {
        val buf = ByteArray(count)
        raf.readFully(buf)
        return buf
    }

    private fun littleEndian(bytes: ByteArray): Int =
        (bytes[0].toInt() and 0xFF) or
            ((bytes[1].toInt() and 0xFF) shl 8) or
            ((bytes[2].toInt() and 0xFF) shl 16) or
            ((bytes[3].toInt() and 0xFF) shl 24)

    @Test
    fun `header is canonical riff with patched sizes after finish`() {
        val file = tmp.newFile("clip.wav")
        val writer = WavWriter(file, sampleRateHz = 16_000)

        // Placeholder sizes while streaming (RIFF = 36, data = 0).
        assertEquals(0, dataSize(file))

        val pcm = ByteArray(32_000) { (it % 251).toByte() } // 1 s @ 16 kHz mono s16
        writer.append(pcm, pcm.size)
        writer.finish()

        assertEquals(WavWriter.HEADER_BYTES.toLong() + pcm.size, file.length())
        assertEquals(pcm.size, dataSize(file))
        assertEquals(pcm.size + 36, riffSize(file))
    }

    @Test
    fun `append accumulates across many buffers`() {
        val file = tmp.newFile("chunked.wav")
        val writer = WavWriter(file, sampleRateHz = 16_000)
        val chunk = ByteArray(1_024)
        repeat(7) { writer.append(chunk, chunk.size) }
        writer.finish()

        assertEquals(7 * 1_024, dataSize(file))
        assertEquals(WavWriter.HEADER_BYTES.toLong() + 7 * 1_024L, file.length())
    }

    @Test
    fun `finish is idempotent and append after finish throws`() {
        val file = tmp.newFile("guarded.wav")
        val writer = WavWriter(file, sampleRateHz = 8_000)
        writer.append(ByteArray(16), 16)
        writer.finish()
        writer.finish() // second call must not corrupt or throw

        val afterFirst = file.length()
        writer.finish()
        assertEquals(afterFirst, file.length())

        val threw = runCatching { writer.append(ByteArray(4), 4) }.isFailure
        assertTrue(threw)
    }

    @Test
    fun `header magic and fmt fields are correct`() {
        val file = tmp.newFile("magic.wav")
        val writer = WavWriter(file, sampleRateHz = 16_000, channels = 1, bitsPerSample = 16)
        writer.finish()

        val bytes = file.readBytes()
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(bytes, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        // fmt chunk size
        assertEquals(16, littleEndian(bytes.copyOfRange(16, 20)))
        // PCM tag
        assertEquals(1, bytes[20].toInt() and 0xFF)
        // sample rate
        assertEquals(16_000, littleEndian(bytes.copyOfRange(24, 28)))
        // byte rate = rate * channels * bits / 8 = 32000
        assertEquals(32_000, littleEndian(bytes.copyOfRange(28, 32)))
        // block align = channels * bits / 8 = 2
        assertEquals(2, (bytes[32].toInt() and 0xFF) or ((bytes[33].toInt() and 0xFF) shl 8))
    }

    @Test
    fun `patchHeaderSizes recovers an orphaned partial file`() {
        // Simulate a crashed process: 44-byte header with placeholder (zero)
        // sizes plus 9_999 PCM bytes that never got their finish() patch.
        val orphan = tmp.newFile("orphan.wav")
        val header = ByteArray(WavWriter.HEADER_BYTES) // zero-filled placeholders
        orphan.writeBytes(header + ByteArray(9_999))

        WavWriter.patchHeaderSizes(orphan)

        assertEquals(9_999, dataSize(orphan))
        assertEquals(9_999 + 36, riffSize(orphan))
    }
}
