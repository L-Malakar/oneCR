package com.lmalakar.onecr.privilege.embeddedadb

import java.io.DataInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.conscrypt.Conscrypt

/**
 * Public bridges into the internal protocol classes for the out-of-process mock
 * servers ([MockServerMain]). The helper JVM must not see the mockable
 * android.jar (its javax.net.ssl stubs break TLS casts), so the mocks run in a
 * clean subprocess while the tests stay in the normal unit-test JVM.
 */
internal object TestHooks {

    @JvmStatic
    fun newIdentity(): AdbIdentity = AdbIdentity.generate()

    @JvmStatic
    fun certDer(identity: AdbIdentity): ByteArray = identity.selfSignedCertificate()

    @JvmStatic
    fun privateKeyOf(identity: AdbIdentity): java.security.PrivateKey = identity.privateKey

    @JvmStatic
    fun adbPubKeyLine(identity: AdbIdentity): String = identity.adbPublicKeyLine()

    /**
     * Runs the SERVER side of the pairing protocol over an established TLS
     * socket: exporter ("adb-label\0"), SPAKE2 (Bob), AES-128-GCM PeerInfo
     * exchange. Returns the client's adb public key line, or throws.
     */
    @JvmStatic
    fun servePairing(ssl: SSLSocket, code: String): String {
        val exported = Conscrypt.exportKeyingMaterial(ssl, "adb-label\u0000", null, 64)
            ?: error("no exporter output")
        val password = code.toByteArray(StandardCharsets.US_ASCII) + exported
        val (spake, serverMsg) = Spake2.server(password)

        val input = DataInputStream(ssl.inputStream)
        val out: OutputStream = ssl.outputStream

        val (type1, clientMsg) = readPacket(input)
        check(type1 == TYPE_SPAKE2_MSG) { "expected SPAKE2 msg, got $type1" }
        val keyMaterial = spake.process(clientMsg)
        val cipher = PairingCipher(keyMaterial)
        writePacket(out, TYPE_SPAKE2_MSG, serverMsg)

        val (type2, ct) = readPacket(input)
        check(type2 == TYPE_PEER_INFO) { "expected PEER_INFO, got $type2" }
        val info = cipher.decrypt(ct)
        check(info.size == 8192) { "bad PeerInfo size ${info.size}" }
        check(info[0] == PEER_TYPE_RSA_PUB_KEY) { "expected ADB_RSA_PUB_KEY" }
        val keyLine = readNullTerminated(info, 1)
        require(keyLine != null) { "public key line missing" }

        val ourInfo = ByteArray(8192)
        ourInfo[0] = PEER_TYPE_DEVICE_GUID
        val guid = "mock-device-guid-01".toByteArray(StandardCharsets.US_ASCII)
        System.arraycopy(guid, 0, ourInfo, 1, guid.size)
        writePacket(out, TYPE_PEER_INFO, cipher.encrypt(ourInfo))
        return keyLine
    }

    /**
     * Serves one adbd session over an established TLS socket: CNXN handshake,
     * OPEN "shell,v2:<cmd>", OKAY, WRTE frames, exit frame, CLSE.
     * Canned responses cover every command the shell tests issue.
     */
    @JvmStatic
    fun serveAdbd(ssl: SSLSocket) {
        val input = DataInputStream(ssl.inputStream)
        val out: OutputStream = ssl.outputStream

        val cnxn = readAdbPacket(input)
        check(cnxn.cmd == CMD_CNXN) { "expected CNXN" }
        writeAdbPacket(out, CMD_CNXN, VERSION, MAX_PAYLOAD, "device::ro.product=mock".toByteArray())

        var remoteId = 100
        while (true) {
            val p = readAdbPacket(input)
            when (p.cmd) {
                CMD_OPEN -> {
                    val service = String(p.payload, 0, p.payload.size - 1, StandardCharsets.US_ASCII)
                    check(service.startsWith("shell,v2:")) { "service $service" }
                    remoteId += 1
                    writeAdbPacket(out, CMD_OKAY, remoteId, p.arg0, ByteArray(0))
                    serveCommand(ssl, out, remoteId, p.arg0, service.removePrefix("shell,v2:"))
                }
                CMD_CLSE -> Unit // stream close — the session (connection) survives
                CMD_OKAY -> Unit
                else -> return
            }
        }
    }

