package com.lmalakar.onecr.privilege.embeddedadb

import com.lmalakar.onecr.privilege.api.CaptureProtocol
import com.lmalakar.onecr.privilege.api.CaptureStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket

/**
 * Dedicated streaming shell channel for PCM capture (M3, embedded-ADB mode).
 *
 * A second TLS session to the phone's own adbd — deliberately separate from
 * the command client, so health pings and shell checks keep flowing on the
 * main bridge while audio streams here. Same wire protocol, different loop:
 * stdout frames are forwarded RAW into a pipe (the PCM), stderr into a small
 * buffer (diagnostics + the `RATE|n` granted-rate line), stdin frames carry
 * the stop control.
 *
 * Thread model: one background reader drains the socket; [stop]/[writeStdin]
 * are synchronized so a stop from another thread never interleaves a frame.
 */
internal class AdbStreamClient(
    private val identity: AdbIdentity,
    private val host: String,
    private val port: Int,
    command: String,
) : CaptureStream, Closeable {

    private val socket: SSLSocket = TlsSupport.connect(identity, host, port, AdbWire.CONNECT_TIMEOUT_MS)
    private val input = DataInputStream(socket.inputStream.buffered())
    private val output: OutputStream = socket.outputStream
    private var maxPayload = AdbWire.DEFAULT_MAX_PAYLOAD

    private val pipedOutput = java.io.PipedOutputStream()
    private val pipedInput = java.io.PipedInputStream(pipedOutput, PIPE_BUFFER_BYTES)

    private val stderrBuffer = ByteArrayOutputStream()
    private val exitCodeRef = AtomicReference<Int?>(null)
    private val finished = AtomicBoolean(false)
    private var remoteId = 0
    private var localId = 0
    private var frameCarry: ByteArray = ByteArray(0)
    private var grantedRate = 0

    override val pcm: InputStream get() = pipedInput
    override val grantedSampleRateHz: Int get() = grantedRate

    init {
        handshake()
        openShellChannel(command)
        Thread({ readerLoop() }, "onecr-adb-stream").apply {
            isDaemon = true
            start()
        }
    }

    // ------------------------------------------------------------------
    // CaptureStream
    // ------------------------------------------------------------------

    /** Writes the stop control word to the capture process's stdin. Best effort. */
    @Synchronized
    override fun stop() {
        runCatching { writeStdin((CaptureProtocol.STOP_LINE + "\n").toByteArray()) }
        runCatching { AdbWire.writePacket(output, AdbWire.CMD_CLSE, localId, remoteId, AdbWire.EMPTY) }
    }

    override fun awaitExit(timeoutMs: Long): Int? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (finished.get()) return exitCodeRef.get()
            Thread.sleep(50)
        }
        return exitCodeRef.get()
    }

    @Synchronized
    override fun close() {
        runCatching { stop() }
        finished.set(true)
        runCatching { socket.close() }
        runCatching { pipedOutput.close() }
        runCatching { pipedInput.close() }
    }

    // ------------------------------------------------------------------
    // Protocol
    // ------------------------------------------------------------------

    private fun handshake() {
        AdbWire.writePacket(
            output, AdbWire.CMD_CNXN, AdbWire.VERSION, AdbWire.DEFAULT_MAX_PAYLOAD,
            "host::onecr".toByteArray(),
        )
        val (cmd, arg0, arg1, _) = AdbWire.readPacket(input, maxPayload)
        when (cmd) {
            AdbWire.CMD_CNXN -> maxPayload = arg1.takeIf { it > 0 } ?: AdbWire.DEFAULT_MAX_PAYLOAD
            AdbWire.CMD_AUTH -> throw IOException("device demands legacy AUTH — pairing key not accepted")
            else -> throw IOException("unexpected packet ${AdbWire.cmdString(cmd)} during CNXN handshake")
        }
    }

    private fun openShellChannel(command: String) {
        localId = AdbWire.NEXT_LOCAL_ID.incrementAndGet()
        val service = "shell,v2:$command".toByteArray(StandardCharsets.US_ASCII) + 0
        AdbWire.writePacket(output, AdbWire.CMD_OPEN, localId, 0, service)
        // OKAY for OPEN carries the remote id directly (single-channel client).
        val (cmd, arg0, arg1) = AdbWire.readPacket(input, maxPayload)
        if (cmd != AdbWire.CMD_OKAY || arg1 != localId) {
            throw IOException("stream channel rejected (${AdbWire.cmdString(cmd)}, $arg0, $arg1)")
        }
        remoteId = arg0
    }

    private fun readerLoop() {
        try {
            var done = false
            while (!done) {
                val (cmd, arg0, arg1, payload) = AdbWire.readPacket(input, maxPayload)
                when (cmd) {
                    AdbWire.CMD_WRTE -> {
                        if (arg1 != localId) throw IOException("WRTE id mismatch ($arg0, $arg1)")
                        routeFrames(payload)
                        // adb ACKs every WRTE with OKAY.
                        AdbWire.writePacket(output, AdbWire.CMD_OKAY, localId, remoteId, AdbWire.EMPTY)
                    }

                    AdbWire.CMD_CLSE -> {
                        if (arg1 == localId) done = true
                    }

                    AdbWire.CMD_OKAY -> Unit // ACKs of our stdin WRTEs — ignore

                    else -> throw IOException("unexpected packet ${AdbWire.cmdString(cmd)} during stream")
                }
            }
        } catch (_: Exception) {
            // Socket torn down (stop()/close() or device vanished) — drain ends below.
        } finally {
            finished.set(true)
            runCatching { pipedOutput.flush() }
            runCatching { pipedOutput.close() } // reader of `pcm` sees EOF
        }
    }

    /** Shell v2 frame demux: stdout → pipe, stderr → buffer, exit → code. */
    private fun routeFrames(chunk: ByteArray) {
        val buf = if (frameCarry.isEmpty()) {
            chunk
        } else {
            val merged = ByteArray(frameCarry.size + chunk.size)
            System.arraycopy(frameCarry, 0, merged, 0, frameCarry.size)
            System.arraycopy(chunk, 0, merged, frameCarry.size, chunk.size)
            merged
        }

        var off = 0
        while (off + 5 <= buf.size) {
            val id = buf[off].toInt() and 0xFF
            val len = (buf[off + 1].toInt() and 0xFF) or
                ((buf[off + 2].toInt() and 0xFF) shl 8) or
                ((buf[off + 3].toInt() and 0xFF) shl 16) or
                ((buf[off + 4].toInt() and 0xFF) shl 24)
            if (off + 5 + len > buf.size) break // incomplete frame — carried over
            when (id) {
                AdbWire.FRAME_STDOUT -> {
                    pipedOutput.write(buf, off + 5, len)
                    if (len >= FLUSH_THRESHOLD_BYTES) pipedOutput.flush()
                }

                AdbWire.FRAME_STDERR -> {
                    stderrBuffer.write(buf, off + 5, len)
                    scanGrantedRate()
                }

                AdbWire.FRAME_EXIT -> {
                    if (len >= 1) exitCodeRef.compareAndSet(null, buf[off + 5].toInt() and 0xFF)
                }
            }
            off += 5 + len
        }
        frameCarry = if (off < buf.size) buf.copyOfRange(off, buf.size) else ByteArray(0)
    }

    /** The capture process announces the actually-granted rate: `RATE|<hz>` on stderr. */
    private fun scanGrantedRate() {
        if (grantedRate != 0) return
        val text = stderrBuffer.toString(StandardCharsets.ISO_8859_1)
        val idx = text.lastIndexOf("RATE|")
        if (idx >= 0) {
            val tail = text.substring(idx + 5).lineSequence().firstOrNull().orEmpty()
            grantedRate = tail.trim().toIntOrNull() ?: 0
        }
    }

    @Synchronized
    private fun writeStdin(data: ByteArray) {
        val frame = ByteArray(5 + data.size)
        frame[0] = AdbWire.FRAME_STDIN.toByte()
        frame[1] = (data.size and 0xFF).toByte()
        frame[2] = ((data.size shr 8) and 0xFF).toByte()
        frame[3] = ((data.size shr 16) and 0xFF).toByte()
        frame[4] = ((data.size shr 24) and 0xFF).toByte()
        System.arraycopy(data, 0, frame, 5, data.size)
        AdbWire.writePacket(output, AdbWire.CMD_WRTE, localId, remoteId, frame)
    }

    /** stderr diagnostics (never PCM) — for the engine's failure notes. */
    fun stderrText(): String = stderrBuffer.toString(StandardCharsets.ISO_8859_1)

    companion object {
        private const val PIPE_BUFFER_BYTES = 256 * 1024
        private const val FLUSH_THRESHOLD_BYTES = 16 * 1024
    }
}
