package com.lmalakar.onecr.privilege.embeddedadb

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLSocket

/**
 * Implements the client side of Android's adb wireless-debugging pairing protocol
 * (packages/modules/adb pairing_connection.cpp) — see PROTOCOL-SPEC.md:
 *
 *  TLS 1.3 → export 64 bytes ("adb-label\0") → SPAKE2(password = code + exported)
 *  → SPAKE2_MSG exchange → AES-128-GCM PeerInfo exchange (our public key out,
 *  the device GUID in). On success the phone stores our adb key as authorized.
 */
internal class AdbPairingClient(
    private val identity: AdbIdentity,
    private val host: String,
    private val port: Int,
    private val pairingCode: String,
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
) {

    /** Result of a successful pairing: the device GUID reported by the phone. */
    data class Result(val deviceGuid: String)

    /** Runs the pairing; throws IOException / IllegalStateException on failure. */
    fun run(): Result {
        require(pairingCode.isNotEmpty()) { "pairing code must not be empty" }
        val socket = TlsSupport.connect(identity, host, port, timeoutMs)
        try {
            val exported = TlsSupport.exportKeyingMaterial(socket)
                ?: throw IOException("TLS keying material export unavailable (no Conscrypt)")
            val password = pairingCode.toByteArray(StandardCharsets.US_ASCII) + exported

            val (spake, ourMsg) = Spake2.client(password)
            val input = DataInputStream(socket.inputStream.buffered())
            val output: OutputStream = socket.outputStream

            // --- SPAKE2_MSG ---
            writePacket(output, TYPE_SPAKE2_MSG, ourMsg)
            val (theirSpakeType, theirSpakeMsg) = readPacket(input)
            check(theirSpakeType == TYPE_SPAKE2_MSG) { "expected SPAKE2 msg, got $theirSpakeType" }
            val keyMaterial = spake.process(theirSpakeMsg)
            val cipher = PairingCipher(keyMaterial)

            // --- PEER_INFO (our adb public key) ---
            val ourInfo = ByteArray(PEER_INFO_SIZE)
            ourInfo[0] = PEER_TYPE_RSA_PUB_KEY.toByte()
            val keyLine = identity.adbPublicKeyLine().toByteArray(StandardCharsets.US_ASCII)
            check(keyLine.size <= PEER_INFO_SIZE - 1) { "public key line too large" }
            System.arraycopy(keyLine, 0, ourInfo, 1, keyLine.size)
            writePacket(output, TYPE_PEER_INFO, cipher.encrypt(ourInfo))

            // --- PEER_INFO (device GUID) ---
            val (theirInfoType, theirInfoCiphertext) = readPacket(input)
            check(theirInfoType == TYPE_PEER_INFO) { "expected PEER_INFO, got $theirInfoType" }
            val theirInfo = cipher.decrypt(theirInfoCiphertext)
            if (theirInfo.size != PEER_INFO_SIZE) {
                throw IOException("bad PeerInfo size ${theirInfo.size}")
            }
            if (theirInfo[0].toInt() != PEER_TYPE_DEVICE_GUID) {
                throw IOException("unexpected PeerInfo type ${theirInfo[0]}")
            }
            val guid = readNullTerminated(theirInfo, 1)
                ?: throw IOException("device GUID missing from PeerInfo")

            return Result(guid)
        } finally {
            runCatching { socket.close() }
        }
    }

    // ------------------------------------------------------------------
    // PairingPacket framing: [version u8][type u8][payload u32 BE] + payload
    // ------------------------------------------------------------------

    private fun writePacket(out: OutputStream, type: Int, payload: ByteArray) {
        val header = ByteArray(6)
        header[0] = HEADER_VERSION.toByte()
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
        val version = header[0].toInt() and 0xFF
        if (version != HEADER_VERSION) {
            throw IOException("pairing header version $version unsupported")
        }
        val type = header[1].toInt() and 0xFF
        val payloadLen =
            ((header[2].toInt() and 0xFF) shl 24) or
                ((header[3].toInt() and 0xFF) shl 16) or
                ((header[4].toInt() and 0xFF) shl 8) or
                (header[5].toInt() and 0xFF)
        if (payloadLen == 0 || payloadLen > MAX_PAYLOAD) {
            throw IOException("pairing payload size $payloadLen out of range")
        }
        val payload = ByteArray(payloadLen)
        input.readFully(payload)
        return Pair(type, payload)
    }

    private fun readNullTerminated(buf: ByteArray, offset: Int): String? {
        var end = offset
        while (end < buf.size && buf[end] != 0.toByte()) end++
        if (end == offset) return null
        return String(buf, offset, end - offset, StandardCharsets.US_ASCII)
    }

    companion object {
        private const val HEADER_VERSION = 1
        private const val TYPE_SPAKE2_MSG = 0
        private const val TYPE_PEER_INFO = 1
        private const val PEER_TYPE_RSA_PUB_KEY = 0
        private const val PEER_TYPE_DEVICE_GUID = 1

        /** PeerInfo struct is exactly 8192 bytes: 1 type byte + 8191 data bytes. */
        const val PEER_INFO_SIZE = 8192
        private const val MAX_PAYLOAD = 8192 * 2
        private const val DEFAULT_TIMEOUT_MS = 15_000
    }
}
