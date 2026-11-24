package com.lmalakar.onecr.privilege.api

import com.lmalakar.onecr.core.model.CaptureSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureProtocolTest {

    // ------------------------------------------------------------------
    // Command builder
    // ------------------------------------------------------------------

    @Test
    fun `probe command embeds apk path, rates and window`() {
        val cmd = CaptureProtocol.probeCommand(
            "/data/app/com.lmalakar.onecr/base.apk",
            rates = listOf(16_000, 8_000),
            probeMs = 250,
        )
        assertTrue(cmd.startsWith("CLASSPATH='/data/app/com.lmalakar.onecr/base.apk'"))
        assertTrue(cmd.contains("app_process / ${CaptureProtocol.MAIN_CLASS} probe"))
        assertTrue(cmd.contains("--rates 16000,8000"))
        assertTrue(cmd.contains("--ms 250"))
    }

    @Test
    fun `start command maps sources and guards the max duration`() {
        val cmd = CaptureProtocol.startCommand(
            apkPath = "/data/app/x/base.apk",
            source = CaptureSource.VOICE_CALL,
            sampleRateHz = 16_000,
            maxDurationMs = 7_200_000,
        )
        assertTrue(cmd.contains("start --source VOICE_CALL --rate 16000 --max-ms 7200000"))
    }

    @Test
    fun `apostrophes in the apk path are shell-escaped`() {
        val cmd = CaptureProtocol.probeCommand("/data/app/it's/base.apk")
        assertTrue(cmd.contains("'\\''"))
    }

    // ------------------------------------------------------------------
    // Probe parser
    // ------------------------------------------------------------------

    @Test
    fun `probe report parses uid and every source line`() {
        val report = """
            UID|2000
            SRC|VOICE_CALL|16000|OK|6400
            SRC|VOICE_CALL|8000|SILENT|3200
            SRC|VOICE_DOWNLINK|16000|FAIL|0|init state=0
            SRC|VOICE_UPLINK|16000|OK|6400
            SRC|MIC|16000|OK|6400
            DONE
        """.trimIndent()

        val result = CaptureProtocol.parseProbeOutput(report)
        assertEquals(2000, result.uid)
        assertEquals(5, result.sources.size)
        val call = result.sources.first()
        assertEquals("VOICE_CALL", call.source)
        assertEquals(16_000, call.rateHz)
        assertEquals(CaptureProtocol.ProbeOutcome.OK, call.outcome)
        assertEquals(6_400L, call.framesRead)
        assertNull(call.error)
    }

    @Test
    fun `garbled lines are skipped, not fatal`() {
        val result = CaptureProtocol.parseProbeOutput("hello world\n\nUID|notanumber\nSRC|x|y|z\nDONE")
        assertNull(result.uid)
        // "SRC|x|y|z" has only 4 segments → skipped; unknown → nothing parsed
        assertEquals(0, result.sources.size)
    }

    // ------------------------------------------------------------------
    // Fallback chain composition
    // ------------------------------------------------------------------

    @Test
    fun `VOICE_CALL wins the chain when it works`() {
        val result = CaptureProtocol.parseProbeOutput(
            """
            UID|2000
            SRC|VOICE_CALL|16000|OK|6400
            SRC|VOICE_DOWNLINK|16000|OK|6400
            SRC|VOICE_UPLINK|16000|OK|6400
            SRC|MIC|16000|OK|6400
            """.trimIndent(),
        )
        assertEquals(
            listOf(CaptureSource.VOICE_CALL, CaptureSource.VOICE_DOWNLINK_UPLINK, CaptureSource.MICROPHONE),
            CaptureProtocol.workingSources(result),
        )
        assertEquals(16_000, CaptureProtocol.workingSampleRate(result))
    }

    @Test
    fun `dual pair needs BOTH sides working`() {
        val result = CaptureProtocol.parseProbeOutput(
            """
            SRC|VOICE_CALL|16000|FAIL|0|init state=0
            SRC|VOICE_DOWNLINK|16000|OK|6400
            SRC|VOICE_UPLINK|16000|FAIL|0|init state=0
            SRC|MIC|16000|OK|6400
            """.trimIndent(),
        )
        assertEquals(listOf(CaptureSource.MICROPHONE), CaptureProtocol.workingSources(result))
    }

    @Test
    fun `silent-but-open sources fall back to mic`() {
        val result = CaptureProtocol.parseProbeOutput(
            """
            SRC|VOICE_CALL|16000|SILENT|6400
            SRC|MIC|16000|SILENT|6400
            """.trimIndent(),
        )
        assertEquals(listOf(CaptureSource.MICROPHONE), CaptureProtocol.workingSources(result))
    }

    @Test
    fun `empty chain when nothing opens`() {
        val result = CaptureProtocol.parseProbeOutput(
            "SRC|VOICE_CALL|16000|FAIL|0|permission denied",
        )
        assertTrue(CaptureProtocol.workingSources(result).isEmpty())
        assertEquals(16_000, CaptureProtocol.workingSampleRate(result))
    }
}
