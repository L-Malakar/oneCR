package com.lmalakar.onecr.privilege.embeddedadb

import java.math.BigInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Ed25519Test {

    @Test
    fun `base point is on the curve`() {
        assertTrue(Ed25519.isOnCurve(Ed25519.basePoint))
    }

    @Test
    fun `spake2 M and N constants decode to valid curve points`() {
        val m = Ed25519.decode(
            hex("5ADA7E4BF6DDD9ADB6626D32131C6B5C51A1E347A3478F53CFCF441B88EED12E"),
        )
        val n = Ed25519.decode(
            hex("10E3DF0AE37D8E7A99B5FE74B44672103DBDDCBD06AF680D71329A11693BC778"),
        )
        assertNotNull(m)
        assertNotNull(n)
    }

    @Test
    fun `encode decode roundtrip preserves the point`() {
        for (k in 1L..5L) {
            val p = Ed25519.scalarMult(BigInteger.valueOf(k), Ed25519.basePoint)
            val encoded = Ed25519.encode(p)
            val decoded = Ed25519.decode(encoded)
            assertNotNull(decoded)
            // The projective representation may differ; compare AFFINE coordinates.
            assertEquals(
                p.y.multiply(p.z.modInverse(Ed25519.p)).mod(Ed25519.p),
                decoded!!.y.multiply(decoded.z.modInverse(Ed25519.p)).mod(Ed25519.p),
            )
            assertEquals(
                p.x.multiply(p.z.modInverse(Ed25519.p)).mod(Ed25519.p),
                decoded.x.multiply(decoded.z.modInverse(Ed25519.p)).mod(Ed25519.p),
            )
        }
    }

    @Test
    fun `scalar multiplication matches addition of multiples`() {
        val a = BigInteger.valueOf(12345)
        val b = BigInteger.valueOf(67890)
        val ab = a.add(b)
        val lhs = Ed25519.encode(Ed25519.scalarMult(ab, Ed25519.basePoint))
        val rhs = Ed25519.encode(
            Ed25519.add(
                Ed25519.scalarMult(a, Ed25519.basePoint),
                Ed25519.scalarMult(b, Ed25519.basePoint),
            ),
        )
        assertArrayEquals(lhs, rhs)
    }

    @Test
    fun `scalar mult of base by l is the identity`() {
        assertTrue(Ed25519.isIdentity(Ed25519.scalarMult(Ed25519.l, Ed25519.basePoint)))
    }

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
