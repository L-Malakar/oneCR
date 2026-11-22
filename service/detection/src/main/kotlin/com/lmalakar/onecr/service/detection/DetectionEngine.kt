package com.lmalakar.onecr.service.detection

import android.content.Context
import com.lmalakar.onecr.core.common.DispatchersProvider
import com.lmalakar.onecr.core.common.Logger
import com.lmalakar.onecr.core.common.TimeProvider
import com.lmalakar.onecr.core.model.CallDirection
import com.lmalakar.onecr.data.files.EventLogEntry
import com.lmalakar.onecr.data.files.EventLogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the Dashboard shows live. */
sealed interface LiveCallState {
    data object Idle : LiveCallState
    data class CellularRinging(val number: String?) : LiveCallState
    data class CellularActive(
        val direction: CallDirection,
        val number: String?,
        val contactName: String?,
    ) : LiveCallState

    data class VoipActive(val packageName: String, val appLabel: String?) : LiveCallState
}

/**
 * The M2 detection facade: cellular state machine + VoIP heuristic + event log.
 *
 * Exposes two StateFlows for the UI ([callState], [recentEvents]) and pushes every
 * event into the JSONL log via [EventLogStore]. :svc-recorder subscribes to the same
 * flows from M3 to trigger actual capture.
 *
 * Everything runs in-process; no privileges needed for detection itself — the shell
 * bridge is only required for audio capture (M3).
 */
