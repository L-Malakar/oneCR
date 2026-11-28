package com.lmalakar.onecr.service.recorder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.lmalakar.onecr.core.common.DispatchersProvider
import com.lmalakar.onecr.core.common.Logger
import com.lmalakar.onecr.core.common.TimeProvider
import com.lmalakar.onecr.core.model.CallDirection
import com.lmalakar.onecr.core.model.CaptureSource
import com.lmalakar.onecr.data.files.EventLogEntry
import com.lmalakar.onecr.data.files.EventLogStore
import com.lmalakar.onecr.data.files.RecordingJournal
import com.lmalakar.onecr.data.files.RecordingSession
import com.lmalakar.onecr.data.files.StorageLayout
import com.lmalakar.onecr.data.files.WavWriter
import com.lmalakar.onecr.privilege.api.CaptureCapability
import com.lmalakar.onecr.privilege.api.CaptureStreamSpec
import com.lmalakar.onecr.privilege.api.PrivilegeProvider
import com.lmalakar.onecr.service.recorder.voip.VoipPcmSource
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The M3 capture orchestrator (v0.3.6): one active shell capture session, PCM
 * piped from the privileged process into a WAV file, with the crash-recovery
 * journal underneath.
 *
 * Step scope (this release):
 *  - cellular: probe-confirmed VOICE_CALL → MICROPHONE fallback chain;
 *  - manual "Record now" from the Dashboard as the universal fallback;
 *  - auto capture on detected cellular calls when the setting is on.
 * VoIP in-app capture (MediaProjection mix) and dual downlink/uplink per-speaker
 * files join in the next M3 step.
 *
 * Threading: session lifecycle is confined to a single lane; the PCM pump
 * runs on its own daemon thread and hands finalization back to the lane.
 */
