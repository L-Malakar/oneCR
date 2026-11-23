package com.lmalakar.onecr.privilege.embeddedadb

import android.content.Context

/**
 * Persisted state for the embedded-ADB bridge (SharedPreferences):
 * the RSA identity PEM, the paired flag, the device GUID, and the last known
 * wireless-debugging port (NSD discovery is primary; this is the fallback).
 */
internal class PairingStore(context: Context) {

    private val prefs = context.getSharedPreferences("embedded_adb", Context.MODE_PRIVATE)

    var identityPem: String?
        get() = prefs.getString(KEY_IDENTITY, null)
        set(value) = prefs.edit().putString(KEY_IDENTITY, value).apply()

    var paired: Boolean
        get() = prefs.getBoolean(KEY_PAIRED, false)
        set(value) = prefs.edit().putBoolean(KEY_PAIRED, value).apply()

    var deviceGuid: String?
        get() = prefs.getString(KEY_GUID, null)
        set(value) = prefs.edit().putString(KEY_GUID, value).apply()

    /** Last wireless-debugging (adbd) port — changes after reboot/Wi-Fi toggle. */
    var lastConnectPort: Int
        get() = prefs.getInt(KEY_CONNECT_PORT, -1)
        set(value) = prefs.edit().putInt(KEY_CONNECT_PORT, value).apply()

    /** Device name recorded at pairing time, for display. */
    var deviceName: String?
        get() = prefs.getString(KEY_DEVICE_NAME, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value).apply()

    fun clearPairing() {
        prefs.edit()
            .putBoolean(KEY_PAIRED, false)
            .remove(KEY_GUID)
            .remove(KEY_DEVICE_NAME)
            .remove(KEY_CONNECT_PORT)
            .apply()
    }

    private companion object {
        const val KEY_IDENTITY = "identity_pem"
        const val KEY_PAIRED = "paired"
        const val KEY_GUID = "device_guid"
        const val KEY_CONNECT_PORT = "last_connect_port"
        const val KEY_DEVICE_NAME = "device_name"
    }
}
