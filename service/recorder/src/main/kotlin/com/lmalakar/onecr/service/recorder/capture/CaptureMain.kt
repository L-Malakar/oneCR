package com.lmalakar.onecr.service.recorder.capture

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The shell capture process (M3, v0.3.2) — the tiny privileged entry point
 * spawned via `CLASSPATH=<apk> app_process / com.lmalakar.onecr.service.recorder.capture.CaptureMain …`
 * as the adb-shell user (UID 2000), which holds CAPTURE_AUDIO_OUTPUT.
 *
 * Subcommands (see :privilege:api CaptureProtocol for the full contract):
 *
 *  probe --rates 16000,8000,44100 --ms 400
 *      Opens every (source, rate) pair for a short window and prints a
 *      line-based report to stdout:
 *        UID|<uid>
 *        SRC|<source>|<rate>|OK|<framesRead>
 *        SRC|<source>|<rate>|SILENT|<framesRead>   (opened, but routed nothing)
 *        SRC|<source>|<rate>|FAIL|0|<error>
 *        DONE
 *      Human/diagnostic detail goes to stderr ONLY.
 *
 *  start --source VOICE_CALL --rate 16000 --max-ms 7200000
 *      Streams RAW PCM (s16 LE, mono) to stdout until a stdin line starting
 *      with "stop" arrives, the stream closes, or --max-ms elapses.
 *      stderr carries `RATE|<hz>` (granted rate) and `END|exit=0` markers.
 *
 * MUST stay framework-only (no androidx, no app classes): it runs inside a
 * bare app_process with just this APK on the classpath.
 */
object CaptureMain {

    private const val EXIT_OK = 0
    private const val EXIT_FATAL = 1
    private const val EXIT_PIPE_BROKEN = 2

    // Ordered probe plan: preferred mixed source first, then the split pair
    // (downlink+uplink compose into the dual-stream mode), mic as the fallback.
    private val PROBE_SOURCES = listOf("VOICE_CALL", "VOICE_DOWNLINK", "VOICE_UPLINK", "MIC")

    private val stopFlag = AtomicBoolean(false)

    @JvmStatic
    fun main(args: Array<String>) {
        val stdout = System.out
        val stderr = System.err
        try {
            when (args.firstOrNull()) {
                "probe" -> runProbe(args, stdout, stderr)
                "start" -> runStart(args, stdout, stderr)
                else -> {
                    stderr.println("usage: CaptureMain probe|start …")
                    kotlin.system.exitProcess(EXIT_FATAL)
                }
            }
        } catch (t: Throwable) {
            stderr.println("FATAL|${t.javaClass.simpleName}: ${t.message}")
            kotlin.system.exitProcess(EXIT_FATAL)
        }
    }

    // ------------------------------------------------------------------
    // probe
    // ------------------------------------------------------------------

    private fun runProbe(args: Array<String>, stdout: java.io.PrintStream, stderr: java.io.PrintStream) {
        val rates = args.option("rates")
            ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
            ?: listOf(16_000, 8_000, 44_100)
        val windowMs = args.option("ms")?.toIntOrNull() ?: 400

        stdout.println("UID|${android.os.Process.myUid()}")
        stdout.flush()

        for (source in PROBE_SOURCES) {
            for (rate in rates) {
                val outcome = probeSource(source, rate, windowMs)
                stdout.println(outcome)
                stdout.flush()
            }
        }
        stdout.println("DONE")
        stdout.flush()
    }

