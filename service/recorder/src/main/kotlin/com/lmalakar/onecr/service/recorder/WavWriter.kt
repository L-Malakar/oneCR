package com.lmalakar.onecr.service.recorder

import java.io.File
import java.io.RandomAccessFile

/**
 * Streaming WAV (RIFF) writer for one capture stream (M3).
 *
 * The 44-byte canonical header is written upfront with placeholder sizes so a
 * killed process still leaves a recoverable file (the orphan sweep patches the
 * real sizes from the file length). [finalize] patches both size fields and
 * closes the file — call it exactly once per session.
 *
 * Pure JVM (java.io only): unit-testable, no android.media dependency.
 */
class WavWriter(
    private val file: File,
    val sampleRateHz: Int,
    val channels: Int = 1,
    val bitsPerSample: Int = 16,
) {

    private var raf: RandomAccessFile? = null
    private var dataBytes: Long = 0
    private var finalized = false

    init {
        file.parentFile?.mkdirs()
        raf = RandomAccessFile(file, "rw").apply {
            setLength(0)
            write(header())
        }
    }

    /** Total WAV file size once finalized (header + data). */
    val writtenBytes: Long get() = HEADER_BYTES + dataBytes

    /** Appends one little-endian PCM chunk (already s16 LE — CaptureMain's format). */
    @Synchronized
    fun append(pcm: ByteArray, length: Int) {
        val out = raf ?: return
        out.write(pcm, 0, length)
        dataBytes += length
    }

    /** Patches the RIFF/data size fields and closes the file. Idempotent.
     *  (Named `finish` — a member named `finalize` collides with the JVM
     *  object-lifecycle method and is refused by the Kotlin compiler.) */
    @Synchronized
    fun finish() {
        if (finalized) return
        finalized = true
        val out = raf ?: return
        runCatching {
            out.seek(4)
            out.writeIntLe(36 + dataBytes) // RIFF chunk size
            out.seek(40)
            out.writeIntLe(dataBytes)      // data chunk size
        }
        runCatching { out.close() }
        raf = null
    }

    private fun header(): ByteArray {
        val byteRate = sampleRateHz * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val head = StringBuilder()
        head.append("RIFF")
        head.append("----") // placeholder RIFF size, patched at finalize
        head.append("WAVE")
        head.append("fmt ")
        head.append(intLe(16))          // fmt chunk size
        head.append(shortLe(1))         // PCM
        head.append(shortLe(channels))
        head.append(intLe(sampleRateHz))
        head.append(intLe(byteRate))
        head.append(shortLe(blockAlign))
        head.append(shortLe(bitsPerSample))
        head.append("data")
        head.append("----")             // placeholder data size, patched at finalize
        val bytes = head.toString().toByteArray(Charsets.ISO_8859_1)
        check(bytes.size == HEADER_BYTES) { "WAV header must be 44 bytes, got ${bytes.size}" }
        return bytes
    }

    private fun RandomAccessFile.writeIntLe(value: Long) {
        write((value and 0xFF).toInt())
        write((value shr 8 and 0xFF).toInt())
        write((value shr 16 and 0xFF).toInt())
        write((value shr 24 and 0xFF).toInt())
    }

    private fun intLe(value: Int): String {
        val b = byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte(),
        )
        return String(b, Charsets.ISO_8859_1)
    }

    private fun shortLe(value: Int): String {
        val b = byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
        )
        return String(b, Charsets.ISO_8859_1)
    }

    companion object {
        const val HEADER_BYTES = 44
    }
}
