package com.lmalakar.onecr.privilege.embeddedadb

import android.content.Context

/**
 * Persists the embedded-ADB pairing state (v0.3.0): whether the phone granted
 * our key during wireless-debugging pairing, the device GUID it reported, and
 * the last adbd port we connected on (the fallback when NSD discovery misses —
 * the port changes on every reboot).
 */
internal class PairingStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True once a pairing handshake completed (our key is authorized on the phone). */
    var paired: Boolean
        get() = prefs.getBoolean(KEY_PAIRED, false)
        set(value) = prefs.edit().putBoolean(KEY_PAIRED, value).apply()

    /** GUID the device reported in the PeerInfo exchange (null if never paired). */
    var deviceGuid: String?
        get() = prefs.getString(KEY_DEVICE_GUID, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_GUID, value).apply()

    /** Last adbd wireless-debugging port that accepted a connection (0 = unknown). */
    var lastConnectPort: Int
        get() = prefs.getInt(KEY_LAST_PORT, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_PORT, value).apply()

    /** Forgets the pairing (the key stays on the phone — there is no remote revoke). */
    fun clearPairing() {
        prefs.edit()
            .putBoolean(KEY_PAIRED, false)
            .putString(KEY_DEVICE_GUID, null)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "onecr_adb_pairing"
        const val KEY_PAIRED = "paired"
        const val KEY_DEVICE_GUID = "device_guid"
        const val KEY_LAST_PORT = "last_connect_port"
    }
}
