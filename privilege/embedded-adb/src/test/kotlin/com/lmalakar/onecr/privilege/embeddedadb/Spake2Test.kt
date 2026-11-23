package com.lmalakar.onecr.privilege.embeddedadb

import java.math.BigInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

/**
 * SPAKE2 correctness, cross-validated against the independent pure-Python
 * reference (scripts/gen_test_vectors.py — written straight from the AOSP/
 * BoringSSL spec; the HKDF vectors include RFC 5869 test cases).
 */
class Spake2Test {

    private data class Vector(
        val password: ByteArray,
        val clientPriv: BigInteger,
        val serverPriv: BigInteger,
        val clientMsg: ByteArray,
        val serverMsg: ByteArray,
        val key: ByteArray,
    )

    private fun vectors(): List<Vector> {
        val text = javaClass.getResourceAsStream("/testvectors.txt")
            ?.bufferedReader()?.readText()
            ?: error("testvectors.txt missing from test resources")
        return text.lineSequence()
            .filter { it.startsWith("SPAKE2 ") }
            .map { line ->
                val f = line.trim().split(" ")
                Vector(
                    password = f[1].hex(),
                    clientPriv = BigInteger(f[2]),
                    serverPriv = BigInteger(f[3]),
                    clientMsg = f[4].hex(),
                    serverMsg = f[5].hex(),
                    key = f[6].hex(),
                )
            }
            .toList()
    }

    @Test
    fun `client matches the python reference vector`() {
        vectors().forEach { v ->
            val (ctx, msg) = Spake2.clientForTest(v.password, v.clientPriv)
            assertArrayEquals("client msg mismatch", v.clientMsg, msg)
            val key = ctx.process(v.serverMsg)
            assertArrayEquals("client key mismatch", v.key, key)
        }
    }

    @Test
    fun `server matches the python reference vector`() {
        vectors().forEach { v ->
            val (ctx, msg) = Spake2.serverForTest(v.password, v.serverPriv)
            assertArrayEquals("server msg mismatch", v.serverMsg, msg)
            val key = ctx.process(v.clientMsg)
            assertArrayEquals("server key mismatch", v.key, key)
        }
    }

    @Test
    fun `client and server agree on the key for the same password`() {
        val password = "314159".toByteArray()
        val (alice, aliceMsg) = Spake2.client(password)
        val (bob, bobMsg) = Spake2.server(password)
        val keyAlice = alice.process(bobMsg)
        val keyBob = bob.process(aliceMsg)
        assertArrayEquals(keyAlice, keyBob)
        assertEquals(64, keyAlice.size)
    }

    @Test
    fun `wrong password produces a different key`() {
        val (alice, aliceMsg) = Spake2.client("111111".toByteArray())
        val (bob, bobMsg) = Spake2.server("222222".toByteArray())
        val keyAlice = alice.process(bobMsg)
        val keyBob = bob.process(aliceMsg)
        assertFalse(keyAlice.contentEquals(keyBob))
    }

    @Test
    fun `low order peer message is rejected`() {
        val (alice, _) = Spake2.client("999999".toByteArray())
        try {
            // The all-zero encoding decodes to the 2-torsion point (x²=-1, y=0)
            // — valid curve point with low order, so process() must reject it.
            alice.process(ByteArray(32))
            fail("expected an exception for a low-order peer message")
        } catch (expected: IllegalStateException) {
            // check() throws IllegalStateException
        }
    }

    private fun String.hex(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
