package com.lmalakar.onecr.privilege.embeddedadb

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end shell test: AdbClient (TLS 1.3 + CNXN + shell v2) against the
 * out-of-process mock adbd (see MockServerProcess for why it is a subprocess).
 */
class AdbClientTest {

    private lateinit var server: MockServerProcess.Started
    private lateinit var identity: AdbIdentity
    private lateinit var client: AdbClient

    @Before
    fun setUp() {
        identity = AdbIdentity.generate()
        server = MockServerProcess.start("adbd")
        client = AdbClient(identity, "127.0.0.1", server.port)
    }

    @After
    fun tearDown() {
        client.close()
        server.stop()
    }

    @Test
    fun `cnxn handshake and simple shell command`() {
        client.connect()
        val result = client.runShell("echo hello")
        assertEquals("hello", result.combinedOutput)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `shell with stderr and non-zero exit`() {
        client.connect()
        val result = client.runShell("boom")
        // stdout first, then stderr appended after a newline; the stderr frame
        // is split across two WRTE packets on the wire.
        assertEquals("out-\nbefore", result.combinedOutput)
        assertEquals(3, result.exitCode)
    }

    @Test
    fun `large output split across frames`() {
        client.connect()
        val result = client.runShell("big")
        assertTrue(result.combinedOutput.length >= 5000)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `commands run over a single connection sequentially`() {
        client.connect()
        assertEquals("one", client.runShell("a").combinedOutput)
        assertEquals("two", client.runShell("b").combinedOutput)
        assertEquals(0, client.runShell("c").exitCode)
    }

    @Test
    fun `reconnect after server-side socket death`() {
        client.connect()
        // "drop" serves its output and then the server kills the TLS session
        // without a clean CLSE.
        assertEquals("one", client.runShell("drop").combinedOutput)
        assertEquals("two", client.runShell("b").combinedOutput)
    }
}
