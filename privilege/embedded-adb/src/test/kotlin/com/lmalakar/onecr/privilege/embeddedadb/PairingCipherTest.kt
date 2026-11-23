package com.lmalakar.onecr.privilege.embeddedadb

import javax.crypto.AEADBadTagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingCipherTest {

    @Test
    fun `hkdf matches RFC 5869 and adb pairing vectors`() {
        val text = javaClass.getResourceAsStream("/testvectors.txt")
            ?.bufferedReader()?.readText()
            ?: error("testvectors.txt missing")
        val lines = text.lineSequence().filter { it.startsWith("HKDF ") || it.startsWith("PAIRINGKEY ") }.toList()

        lines.filter { it.startsWith("HKDF ") }.forEach { line ->
            val f = line.split(" ")
            val ikm = f[1].hex()
            val salt = if (f[2] == "-") ByteArray(0) else f[2].hex()
            val info = if (f[3] == "-") ByteArray(0) else f[3].hex()
            val length = f[4].toInt()
            val okm = f[5].hex()
            assertArrayEquals(okm, PairingCipher.hkdfSha256(ikm, salt, info, length))
        }

        lines.filter { it.startsWith("PAIRINGKEY ") }.forEach { line ->
            val f = line.split(" ")
            val material = f[1].hex()
            val expectedKey = f[2].hex()
            val cipher = PairingCipher(material)
            // The key is private; prove correctness indirectly via AES-GCM against
            // an independently computed ciphertext below (sequence 0 nonce).
            val nonce = ByteArray(12)
            val plaintext = "probe-${f[1].take(16)}".toByteArray()
            val javaxCt = javaxAesGcm(expectedKey, nonce, plaintext)
            val decrypted = cipher.decrypt(javaxCt) // our decrypt must accept theirs
            assertArrayEquals(plaintext, decrypted)
            // And our encrypt must decrypt with the reference key.
            val ours = cipher.encrypt(plaintext)
            val roundtrip = javaxAesGcmDecrypt(expectedKey, nonce, ours)
            assertArrayEquals(plaintext, roundtrip)
        }
    }

    @Test
    fun `encrypt decrypt roundtrip with independent sequences`() {
        val cipher = PairingCipher(ByteArray(64) { it.toByte() })
        val plain = "hello adb pairing".toByteArray()
        val ct1 = cipher.encrypt(plain)
        val ct2 = cipher.encrypt(plain)
        // Different sequence → different ciphertext for the same plaintext.
        assertFalse(ct1.contentEquals(ct2))
        assertEquals(plain.size + 16, ct1.size)
        assertArrayEquals(plain, cipher.decrypt(ct1))
        assertArrayEquals(plain, cipher.decrypt(ct2))
    }

    @Test
    fun `decrypt with the wrong key fails`() {
        val good = PairingCipher(ByteArray(64) { 1 })
        val bad = PairingCipher(ByteArray(64) { 2 })
        val ct = good.encrypt("x".toByteArray())
        assertThrows(AEADBadTagException::class.java) { bad.decrypt(ct) }
    }

    // --- reference javax implementations (independent of PairingCipher) ---

    private fun javaxAesGcm(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, nonce),
        )
        return c.doFinal(plaintext)
    }

    private fun javaxAesGcmDecrypt(key: ByteArray, nonce: ByteArray, ct: ByteArray): ByteArray {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, nonce),
        )
        return c.doFinal(ct)
    }

    private fun String.hex(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
