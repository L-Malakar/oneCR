package com.lmalakar.onecr.privilege.embeddedadb

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * Full pairing protocol test: AdbPairingClient against an out-of-process mock
 * of the phone's pairing server — TLS 1.3 (client certs) + RFC 5705 exporter
 * ("adb-label\0") + SPAKE2 (Bob) + AES-128-GCM PeerInfo exchange, all on the
 * bundled BoringSSL (Conscrypt) — the same stack the real adbd uses.
 */
class PairingEndToEndTest {

    private lateinit var server: MockServerProcess.Started
    private lateinit var identity: AdbIdentity

    @Before
    fun setUp() {
        identity = AdbIdentity.generate()
        server = MockServerProcess.start("pairing", code = PAIRING_CODE)
    }

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun `pairing succeeds with the correct code`() {
        val result = AdbPairingClient(
            identity,
            "127.0.0.1",
            server.port,
            PAIRING_CODE,
            timeoutMs = 10_000,
        ).run()
        assertEquals(MOCK_GUID, result.deviceGuid)
    }

    @Test
    fun `pairing with the wrong code fails`() {
        assertThrows(Exception::class.java) {
            AdbPairingClient(
                identity,
                "127.0.0.1",
                server.port,
                WRONG_CODE,
                timeoutMs = 10_000,
            ).run()
        }
    }

    companion object {
        private const val PAIRING_CODE = "419207"
        private const val WRONG_CODE = "555555"
        private const val MOCK_GUID = "mock-device-guid-01"
    }
}
