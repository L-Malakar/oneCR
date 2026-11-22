package com.lmalakar.onecr.service.detection

import com.lmalakar.onecr.core.common.TimeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeVoipTime(var now: Long = 1_000_000L) : TimeProvider {
    override fun nowMillis(): Long = now
}

class VoipHeuristicTest {

    private val time = FakeVoipTime()
    private val heuristic = VoipHeuristic(time, settleMs = 1_000)

    private fun advance(ms: Long) {
        time.now += ms
    }

    @Test
    fun `notification plus playback for settle window starts the call`() {
        heuristic.onNotificationCall("com.whatsapp")
        heuristic.onVoicePlaybackActive(true)

        assertNull(heuristic.evaluate()) // condition just started, not settled
        advance(999)
        assertNull(heuristic.evaluate()) // still under the window

        advance(1)
        val decision = heuristic.evaluate() as VoipDecision.Started
        assertEquals("com.whatsapp", decision.packageName)
        assertTrue(heuristic.callActive)
    }

    @Test
    fun `notification alone never starts a call`() {
        heuristic.onNotificationCall("com.whatsapp")
        advance(10_000)
        assertNull(heuristic.evaluate())
        assertEquals(false, heuristic.callActive)
    }

    @Test
    fun `playback alone never starts a call`() {
        heuristic.onVoicePlaybackActive(true)
        advance(10_000)
        assertNull(heuristic.evaluate())
        assertEquals(false, heuristic.callActive)
    }

    @Test
    fun `call ends after condition drops for the settle window`() {
        heuristic.onNotificationCall("com.whatsapp")
        heuristic.onVoicePlaybackActive(true)
        advance(1_500)
        heuristic.evaluate() as VoipDecision.Started
        advance(120_000) // talk for two minutes

        heuristic.onVoicePlaybackActive(false)
        heuristic.onNotificationCall(null)
        advance(999)
        assertNull(heuristic.evaluate()) // still inside debounce

        advance(1)
        val ended = heuristic.evaluate() as VoipDecision.Ended
        assertEquals("com.whatsapp", ended.packageName)
        assertTrue(ended.durationMillis >= 120_000L)
        assertEquals(false, heuristic.callActive)
    }

    @Test
    fun `brief flicker inside the debounce window does not end the call`() {
        heuristic.onNotificationCall("com.whatsapp")
        heuristic.onVoicePlaybackActive(true)
        advance(1_500)
        heuristic.evaluate() as VoipDecision.Started

        // Notification disappears for a split second, then returns.
        heuristic.onNotificationCall(null)
        advance(500)
        heuristic.onNotificationCall("com.whatsapp")
        advance(500)
        assertNull(heuristic.evaluate())
        assertEquals(true, heuristic.callActive)
    }

    @Test
    fun `bounce before settle does not start a call`() {
        heuristic.onNotificationCall("com.whatsapp")
        heuristic.onVoicePlaybackActive(true)
        advance(700)
        heuristic.onVoicePlaybackActive(false)
        advance(700)
        heuristic.onVoicePlaybackActive(true)
        advance(700)
        // Condition kept restarting — never settled for the full window.
        assertNull(heuristic.evaluate())
        assertEquals(false, heuristic.callActive)
    }

    @Test
    fun `recording configuration can substitute for playback`() {
        heuristic.onNotificationCall("us.zoom.videomeetings")
        heuristic.onVoiceRecordingActive(true)
        advance(1_500)
        val decision = heuristic.evaluate() as VoipDecision.Started
        assertEquals("us.zoom.videomeetings", decision.packageName)
    }

    @Test
    fun `reset forgets everything`() {
        heuristic.onNotificationCall("com.whatsapp")
        heuristic.onVoicePlaybackActive(true)
        advance(1_500)
        heuristic.evaluate()
        heuristic.reset()
        assertEquals(false, heuristic.callActive)
        assertNull(heuristic.evaluate())
    }
}
