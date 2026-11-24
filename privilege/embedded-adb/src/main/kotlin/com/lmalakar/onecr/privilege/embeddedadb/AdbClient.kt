package com.lmalakar.onecr.privilege.embeddedadb

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLSocket

/**
 * Minimal ADB client over a TLS 1.3 wireless-debugging connection.
 *
 * After the TLS handshake the classic ADB protocol runs inside it (adb does the
 * same — client/auth.cpp adb_auth_tls_handshake: TLS then straight to CNXN):
 *  CNXN handshake → OPEN "shell,v2:<cmd>" → OKAY → WRTE frames → CLSE.
 * Shell v2 frame = [id u8][len u32 LE][data], ids: 1 stdout / 2 stderr / 3 exit.
 *
 * v0.3.2: the packet codec moved to [AdbWire] (shared with the PCM streaming
 * client); behavior unchanged.
 */
internal class AdbClient(
    private val identity: AdbIdentity,
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
) : Closeable {

    data class ShellResult(val combinedOutput: String, val exitCode: Int?)

    private var socket: SSLSocket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null
    private var maxPayload = AdbWire.DEFAULT_MAX_PAYLOAD
    private var frameCarry: ByteArray = ByteArray(0) // partial shell v2 frame bytes

    @Synchronized
    fun connect() {
        if (isAlive()) return
        closeQuietly()
        val s = TlsSupport.connect(identity, host, port, connectTimeoutMs)
        val i = DataInputStream(BufferedInputStream(s.inputStream))
        val o = s.outputStream
        AdbWire.writePacket(o, AdbWire.CMD_CNXN, AdbWire.VERSION, AdbWire.DEFAULT_MAX_PAYLOAD, "host::onecr".toByteArray())
        val (cmd, arg0, arg1, payload) = AdbWire.readPacket(i, maxPayload)
        when (cmd) {
            AdbWire.CMD_CNXN -> {
                maxPayload = arg1.takeIf { it > 0 } ?: AdbWire.DEFAULT_MAX_PAYLOAD
            }
            AdbWire.CMD_AUTH -> throw IOException("device demands legacy AUTH — pairing key not accepted")
            else -> throw IOException("unexpected packet ${AdbWire.cmdString(cmd)} during CNXN handshake")
        }
        socket = s
        input = i
        output = o
        frameCarry = ByteArray(0)
    }

    /** Cheap liveness probe (TLS session open). */
    fun isAlive(): Boolean {
        val s = socket ?: return false
        return !s.isClosed && !s.isInputShutdown && !s.isOutputShutdown
    }

    /**
     * Runs a command via the shell v2 service; returns merged output and the
     * exit code (null when the device closed without an exit frame).
     * A stale TLS session (device rebooted, Wi-Fi changed, adbd restarted)
     * triggers exactly one reconnect-and-retry.
     */
    @Synchronized
    fun runShell(command: String): ShellResult = try {
        runShellOnce(command)
    } catch (e: IOException) {
        closeQuietly()
        connect()
        runShellOnce(command)
    }

    private fun runShellOnce(command: String): ShellResult {
        if (!isAlive()) connect()
        val i = input ?: throw IOException("not connected")
        val o = output ?: throw IOException("not connected")

        frameCarry = ByteArray(0)
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        var exitCode: Int? = null

        val localId = AdbWire.NEXT_LOCAL_ID.incrementAndGet()
        val service = "shell,v2:$command".toByteArray(StandardCharsets.US_ASCII) + 0
        AdbWire.writePacket(o, AdbWire.CMD_OPEN, localId, 0, service)

        var remoteId = 0
        var closed = false
        while (!closed && exitCode == null) {
            val (cmd, arg0, arg1, payload) = AdbWire.readPacket(i, maxPayload)
            when (cmd) {
                AdbWire.CMD_OKAY -> {
                    if (arg1 != localId) throw IOException("OKAY id mismatch (${arg0}, ${arg1})")
                    remoteId = arg0
                }
                AdbWire.CMD_WRTE -> {
                    if (arg1 != localId) throw IOException("WRTE id mismatch (${arg0}, ${arg1})")
                    consumeFrames(payload, stdout, stderr) {
                        exitCode = it
                    }
                    // The adb protocol ACKs every WRTE with OKAY.
                    AdbWire.writePacket(o, AdbWire.CMD_OKAY, localId, remoteId, AdbWire.EMPTY)
                }
                AdbWire.CMD_CLSE -> {
                    if (arg1 == localId) closed = true
                }
                AdbWire.CMD_AUTH -> throw IOException("unexpected AUTH during shell")
                else -> throw IOException("unexpected packet ${AdbWire.cmdString(cmd)} during shell")
            }
        }

        // Politely close the stream if the device did not.
        if (!closed && remoteId != 0) {
            runCatching { AdbWire.writePacket(o, AdbWire.CMD_CLSE, localId, remoteId, AdbWire.EMPTY) }
        }

        val combined = buildString {
            append(stdout)
            if (stderr.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append(stderr)
            }
        }
        return ShellResult(combined.trimEnd(), exitCode)
    }

    /**
     * Parses shell v2 frames ([id u8][len u32 LE][data]) out of accumulated
     * WRTE payloads, carrying partial frames across packets.
     */
    private fun consumeFrames(chunk: ByteArray, stdout: StringBuilder, stderr: StringBuilder, onExit: (Int) -> Unit) {
        var buf: ByteArray
        if (frameCarry.isEmpty()) {
            buf = chunk
        } else {
            buf = ByteArray(frameCarry.size + chunk.size)
            System.arraycopy(frameCarry, 0, buf, 0, frameCarry.size)
            System.arraycopy(chunk, 0, buf, frameCarry.size, chunk.size)
        }

        var off = 0
        while (off + 5 <= buf.size) {
            val id = buf[off].toInt() and 0xFF
            val len = ((buf[off + 1].toInt() and 0xFF)) or
                ((buf[off + 2].toInt() and 0xFF) shl 8) or
                ((buf[off + 3].toInt() and 0xFF) shl 16) or
                ((buf[off + 4].toInt() and 0xFF) shl 24)
            if (off + 5 + len > buf.size) break // incomplete frame — wait for more
            val data = String(buf, off + 5, len, Charsets.ISO_8859_1)
            when (id) {
                AdbWire.FRAME_STDOUT, AdbWire.FRAME_STDIN -> stdout.append(data)
                AdbWire.FRAME_STDERR -> stderr.append(data)
                AdbWire.FRAME_EXIT -> if (len >= 1) onExit(buf[off + 5].toInt() and 0xFF)
                else -> Unit // unknown frame — ignore (forward compatible)
            }
            off += 5 + len
        }
        frameCarry = if (off < buf.size) buf.copyOfRange(off, buf.size) else ByteArray(0)
    }

    @Synchronized
    override fun close() {
        closeQuietly()
    }

    private fun closeQuietly() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = AdbWire.CONNECT_TIMEOUT_MS
    }
}
