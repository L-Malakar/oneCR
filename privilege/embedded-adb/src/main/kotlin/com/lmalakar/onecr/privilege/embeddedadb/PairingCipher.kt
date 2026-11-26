package com.lmalakar.onecr.privilege.embeddedadb

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The adb pairing cipher (packages/modules/adb pairing_cipher.cpp, reimplemented
 * on-device for the M2.5 embedded bridge):
 *
 *  - HKDF-SHA256 over the SPAKE2 shared secret (salt = empty,
 *    info = "adb-pairing-key") derives the AES-128-GCM key;
 *  - every encrypt/decrypt uses a 12-byte IV built from an 8-byte
 *    little-endian sequence counter (separate tx/rx sequences, both starting
 *    at 0 — mirrors the memcpy(&seq_) layout in AOSP) followed by zero bytes;
 *  - ciphertext format is `ct || 16-byte tag`, exactly what the phone's adbd
 *    produces and expects for the PeerInfo exchange.
 */
internal class PairingCipher(keyMaterial: ByteArray) {

    private val key = ByteArray(AES_KEY_SIZE)
    private var txSequence: Long = 0
    private var rxSequence: Long = 0

    init {
        val derived = hkdfSha256(keyMaterial, ByteArray(0), HKDF_INFO.toByteArray(Charsets.US_ASCII), AES_KEY_SIZE)
        System.arraycopy(derived, 0, key, 0, AES_KEY_SIZE)
    }

    /** Encrypts [plaintext]; the result is `ciphertext || tag` (tag = 16 bytes). */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce(txSequence++)))
        return cipher.doFinal(plaintext)
    }

    /** Decrypts `ct || tag`; throws [AEADBadTagException] on a wrong key/ corrupted data. */
    fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size > TAG_BYTES) { "ciphertext too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce(rxSequence++)))
        return cipher.doFinal(ciphertext)
    }

    private fun nonce(sequence: Long): ByteArray {
        val iv = ByteArray(IV_SIZE)
        for (i in 0 until 8) {
            iv[i] = ((sequence ushr (8 * i)) and 0xFF).toByte() // little-endian counter
        }
        return iv
    }

    companion object {

        private const val AES_KEY_SIZE = 16
        private const val IV_SIZE = 12
        private const val TAG_BYTES = 16
        private const val TAG_BITS = 128
        private const val HKDF_INFO = "adb-pairing-key"

        /**
         * RFC 5869 HKDF (SHA-256): PRK = HMAC(salt, IKM), then T(i) =
         * HMAC(PRK, T(i-1) | info | i). Cross-validated against the RFC test
         * vectors in src/test/resources/testvectors.txt.
         */
        fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
            require(length <= 255 * 32) { "HKDF output too long" }
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")

            // Extract
            val prk = run {
                mac.init(javax.crypto.spec.SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
                mac.doFinal(ikm)
            }

            // Expand
            val okm = ByteArray(length)
            var t = ByteArray(0)
            var offset = 0
            var counter = 1
            while (offset < length) {
                mac.init(javax.crypto.spec.SecretKeySpec(prk, "HmacSHA256"))
                mac.update(t)
                mac.update(info)
                mac.update(counter.toByte())
                t = mac.doFinal()
                val n = minOf(t.size, length - offset)
                System.arraycopy(t, 0, okm, offset, n)
                offset += n
                counter++
            }
            return okm
        }
    }
}
