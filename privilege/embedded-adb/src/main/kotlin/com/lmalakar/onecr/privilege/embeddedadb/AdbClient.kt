package com.lmalakar.onecr.privilege.embeddedadb

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket

/**
 * Minimal ADB client over a TLS 1.3 wireless-debugging connection.
 *
 * After the TLS handshake the classic ADB protocol runs inside it (adb does the
 * same — client/auth.cpp adb_auth_tls_handshake: TLS then straight to CNXN):
 *  CNXN handshake → OPEN "shell,v2:<cmd>" → OKAY → WRTE frames → CLSE.
 * Shell v2 frame = [id u8][len u32 LE][data], ids: 1 stdout / 2 stderr / 3 exit.
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
    private var maxPayload = DEFAULT_MAX_PAYLOAD
    private var frameCarry: ByteArray = ByteArray(0) // partial shell v2 frame bytes

    @Synchronized
    fun connect() {
        if (isAlive()) return
        closeQuietly()
        val s = TlsSupport.connect(identity, host, port, connectTimeoutMs)
        val i = DataInputStream(BufferedInputStream(s.inputStream))
        val o = s.outputStream
        writePacket(o, CMD_CNXN, VERSION, DEFAULT_MAX_PAYLOAD, "host::onecr".toByteArray())
        val (cmd, arg0, arg1, payload) = readPacket(i)
        when (cmd) {
            CMD_CNXN -> {
                maxPayload = arg1.takeIf { it > 0 } ?: DEFAULT_MAX_PAYLOAD
            }
            CMD_AUTH -> throw IOException("device demands legacy AUTH — pairing key not accepted")
            else -> throw IOException("unexpected packet ${cmdString(cmd)} during CNXN handshake")
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

        val localId = NEXT_LOCAL_ID.incrementAndGet()
        val service = "shell,v2:$command".toByteArray(StandardCharsets.US_ASCII) + 0
        writePacket(o, CMD_OPEN, localId, 0, service)

        var remoteId = 0
        var closed = false
        while (!closed && exitCode == null) {
            val (cmd, arg0, arg1, payload) = readPacket(i)
            when (cmd) {
                CMD_OKAY -> {
                    if (arg1 != localId) throw IOException("OKAY id mismatch (${arg0}, ${arg1})")
                    remoteId = arg0
                }
                CMD_WRTE -> {
                    if (arg1 != localId) throw IOException("WRTE id mismatch (${arg0}, ${arg1})")
                    consumeFrames(payload, stdout, stderr) {
                        exitCode = it
                    }
                    // The adb protocol ACKs every WRTE with OKAY.
                    writePacket(o, CMD_OKAY, localId, remoteId, EMPTY)
                }
                CMD_CLSE -> {
                    if (arg1 == localId) closed = true
                }
                CMD_AUTH -> throw IOException("unexpected AUTH during shell")
                else -> throw IOException("unexpected packet ${cmdString(cmd)} during shell")
            }
        }

        // Politely close the stream if the device did not.
        if (!closed && remoteId != 0) {
            runCatching { writePacket(o, CMD_CLSE, localId, remoteId, EMPTY) }
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
                FRAME_STDOUT, FRAME_STDIN -> stdout.append(data)
                FRAME_STDERR -> stderr.append(data)
                FRAME_EXIT -> if (len >= 1) onExit(buf[off + 5].toInt() and 0xFF)
                else -> Unit // unknown frame — ignore (forward compatible)
            }
            off += 5 + len
        }
        frameCarry = if (off < buf.size) buf.copyOfRange(off, buf.size) else ByteArray(0)
    }

    // ------------------------------------------------------------------
    // Packet codec (adb.h): cmd(4) arg0 arg1 len checksum magic
    // ------------------------------------------------------------------

    private fun writePacket(out: OutputStream, cmd: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        val checksum = payload.sumOf { (it.toInt() and 0xFF).toLong() }.toInt()
        val buf = ByteBuffer.allocate(24 + payload.size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(cmd)
        buf.putInt(arg0)
        buf.putInt(arg1)
        buf.putInt(payload.size)
        buf.putInt(checksum)
        buf.putInt(checksum.inv()) // magic = checksum ^ 0xffffffff
        buf.put(payload)
        out.write(buf.array())
        out.flush()
    }

    private fun readPacket(input: DataInputStream): Packet {
        val header = ByteArray(24)
        input.readFully(header)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val cmd = bb.int
        val arg0 = bb.int
        val arg1 = bb.int
        val len = bb.int
        val checksum = bb.int
        val magic = bb.int
        if (checksum != magic.inv()) {
            throw IOException("adb packet checksum/magic mismatch")
        }
        if (len < 0 || len > maxPayload * 2) {
            throw IOException("adb packet payload ${len}B exceeds sane bounds")
        }
        val payload = ByteArray(len)
        if (len > 0) input.readFully(payload)
        val actual = payload.sumOf { (it.toInt() and 0xFF).toLong() }.toInt()
        if (len > 0 && actual != checksum) {
            throw IOException("adb packet payload checksum mismatch")
        }
        return Packet(cmd, arg0, arg1, payload)
    }

    private data class Packet(val cmd: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)

    private fun cmdString(cmd: Int): String = String(
        byteArrayOf(
            ((cmd ushr 24) and 0xFF).toByte(),
            ((cmd ushr 16) and 0xFF).toByte(),
            ((cmd ushr 8) and 0xFF).toByte(),
            (cmd and 0xFF).toByte(),
        ),
    )

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
        private const val VERSION = 0x01000001
        private const val DEFAULT_MAX_PAYLOAD = 256 * 1024
        private const val CONNECT_TIMEOUT_MS = 10_000

        private val CMD_CNXN = cmdInt("CNXN")
        private val CMD_AUTH = cmdInt("AUTH")
        private val CMD_OPEN = cmdInt("OPEN")
        private val CMD_OKAY = cmdInt("OKAY")
        private val CMD_CLSE = cmdInt("CLSE")
        private val CMD_WRTE = cmdInt("WRTE")

        private const val FRAME_STDIN = 0
        private const val FRAME_STDOUT = 1
        private const val FRAME_STDERR = 2
        private const val FRAME_EXIT = 3

        private val NEXT_LOCAL_ID = AtomicInteger(1)
        private val EMPTY = ByteArray(0)

        /** adb commands are stored as 4-char strings read big-endian on the wire. */
        private fun cmdInt(s: String): Int =
            ((s[0].code and 0xFF) shl 24) or
                ((s[1].code and 0xFF) shl 16) or
                ((s[2].code and 0xFF) shl 8) or
                (s[3].code and 0xFF)
    }
}
