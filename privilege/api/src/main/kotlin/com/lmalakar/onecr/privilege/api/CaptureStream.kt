package com.lmalakar.onecr.privilege.api

import com.lmalakar.onecr.core.model.CaptureSource
import java.io.Closeable
import java.io.InputStream

/** Parameters for one shell capture session (M3). */
data class CaptureStreamSpec(
    /** Audio source the capture process opens as UID 2000. */
    val source: CaptureSource,

    /** Requested sample rate; the probe result is the usual source of truth. */
    val sampleRateHz: Int = 16_000,

    /**
     * Hard safety stop — a stuck capture process must never record forever.
     * Default 2 h: no call runs longer, and the engine stops the stream on
     * call end anyway.
     */
    val maxDurationMs: Long = 2 * 60 * 60 * 1000L,
)

/**
 * A live PCM stream from the privileged capture process.
 *
 * `pcm` carries RAW 16-bit little-endian mono samples at [CaptureStreamSpec.sampleRateHz].
 * [stop] is best-effort and non-blocking: it writes the control word and closes
 * the control channel; the process then finishes its WAV-side handshake and exits.
 * [awaitExit] lets the caller reap the exit code (null = closed without one).
 */
interface CaptureStream : Closeable {
    val pcm: InputStream

    /** Sample rate actually granted (the device may differ from the request). */
    val grantedSampleRateHz: Int

    /** Sends the stop control. Safe to call more than once. */
    fun stop()

    /** Blocks until the capture process exits or [timeoutMs] elapses. */
    fun awaitExit(timeoutMs: Long): Int?
}
