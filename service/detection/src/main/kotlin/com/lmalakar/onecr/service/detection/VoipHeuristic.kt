package com.lmalakar.onecr.service.detection

import com.lmalakar.onecr.core.common.TimeProvider

/** What the heuristic decided — fed back as CallEvent.Voip* by the engine. */
sealed interface VoipDecision {
    data class Started(val packageName: String) : VoipDecision
    data class Ended(val packageName: String, val durationMillis: Long) : VoipDecision
}

/**
 * Pure VoIP call heuristic + debouncer (no Android imports — unit-tested).
 *
 * Inputs (ARCHITECTURE.md §3):
 *  1. an ongoing CATEGORY_CALL notification from an allowlisted package;
 *  2. active voice-communication playback (other app's call audio);
 *  3. an active voice-communication recording configuration.
 *
 * Rule: a VoIP call is STARTED when (1) AND (2 or 3) hold continuously for
 * [settleMs]; it ENDS when the condition drops continuously for [settleMs].
 * The debounce suppresses pre-call UI noise (WhatsApp posts the notification
 * before audio starts ringing) and post-call teardown flicker.
 */
class VoipHeuristic(
    private val time: TimeProvider,
    private val settleMs: Long = 1_500,
) {

    /** Package with an active call notification (null = none). */
    private var notificationPackage: String? = null

    private var voicePlaybackActive = false
    private var voiceRecordingActive = false

    /** First timestamp the start condition currently holds (0 = not holding). */
    private var startConditionSince = 0L

    /** First timestamp the started call lost its condition (0 = condition still holds). */
    private var endConditionSince = 0L

    private var activePackage: String? = null
    private var activeSinceMillis = 0L

    /** True when the heuristic currently reports an ongoing VoIP call. */
    val callActive: Boolean get() = activePackage != null

    // ------------------------------------------------------------------
    // Inputs
    // ------------------------------------------------------------------

    fun onNotificationCall(packageName: String?) {
        notificationPackage = packageName
        syncConditionClock()
    }

    fun onVoicePlaybackActive(active: Boolean) {
        voicePlaybackActive = active
        syncConditionClock()
    }

    fun onVoiceRecordingActive(active: Boolean) {
        voiceRecordingActive = active
        syncConditionClock()
    }

    /**
     * Starts/stops the settle clocks at INPUT time so debounce windows are
     * measured from the moment the condition changed, not from the first
     * [evaluate] tick after it.
     */
    private fun syncConditionClock() {
        val holds = notificationPackage != null && (voicePlaybackActive || voiceRecordingActive)
        if (activePackage == null) {
            when {
                holds && startConditionSince == 0L -> startConditionSince = time.nowMillis()
                !holds -> startConditionSince = 0L
            }
        } else {
            when {
                !holds && endConditionSince == 0L -> endConditionSince = time.nowMillis()
                holds -> endConditionSince = 0L
            }
        }
    }

    // ------------------------------------------------------------------
    // Evaluation
    // ------------------------------------------------------------------

    /**
     * Evaluate the current inputs; returns a decision when a settled state change
     * happened, null otherwise. Call this on every input change AND periodically
     * (the engine ticks ~2× per settle window) so debounces fire without new input.
     */
    fun evaluate(): VoipDecision? {
        val now = time.nowMillis()
        val pkg = notificationPackage
        val conditionHolds = pkg != null && (voicePlaybackActive || voiceRecordingActive)

        if (activePackage == null) {
            if (conditionHolds) {
                if (startConditionSince == 0L) {
                    startConditionSince = now
                    return null
                }
                if (now - startConditionSince >= settleMs) {
                    activePackage = pkg
                    activeSinceMillis = now
                    startConditionSince = 0L
                    endConditionSince = 0L
                    return VoipDecision.Started(pkg!!)
                }
            } else {
                startConditionSince = 0L
            }
        } else {
            if (conditionHolds) {
                // Condition still holds (possibly for another package) — stay in call.
                endConditionSince = 0L
                if (pkg != null && pkg != activePackage) {
                    // Hot hand-off between two VoIP apps: treat as same session.
                    activePackage = pkg
                }
            } else {
                if (endConditionSince == 0L) {
                    endConditionSince = now
                    return null
                }
                if (now - endConditionSince >= settleMs) {
                    val decision = VoipDecision.Ended(
                        packageName = activePackage!!,
                        durationMillis = (now - activeSinceMillis).coerceAtLeast(0),
                    )
                    activePackage = null
                    activeSinceMillis = 0L
                    startConditionSince = 0L
                    endConditionSince = 0L
                    return decision
                }
            }
        }
        return null
    }

    /** Hard reset (detection stopped). Emits nothing. */
    fun reset() {
        notificationPackage = null
        voicePlaybackActive = false
        voiceRecordingActive = false
        startConditionSince = 0L
        endConditionSince = 0L
        activePackage = null
        activeSinceMillis = 0L
    }
}
