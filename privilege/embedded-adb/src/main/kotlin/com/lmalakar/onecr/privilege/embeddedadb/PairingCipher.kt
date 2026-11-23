package com.lmalakar.onecr.privilege.embeddedadb

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The pairing_auth cipher: AES-128-GCM keyed by HKDF-SHA256 over the SPAKE2
 * keying material, nonce = little-endian uint64 sequence + 4 zero bytes,
 * separate tx/rx sequence counters, no AAD, 16-byte tag appended.
 *
 * Mirrors adb/pairing_auth/aes_128_gcm.cpp exactly.
 */
internal class PairingCipher(keyMaterial: ByteArray) {

    private val key: ByteArray
    private var encSequence: Long = 0
    private var decSequence: Long = 0

    init {
        require(keyMaterial.isNotEmpty())
        key = hkdfSha256(
            ikm = keyMaterial,
            salt = ByteArray(32), // BoringSSL HKDF with salt=NULL uses zero-filled salt
            info = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.US_ASCII),
            length = 16,
        )
    }

    /** Encrypts [plaintext]; returns ciphertext with the 16-byte GCM tag appended. */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val nonce = nonceFor(encSequence)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val out = cipher.doFinal(plaintext) // ciphertext || 16-byte tag
        encSequence++
        return out
    }

    /** Decrypts [ciphertext] (with 16-byte tag); throws AEADBadTagException on wrong key. */
    fun decrypt(ciphertext: ByteArray): ByteArray {
        val nonce = nonceFor(decSequence)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val out = cipher.doFinal(ciphertext)
        decSequence++
        return out
    }

    private fun nonceFor(sequence: Long): ByteArray {
        val nonce = ByteArray(12)
        var s = sequence
        for (i in 0 until 8) { // little-endian uint64
            nonce[i] = (s and 0xFF).toByte()
            s = s ushr 8
        }
        return nonce
    }

    companion object {
        /** RFC 5869 HKDF with SHA-256. */
        fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
            val hmac = Mac.getInstance("HmacSHA256")
            // Extract
            hmac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
            val prk = hmac.doFinal(ikm)
            // Expand
            hmac.init(SecretKeySpec(prk, "HmacSHA256"))
            val out = ByteArray(length)
            var t = ByteArray(0)
            var pos = 0
            var counter = 1
            while (pos < length) {
                hmac.reset()
                hmac.init(SecretKeySpec(prk, "HmacSHA256"))
                hmac.update(t)
                hmac.update(info)
                hmac.update(counter.toByte())
                t = hmac.doFinal()
                val toCopy = minOf(t.size, length - pos)
                System.arraycopy(t, 0, out, pos, toCopy)
                pos += toCopy
                counter++
            }
            return out
        }

        fun sha256(data: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(data)
    }
}
