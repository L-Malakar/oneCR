package com.lmalakar.onecr.service.detection

import android.content.Context
import android.provider.CallLog
import com.lmalakar.onecr.core.model.CallDirection

/**
 * Resolves caller metadata from the system CallLog.
 *
 * Why post-call: on modern Android the PhoneStateListener/TelephonyCallback number
 * is empty or missing (especially for outgoing calls), but the CallLog row written
 * at call end has the number, the cached contact name and the direction. We read
 * only the newest row within a small time window — never the whole log.
 *
 * Requires READ_CALL_LOG; returns null (never throws) when the permission is missing.
 */
class CallerIdResolver(private val context: Context) {

    data class CallerId(
        val number: String?,
        val contactName: String?,
        val direction: CallDirection,
        val startedAtMillis: Long,
    )

    /**
     * Newest CallLog row written within [withinMillis]; null when the log is
     * inaccessible or nothing recent exists.
     */
    fun latestCall(withinMillis: Long = 20_000): CallerId? {
        val since = System.currentTimeMillis() - withinMillis
        return runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.TYPE,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.DATE,
                ),
                "${CallLog.Calls.DATE} > ?",
                arrayOf(since.toString()),
                "${CallLog.Calls.DATE} DESC",
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                CallerId(
                    number = cursor.getString(1)?.takeIf { it.isNotBlank() },
                    contactName = cursor.getString(2)?.takeIf { it.isNotBlank() },
                    direction = when (cursor.getInt(0)) {
                        CallLog.Calls.INCOMING_TYPE -> CallDirection.INCOMING
                        CallLog.Calls.OUTGOING_TYPE -> CallDirection.OUTGOING
                        else -> CallDirection.UNKNOWN
                    },
                    startedAtMillis = cursor.getLong(3),
                )
            }
        }.getOrNull()
    }
}
