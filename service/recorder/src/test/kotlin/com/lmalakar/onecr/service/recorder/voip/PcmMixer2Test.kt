package com.lmalakar.onecr.service.recorder.voip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The mixing math behind the VoIP dual-path capture (v0.3.7). */
class PcmMixer2Test {

    private fun le16(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
    )

    private fun s16(buf: ByteArray, i: Int): Int =
        (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)

    @Test
    fun `mixes two identical streams sample by sample`() {
        val m = PcmMixer2()
        m.pushA(le16(100) + le16(-200) + le16(1000))
        m.pushB(le16(50) + le16(-50) + le16(2000))
        val dst = ByteArray(6)
        assertEquals(6, m.mixInto(dst))
        assertEquals(150, s16(dst, 0))
        assertEquals(-250, s16(dst, 2))
        assertEquals(3000, s16(dst, 4))
    }

    @Test
    fun `saturates instead of overflowing`() {
        val m = PcmMixer2()
        m.pushA(le16(30000))
        m.pushB(le16(30000))
        val dst = ByteArray(2)
        m.mixInto(dst)
        assertEquals(32767, s16(dst, 0))

        val n = PcmMixer2()
        n.pushA(le16(-30000))
        n.pushB(le16(-30000))
        n.mixInto(dst)
        assertEquals(-32768, s16(dst, 0))
    }

    @Test
    fun `unequal input lengths mix the minimum and keep the remainder`() {
        val m = PcmMixer2()
        m.pushA(le16(100) + le16(200) + le16(400)) // 3 frames
        m.pushB(le16(10) + le16(20))               // 2 frames
        val dst = ByteArray(8)
        assertEquals(4, m.mixInto(dst))
        assertEquals(110, s16(dst, 0))
        assertEquals(220, s16(dst, 2))
        // A's third frame stays queued until B catches up.
        assertEquals(2, m.queuedABytes())
        assertEquals(0, m.queuedBBytes())
        m.pushB(le16(30))
        assertEquals(2, m.mixInto(dst))
        assertEquals(430, s16(dst, 0))
    }

    @Test
    fun `a single live path passes through untouched`() {
        val m = PcmMixer2()
        m.pushA(le16(-1234) + le16(777))
        val dst = ByteArray(8)
        assertEquals(4, m.mixInto(dst))
        assertEquals(-1234, s16(dst, 0))
        assertEquals(777, s16(dst, 2))
    }

    @Test
    fun `a late-starting second path joins cleanly`() {
        val m = PcmMixer2()
        m.pushA(le16(100) + le16(100))
        val dst = ByteArray(8)
        assertEquals(4, m.mixInto(dst)) // passthrough while alone
        m.pushB(le16(5))
        assertEquals(0, m.mixInto(dst)) // B waits until A supplies frames again
        m.pushA(le16(100) + le16(200))
        assertEquals(2, m.mixInto(dst)) // one mixed frame now
        assertEquals(105, s16(dst, 0))
    }

    @Test
    fun `carry buffers are capped to the newest frames`() {
        val tiny = PcmMixer2(maxCarryBytes = 4)
        tiny.pushA(le16(1))
        tiny.pushA(le16(2))
        tiny.pushA(le16(3)) // cap = 2 frames: frame 1 is dropped
        assertEquals(4, tiny.queuedABytes())
        tiny.pushB(le16(10))
        val dst = ByteArray(8)
        // B has only one frame, so exactly one frame is mixed now.
        assertEquals(2, tiny.mixInto(dst))
        assertEquals(12, s16(dst, 0)) // A's frame 2 mixed with B's frame 1
        assertEquals(2, tiny.queuedABytes()) // A's frame 3 stays queued
        tiny.pushB(le16(20))
        assertEquals(2, tiny.mixInto(dst))
        assertEquals(23, s16(dst, 0)) // A's frame 3 mixed with B's frame 2
    }

    @Test
    fun `an odd trailing byte is ignored`() {
        val m = PcmMixer2()
        m.pushA(byteArrayOf(1, 0, 7)) // 1 frame + stray byte
        val dst = ByteArray(8)
        assertEquals(2, m.mixInto(dst))
        assertEquals(1, s16(dst, 0))
    }

    @Test
    fun `empty mixer writes nothing`() {
        val m = PcmMixer2()
        val dst = ByteArray(8)
        assertEquals(0, m.mixInto(dst))
        assertTrue(dst.all { it == 0.toByte() })
    }

    private infix fun ByteArray.plus(other: ByteArray): ByteArray =
        this + other
}