class RecordingEngine(
    private val appContext: Context,
    private val dispatchers: DispatchersProvider,
    private val time: TimeProvider,
    private val logger: Logger,
    private val eventLog: EventLogStore,
    private val journal: RecordingJournal,
    private val provider: () -> PrivilegeProvider,
    private val autoRecordEnabled: () -> Boolean,
    private val voipCaptureEnabled: () -> Boolean,
    private val scope: CoroutineScope,
) {

    /** What the Dashboard/notifications show. */
    sealed interface UiState {
        data object Idle : UiState

        data class Recording(
            val recordingId: String,
            val sourceLabel: String,
            val startedAtMillis: Long,
            val direction: CallDirection,
            val phone: String?,
            val trigger: String,
        ) : UiState

        data class Finalizing(val recordingId: String) : UiState
        data class Failed(val message: String) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Last probe result — powers the Settings diagnostics card and source picking. */
    private val _capability = MutableStateFlow<CaptureCapability?>(null)
    val capability: StateFlow<CaptureCapability?> = _capability.asStateFlow()

    private val confined = dispatchers.default.limitedParallelism(1)
    private val lane = CoroutineScope(Job() + confined)

    @Volatile
    private var session: Session? = null

    // ---- VoIP dual-path capture (v0.3.7) ----

    private val _voipArmed = MutableStateFlow(false)

    /** True while a MediaProjection session is held for VoIP capture. */
    val voipArmed: StateFlow<Boolean> = _voipArmed.asStateFlow()

    private class PendingProjection(val resultCode: Int, val data: Intent)

    @Volatile
    private var pendingProjection: PendingProjection? = null

    @Volatile
    private var projection: MediaProjection? = null

    private class Session(
        val id: String,
        val dir: File,
        val wav: WavWriter,
        val wavFile: File,
        val source: PcmSource,
        val trigger: String,
        val sourceLabel: String,
        val fileName: String,
    ) {
        val bytes = AtomicLong(0)
        val finalizing = AtomicBoolean(false)
    }

    // ------------------------------------------------------------------
    // Public controls (Dashboard button, notification action, auto capture)
    // ------------------------------------------------------------------

    /**
     * Starts one recording session. Fails softly with a message on any error.
     *
     * Two capture paths (v0.3.7):
     *  - shell (default): the privileged capture process opens the telephony
     *    sources — the probe-confirmed VOICE_CALL → MICROPHONE chain;
     *  - VoIP mix ([voip] = true): the app's own MediaProjection playback
     *    capture + microphone, mixed in-process (needs an armed projection).
     */
    suspend fun start(
        trigger: String,
        direction: CallDirection = CallDirection.UNKNOWN,
        phone: String? = null,
        voip: Boolean = false,
    ): Result<Unit> {
        if (session != null) {
            return Result.failure(IllegalStateException("a recording is already active"))
        }

        val id = UUID.randomUUID().toString()
        val dir = File(File(appContext.filesDir, StorageLayout.RECORDINGS_ROOT), id)
        val startedAt = time.nowMillis()

        try {
            val source: PcmSource
            val sourceLabel: String
            val fileName: String
            if (voip) {
                val projection = this.projection
                    ?: throw IllegalStateException("VoIP capture is not armed")
                val requestedRate = _capability.value?.sampleRateHz?.takeIf { it > 0 } ?: 16_000
                source = VoipPcmSource(
                    context = appContext,
                    projection = projection,
                    rawDir = File(dir, StorageLayout.RAW_SUBDIR),
                    sampleRateHz = requestedRate,
                    micEnabled = hasRecordAudioPermission(),
                    onNote = { note -> log(EventLogEntry.KIND_SERVICE, "VoIP capture — $note") },
                )
                sourceLabel = "playback+mic"
                fileName = StorageLayout.CALL_STREAM
            } else {
                val capability = _capability.value
                val captureSource = capability?.workingSources
                    ?.firstOrNull { it == CaptureSource.VOICE_CALL || it == CaptureSource.MICROPHONE }
                    ?: CaptureSource.VOICE_CALL
                val requestedRate = capability?.sampleRateHz?.takeIf { it > 0 } ?: 16_000

                val spec = CaptureStreamSpec(source = captureSource, sampleRateHz = requestedRate)
                source = ShellPcmSource(provider().openCaptureStream(spec))
                sourceLabel = captureSource.name
                fileName = streamFile(captureSource)
            }

            val granted = source.grantedSampleRateHz.takeIf { it > 0 } ?: 16_000
            val wavFile = File(File(dir, StorageLayout.RAW_SUBDIR), fileName)
            val wav = WavWriter.create(wavFile, granted, channels = 1)

            val active = Session(id, dir, wav, wavFile, source, trigger, sourceLabel, fileName)
            session = active

            // The journal is written only once the stream is actually up: a
            // failed start leaves nothing behind, a crash mid-call leaves a
            // `started` journal the sweep can recover.
            journal.start(
                RecordingSession(
                    id = id,
                    direction = direction.name.lowercase(),
                    phone = phone,
                    source = sourceLabel,
                    sampleRate = granted,
                    channels = 1,
                    startedAt = startedAt,
                ),
            )

            _state.value = UiState.Recording(
                recordingId = id,
                sourceLabel = sourceLabel,
                startedAtMillis = startedAt,
                direction = direction,
                phone = phone,
                trigger = trigger,
            )
            startRecordingService(voip = voip)
            log(
                EventLogEntry.KIND_SERVICE,
                "Recording started — $sourceLabel @ ${granted}Hz ($trigger)",
            )

            active.pumpLoop(
                onDone = { finalize(active, reason = "stream ended") },
            )
            return Result.success(Unit)
        } catch (t: Throwable) {
            return failSession(id, dir, t)
        }
    }

    /** Best-effort stop: sends the control word; the pump loop finalizes. */
    fun stop() {
        val active = session ?: return
        runCatching { active.source.stop() }
    }

    /** Runs the audio probe now (Settings diagnostics + source selection). */
    suspend fun probeNow(): CaptureCapability {
        val capability = provider().probeCapture()
        _capability.value = capability
        log(
            EventLogEntry.KIND_SERVICE,
            "Capture probe — ${capability.notes}",
        )
        return capability
    }

    // ------------------------------------------------------------------
    // VoIP arming (v0.3.7) — MediaProjection consent → armed session
    // ------------------------------------------------------------------

    /**
     * Stage 1 of arming: park the consent result and bring the recording
     * service up with the mediaProjection foreground type — Android 14+
     * requires that BEFORE [MediaProjectionManager.getMediaProjection] runs.
     * The service calls [onServiceForegroundReady] once the foreground
     * contract is satisfied.
     */
    fun armVoip(resultCode: Int, data: Intent) {
        if (Build.VERSION.SDK_INT < 29) {
            log(EventLogEntry.KIND_ERROR, "VoIP capture needs Android 10+")
            return
        }
        pendingProjection = PendingProjection(resultCode, data)
        runCatching {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, RecordingService::class.java)
                    .setAction(RecorderContract.ACTION_ARM_VOIP),
            )
        }.onFailure {
            pendingProjection = null
            logger.w(TAG, "could not start the recorder service for arming: ${it.message}")
        }
    }

    /** Stage 2 (called by the service after startForeground): claim the projection. */
    fun onServiceForegroundReady() {
        val pending = pendingProjection ?: return
        pendingProjection = null
        lane.launch {
            runCatching {
                val manager = appContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager
                val p = manager.getMediaProjection(pending.resultCode, pending.data)
                // The system can revoke the projection at any moment (cast
                // tile, another app claiming it) — disarm when it stops.
                p.registerCallback(
                    object : MediaProjection.Callback() {
                        override fun onStop() {
                            disarmVoip()
                        }
                    },
                    Handler(Looper.getMainLooper()),
                )
                projection = p
                _voipArmed.value = true
                log(EventLogEntry.KIND_SERVICE, "VoIP capture armed — internet calls will be recorded")
            }.onFailure {
                _voipArmed.value = false
                log(EventLogEntry.KIND_ERROR, "VoIP arm failed — ${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    /**
     * Release the projection (user toggle or system revocation). An active
     * VoIP take finalizes on its own: the capture streams end, the pump sees
     * EOF and the journal is closed out.
     */
    fun disarmVoip() {
        pendingProjection = null
        val p = projection
        projection = null
        val wasArmed = _voipArmed.value
        _voipArmed.value = false
        runCatching { p?.stop() }
        if (wasArmed || p != null) {
            log(EventLogEntry.KIND_SERVICE, "VoIP capture disarmed")
        }
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Auto-capture hook the app wires to the detection engine's live call state
     * (the recorder module stays decoupled from :service:detection).
     *
     * v0.3.7: VoIP calls auto-record through the in-app MediaProjection + mic
     * mix — but only while the user has armed the projection in Settings
     * (Android requires fresh consent per session, so the arm toggle IS the
     * opt-in) and the VoIP capture switch is on. Cellular calls keep the
     * shell path with the plain auto-record switch. Manual recordings survive
     * the call ending; auto ones stop with it.
     */
    fun onCallStateChanged(callActive: Boolean, direction: CallDirection, phone: String?) {
        val active = session
        if (callActive) {
            if (active != null) return
            if (direction == CallDirection.VOIP) {
                if (_voipArmed.value && voipCaptureEnabled()) {
                    lane.launch {
                        start(
                            RecorderContract.TRIGGER_AUTO,
                            direction,
                            phone,
                            voip = true,
                        ).onFailure { logger.w(TAG, "auto VoIP capture failed: ${it.message}") }
                    }
                }
                return
            }
            if (autoRecordEnabled()) {
                lane.launch {
                    start(RecorderContract.TRIGGER_AUTO, direction, phone)
                        .onFailure { logger.w(TAG, "auto capture failed: ${it.message}") }
                }
            }
        } else if (active != null && active.trigger == RecorderContract.TRIGGER_AUTO) {
            // Manual recordings survive the call ending; auto ones stop with it.
            stop()
        }
    }

    // ------------------------------------------------------------------
    // Session plumbing
    // ------------------------------------------------------------------

    private fun Session.pumpLoop(onDone: () -> Unit) {
        val self = this
        Thread({
            val buf = ByteArray(PUMP_BUFFER_BYTES)
            try {
                val input = source.pcm
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    // WavWriter requires whole frames (2 bytes, mono s16); the
                    // capture process always emits sample-aligned chunks, but
                    // guard against a torn final read anyway.
                    val usable = n - (n % 2)
                    if (usable > 0) {
                        wav.writePcm(buf, usable)
                        bytes.addAndGet(usable.toLong())
                    }
                }
            } catch (_: Throwable) {
                // stream torn down (stop/crash/device gone) — finalize below
            } finally {
                lane.launch {
                    onDone()
                }
                Unit
            }
        }, "onecr-pcm-pump").apply {
            isDaemon = true
            start()
        }
        Unit
    }

    /** Confined finalization: patch WAV, close stream, update journal + UI. */
    private fun finalize(active: Session, reason: String) {
        if (!active.finalizing.compareAndSet(false, true)) return
        session = null

        _state.value = UiState.Finalizing(active.id)
        runCatching { active.wav.finalize() }
        runCatching { active.wav.close() }
        runCatching { active.source.close() }

        val bytes = active.bytes.get()
        // Mono s16: one frame = 2 bytes; the journal holds the granted rate.
        val rate = journal.load(active.id)?.sampleRate ?: 0
        val finalDurationMs = if (rate > 0) bytes * 1000 / (rate * 2) else 0

        // The raw stream is the final artifact in this M3 step (the mixer that
        // would produce final/audio.wav arrives with per-speaker capture).
        val relativePath = StorageLayout.recordingRawDir(active.id) + "/" + active.fileName
        val finalized = runCatching {
            journal.finalize(
                id = active.id,
                endedAt = time.nowMillis(),
                finalPath = relativePath,
                finalBytes = active.wavFile.length(),
            )
        }.getOrNull()

        _state.value = UiState.Idle
        log(
            EventLogEntry.KIND_SERVICE,
            if (finalized != null) {
                "Recording finished — ${formatDuration(finalDurationMs)}, ${formatBytes(bytes)}, " +
                    "${active.sourceLabel} ($reason)"
            } else {
                "Recording ended without a verifiable file — the sweep will mark it ($reason)"
            },
        )
    }

    private fun failSession(id: String, dir: File, t: Throwable): Result<Unit> {
        val message = t.message ?: t.javaClass.simpleName
        _state.value = UiState.Failed(message)
        log(EventLogEntry.KIND_ERROR, "Recording failed — $message")
        // A failed start never wrote a journal (it is written after the stream
        // opens) — remove the empty session dir so the sweep sees no debris.
        if (journal.load(id) == null) {
            runCatching { dir.deleteRecursively() }
        }
        return Result.failure(t)
    }

    private fun startRecordingService(voip: Boolean = false) {
        val action = if (voip) {
            RecorderContract.ACTION_ARM_VOIP
        } else {
            RecorderContract.ACTION_START_RECORDING
        }
        runCatching {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, RecordingService::class.java)
                    .setAction(action),
            )
        }.onFailure {
            logger.w(TAG, "could not start the recording service: ${it.message}")
        }
    }

    private fun streamFile(source: CaptureSource): String = when (source) {
        CaptureSource.MICROPHONE -> StorageLayout.MIC_STREAM
        else -> StorageLayout.CALL_STREAM
    }

    private fun log(kind: String, message: String) {
        logger.d(TAG, message)
        eventLog.append(EventLogEntry(time.nowMillis(), kind, message))
    }

    private fun formatDuration(ms: Long): String {
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024 -> "%.0f KB".format(bytes / 1_024.0)
        else -> "$bytes B"
    }

    companion object {
        private const val TAG = "RecordingEngine"
        private const val PUMP_BUFFER_BYTES = 16 * 1024
    }
}
