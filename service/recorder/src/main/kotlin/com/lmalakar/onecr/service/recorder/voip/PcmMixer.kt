package com.lmalakar.onecr.service.recorder.voip

/**
 * Two-input saturating mixer for 16-bit little-endian mono PCM streams
 * (v0.3.7 — the VoIP dual-path capture mixer).
 *
 * Real-time sources never deliver in lockstep, so each input keeps a carry
 * buffer: [pushA]/[pushB] append whole frames, [mixInto] consumes exactly
 * min(framesA, framesB) of them as a saturating sum and keeps the remainder
 * for the next round. When only one input has ever pushed (the other path is
 * disabled or has not started yet), it passes through untouched — a
 * one-path recording is still a recording.
 *
 * Pure JVM — no Android imports — so the mixing math is unit-testable.
 */
class PcmMixer2(private val maxCarryBytes: Int = DEFAULT_MAX_CARRY_BYTES) {

    private var hasA = false
    private var hasB = false
    private var carryA = ByteArray(0)
    private var carryB = ByteArray(0)

    /** Appends [chunk] (whole frames; a trailing odd byte is ignored) to input A. */
    fun pushA(chunk: ByteArray) {
        hasA = true
        carryA = append(carryA, chunk)
    }

    /** Appends [chunk] (whole frames; a trailing odd byte is ignored) to input B. */
    fun pushB(chunk: ByteArray) {
        hasB = true
        carryB = append(carryB, chunk)
    }

    /** Bytes queued on input A (diagnostics). */
    fun queuedABytes(): Int = carryA.size

    /** Bytes queued on input B (diagnostics). */
    fun queuedBBytes(): Int = carryB.size

    /**
     * Writes the next mixed frames into [dst]; returns the number of bytes
     * written (always a whole number of 2-byte frames).
     */
    fun mixInto(dst: ByteArray): Int {
        if (!hasA && !hasB) return 0

        if (hasA && hasB) {
            val bytes = minOf(carryA.size, carryB.size)
            val whole = bytes - (bytes % 2)
            var i = 0
            while (i < whole) {
                val mixed = (le16(carryA, i) + le16(carryB, i)).coerceIn(-32768, 32767)
                dst[i] = (mixed and 0xFF).toByte()
                dst[i + 1] = ((mixed shr 8) and 0xFF).toByte()
                i += 2
            }
            carryA = drop(carryA, whole)
            carryB = drop(carryB, whole)
            return whole
        }

        // One live path: pass it through untouched (capped to dst, whole frames).
        val src = if (hasA) carryA else carryB
        val bytes = minOf(src.size, dst.size)
        val whole = bytes - (bytes % 2)
        System.arraycopy(src, 0, dst, 0, whole)
        if (hasA) carryA = drop(carryA, whole) else carryB = drop(carryB, whole)
        return whole
    }

    // ------------------------------------------------------------------

    private fun append(carry: ByteArray, chunk: ByteArray): ByteArray {
        val usable = chunk.size - (chunk.size % 2)
        if (usable == 0) return carry
        val merged = ByteArray(carry.size + usable)
        System.arraycopy(carry, 0, merged, 0, carry.size)
        System.arraycopy(chunk, 0, merged, carry.size, usable)
        return if (merged.size > maxCarryBytes) {
            // Memory guard for a stalled consumer: keep the newest frames.
            merged.copyOfRange(merged.size - maxCarryBytes, merged.size)
        } else {
            merged
        }
    }

    private fun drop(carry: ByteArray, bytes: Int): ByteArray =
        if (bytes == 0) carry else carry.copyOfRange(bytes, carry.size)

    /** Signed 16-bit little-endian sample at [offset]. */
    private fun le16(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or (buf[offset + 1].toInt() shl 8)

    private companion object {
        /** Memory guard per input: ~4 s of 16 kHz mono, far beyond jitter. */
        const val DEFAULT_MAX_CARRY_BYTES = 256 * 1024
    }
}
