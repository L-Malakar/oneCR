package com.lmalakar.onecr.privilege.embeddedadb

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * SPAKE2 exactly as BoringSSL implements it (the variant used by Android's
 * `adb pair` wireless-debugging pairing protocol).
 *
 * Key properties (see work/adb-spec/PROTOCOL-SPEC.md):
 *  - Curve: edwards25519 with fixed M/N constants below.
 *  - password_hash = SHA-512(password); scalar reduced mod l, then made
 *    divisible by 8 (BoringSSL "password_scalar_hack") by adding k*l.
 *  - Key = SHA-512 transcript over (names, both messages, DH secret, password_hash)
 *    with each field prefixed by its byte length as little-endian uint64.
 *  - The 64-byte transcript hash IS the keying material adb feeds to HKDF.
 *
 * Client side is "Alice" (masks with M), server is "Bob" (masks with N) —
 * pairing_auth.cpp: kClientRole = spake2_role_alice.
 */
internal class Spake2 private constructor(
    private val role: Role,
    private val myName: ByteArray,
    private val theirName: ByteArray,
    private val privateKeyOverride: BigInteger? = null, // test vectors only
) {

    enum class Role { CLIENT, SERVER }

    private val random = SecureRandom()
    private var passwordHash: ByteArray? = null
    private var pwScalar: BigInteger? = null
    private var privateKey: BigInteger? = null
    private var myMsg: ByteArray? = null

    companion object {
        // BoringSSL SPAKE2 constants (compressed ed25519 points), generated from
        // seeds "edwards25519 point generation seed (M)" and "(N)".
        private val M: ByteArray = hex("5ADA7E4BF6DDD9ADB6626D32131C6B5C51A1E347A3478F53CFCF441B88EED12E")
        private val N: ByteArray = hex("10E3DF0AE37D8E7A99B5FE74B44672103DBDDCBD06AF680D71329A11693BC778")

        // pairing_auth.cpp: kClientName/kServerName — sizeof() INCLUDES the NUL.
        private val CLIENT_NAME = "adb pair client".toByteArray(Charsets.US_ASCII) + 0
        private val SERVER_NAME = "adb pair server".toByteArray(Charsets.US_ASCII) + 0

        private const val MAX_MSG_SIZE = 32

        fun client(password: ByteArray): Pair<Spake2, ByteArray> {
            val ctx = Spake2(Role.CLIENT, CLIENT_NAME, SERVER_NAME)
            return ctx to ctx.start(password)
        }

        fun server(password: ByteArray): Pair<Spake2, ByteArray> {
            val ctx = Spake2(Role.SERVER, SERVER_NAME, CLIENT_NAME)
            return ctx to ctx.start(password)
        }

        /** Deterministic instance for cross-language test vectors. */
        fun clientForTest(password: ByteArray, privateKey: BigInteger): Pair<Spake2, ByteArray> {
            val ctx = Spake2(Role.CLIENT, CLIENT_NAME, SERVER_NAME, privateKey)
            return ctx to ctx.start(password)
        }

        fun serverForTest(password: ByteArray, privateKey: BigInteger): Pair<Spake2, ByteArray> {
            val ctx = Spake2(Role.SERVER, SERVER_NAME, CLIENT_NAME, privateKey)
            return ctx to ctx.start(password)
        }

        private fun hex(s: String): ByteArray =
            s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    /** Generates our SPAKE2 message (32-byte compressed point) to send to the peer. */
    private fun start(password: ByteArray): ByteArray {
        require(password.isNotEmpty())
        val hash = MessageDigest.getInstance("SHA-512").digest(password)
        passwordHash = hash

        // password scalar: reduce mod l, then clear the bottom 3 bits by adding
        // multiples of l (BoringSSL password_scalar_hack — M/N are not in the
        // prime-order subgroup, so the numeric scalar value matters).
        var scalar = Ed25519.reduceModL(hash)
        val l = Ed25519.l
        if (scalar.testBit(0)) scalar = scalar.add(l)
        if (scalar.testBit(1)) scalar = scalar.add(l.multiply(BigInteger.TWO))
        if (scalar.testBit(2)) scalar = scalar.add(l.multiply(BigInteger.valueOf(4)))
        pwScalar = scalar

        // Ephemeral key: 64 random bytes reduced mod l, then * cofactor 8.
        val priv = privateKeyOverride
            ?: Ed25519.reduceModL(ByteArray(64).also { random.nextBytes(it) })
                .multiply(BigInteger.valueOf(8))
        privateKey = priv

        // Mask point: Alice multiplies by M, Bob by N.
        val maskPoint = Ed25519.decode(if (role == Role.CLIENT) M else N)
            ?: error("SPAKE2 constant point failed to decode")
        val mask = Ed25519.scalarMult(scalar, maskPoint)
        val p = Ed25519.add(Ed25519.scalarMult(priv, Ed25519.basePoint), mask)
        val msg = Ed25519.encode(p)
        check(msg.size <= MAX_MSG_SIZE)
        myMsg = msg
        return msg
    }

    /**
     * Processes the peer's message and derives the 64-byte keying material.
     * Throws IllegalArgumentException on a malformed/low-order peer message
     * and IllegalStateException when the DH secret collapses to identity
     * (wrong password produces this deterministically).
     */
    fun process(theirMsg: ByteArray): ByteArray {
        val hash = checkNotNull(passwordHash)
        val scalar = checkNotNull(pwScalar)
        val priv = checkNotNull(privateKey)
        val mine = checkNotNull(myMsg)

        val theirBlinded = Ed25519.decode(theirMsg)
            ?: throw IllegalArgumentException("peer SPAKE2 message is not a valid curve point")
        check(!Ed25519.isLowOrder(theirBlinded)) { "peer SPAKE2 message has low order" }

        // Unmask: client subtracts scalar*N, server subtracts scalar*M.
        val unmaskPoint = Ed25519.decode(if (role == Role.CLIENT) N else M)
            ?: error("SPAKE2 constant point failed to decode")
        val unmasked = Ed25519.add(
            theirBlinded,
            Ed25519.scalarMult(scalar, negate(unmaskPoint)),
        )
        check(!Ed25519.isLowOrder(unmasked)) { "wrong password or malicious peer" }

        // K = priv * Q
        val shared = Ed25519.encode(Ed25519.scalarMult(priv, unmasked))

        // Transcript: canonical alice-first order.
        val (aliceName, bobName, aliceMsg, bobMsg) =
            if (role == Role.CLIENT) {
                arrayOf(myName, theirName, mine, theirMsg)
            } else {
                arrayOf(theirName, myName, theirMsg, mine)
            }

        val digest = MessageDigest.getInstance("SHA-512")
        digest.update(lengthPrefixed(aliceName))
        digest.update(lengthPrefixed(bobName))
        digest.update(lengthPrefixed(aliceMsg))
        digest.update(lengthPrefixed(bobMsg))
        digest.update(lengthPrefixed(shared))
        digest.update(lengthPrefixed(hash))
        return digest.digest() // 64 bytes — the keying material
    }

    /** -(x, y) on ed25519 is (-x, y). */
    private fun negate(pt: Ed25519.Point): Ed25519.Point {
        val negX = Ed25519.p.subtract(pt.x)
        return Ed25519.Point(negX, pt.y, pt.z, negX.multiply(pt.y).mod(Ed25519.p))
    }

    private fun lengthPrefixed(data: ByteArray): ByteArray {
        val len = java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(data.size.toLong())
            .array()
        return len + data
    }
}
