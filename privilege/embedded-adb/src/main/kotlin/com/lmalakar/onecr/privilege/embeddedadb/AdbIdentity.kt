package com.lmalakar.onecr.privilege.embeddedadb

import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * The embedded ADB identity: one RSA-2048 keypair, persisted as PKCS#8 PEM.
 *
 * From this key we derive (exactly like adb does):
 *  - a self-signed X.509 v3 certificate for the TLS sessions (pairing + connect);
 *  - the classic adb public-key line ("base64(android_pubkey) name") that is
 *    exported to the phone during pairing.
 */
internal class AdbIdentity private constructor(val keyPair: KeyPair) {

    val privateKey: PrivateKey get() = keyPair.private
    val rsaPublicKey: RSAPublicKey get() = keyPair.public as RSAPublicKey

    companion object {
        private const val KEY_FILE = "adb_identity_key.pem"
        private const val PUBKEY_NAME = "onecr@android"

        /** Loads or creates the identity stored under [filesDir]. */
        fun loadOrCreate(filesDir: File): AdbIdentity {
            val keyFile = File(filesDir, KEY_FILE)
            if (keyFile.exists()) {
                runCatching { return fromPem(keyFile.readText()) }
                // Corrupt key file — fall through and regenerate.
                keyFile.delete()
            }
            val identity = generate()
            keyFile.writeText(toPem(identity.keyPair.private.encoded))
            return identity
        }

        fun fromPem(pem: String): AdbIdentity {
            val der = pemDecode(pem, "PRIVATE KEY")
            val kf = KeyFactory.getInstance("RSA")
            val privateKey = kf.generatePrivate(PKCS8EncodedKeySpec(der))
                as java.security.interfaces.RSAPrivateCrtKey
            val publicKey = kf.generatePublic(
                RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent),
            )
            return AdbIdentity(KeyPair(publicKey, privateKey))
        }

