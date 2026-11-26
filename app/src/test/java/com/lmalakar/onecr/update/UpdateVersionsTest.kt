package com.lmalakar.onecr.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionsTest {

    @Test
    fun `parse strips the v prefix and splits numerically`() {
        assertEquals(listOf(0, 3, 3), UpdateVersions.parse("0.3.3"))
        assertEquals(listOf(0, 3, 3), UpdateVersions.parse("v0.3.3"))
        assertEquals(listOf(0, 3, 3), UpdateVersions.parse("V0.3.3"))
        assertEquals(listOf(0, 4, 0), UpdateVersions.parse("v0.4.0-beta"))
        assertEquals(emptyList<Int>(), UpdateVersions.parse(""))
        assertEquals(emptyList<Int>(), UpdateVersions.parse("garbage"))
    }

    @Test
    fun `newer patch version is detected`() {
        assertTrue(UpdateVersions.isNewer("0.3.3", "0.3.2"))
        assertTrue(UpdateVersions.isNewer("v0.3.3", "0.3.2"))
    }

    @Test
    fun `same version is not newer`() {
        assertFalse(UpdateVersions.isNewer("0.3.3", "0.3.3"))
        assertFalse(UpdateVersions.isNewer("v0.3.3", "0.3.3"))
    }

    @Test
    fun `older or garbage is not newer`() {
        assertFalse(UpdateVersions.isNewer("0.3.1", "0.3.2"))
        assertFalse(UpdateVersions.isNewer("garbage", "0.3.2"))
        assertFalse(UpdateVersions.isNewer("", "0.3.2"))
    }

    @Test
    fun `missing components count as zero`() {
        assertTrue(UpdateVersions.isNewer("0.4", "0.3.9")) // 0.4.0 > 0.3.9
        assertTrue(UpdateVersions.isNewer("1", "0.99.99"))
        assertFalse(UpdateVersions.isNewer("0.3", "0.3.1"))
    }

    @Test
    fun `major and minor bumps win over patch`() {
        assertTrue(UpdateVersions.isNewer("1.0.0", "0.9.9"))
        assertTrue(UpdateVersions.isNewer("0.4.0", "0.3.99"))
    }
}
