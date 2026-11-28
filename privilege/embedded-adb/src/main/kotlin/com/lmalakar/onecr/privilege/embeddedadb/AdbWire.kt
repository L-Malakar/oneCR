package com.lmalakar.onecr.privilege.embeddedadb

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared adb wire codec for both connection kinds (v0.3.2): the buffered
 * command client ([AdbClient]) and the dedicated PCM streaming client
 * ([AdbStreamClient]). Packet framing per adb.h:
 * `cmd(4) arg0 arg1 data-len data-checksum data-magic payload`.
 * Shell v2 frame = `[id u8][len u32 LE][data]`, ids 0..3 = stdin/stdout/stderr/exit.
 */
internal object AdbWire {

    const val VERSION = 0x01000001
    const val DEFAULT_MAX_PAYLOAD = 256 * 1024
    const val CONNECT_TIMEOUT_MS = 10_000

    val CMD_CNXN = cmdInt("CNXN")
    val CMD_AUTH = cmdInt("AUTH")
    val CMD_OPEN = cmdInt("OPEN")
    val CMD_OKAY = cmdInt("OKAY")
    val CMD_CLSE = cmdInt("CLSE")
    val CMD_WRTE = cmdInt("WRTE")

    const val FRAME_STDIN = 0
    const val FRAME_STDOUT = 1
    const val FRAME_STDERR = 2
    const val FRAME_EXIT = 3

    val NEXT_LOCAL_ID = AtomicInteger(1)
    val EMPTY = ByteArray(0)

    data class Packet(val cmd: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)

    fun writePacket(out: OutputStream, cmd: Int, arg0: Int, arg1: Int, payload: ByteArray) {
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

    fun readPacket(input: DataInputStream, maxPayload: Int): Packet {
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

    fun cmdString(cmd: Int): String = String(
        byteArrayOf(
            ((cmd ushr 24) and 0xFF).toByte(),
            ((cmd ushr 16) and 0xFF).toByte(),
            ((cmd ushr 8) and 0xFF).toByte(),
            (cmd and 0xFF).toByte(),
        ),
    )

    /** adb commands are stored as 4-char strings read big-endian on the wire. */
    fun cmdInt(s: String): Int =
        ((s[0].code and 0xFF) shl 24) or
            ((s[1].code and 0xFF) shl 16) or
            ((s[2].code and 0xFF) shl 8) or
            (s[3].code and 0xFF)
}
