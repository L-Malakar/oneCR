package com.lmalakar.onecr.data.files

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Incremental 16-bit PCM WAV writer — the first slice of the M3 capture engine
 * (v0.3.4). The shell recorder writes PCM frames as a call happens; this class
 * owns the container format so every recording is a valid WAV the moment it is
 * finalized, with no second pass over the audio.
 *
 * The classic 44-byte canonical header is written up front with zero sizes;
 * [finalize] patches the RIFF and data chunk sizes in place. A crash before
 * finalize leaves a file with zero sizes — that is exactly the signature the
 * [RecordingJournal] sweep uses to recognize an unfinished take.
 *
 * Pure java.io (no Android imports) so it is unit-testable on the JVM and
 * usable from any layer, including the future capture dex host.
 */
class WavWriter private constructor(
    private val file: RandomAccessFile,
    private val sampleRate: Int,
    private val channels: Int,
) : Closeable {

    private val blockAlign: Int = channels * BITS_PER_SAMPLE / 8
    private var dataBytes: Long = 0L
    private var finalized = false

    /** Number of complete audio frames handed to [writePcm] so far. */
    val framesWritten: Long get() = if (blockAlign == 0) 0 else dataBytes / blockAlign

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /**
     * Appends PCM frames. [length] must be a whole number of frames
     * (a multiple of channels × 2 bytes for 16-bit audio) — recorders read
     * whole frames, and a partial frame would desync the channel layout.
     */
    fun writePcm(buffer: ByteArray, length: Int = buffer.size) {
        check(!finalized) { "WAV already finalized" }
        require(length >= 0) { "negative length" }
        require(length % blockAlign == 0) {
            "partial frame: $length bytes is not a multiple of $blockAlign"
        }
        if (length == 0) return
        file.write(buffer, 0, length)
        dataBytes += length
    }

    /**
     * Patches the RIFF and data sizes. Safe to call twice; [close] finalizes
     * automatically so the try-with-usual-misfortune pattern never loses a
     * take's header.
     */
    fun finalize() {
        if (finalized) return
        // RIFF chunk size (offset 4): everything after this field = 36 + data.
        file.seek(RIFF_SIZE_OFFSET)
        writeInt32Le(CHUNK_BASE + dataBytes)
        // data chunk size (offset 40): the PCM payload itself.
        file.seek(DATA_SIZE_OFFSET)
        writeInt32Le(dataBytes)
        finalized = true
    }

    override fun close() {
        runCatching { finalize() }
        runCatching { file.close() }
    }

    private fun writeInt32Le(value: Long) {
        // RandomAccessFile.writeInt is big-endian; WAV is little-endian.
        val v = value and 0xFFFFFFFFL
        file.write(byteArrayOf(
            (v and 0xFF).toByte(),
            ((v ushr 8) and 0xFF).toByte(),
            ((v ushr 16) and 0xFF).toByte(),
            ((v ushr 24) and 0xFF).toByte(),
        ))
    }

    companion object {
        const val BITS_PER_SAMPLE = 16

        /** Bytes before the RIFF size field / the data size field. */
        private const val RIFF_SIZE_OFFSET = 4L
        private const val DATA_SIZE_OFFSET = 40L

        /** Canonical header size; also "RIFF size + 8" for a well-formed file. */
        const val HEADER_BYTES = 44L

        /** RIFF size field excludes the 8-byte "RIFF<size>" preamble. */
        private const val CHUNK_BASE = 36L

        /**
         * Opens [target] for writing (existing content is replaced) and writes
         * the zero-sized header. The parent directory is created if needed.
         */
        fun create(target: File, sampleRate: Int, channels: Int): WavWriter {
            require(sampleRate in 8_000..192_000) { "sample rate out of range: $sampleRate" }
            require(channels in 1..8) { "channel count out of range: $channels" }
            target.parentFile?.mkdirs()
            val raf = try {
                RandomAccessFile(target, "rw").apply { setLength(0) }
            } catch (e: IOException) {
                throw IOException("cannot open ${target.path}: ${e.message}", e)
            }
            return WavWriter(raf, sampleRate, channels).apply { writeHeader() }
        }

        private fun WavWriter.writeHeader() {
            val byteRate = sampleRate * channels * BITS_PER_SAMPLE / 8
            val header = ByteArray(HEADER_BYTES.toInt())
            fun putAscii(offset: Int, text: String) {
                for (i in text.indices) header[offset + i] = text[i].code.toByte()
            }
            fun putInt32Le(offset: Int, v: Int) {
                header[offset] = (v and 0xFF).toByte()
                header[offset + 1] = ((v ushr 8) and 0xFF).toByte()
                header[offset + 2] = ((v ushr 16) and 0xFF).toByte()
                header[offset + 3] = ((v ushr 24) and 0xFF).toByte()
            }
            fun putInt16Le(offset: Int, v: Int) {
                header[offset] = (v and 0xFF).toByte()
                header[offset + 1] = ((v ushr 8) and 0xFF).toByte()
            }
            putAscii(0, "RIFF")
            putInt32Le(4, CHUNK_BASE.toInt()) // patched by finalize()
            putAscii(8, "WAVE")
            putAscii(12, "fmt ")
            putInt32Le(16, 16)               // fmt chunk size
            putInt16Le(20, 1)                // PCM
            putInt16Le(22, channels)
            putInt32Le(24, sampleRate)
            putInt32Le(28, byteRate)
            putInt16Le(32, blockAlign)
            putInt16Le(34, BITS_PER_SAMPLE)
            putAscii(36, "data")
            putInt32Le(40, 0)                // patched by finalize()
            file.seek(0)
            file.write(header)
        }

        /**
         * Reads the header of a (possibly unfinished) WAV without loading it.
         * Returns null when the file is too short or not a PCM WAV — the
         * journal sweep treats that as an unfinished take.
         */
        fun readInfo(source: File): WavInfo? {
            if (!source.isFile || source.length() < HEADER_BYTES) return null
            return try {
                RandomAccessFile(source, "r").use { raf ->
                    val head = ByteArray(HEADER_BYTES.toInt())
                    raf.seek(0)
                    raf.readFully(head)
                    if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                        String(head, 8, 4, Charsets.US_ASCII) != "WAVE" ||
                        String(head, 36, 4, Charsets.US_ASCII) != "data"
                    ) {
                        return null
                    }
                    fun int32Le(offset: Int): Long {
                        var v = 0L
                        for (i in 3 downTo 0) v = (v shl 8) or (head[offset + i].toLong() and 0xFF)
                        return v
                    }
                    fun int16Le(offset: Int): Int {
                        var v = 0
                        for (i in 1 downTo 0) v = (v shl 8) or (head[offset + i].toInt() and 0xFF)
                        return v
                    }
                    val channels = int16Le(22)
                    val rate = int32Le(24).toInt()
                    val bits = int16Le(34)
                    val declared = int32Le(40)
                    if (channels < 1 || rate < 1 || bits != BITS_PER_SAMPLE) return null
                    WavInfo(
                        sampleRate = rate,
                        channels = channels,
                        bitsPerSample = bits,
                        dataBytes = declared,
                        finalized = declared > 0,
                    )
                }
            } catch (_: IOException) {
                null
            }
        }
    }

    /** Header facts for a file on disk (see [readInfo]). */
    data class WavInfo(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataBytes: Long,
        /** False for a crashed take: header still says zero data bytes. */
        val finalized: Boolean,
    )
}
