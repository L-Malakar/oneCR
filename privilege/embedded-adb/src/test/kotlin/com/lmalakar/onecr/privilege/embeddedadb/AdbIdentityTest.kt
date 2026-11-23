package com.lmalakar.onecr.privilege.embeddedadb

import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.cert.CertificateFactory
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AdbIdentityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `self-signed certificate parses and carries the expected fields`() {
        val identity = AdbIdentity.generate()
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(identity.selfSignedCertificate()))
            as java.security.cert.X509Certificate

        assertEquals("X.509", cert.type)
        assertEquals(1, cert.serialNumber.toLong())
        assertTrue(cert.subjectX500Principal.name.contains("CN=Adb"))
        assertTrue(cert.subjectX500Principal.name.contains("O=Android"))
        assertTrue(cert.subjectX500Principal.name.contains("C=US"))
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        assertEquals("SHA256withRSA", cert.sigAlgName)
        // The certificate must carry the identity key's public key.
        assertArrayEquals(
            identity.rsaPublicKey.encoded,
            (cert.publicKey as java.security.interfaces.RSAPublicKey).encoded,
        )
        // Signature must verify against the certificate's own public key.
        cert.verify(cert.publicKey)
        // Validity ~10 years.
        val days = (cert.notAfter.time - cert.notBefore.time) / 86_400_000L
        assertTrue(days in 3640..3660)
    }

    @Test
    fun `android_pubkey encoding matches the adb struct layout`() {
        val identity = AdbIdentity.generate()
        val line = identity.adbPublicKeyLine()
        val b64 = line.substringBefore(" ")
        val name = line.substringAfter(" ")
        assertEquals("onecr@android", name)

        val raw = Base64.getDecoder().decode(b64)
        assertEquals(524, raw.size) // 4 + 4 + 256 + 256 + 4

        val bb = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val wordCount = bb.int
        val n0inv = bb.int.toLong() and 0xFFFFFFFFL
        val modulus = ByteArray(256)
        bb.get(modulus)
        val rr = ByteArray(256)
        bb.get(rr)
        val exponent = bb.int.toLong() and 0xFFFFFFFFL

        assertEquals(64, wordCount)
        assertEquals(65537L, exponent)

        val keyN = identity.rsaPublicKey.modulus
        // Modulus little-endian must reconstruct the key modulus.
        val rebuilt = java.math.BigInteger(
            1,
            modulus.reversedArray().let { b ->
                if (b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
            },
        )
        assertEquals(keyN, rebuilt)

        // rr = 2^4096 mod n
        assertEquals(
            java.math.BigInteger.TWO.pow(4096).mod(keyN),
            java.math.BigInteger(1, rr.reversedArray().let { b ->
                if (b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
            }),
        )

        // n0inv = r32 - (n mod 2^32)^-1 mod 2^32 (verify by modular algebra)
        val r32 = BigInteger.TWO.pow(32)
        val inv = keyN.mod(r32).modInverse(r32)
        assertEquals(r32.subtract(inv).mod(r32), BigInteger.valueOf(n0inv))
    }

    @Test
    fun `identity persists and reloads`() {
        val dir = tmp.newFolder()
        val first = AdbIdentity.loadOrCreate(dir)
        val pem = first.privateKeyPem()
        assertTrue(pem.startsWith("-----BEGIN PRIVATE KEY-----"))
        val second = AdbIdentity.loadOrCreate(dir)
        // PKCS#8 re-encodings may differ in principle; compare key material.
        val k1 = first.privateKey as java.security.interfaces.RSAPrivateCrtKey
        val k2 = second.privateKey as java.security.interfaces.RSAPrivateCrtKey
        assertEquals(k1.modulus, k2.modulus)
        assertEquals(k1.privateExponent, k2.privateExponent)

        val third = AdbIdentity.fromPem(pem)
        assertEquals(
            first.rsaPublicKey.modulus,
            third.rsaPublicKey.modulus,
        )
    }

    @Test
    fun `corrupt key file is regenerated`() {
        val dir = tmp.newFolder()
        File(dir, "adb_identity_key.pem").writeText("not a key")
        val identity = AdbIdentity.loadOrCreate(dir)
        val again = AdbIdentity.loadOrCreate(dir)
        assertEquals(
            (identity.privateKey as java.security.interfaces.RSAPrivateCrtKey).modulus,
            (again.privateKey as java.security.interfaces.RSAPrivateCrtKey).modulus,
        )
        assertNotEquals("not a key", File(dir, "adb_identity_key.pem").readText())
    }
}
