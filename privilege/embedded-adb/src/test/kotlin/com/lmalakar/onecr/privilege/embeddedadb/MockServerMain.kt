package com.lmalakar.onecr.privilege.embeddedadb

import java.net.ServerSocket

/**
 * Entry point of the out-of-process mock server helper JVM.
 *
 * Usage: MockServerMain <adbd|pairing> [code]
 *
 * Prints "READY <port>" on stdout once the TLS listener accepts, then serves
 * connections until killed. Run with a CLEAN classpath (module jar + test
 * classes + conscrypt uber + kotlin stdlib) — no android.jar.
 */
internal object MockServerMain {

    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args.getOrNull(0) ?: error("mode required")
        val code = args.getOrNull(1) ?: "419207"
        val identity = TestHooks.newIdentity()
        val listener: ServerSocket = when (mode) {
            "adbd" -> TestHooks.tlsListener(identity, 0, needClientAuth = true)
            "pairing" -> TestHooks.tlsListener(identity, 0, needClientAuth = true)
            else -> error("unknown mode $mode")
        }
        val acceptThread = Thread {
            while (!listener.isClosed) {
                try {
                    val raw = listener.accept()
                    Thread {
                        try {
                            val ssl = raw as javax.net.ssl.SSLSocket
                            ssl.startHandshake()
                            when (mode) {
                                "adbd" -> TestHooks.serveAdbd(ssl)
                                "pairing" -> TestHooks.servePairing(ssl, code)
                            }
                        } catch (e: Exception) {
                            System.err.println("[mock] session error: $e")
                        } finally {
                            runCatching { raw.close() }
                        }
                    }.apply { isDaemon = true }.start()
                } catch (e: Exception) {
                    if (!listener.isClosed) System.err.println("[mock] accept error: $e")
                }
            }
        }.apply { isDaemon = true }
        acceptThread.start()
        println("READY ${listener.localPort}")
        System.out.flush()
        // Block until killed.
        Thread.currentThread().join()
    }
}
