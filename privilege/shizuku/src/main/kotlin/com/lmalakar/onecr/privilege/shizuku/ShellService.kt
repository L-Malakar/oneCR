package com.lmalakar.onecr.privilege.shizuku

import android.os.ParcelFileDescriptor
import android.system.Os
import java.util.concurrent.TimeUnit

/**
 * The ENTIRE privileged surface of oneCR (M2 edition).
 *
 * This class is loaded by the Shizuku server into its own process, which runs as
 * the adb shell user (UID 2000). Everything here executes with shell privileges —
 * keep it tiny, auditable and free of app logic. The audio-capture methods
 * (probe/start/stop with pipe FDs) join this same class in M3.
 *
 * Conventions (see IShellService.aidl):
 *  - [exec] returns combined stdout+stderr plus a trailing "__onecr_exit__N" line;
 *  - the app-side provider parses that trailer into a real exit code.
 */
class ShellService : IShellService.Stub() {

    override fun destroy() {
        // Reserved method the Shizuku server calls when unbinding (remove=true).
        System.exit(0)
    }

    override fun exec(command: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = runCatching {
                process.inputStream.use { it.readBytes().decodeToString() }
            }.getOrDefault("")

            val finished = runCatching {
                process.waitFor(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }.getOrDefault(false)
            if (!finished) {
                runCatching { process.destroyForcibly() }
            }
            val code = runCatching { process.exitValue() }.getOrDefault(-1)
            "$output\n$EXIT_MARKER$code"
        } catch (t: Throwable) {
            "\n$EXIT_MARKER-1 ${t.message.orEmpty().replace('\n', ' ')}"
        }
    }

    override fun getUid(): Int = runCatching { Os.getuid() }.getOrDefault(-1)

    /**
     * M3: spawn the capture process (`CLASSPATH=<apk> app_process … CaptureMain …`)
     * as this process (UID 2000) and pipe its PCM back over binder.
     *
     * spec    — the full shell command (CaptureProtocol.startCommand);
     * control — write end the app uses to send the stop word (becomes the
     *           capture process's stdin);
     * return  — read end of a pipe carrying the process's raw stdout (PCM).
     *
     * Short-lived pump threads move bytes between the process streams and
     * the binder pipes; both die with the process. Errors are reported by
     * closing the returned pipe (the app sees EOF immediately).
     */
    override fun openCaptureStream(spec: String, control: ParcelFileDescriptor): ParcelFileDescriptor {
        try {
            val pcmPipe = ParcelFileDescriptor.createPipe() // [0]=read, [1]=write
            val process = ProcessBuilder("sh", "-c", spec)
                .redirectErrorStream(false)
                .start()

            // process stdout → pcm pipe write end (PCM bytes only; stderr is
            // drained separately so the process never blocks on a full pipe).
            Thread({
                try {
                    process.inputStream.use { src ->
                        ParcelFileDescriptor.AutoCloseOutputStream(pcmPipe[1]).use { dst ->
                            src.copyTo(dst, PUMP_BUFFER_BYTES)
                        }
                    }
                } catch (_: Throwable) {
                    runCatching { pcmPipe[1].close() }
                } finally {
                    runCatching { process.waitFor(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                }
            }, "onecr-pcm-pump").apply { isDaemon = true }.start()

            // control pipe read end → process stdin (the "stop" word).
            Thread({
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(control).use { src ->
                        process.outputStream.use { dst ->
                            src.copyTo(dst, PUMP_BUFFER_BYTES)
                        }
                    }
                } catch (_: Throwable) {
                    // control closed — EOF also signals the capture process to stop
                } finally {
                    runCatching { process.outputStream.close() }
                }
            }, "onecr-control-pump").apply { isDaemon = true }.start()

            // stderr → nowhere (drain so the process never blocks on a full pipe).
            Thread({
                runCatching { process.errorStream.use { it.readBytes() } }
            }, "onecr-stderr-drain").apply { isDaemon = true }.start()

            return pcmPipe[0]
        } catch (t: Throwable) {
            runCatching { control.close() }
            // An empty closed pipe = immediate EOF on the app side.
            val pipe = ParcelFileDescriptor.createPipe()
            runCatching { pipe[1].close() }
            return pipe[0]
        }
    }

    companion object {
        private const val EXEC_TIMEOUT_SECONDS = 10L
        private const val PUMP_BUFFER_BYTES = 16 * 1024
        const val EXIT_MARKER = "__onecr_exit__"
    }
}
