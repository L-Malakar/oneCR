package com.lmalakar.onecr.privilege.embeddedadb

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Starts the mock server helper JVM (clean classpath: no android.jar, so
 * javax.net.ssl resolves from the JDK and Conscrypt's uber jar works).
 */
internal object MockServerProcess {

    @Volatile
    private var stderrFileRef: File? = null

    class Started(val process: Process, val port: Int) {
        fun stop() {
            process.destroyForcibly()
            runCatching { process.waitFor(2, TimeUnit.SECONDS) }
            stderrFileRef?.takeIf { it.length() > 0 }?.let {
                System.err.println("[mock-server stderr] " + it.absolutePath)
            }
        }
    }

    fun start(mode: String, code: String = "419207"): Started {
        val javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java"
        val classpath = listOf(
            codeSourceLocation(MockServerMain::class.java),   // test classes
            codeSourceLocation(Spake2::class.java),          // module main classes
            jarForResource("org/conscrypt/Conscrypt.class"), // conscrypt uber jar
            jarForResource("kotlin/Unit.class"),             // kotlin stdlib
            jarForResource("org/jetbrains/annotations/NotNull.class"), // annotations, if present
        ).filterNotNull().joinToString(File.pathSeparator)

        val stderrFile = File.createTempFile("mock-server-$mode", ".err")
        stderrFile.deleteOnExit()
        val process = ProcessBuilder(
            javaBin,
            "--add-opens", "java.base/java.net=ALL-UNNAMED",
            "--add-opens", "java.base/java.nio=ALL-UNNAMED",
            "-cp", classpath, MockServerMain::class.java.name, mode, code,
        )
            .redirectErrorStream(false)
            .redirectError(ProcessBuilder.Redirect.appendTo(stderrFile))
            .start()
        stderrFileRef = stderrFile

        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val readyLine = reader.readLine() // "READY <port>"
        if (readyLine == null || !readyLine.startsWith("READY ")) {
            val err = process.errorStream.bufferedReader().readText()
            process.destroyForcibly()
            error("mock server failed to start: '$readyLine' stderr=$err")
        }
        return Started(process, readyLine.removePrefix("READY ").trim().toInt())
    }

    private fun codeSourceLocation(clazz: Class<*>): String? =
        runCatching { clazz.protectionDomain.codeSource.location.toURI().path }.getOrNull()

    private fun jarForResource(resource: String): String? {
        val url = MockServerProcess::class.java.classLoader.getResource(resource) ?: return null
        val path = url.path
        return when {
            path.startsWith("file:") && path.contains("!") ->
                path.removePrefix("file:").substringBefore("!").removeSuffix("!")
            path.startsWith("file:") && path.endsWith(".jar") -> path.removePrefix("file:")
            else -> null
        }
    }
}