    private fun serveCommand(ssl: SSLSocket, out: OutputStream, remoteId: Int, localId: Int, command: String) {
        fun frame(id: Int, data: ByteArray): ByteArray {
            val bb = ByteBuffer.allocate(5 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            bb.put(id.toByte())
            bb.putInt(data.size)
            bb.put(data)
            return bb.array()
        }
        when (command) {
            "echo hello" -> {
                writeAdbPacket(out, CMD_WRTE, remoteId, localId, frame(1, "hello".toByteArray()))
                writeAdbPacket(out, CMD_WRTE, remoteId, localId, frame(3, byteArrayOf(0)))
            }
            "boom" -> {
                writeAdbPacket(out, CMD_WRTE, remoteId, localId, frame(1, "out-".toByteArray()))
                val full = frame(2, "before".toByteArray())
                writeAdbPacket(out, CMD_WRTE, remoteId, localId, full.copyOfRange(0, 4))
                writeAdbPacket(out, CMD_WRTE, remoteId, localId, full.copyOfRange(4, full.size))
                writeAdbPacket(out, CMD_WRTE, remoteId, localId, frame(3, byteArrayOf(3)))
            }
            "big" -> {
                val chunk = "x".repeat(2000).toByteArray()
                repeat(3) { writeAdbPacket(out, CMD_WRTE, remoteId, localId, frame(1, chunk)) }
                writeAdbPacket(out, CMD_WRTE, remoteId, localId, frame(3, byteArrayOf(0)))
            }
            "a" -> {
                writeAdbPacket(out, CMD_WRTE, remoteId, localId, frame(1, "one".toByteArray()))
                writeAdbPacket(out, CMD_WRTE, remoteId, localId, frame(3, byteArrayOf(0)))
            }
            "drop" -> {
                writeAdbPacket(out, CMD_WRTE, remoteId, localId, frame(1, "one".toByteArray()))
                writeAdbPacket(out, CMD_WRTE, remoteId, localId, frame(3, byteArrayOf(0)))
                // Simulate the server dying: close without CLSE.
                runCatching { ssl.close() }
                return
            }
            "b" -> {
                writeAdbPacket(out, CMD_WRTE, remoteId, localId, frame(1, "two".toByteArray()))
                writeAdbPacket(out, CMD_WRTE, remoteId, localId, frame(3, byteArrayOf(0)))
            }
            "c" -> writeAdbPacket(out, CMD_WRTE, remoteId, localId, frame(3, byteArrayOf(0)))
            else -> writeAdbPacket(out, CMD_WRTE, remoteId, localId, frame(3, byteArrayOf(1)))
        }
        writeAdbPacket(out, CMD_CLSE, remoteId, localId, ByteArray(0))
    }

    // ---- TLS listener shared by both mock modes ----

    @JvmStatic
    fun tlsListener(certIdentity: AdbIdentity, port: Int, needClientAuth: Boolean): ServerSocket {
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(
            java.io.ByteArrayInputStream(certDer(certIdentity)),
        ) as X509Certificate
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        ks.setKeyEntry("mock", privateKeyOf(certIdentity), CharArray(0), arrayOf(cert))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, CharArray(0))
        val tm = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        // Conscrypt when present (pairing mode needs the exporter anyway);
        // JDK TLS otherwise. Both speak TLS 1.3 with client certs.
        val ctx = try {
            SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider())
        } catch (_: Exception) {
            SSLContext.getInstance("TLSv1.3")
        }
        ctx.init(kmf.keyManagers, tm, SecureRandom())
        // Runs inside the helper JVM (clean classpath) — javax types come from the JDK.
        val server = ctx.serverSocketFactory.createServerSocket(port) as javax.net.ssl.SSLServerSocket
        server.needClientAuth = needClientAuth
        server.enabledProtocols = arrayOf("TLSv1.3")
        return server
    }

    // ---- pairing packet framing ----

    private const val TYPE_SPAKE2_MSG = 0
    private const val TYPE_PEER_INFO = 1
    private const val PEER_TYPE_RSA_PUB_KEY: Byte = 0
    private const val PEER_TYPE_DEVICE_GUID: Byte = 1

    private fun writePacket(out: OutputStream, type: Int, payload: ByteArray) {
        val header = ByteArray(6)
        header[0] = 1
        header[1] = type.toByte()
        header[2] = ((payload.size ushr 24) and 0xFF).toByte()
        header[3] = ((payload.size ushr 16) and 0xFF).toByte()
        header[4] = ((payload.size ushr 8) and 0xFF).toByte()
        header[5] = (payload.size and 0xFF).toByte()
        out.write(header)
        out.write(payload)
        out.flush()
    }

    private fun readPacket(input: DataInputStream): Pair<Int, ByteArray> {
        val header = ByteArray(6)
        input.readFully(header)
        check((header[0].toInt() and 0xFF) == 1) { "version" }
        val type = header[1].toInt() and 0xFF
        val len = ((header[2].toInt() and 0xFF) shl 24) or ((header[3].toInt() and 0xFF) shl 16) or
            ((header[4].toInt() and 0xFF) shl 8) or (header[5].toInt() and 0xFF)
        val payload = ByteArray(len)
        input.readFully(payload)
        return Pair(type, payload)
    }

    private fun readNullTerminated(buf: ByteArray, offset: Int): String? {
        var end = offset
        while (end < buf.size && buf[end] != 0.toByte()) end++
        if (end == offset) return null
        return String(buf, offset, end - offset, StandardCharsets.US_ASCII)
    }

    // ---- adb packet framing ----

    private val CMD_CNXN = cmdInt("CNXN")
    private val CMD_OPEN = cmdInt("OPEN")
    private val CMD_OKAY = cmdInt("OKAY")
    private val CMD_CLSE = cmdInt("CLSE")
    private val CMD_WRTE = cmdInt("WRTE")
    private val VERSION = 0x01000001
    private val MAX_PAYLOAD = 256 * 1024

    private fun cmdInt(s: String): Int =
        ((s[0].code and 0xFF) shl 24) or ((s[1].code and 0xFF) shl 16) or
            ((s[2].code and 0xFF) shl 8) or (s[3].code and 0xFF)

    private data class AdbPacket(val cmd: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)

    private fun readAdbPacket(input: DataInputStream): AdbPacket {
        val header = ByteArray(24)
        input.readFully(header)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val cmd = bb.int
        val arg0 = bb.int
        val arg1 = bb.int
        val len = bb.int
        val checksum = bb.int
        val magic = bb.int
        check(checksum == magic.inv()) { "checksum" }
        val payload = ByteArray(len)
        if (len > 0) input.readFully(payload)
        return AdbPacket(cmd, arg0, arg1, payload)
    }

    private fun writeAdbPacket(out: OutputStream, cmd: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        val checksum = payload.sumOf { (it.toInt() and 0xFF).toLong() }.toInt()
        val buf = ByteBuffer.allocate(24 + payload.size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(cmd)
        buf.putInt(arg0)
        buf.putInt(arg1)
        buf.putInt(payload.size)
        buf.putInt(checksum)
        buf.putInt(checksum.inv())
        buf.put(payload)
        out.write(buf.array())
        out.flush()
    }
}