class DetectionEngine(
    private val appContext: Context,
    private val dispatchers: DispatchersProvider,
    private val time: TimeProvider,
    private val logger: Logger,
    private val callerId: CallerIdResolver,
    private val eventLog: EventLogStore,
) {

    /** All detector callbacks, the debounce ticker and heuristic evaluation are
     *  confined to this single lane so the state machines never race. */
    private val confined = dispatchers.default.limitedParallelism(1)
    private val scope = CoroutineScope(Job() + confined)

    private val _callState = MutableStateFlow<LiveCallState>(LiveCallState.Idle)
    val callState: StateFlow<LiveCallState> = _callState.asStateFlow()

    private val _recentEvents = MutableStateFlow<List<EventLogEntry>>(emptyList())
    val recentEvents: StateFlow<List<EventLogEntry>> = _recentEvents.asStateFlow()

    /** Observable running flag — the Dashboard Start/Stop buttons and the
     *  notification center "detection is off" item key off this (v0.2.2). */
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** True when the VoIP half is at least partially functional (notification access). */
    private val _capabilities = MutableStateFlow(DetectionCapabilities())
    val capabilities: StateFlow<DetectionCapabilities> = _capabilities.asStateFlow()

    private val cellular = CallStateMachine(time)
    private val voip = VoipHeuristic(time)

    private var cellularDetector: CellularCallDetector? = null
    private var audioWatcher: VoipAudioWatcher? = null
    private var ticker: Job? = null
    private var notificationCollector: Job? = null

    @Volatile
    private var started = false

    data class DetectionCapabilities(
        val cellular: Boolean = false,
        val voipNotifications: Boolean = false,
        val voipAudio: Boolean = false,
    ) {
        val anyVoip: Boolean get() = voipNotifications || voipAudio
        val any: Boolean get() = cellular || anyVoip
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Starts detection. Returns the capability set (false flags = missing permission). */
    fun start(): DetectionCapabilities {
        if (started) return _capabilities.value
        started = true
        _running.value = true
        _recentEvents.value = eventLog.loadRecent(RECENT_LIMIT)

        // Startup failures are logged as error events so the notification center
        // surfaces them instead of the service failing silently (v0.2.2).
        val cellularOk = runCatching { startCellular() }
            .getOrElse { logError("Cellular detector failed to start: ${it.message}"); false }
        val voipAudioOk = runCatching { startVoipAudio() }
            .getOrElse { logError("VoIP audio watcher failed to start: ${it.message}"); false }
        val voipNotificationsOk = runCatching { startVoipNotifications() }
            .getOrElse { logError("VoIP notification listener failed to start: ${it.message}"); false }

        _capabilities.value = DetectionCapabilities(
            cellular = cellularOk,
            voipNotifications = voipNotificationsOk,
            voipAudio = voipAudioOk,
        )

        // Debounce ticker: lets the VoIP heuristic settle windows fire on time.
        ticker = scope.launch {
            while (isActive) {
                handleVoipDecision(voip.evaluate())
                delay(TICK_MS)
            }
        }

        val caps = _capabilities.value
        log(
            EventLogEntry.KIND_SERVICE,
            "Detection started — cellular ${if (caps.cellular) "on" else "off (no phone permission)"}; " +
                "VoIP ${if (caps.anyVoip) "on" else "off (no notification access)"}",
        )
        return caps
    }

    fun stop() {
        if (!started) return
        started = false
        _running.value = false
        ticker?.cancel(); ticker = null
        notificationCollector?.cancel(); notificationCollector = null
        cellularDetector?.stop(); cellularDetector = null
        audioWatcher?.stop(); audioWatcher = null
        cellular.reset()
        voip.reset()
        _callState.value = LiveCallState.Idle
        log(EventLogEntry.KIND_SERVICE, "Detection stopped")
    }

    val isRunning: Boolean get() = started

    /** Clears the persisted + in-memory event log (Settings → Event log → Clear). */
    fun clearLog() {
        eventLog.clear()
        _recentEvents.value = emptyList()
    }

    // ------------------------------------------------------------------
    // Cellular
    // ------------------------------------------------------------------

    private fun startCellular(): Boolean {
        val detector = CellularCallDetector(appContext, confined.asExecutor()) { state, number ->
            onCellularState(state, number)
        }
        val ok = detector.start()
        if (!ok) {
            logger.w(TAG, "cellular detector: READ_PHONE_STATE missing — cellular detection off")
        }
        cellularDetector = detector
        return ok
    }

    private fun onCellularState(state: CallState, number: String?) {
        if (state == CallState.RINGING) {
            _callState.value = LiveCallState.CellularRinging(number ?: cellular.lastNumber)
        }
        val events = cellular.onCallStateChanged(state, number)
        if (events.isEmpty()) return

        for (event in events) {
            when (event) {
                is CallEvent.IncomingCallStarted -> {
                    _callState.value = LiveCallState.CellularActive(
                        CallDirection.INCOMING,
                        event.callerNumber,
                        null,
                    )
                    log(EventLogEntry.KIND_CALL, "Incoming call answered ${mask(event.callerNumber)}")
                }

                is CallEvent.OutgoingCallStarted -> {
                    _callState.value = LiveCallState.CellularActive(
                        CallDirection.OUTGOING,
                        event.dialedNumber,
                        null,
                    )
                    log(EventLogEntry.KIND_CALL, "Outgoing call started ${mask(event.dialedNumber)}")
                }

                is CallEvent.CallMissed -> {
                    _callState.value = LiveCallState.Idle
                    log(EventLogEntry.KIND_CALL, "Missed call ${mask(event.callerNumber)}")
                }

                is CallEvent.CallEnded -> scope.launch {
                    val enriched = enrichWithCallerId(event)
                    _callState.value = LiveCallState.Idle
                    log(
                        EventLogEntry.KIND_CALL,
                        "Call ended — ${enriched.direction}, ${formatDuration(enriched.durationMillis)}" +
                            ", ${mask(enriched.phone)}${enriched.contactName?.let { " ($it)" } ?: ""}",
                    )
                }

                is CallEvent.VoipCallDetected, is CallEvent.VoipCallEnded -> Unit
            }
        }
    }

    /** Post-call CallLog lookup: fills number/name that listeners no longer provide. */
    private suspend fun enrichWithCallerId(event: CallEvent.CallEnded): CallEvent.CallEnded {
        // CallLog row is written a moment after IDLE — brief, bounded wait.
        repeat(CALLLOG_WAIT_ROUNDS) {
            val id = withContext(dispatchers.io) { callerId.latestCall() }
            if (id != null) {
                return event.copy(
                    phone = event.phone ?: id.number,
                    contactName = id.contactName,
                    direction = if (event.direction == CallDirection.UNKNOWN) id.direction else event.direction,
                )
            }
            delay(CALLLOG_WAIT_STEP_MS)
        }
        return event
    }

    // ------------------------------------------------------------------
    // VoIP
    // ------------------------------------------------------------------

    private fun startVoipAudio(): Boolean {
        val watcher = VoipAudioWatcher(appContext) { active ->
            voip.onVoicePlaybackActive(active)
        }
        val ok = watcher.start()
        audioWatcher = watcher
        return ok
    }

    private fun startVoipNotifications(): Boolean {
        if (!notificationAccessGranted()) {
            logger.w(TAG, "notification access missing — VoIP detection degraded")
            return false
        }
        notificationCollector = scope.launch {
            VoipNotificationBus.activeCallPackages.collect { packages ->
                voip.onNotificationCall(packages.firstOrNull())
                handleVoipDecision(voip.evaluate())
            }
        }
        return true
    }

    private fun handleVoipDecision(decision: VoipDecision?) {
        when (decision) {
            is VoipDecision.Started -> {
                val label = appLabel(decision.packageName)
                _callState.value = LiveCallState.VoipActive(decision.packageName, label)
                log(EventLogEntry.KIND_VOIP, "VoIP call started — ${label ?: decision.packageName}")
            }

            is VoipDecision.Ended -> {
                if (_callState.value is LiveCallState.VoipActive) {
                    _callState.value = LiveCallState.Idle
                }
                log(
                    EventLogEntry.KIND_VOIP,
                    "VoIP call ended — ${appLabel(decision.packageName) ?: decision.packageName}, " +
                        "${formatDuration(decision.durationMillis)}",
                )
            }

            null -> Unit
        }
    }

    private fun appLabel(packageName: String): String? = runCatching {
        appContext.packageManager.getApplicationLabel(
            appContext.packageManager.getApplicationInfo(packageName, 0),
        ).toString()
    }.getOrNull()

    private fun notificationAccessGranted(): Boolean = runCatching {
        val flat = android.provider.Settings.Secure.getString(
            appContext.contentResolver,
            "enabled_notification_listeners",
        )
        flat != null && flat.split(':').any { it.startsWith(appContext.packageName) }
    }.getOrDefault(false)

    // ------------------------------------------------------------------
    // Log
    // ------------------------------------------------------------------

    private fun log(kind: String, message: String) {
        val entry = EventLogEntry(time.nowMillis(), kind, message)
        logger.d(TAG, "$kind: $message")
        eventLog.append(entry)
        _recentEvents.value = (_recentEvents.value + entry).takeLast(RECENT_LIMIT)
    }

    /** Logs an error event visible in the notification center and the event log. */
    fun logError(message: String) {
        log(EventLogEntry.KIND_ERROR, message)
    }

    // ------------------------------------------------------------------

    private fun mask(number: String?): String = when {
        number.isNullOrBlank() -> "(unknown number)"
        else -> {
            val visible = number.takeLast(4)
            "…$visible"
        }
    }

    private fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return "%d:%02d".format(m, s)
    }

    companion object {
        private const val TAG = "DetectionEngine"
        private const val TICK_MS = 500L
        private const val RECENT_LIMIT = 100
        private const val CALLLOG_WAIT_ROUNDS = 5
        private const val CALLLOG_WAIT_STEP_MS = 300L
    }
}