        fun generate(): AdbIdentity {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048, SecureRandom())
            return AdbIdentity(kpg.generateKeyPair())
        }

        fun toPem(der: ByteArray): String {
            val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
            return "-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----\n"
        }

        fun pemDecode(pem: String, label: String): ByteArray {
            val body = pem
                .substringAfter("-----BEGIN $label-----")
                .substringBefore("-----END $label-----")
                .filterNot { it == '\r' || it == '\n' }
            return Base64.getDecoder().decode(body)
        }
    }

    // ------------------------------------------------------------------
    // Public key line (adb format: base64(android_pubkey) name)
    // ------------------------------------------------------------------

    /**
     * Encodes the public key in Android's custom RSA public key format
     * (libcrypto_utils/android_pubkey.cpp) and returns the adb key line:
     * "base64(struct) onecr@android".
     *
     * struct: u32 word_count(64) | u32 n0inv | modulus[256] LE | rr[256] LE | u32 e
     */
    fun adbPublicKeyLine(): String {
        val n = rsaPublicKey.modulus
        val e = rsaPublicKey.publicExponent
        check(e == BigInteger.valueOf(65537)) { "adb requires e = 65537" }
        val keyLen = 256 // bytes of modulus

        // n0inv = r32 - (n mod 2^32)^-1 mod 2^32
        val r32 = BigInteger.TWO.pow(32)
        val nLow = n.mod(r32)
        val inv = nLow.modInverse(r32)
        val n0inv = r32.subtract(inv).mod(r32)

        // rr = (2^2048)^2 mod n = 2^4096 mod n  (Montgomery R^2)
        val rr = BigInteger.TWO.pow(keyLen * 8 * 2).mod(n)

        val buf = java.nio.ByteBuffer.allocate(4 + 4 + keyLen + keyLen + 4)
        buf.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.putInt(keyLen / 4) // word count: 64
        buf.putInt(n0inv.toInt())
        val nLe = littleEndianBytes(n, keyLen)
        val rrLe = littleEndianBytes(rr, keyLen)
        buf.put(nLe)
        buf.put(rrLe)
        buf.putInt(e.toInt())
        check(buf.hasRemaining() == false)

        val b64 = Base64.getEncoder().encodeToString(buf.array())
        return "$b64 $PUBKEY_NAME"
    }

    private fun littleEndianBytes(v: BigInteger, length: Int): ByteArray {
        val big = v.toByteArray() // big-endian
        val out = ByteArray(length)
        var src = big.size - 1
        var dst = 0
        while (dst < length && src >= 0) out[dst++] = big[src--]
        return out
    }

    // ------------------------------------------------------------------
    // Self-signed X.509 v3 certificate (mirrors adb crypto/x509_generator.cpp)
    // ------------------------------------------------------------------

    /** Generates the certificate; cheap enough to call per session, cached by callers. */
    fun selfSignedCertificate(): ByteArray {
        val now = ZonedDateTime.now(ZoneOffset.UTC)
        val notBefore = now.minusMinutes(5)
        val notAfter = now.plusDays(365L * 10)
        val utc = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'")

        val publicKeyDer = Asn1.rsaPublicKeyDer(rsaPublicKey)
        val spki = Asn1.sequence(
            Asn1.sequence(Asn1.oid("1.2.840.113549.1.1.1"), Asn1.nullValue()),
            Asn1.bitString(publicKeyDer),
        )

        // SKI = SHA-1 over the subjectPublicKey BIT STRING content.
        val ski = MessageDigest.getInstance("SHA-1").digest(publicKeyDer)

        val name = Asn1.sequence(
            Asn1.set(Asn1.nameEntry("2.5.4.6", Asn1.PRINTABLE_STRING, "US")),
            Asn1.set(Asn1.nameEntry("2.5.4.10", Asn1.UTF8_STRING, "Android")),
            Asn1.set(Asn1.nameEntry("2.5.4.3", Asn1.UTF8_STRING, "Adb")),
        )

        val tbs = Asn1.sequence(
            Asn1.context(0, Asn1.integer(2)), // v3
            Asn1.integer(1), // serial
            Asn1.sequence(Asn1.oid("1.2.840.113549.1.1.11"), Asn1.nullValue()), // SHA256withRSA
            name, // issuer
            Asn1.sequence(
                Asn1.utcTime(notBefore.format(utc)),
                Asn1.utcTime(notAfter.format(utc)),
            ),
            name, // subject
            spki,
            Asn1.context(
                3,
                Asn1.sequence(
                    // basicConstraints critical CA:TRUE
                    Asn1.sequence(
                        Asn1.oid("2.5.29.19"),
                        Asn1.booleanTrue(),
                        Asn1.octetString(Asn1.sequence(Asn1.booleanTrue())),
                    ),
                    // keyUsage critical: digitalSignature | keyCertSign | cRLSign
                    Asn1.sequence(
                        Asn1.oid("2.5.29.15"),
                        Asn1.booleanTrue(),
                        Asn1.octetString(Asn1.bitStringRaw(byteArrayOf(0x86.toByte()), 1)),
                    ),
                    // subjectKeyIdentifier (not critical)
                    Asn1.sequence(
                        Asn1.oid("2.5.29.14"),
                        Asn1.octetString(Asn1.octetString(ski)),
                    ),
                ),
            ),
        )

        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(privateKey)
        signer.update(tbs)
        val signature = signer.sign()

        return Asn1.sequence(
            tbs,
            Asn1.sequence(Asn1.oid("1.2.840.113549.1.1.11"), Asn1.nullValue()),
            Asn1.bitString(signature),
        )
    }

    fun certificatePem(): String {
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(selfSignedCertificate())
        return "-----BEGIN CERTIFICATE-----\n$b64\n-----END CERTIFICATE-----\n"
    }

    fun privateKeyPem(): String = Companion.toPem(privateKey.encoded)
}

/** Minimal DER encoder/decoder — only what the adb identity needs. */
internal object Asn1 {
    const val PRINTABLE_STRING = 0x13
    const val UTF8_STRING = 0x0C

    // ------------------------------------------------------------------
    // Encoding
    // ------------------------------------------------------------------

    private fun lengthBytes(len: Int): ByteArray = when {
        len < 0x80 -> byteArrayOf(len.toByte())
        len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
        else -> byteArrayOf(
            0x82.toByte(),
            (len shr 8).toByte(),
            (len and 0xFF).toByte(),
        )
    }

