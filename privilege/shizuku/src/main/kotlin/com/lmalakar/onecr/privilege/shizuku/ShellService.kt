package com.lmalakar.onecr.privilege.shizuku

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

    companion object {
        private const val EXEC_TIMEOUT_SECONDS = 10L
        const val EXIT_MARKER = "__onecr_exit__"
    }
}