    private fun probeSource(sourceName: String, rate: Int, windowMs: Int): String {
        val source = audioSource(sourceName)
            ?: return "SRC|$sourceName|$rate|FAIL|0|unknown source"
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) {
            return "SRC|$sourceName|$rate|FAIL|0|minBufferSize=$minBuffer"
        }
        var record: AudioRecord? = null
        return try {
            record = AudioRecord(
                source,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer * 2, 8_192),
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                return "SRC|$sourceName|$rate|FAIL|0|init state=${record.state}"
            }
            record.startRecording()
            val buf = ShortArray(1_024)
            var frames = 0L
            var silent = true
            val deadline = System.currentTimeMillis() + windowMs
            while (System.currentTimeMillis() < deadline) {
                val n = record.read(buf, 0, buf.size)
                if (n <= 0) {
                    return "SRC|$sourceName|$rate|FAIL|$frames|read=$n"
                }
                frames += n
                var i = 0
                while (i < n) {
                    if (buf[i].toInt() != 0) {
                        silent = false
                        break
                    }
                    i++
                }
                if (!silent) break
            }
            if (silent) "SRC|$sourceName|$rate|SILENT|$frames" else "SRC|$sourceName|$rate|OK|$frames"
        } catch (t: Throwable) {
            "SRC|$sourceName|$rate|FAIL|0|${t.javaClass.simpleName}: ${t.message.orEmpty().take(80)}"
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
        }
    }

    // ------------------------------------------------------------------
    // start
    // ------------------------------------------------------------------

    private fun runStart(args: Array<String>, stdout: java.io.PrintStream, stderr: java.io.PrintStream) {
        val sources = args.option("source")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: listOf("VOICE_CALL")
        val rate = args.option("rate")?.toIntOrNull() ?: 16_000
        val maxMs = args.option("max-ms")?.toLongOrNull() ?: 2 * 60 * 60 * 1000L

        if (sources.size > 1) {
            // Dual downlink+uplink interleaving lands with M3 step 2 — record
            // the preferred stream now and say so on stderr.
            stderr.println("WARN|multi-source request — using ${sources.first()} (dual-stream arrives in a later M3 step)")
        }
        val sourceName = sources.first()
        val source = audioSource(sourceName) ?: run {
            stderr.println("ERR|unknown source $sourceName")
            kotlin.system.exitProcess(EXIT_FATAL)
        }

        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) {
            stderr.println("ERR|minBufferSize=$minBuffer for rate $rate")
            kotlin.system.exitProcess(EXIT_FATAL)
        }

        val record = AudioRecord(
            source,
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer * 2, 8_192),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            stderr.println("ERR|AudioRecord init failed state=${record.state} source=$sourceName rate=$rate")
            record.release()
            kotlin.system.exitProcess(EXIT_FATAL)
        }

        // Stop watcher: a "stop" line on stdin, or stdin closing (EOF), ends the session.
        val stdinWatcher = Thread({
            try {
                val reader = System.`in`.bufferedReader()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.trim().startsWith("stop")) break
                }
            } catch (_: Throwable) {
                // stdin gone — treat as stop
            }
            stopFlag.set(true)
        }, "onecr-capture-stdin").apply {
            isDaemon = true
            start()
        }

        record.startRecording()
        // Granted-rate marker on stderr (stdout carries PCM ONLY).
        stderr.println("RATE|$rate")
        stderr.flush()

        var exit = EXIT_OK
        try {
            val buf = ShortArray(2_048)
            val pcmBytes = ByteArray(buf.size * 2)
            val deadline = System.currentTimeMillis() + maxMs
            var sinceFlush = 0
            while (!stopFlag.get() && System.currentTimeMillis() < deadline) {
                val n = record.read(buf, 0, buf.size)
                if (n < 0) {
                    stderr.println("ERR|read=$n")
                    exit = EXIT_FATAL
                    break
                }
                if (n == 0) continue

                // s16 → little-endian bytes
                var b = 0
                for (i in 0 until n) {
                    pcmBytes[b++] = (buf[i].toInt() and 0xFF).toByte()
                    pcmBytes[b++] = ((buf[i].toInt() shr 8) and 0xFF).toByte()
                }
                stdout.write(pcmBytes, 0, b)
                sinceFlush += b
                if (sinceFlush >= FLUSH_THRESHOLD_BYTES) {
                    stdout.flush()
                    sinceFlush = 0
                }
            }
            stdout.flush()
        } catch (e: IOException) {
            // stdout pipe broken (app crashed/closed) — nothing left to stream to.
            stderr.println("ERR|stdout broken: ${e.message}")
            exit = EXIT_PIPE_BROKEN
        } finally {
            record.stop()
            record.release()
            stopFlag.set(true)
            runCatching { stdinWatcher.join(1_000) }
        }

        stderr.println("END|exit=$exit")
        stderr.flush()
        kotlin.system.exitProcess(exit)
    }

    // ------------------------------------------------------------------

    private fun audioSource(name: String): Int? = when (name.uppercase()) {
        "VOICE_CALL" -> MediaRecorder.AudioSource.VOICE_CALL
        "VOICE_DOWNLINK" -> MediaRecorder.AudioSource.VOICE_DOWNLINK
        "VOICE_UPLINK" -> MediaRecorder.AudioSource.VOICE_UPLINK
        "MIC" -> MediaRecorder.AudioSource.MIC
        else -> null
    }

    private fun Array<String>.option(key: String): String? {
        val idx = indexOf("--$key")
        return if (idx >= 0 && idx + 1 < size) this[idx + 1] else null
    }

    private const val FLUSH_THRESHOLD_BYTES = 16 * 1024
}
