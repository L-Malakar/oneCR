package com.lmalakar.onecr.service.detection

import android.content.Context
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import java.util.concurrent.Executor

/**
 * Bridges the system telephony callbacks into [CallStateMachine] updates.
 *
 * API 31+: TelephonyCallback.CallStateListener.
 * API 26–30: the legacy PhoneStateListener (deprecated but fully functional).
 *
 * Requires READ_PHONE_STATE; [start] returns false when it is missing so the
 * engine can degrade to VoIP-only detection instead of crashing.
 */
class CellularCallDetector(
    private val context: Context,
    private val executor: Executor,
    private val onState: (CallState, String?) -> Unit,
) {

    private var telephonyManager: TelephonyManager? = null

    @Volatile
    private var registered = false

    // API 31+ callback.
    private val telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            dispatch(state, number = null)
        }
    }

    // API 26–30 callback (number only usable when READ_CALL_LOG is also granted).
    @Suppress("DEPRECATION")
    private val phoneStateListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            dispatch(state, phoneNumber)
        }
    }

    /** @return true when the listener was registered successfully. */
    fun start(): Boolean {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            telephonyManager = tm ?: return false
            if (Build.VERSION.SDK_INT >= 31) {
                tm.registerTelephonyCallback(executor, telephonyCallback)
            } else {
                tm.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            }
            registered = true
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun stop() {
        if (!registered) return
        val tm = telephonyManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                tm.unregisterTelephonyCallback(telephonyCallback)
            } else {
                tm.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
            }
        }
        registered = false
    }

    private fun dispatch(state: Int, number: String?) {
        val mapped = when (state) {
            TelephonyManager.CALL_STATE_RINGING -> CallState.RINGING
            TelephonyManager.CALL_STATE_OFFHOOK -> CallState.OFFHOOK
            else -> CallState.IDLE
        }
        onState(mapped, number)
    }
}
