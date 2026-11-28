package com.lmalakar.onecr.service.recorder.voip

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.SystemClock
import com.lmalakar.onecr.data.files.StorageLayout
import com.lmalakar.onecr.data.files.WavWriter
import com.lmalakar.onecr.service.recorder.PcmSource
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The in-app VoIP dual-path capture (v0.3.7 — the M3 next step on the
 * roadmap): one [MediaProjection] playback capture plus the app's own
 * microphone, mixed into a single mono PCM stream the recording engine pumps
 * into a WAV.
 *
 * Honest capability notes (they shape the Settings copy too):
 *  - Android never exposes another app's call audio to playback capture —
 *    `AudioPlaybackCaptureConfiguration` only matches MEDIA/GAME/UNKNOWN
 *    usages, while VoIP apps play remote voice as USAGE_VOICE_COMMUNICATION.
 *    The playback path therefore catches the media-style audio around a call
 *    (ringtones, in-app sounds, and any app that plays its audio as media),
 *    not the protected voice stream itself.
 *  - The microphone path hears the local speaker and, on speakerphone, the
 *    far end acoustically. While a VoIP app holds a voice-communication
 *    capture, Android's concurrent-capture policy may silence this path on
 *    some devices; on others it works — the mix degrades gracefully to
 *    whichever path delivers audio.
 * Both paths are also kept as separate raw WAVs (playback.wav / mic.wav) so
 * per-speaker labeling can pick them apart later (M5).
 *
 * Threading: two reader threads (playback, mic) feed bounded queues; one
 * mixer thread drains both into a piped stream the engine reads. Everything
 * is torn down by [stop]/[close]. The [MediaProjection] itself is NOT stopped
 * here — the armed session owns it and stays armed for the next call.
 */
