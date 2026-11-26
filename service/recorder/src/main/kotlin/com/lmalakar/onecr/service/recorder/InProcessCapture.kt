package com.lmalakar.onecr.service.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.lmalakar.onecr.core.model.CaptureSource
import com.lmalakar.onecr.privilege.api.CaptureStream
import com.lmalakar.onecr.privilege.api.CaptureStreamSpec
import com.lmalakar.onecr.privilege.api.PrivilegeProvider
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * M3 step 1 (v0.3.3): in-process capture stream opener with a probe-verified
 * fallback chain. Requests are resolved in order
 * `requested → VOICE_CALL → MICROPHONE`; a candidate only counts as working
 * when the AudioRecord initializes **and** produces non-silent audio (on
 * Android 11+ a third-party app may open VOICE_CALL but the HAL feeds it
 * zeros — that candidate is skipped instead of recording silence).
 *
 * The privileged shell capture dex (roadmap M3) will replace the in-process
 * AudioRecord path for VOICE_* sources; the [CaptureStream] contract and the
 * engine stay unchanged when that happens.
 */
private const val PROBE_WINDOW_BYTES = 8_000 // ~250 ms @ 16 kHz mono s16

/**
 * Opens one capture stream, walking the fallback chain until a source
 * produces real audio. Throws [IOException] with a human-readable reason when
 * nothing works (missing RECORD_AUDIO, all sources silent/blocked, …).
 */
fun PrivilegeProvider.openCaptureStream(
    context: Context,
    spec: CaptureStreamSpec,
): CaptureStream {
    val chain = buildList {
        add(spec.source)
        add(CaptureSource.VOICE_CALL)
        add(CaptureSource.MICROPHONE)
    }.distinct()

    var lastFailure: String? = null
    for (candidate in chain) {
        val granted = runCatching { openSingle(context, candidate, spec.sampleRateHz) }
            .getOrElse { failure ->
                lastFailure = "${candidate.name}: ${failure.message ?: failure.javaClass.simpleName}"
                null
            }
        if (granted != null) return granted
    }
    throw IOException(
        "no working capture source on this device (tried " +
            chain.joinToString(" → ") { it.name } + "); last failure: ${lastFailure ?: "unknown"}",
    )
}

@SuppressLint("MissingPermission") // permission handled explicitly below
private fun openSingle(context: Context, source: CaptureSource, requestedRate: Int): CaptureStream? {
    val rate = requestedRate.coerceIn(8_000, 48_000)

    // RECORD_AUDIO gates every AudioRecord — including the VOICE_* probe.
    val micGranted = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    if (!micGranted) throw IOException("RECORD_AUDIO permission not granted")

    val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    if (minBuffer <= 0) throw IOException("minBufferSize failed for ${source.name}")

    val audioSource = when (source) {
        CaptureSource.VOICE_CALL -> MediaRecorder.AudioSource.VOICE_CALL
        CaptureSource.VOICE_DOWNLINK_UPLINK -> MediaRecorder.AudioSource.VOICE_DOWNLINK
        CaptureSource.MICROPHONE -> MediaRecorder.AudioSource.MIC
        CaptureSource.VOIP_PLAYBACK_MIX -> MediaRecorder.AudioSource.MIC
    }
    val record = AudioRecord(audioSource, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 4)
    if (record.state != AudioRecord.STATE_INITIALIZED) {
        record.release()
        throw IOException("${source.name} AudioRecord failed to initialize")
    }

    // Non-silence probe: on locked-down devices the open succeeds but the HAL
    // hands out zeros — treat that as "blocked" and let the chain continue.
    // The MIC source is accepted as-is (a silent room is a legitimate result;
    // genuine mic problems surface in the Settings diagnostics probe).
    if (source != CaptureSource.MICROPHONE) {
        val probe = ShortArray(PROBE_WINDOW_BYTES / 2)
        record.startRecording()
        var maxAmp = 0
        var filled = 0
        while (filled < probe.size) {
            val n = record.read(probe, filled, probe.size - filled)
            if (n <= 0) break
            for (i in filled until filled + n) {
                val v = abs(probe[i].toInt())
                if (v > maxAmp) maxAmp = v
            }
            filled += n
        }
        if (maxAmp == 0) {
            record.stop()
            record.release()
            throw IOException("${source.name} is silent (blocked by the OS for third-party apps)")
        }
    }

    // Live stream: pump thread feeds a pipe; the engine reads `pcm` and the
    // pipe back-pressure keeps memory flat for hour-long calls.
    val stopped = AtomicBoolean(false)
    val pipeIn = PipedInputStream(64 * 1024)
    val pipeOut = PipedOutputStream(pipeIn)
    val grantedRate = record.sampleRate

    record.startRecording()
    thread(name = "onecr-capture-${source.name.lowercase()}", isDaemon = true) {
        val buffer = ByteArray(4_096)
        try {
            while (!stopped.get()) {
                val n = record.read(buffer, 0, buffer.size)
                if (n <= 0) break
                pipeOut.write(buffer, 0, n)
            }
        } catch (_: Throwable) {
            // reader gone / device torn down — close below signals the engine
        } finally {
            stopped.set(true)
            runCatching { record.stop() }
            runCatching { record.release() }
            runCatching { pipeOut.flush() }
            runCatching { pipeOut.close() }
        }
    }

    return object : CaptureStream {
        override val source: CaptureSource = source
        override val grantedSampleRateHz: Int = grantedRate
        override val pcm: java.io.InputStream = pipeIn

        override fun stop() {
            if (stopped.compareAndSet(false, true)) {
                // Closing the pipe unblocks the pump loop even if AudioRecord
                // is wedged — the finally-block releases the hardware.
                runCatching { pipeOut.close() }
            }
        }

        override fun close() = stop()
    }
}
