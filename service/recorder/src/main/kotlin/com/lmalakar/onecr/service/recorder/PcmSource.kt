package com.lmalakar.onecr.service.recorder

import com.lmalakar.onecr.privilege.api.CaptureStream
import java.io.Closeable
import java.io.InputStream

/**
 * A live PCM stream the recording engine pumps into a WAV (v0.3.7).
 *
 * Carries RAW 16-bit little-endian mono samples at [grantedSampleRateHz].
 * Two implementations exist: the privileged shell stream (cellular capture —
 * `CaptureStream` from :privilege:api, wrapped by [ShellPcmSource]) and the
 * in-app VoIP dual-path mix (`voip.VoipPcmSource` — MediaProjection playback
 * capture + microphone). The engine treats them identically.
 */
interface PcmSource : Closeable {
    val pcm: InputStream

    /** Sample rate actually granted. */
    val grantedSampleRateHz: Int

    /** Requests the stream to end. Safe to call more than once. */
    fun stop()
}

/** Wraps the privileged shell stream into the engine's source abstraction. */
class ShellPcmSource(private val delegate: CaptureStream) : PcmSource {
    override val pcm: InputStream get() = delegate.pcm
    override val grantedSampleRateHz: Int get() = delegate.grantedSampleRateHz
    override fun stop() = delegate.stop()
    override fun close() = delegate.close()
}