class VoipPcmSource(
    context: Context,
    projection: MediaProjection,
    rawDir: File,
    sampleRateHz: Int,
    micEnabled: Boolean,
    private val maxDurationMs: Long = DEFAULT_MAX_DURATION_MS,
    private val onNote: (String) -> Unit = {},
) : PcmSource {

    override val grantedSampleRateHz: Int = sampleRateHz

    private val pipe = PipedOutputStream()
    override val pcm: InputStream = PipedInputStream(PIPE_BUFFER_BYTES).also { it.connect(pipe) }

    private val stopped = AtomicBoolean(false)
    private val startedAtElapsed = SystemClock.elapsedRealtime()

    private val playbackQueue = ArrayBlockingQueue<Chunk>(QUEUE_CHUNKS)
    private val micQueue = ArrayBlockingQueue<Chunk>(QUEUE_CHUNKS)
    private val mixer = PcmMixer2()

    private val playbackRecord: AudioRecord
    private val micRecord: AudioRecord?
    private val playbackWav: WavWriter
    private val micWav: WavWriter?
    private val workers = ArrayList<Thread>(3)

    private class Chunk(val data: ByteArray, val last: Boolean)

    init {
        if (sampleRateHz !in 8_000..192_000) throw IOException("bad VoIP sample rate $sampleRateHz")
        rawDir.mkdirs()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRateHz)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(MIN_BUFFER_BYTES)

        // Path 1 — playback capture (API 29+): whatever the phone plays as
        // media/game/unknown usage while the projection is live.
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val playback = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuffer)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
        if (playback.state != AudioRecord.STATE_INITIALIZED) {
            playback.release()
            throw IOException("playback capture source not initialized")
        }
        playbackRecord = playback
        playbackWav = WavWriter.create(File(rawDir, StorageLayout.PLAYBACK_STREAM), sampleRateHz, channels = 1)

        // Path 2 — the microphone (optional: RECORD_AUDIO may be declined, and
        // some devices silence it while the VoIP app captures — degrade, never fail).
        var mic: AudioRecord? = null
        var micWriter: WavWriter? = null
        if (micEnabled) {
            val candidate = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer,
            )
            if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                mic = candidate
                micWriter = WavWriter.create(File(rawDir, StorageLayout.MIC_STREAM), sampleRateHz, channels = 1)
            } else {
                candidate.release()
                onNote("microphone unavailable — recording the playback path only")
            }
        }
        micRecord = mic
        micWav = micWriter

        workers += Thread({ readerLoop(playbackRecord, playbackQueue, playbackWav) }, "onecr-voip-playback")
        micRecord?.let { mic ->
            workers += Thread({ readerLoop(mic, micQueue, micWav) }, "onecr-voip-mic")
        }
        workers += Thread({ mixerLoop() }, "onecr-voip-mixer")
        workers.forEach {
            it.isDaemon = true
            it.start()
        }
    }

    // ------------------------------------------------------------------
    // PcmSource
    // ------------------------------------------------------------------

    override fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        // read() only unblocks on an error/stop — nudge both records now.
        runCatching { playbackRecord.stop() }
        micRecord?.let { runCatching { it.stop() } }
    }

    override fun close() {
        stop()
        workers.forEach { runCatching { it.join(THREAD_JOIN_MS) } }
        workers.clear()
        runCatching { pipe.close() }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun readerLoop(record: AudioRecord, queue: ArrayBlockingQueue<Chunk>, wav: WavWriter?) {
        val buf = ByteArray(READ_CHUNK_BYTES)
        try {
            record.startRecording()
            while (!stopped.get()) {
                val n = record.read(buf, 0, buf.size)
                if (n <= 0) break
                val usable = n - (n % 2)
                if (usable > 0) {
                    runCatching { wav?.writePcm(buf, usable) }
                    queue.put(Chunk(buf.copyOf(usable), last = false))
                }
            }
        } catch (_: Throwable) {
            // torn down (stop/close)
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
            runCatching {
                wav?.finalize()
                wav?.close()
            }
            runCatching { queue.put(Chunk(ByteArray(0), last = true)) }
        }
    }

    private fun mixerLoop() {
        val out = ByteArray(MIX_CHUNK_BYTES)
        try {
            var aDone = false
            // The mic path is "done" from the start when it does not exist.
            var bDone = micRecord == null
            while (!stopped.get()) {
                if (SystemClock.elapsedRealtime() - startedAtElapsed > maxDurationMs) break
                if (!aDone) aDone = drain(playbackQueue, mixer::pushA)
                if (!bDone) bDone = drain(micQueue, mixer::pushB)
                val n = mixer.mixInto(out)
                if (n > 0) pipe.write(out, 0, n)
                if (aDone && bDone) break
                if (n == 0) Thread.sleep(MIXER_IDLE_SLEEP_MS)
            }
        } catch (_: Throwable) {
            // stopped or the engine closed the read side
        } finally {
            runCatching { pipe.close() }
        }
    }

    /** Pumps queued chunks into [push]; returns true once the side is done. */
    private fun drain(queue: ArrayBlockingQueue<Chunk>, push: (ByteArray) -> Unit): Boolean {
        var chunk = queue.poll() ?: return false
        while (true) {
            if (chunk.last) return true
            push(chunk.data)
            chunk = queue.poll() ?: return false
        }
    }

    private companion object {
        const val DEFAULT_MAX_DURATION_MS = 2 * 60 * 60 * 1000L

        /** 16 kHz mono s16 needs 32 KB/s — 256 KiB of pipe is 8 s of slack. */
        const val PIPE_BUFFER_BYTES = 256 * 1024

        /** 32 chunks x 4 KiB = ~128 KiB of smoothing per path. */
        const val QUEUE_CHUNKS = 32
        const val READ_CHUNK_BYTES = 4 * 1024
        const val MIX_CHUNK_BYTES = 8 * 1024
        const val MIXER_IDLE_SLEEP_MS = 5L
        const val THREAD_JOIN_MS = 2_000L
        const val MIN_BUFFER_BYTES = 8 * 1024
    }
}