    fun tlv(tag: Int, content: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + lengthBytes(content.size) + content

    fun sequence(vararg parts: ByteArray): ByteArray = tlv(0x30, parts.reduce { a, b -> a + b })
    fun set(vararg parts: ByteArray): ByteArray = tlv(0x31, parts.reduce { a, b -> a + b })
    fun context(index: Int, content: ByteArray): ByteArray = tlv(0xA0 or index, content)
    fun integer(v: Long): ByteArray = tlv(0x02, bigIntBody(BigInteger.valueOf(v)))
    fun integer(v: BigInteger): ByteArray = tlv(0x02, bigIntBody(v))

    private fun bigIntBody(v: BigInteger): ByteArray {
        var b = v.toByteArray()
        // Strip redundant leading zero (except when needed for sign).
        if (b.size > 1 && b[0] == 0.toByte() && (b[1].toInt() and 0x80) == 0) {
            b = b.copyOfRange(1, b.size)
        }
        return b
    }

    fun oid(dotted: String): ByteArray {
        val parts = dotted.split(".").map { it.toInt() }
        val body = mutableListOf<Byte>()
        var first = parts[0] * 40 + parts[1]
        body.add(first.toByte())
        for (i in 2 until parts.size) {
            var v = parts[i].toLong()
            val enc = mutableListOf<Byte>()
            enc.add((v and 0x7F).toByte())
            v = v ushr 7
            while (v > 0) {
                enc.add(0, ((v and 0x7F) or 0x80).toByte())
                v = v ushr 7
            }
            body.addAll(enc)
        }
        return tlv(0x06, body.toByteArray())
    }

    fun nullValue(): ByteArray = tlv(0x05, ByteArray(0))
    fun booleanTrue(): ByteArray = tlv(0x01, byteArrayOf(0xFF.toByte()))
    fun octetString(content: ByteArray): ByteArray = tlv(0x04, content)
    fun bitString(content: ByteArray): ByteArray = tlv(0x03, byteArrayOf(0) + content)
    fun bitStringRaw(content: ByteArray, unusedBits: Int): ByteArray =
        tlv(0x03, byteArrayOf(unusedBits.toByte()) + content)
    fun utcTime(value: String): ByteArray = tlv(0x17, value.toByteArray(Charsets.US_ASCII))

    fun nameEntry(oid: String, stringType: Int, value: String): ByteArray =
        sequence(oid(oid), tlv(stringType, value.toByteArray(Charsets.UTF_8)))

    fun rsaPublicKeyDer(key: RSAPublicKey): ByteArray = sequence(
        integer(key.modulus),
        integer(key.publicExponent),
    )

    // ------------------------------------------------------------------
    // Minimal parsing (for loading keys)
    // ------------------------------------------------------------------

    class Der(val bytes: ByteArray, val offset: Int, val length: Int) {
        val content: ByteArray get() = bytes.copyOfRange(offset, offset + length)
    }

    fun parseSequence(der: ByteArray): List<Der> {
        val out = mutableListOf<Der>()
        var pos = 2 // SEQ tag + length (assume short/medium form, fine for our keys)
        val total = readLength(der, 1).let { (value, headerLen) -> headerLen + value }
        while (pos < total) {
            val (len, header) = readLength(der, pos + 1)
            out.add(Der(der, pos + header, len))
            pos += header + len
        }
        return out
    }

    private fun readLength(bytes: ByteArray, pos: Int): Pair<Int, Int> {
        val first = bytes[pos].toInt() and 0xFF
        return when {
            first < 0x80 -> Pair(first, 1)
            first == 0x81 -> Pair(bytes[pos + 1].toInt() and 0xFF, 2)
            first == 0x82 -> Pair(
                ((bytes[pos + 1].toInt() and 0xFF) shl 8) or (bytes[pos + 2].toInt() and 0xFF),
                3,
            )
            else -> error("unsupported DER length at $pos")
        }
    }

    fun asBytes(der: Der): ByteArray = der.content
}
