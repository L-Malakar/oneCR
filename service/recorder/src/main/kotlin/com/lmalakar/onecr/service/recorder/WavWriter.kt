package com.lmalakar.onecr.service.recorder

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streaming WAV (RIFF) writer for the M3 capture pipeline.
 *
 * Writes the 44-byte canonical header up front with placeholder sizes (so
 * some players can even recover a partially written file), then streams PCM
 * frames through a buffer; [finish] seeks back to byte 0 and patches
 * `RIFF(n)`/`data(n)` with the real byte counts. [RecordingJournal.sweepOrphans]
 * reuses the same size math to patch recordings left behind by a killed
 * process, and [patchHeaderSizes] rewrites orphaned files in place.
 *
 * Pure JVM (java.io only) so it is unit-testable without a device.
 *
 * @param file target file (overwritten if present)
 * @param sampleRateHz PCM sample rate (e.g. 16_000)
 * @param channels channel count (1 = mono for the M3 cellular paths)
 * @param bitsPerSample bits per sample (16 = s16 LE, the AudioRecord default)
 */
class WavWriter(
    file: File,
    private val sampleRateHz: Int,
    private val channels: Int = 1,
    private val bitsPerSample: Int = 16,
) {

    private val raw = FileOutputStream(file)
    private val out = BufferedOutputStream(raw, 64 * 1024)
    private var dataBytes: Long = 0
    private var finished = false

    init {
        out.write(header(dataBytes.toInt()))
        out.flush()
    }

    /** Appends [count] PCM bytes from [buffer] starting at [offset]. */
    fun append(buffer: ByteArray, count: Int, offset: Int = 0) {
        check(!finished) { "WavWriter already finished" }
        if (count <= 0) return
        out.write(buffer, offset, count)
        dataBytes += count
    }

    /**
     * Patches the RIFF/data sizes from the bytes actually written and closes
     * the file. Idempotent — the pump loop's finally-block may race a manual
     * stop, the second call returns silently.
     */
    fun finish() {
        if (finished) return
        finished = true
        runCatching { out.flush() }
        runCatching {
            raw.channel.let { channel ->
                channel.position(0)
                channel.write(ByteBuffer.wrap(header(dataBytes.toInt())))
            }
        }
        runCatching { out.close() }
    }

    /** Bytes of PCM written so far (excludes the 44-byte header). */
    fun bytesWritten(): Long = dataBytes

    /**
     * Builds the 44-byte canonical PCM WAV header.
     *
     * @param dataSize the value patched into `data(n)`/`RIFF(n)`; the crash
     *   sweep derives the same value from the real file size.
     */
    private fun header(dataSize: Int): ByteArray {
        val byteRate = sampleRateHz * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val riffChunk = 36 + dataSize
        val b = ByteArray(HEADER_BYTES)
        putAscii(b, 0, "RIFF")
        putLeInt(b, 4, riffChunk)
        putAscii(b, 8, "WAVE")
        putAscii(b, 12, "fmt ")
        putLeInt(b, 16, 16) // PCM fmt chunk size
        putLeShort(b, 20, 1) // PCM format tag
        putLeShort(b, 22, channels)
        putLeInt(b, 24, sampleRateHz)
        putLeInt(b, 28, byteRate)
        putLeShort(b, 32, blockAlign)
        putLeShort(b, 34, bitsPerSample)
        putAscii(b, 36, "data")
        putLeInt(b, 40, dataSize)
        return b
    }

    companion object {
        /** Canonical PCM WAV header size — also used by the orphan sweep. */
        const val HEADER_BYTES = 44

        private fun putAscii(target: ByteArray, at: Int, s: String) {
            for (i in s.indices) target[at + i] = s[i].code.toByte()
        }

        private fun putLeInt(target: ByteArray, at: Int, value: Int) {
            target[at] = (value and 0xFF).toByte()
            target[at + 1] = (value shr 8 and 0xFF).toByte()
            target[at + 2] = (value shr 16 and 0xFF).toByte()
            target[at + 3] = (value shr 24 and 0xFF).toByte()
        }

        private fun putLeShort(target: ByteArray, at: Int, value: Int) {
            target[at] = (value and 0xFF).toByte()
            target[at + 1] = (value shr 8 and 0xFF).toByte()
        }

        private fun intToLe(value: Int): ByteArray = byteArrayOf(
            (value and 0xFF).toByte(),
            (value shr 8 and 0xFF).toByte(),
            (value shr 16 and 0xFF).toByte(),
            (value shr 24 and 0xFF).toByte(),
        )

        /**
         * Patches the sizes of an orphaned WAV in place (crash recovery) from
         * the actual on-disk byte length. Used by [RecordingJournal.sweepOrphans].
         */
        fun patchHeaderSizes(file: File) {
            val dataChunk = (file.length() - HEADER_BYTES).coerceAtLeast(0).toInt()
            val riffChunk = 36 + dataChunk
            java.io.RandomAccessFile(file, "rw").use { raf ->
                raf.seek(4)
                raf.write(intToLe(riffChunk))
                raf.seek(40)
                raf.write(intToLe(dataChunk))
            }
        }
    }
}
