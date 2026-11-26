package com.lmalakar.onecr.privilege.api

import com.lmalakar.onecr.core.model.CaptureSource
import java.io.InputStream

/**
 * Request for one capture stream (M3). The opener may resolve the request to
 * a different [CaptureSource] than asked — the probe/fallback chain runs
 * inside the opener — so callers must read the granted values from the
 * returned [CaptureStream] instead of assuming the request was honored.
 */
data class CaptureStreamSpec(
    /** Preferred source; the opener falls back down the chain when blocked. */
    val source: CaptureSource,

    /** Preferred sample rate; the device grants what its HAL supports. */
    val sampleRateHz: Int = 16_000,
)

/**
 * One live PCM capture stream (M3, v0.3.3 in-process implementation).
 *
 * `pcm` is a blocking [InputStream]: `read()` returns only when audio bytes
 * are available, `-1` once the stream is stopped/closed (or the source died).
 * The recorder engine pumps this into a [com.lmalakar.onecr.service.recorder.WavWriter]
 * on its own thread. `stop()` ends capture and closes the pipe; `close()` is
 * an alias kept for AutoCloseable-style use.
 */
interface CaptureStream : AutoCloseable {

    /** The source actually granted (may differ from the requested one). */
    val source: CaptureSource

    /** Sample rate the device actually granted for this stream. */
    val grantedSampleRateHz: Int

    /** Blocking PCM (s16 LE) byte stream fed by the capture pump thread. */
    val pcm: InputStream

    /** End the capture: the pump thread stops and the pipe closes (read → -1). */
    fun stop()

    /** Alias of [stop] for try-with-resources style callers. */
    override fun close() = stop()
}
