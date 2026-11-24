package com.lmalakar.onecr.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeModeTest {

    @Test
    fun `decodes every persisted key`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorageKey("SYSTEM"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromStorageKey("LIGHT"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromStorageKey("DARK"))
    }

    @Test
    fun `null or unknown values fall back to SYSTEM`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorageKey(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorageKey(""))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorageKey("dark-mode"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorageKey("system"))
    }

    @Test
    fun `storage keys round-trip and stay stable across releases`() {
        // These strings are persisted in SharedPreferences — never rename them.
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, ThemeMode.fromStorageKey(mode.storageKey))
        }
    }
}
