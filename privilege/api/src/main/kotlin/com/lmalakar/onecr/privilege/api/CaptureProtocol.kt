package com.lmalakar.onecr.privilege.api

import com.lmalakar.onecr.core.model.CaptureSource

/**
 * Wire protocol between the app and the shell capture process (M3).
 *
 * The capture process is the tiny [MAIN_CLASS] entry point that ships inside
 * oneCR's own APK. It is spawned as the adb-shell user (UID 2000), which holds
 * CAPTURE_AUDIO_OUTPUT — the reason normal apps cannot open the telephony
 * capture sources (see ARCHITECTURE.md §1).
 *
 *  - Embedded ADB mode: spawned through a dedicated streaming `shell,v2:`
 *    channel (`CLASSPATH=<apk> app_process / <MAIN_CLASS> …`).
 *  - Shizuku mode: spawned by [com.lmalakar.onecr.privilege.shizuku.ShellService]
 *    (also UID 2000) with the same command, piped over binder FDs.
 *
 * Conventions:
 *  - `probe` prints a line-based report to stdout (machine-readable, no JSON
 *    parser needed on the app side) and human details to stderr;
 *  - `start` writes RAW PCM (16-bit little-endian, mono) to stdout ONLY —
 *    any diagnostic goes to stderr — reads stdin and stops cleanly when it
 *    receives a line that starts with `stop`, or when [STOP_LINE]'s writer
 *    closes the stream.
 */
object CaptureProtocol {

    /** Entry point inside oneCR's APK (kept via ProGuard — see proguard-rules.pro). */
    const val MAIN_CLASS = "com.lmalakar.onecr.service.recorder.capture.CaptureMain"

    /** The single control word the app writes to the capture process's stdin. */
    const val STOP_LINE = "stop"

    private const val APP_PROCESS = "app_process"

    /**
     * Shell command that runs the probe. Short-lived: opens every (source, rate)
     * combination for [probeMs] each and prints the report.
     */
    fun probeCommand(
        apkPath: String,
        rates: List<Int> = DEFAULT_PROBE_RATES,
        probeMs: Int = 400,
    ): String = "CLASSPATH='${apkPath.escapeShell()}' $APP_PROCESS / $MAIN_CLASS probe" +
        " --rates ${rates.joinToString(",")} --ms $probeMs"

    /** Shell command that starts a capture session streaming raw PCM on stdout. */
    fun startCommand(
        apkPath: String,
        source: CaptureSource,
        sampleRateHz: Int,
        maxDurationMs: Long,
    ): String {
        val sourceArg = when (source) {
            CaptureSource.VOICE_CALL -> "VOICE_CALL"
            CaptureSource.VOICE_DOWNLINK_UPLINK -> "VOICE_DOWNLINK,VOICE_UPLINK"
            CaptureSource.MICROPHONE -> "MIC"
            CaptureSource.VOIP_PLAYBACK_MIX -> "MIC"
        }
        return "CLASSPATH='${apkPath.escapeShell()}' $APP_PROCESS / $MAIN_CLASS start" +
            " --source $sourceArg --rate $sampleRateHz --max-ms $maxDurationMs"
    }

    // ------------------------------------------------------------------
    // Probe report parsing
    // ------------------------------------------------------------------

    /** Outcome of one (source, rate) probe attempt. */
    enum class ProbeOutcome { OK, SILENT, FAIL }

    data class SourceProbe(
        val source: String,
        val rateHz: Int,
        val outcome: ProbeOutcome,
        val framesRead: Long,
        val error: String?,
    )

    data class ProbeResult(
        val uid: Int?,
        val sources: List<SourceProbe>,
        val raw: String,
    )

    /** Parses the probe report; unknown/garbled lines are skipped, never fatal. */
    fun parseProbeOutput(raw: String): ProbeResult {
        var uid: Int? = null
        val sources = mutableListOf<SourceProbe>()
        for (line in raw.lineSequence()) {
            val parts = line.split('|')
            when {
                parts.size == 2 && parts[0] == "UID" ->
                    uid = parts[1].trim().toIntOrNull()

                parts.size >= 5 && parts[0] == "SRC" -> {
                    val outcome = when (parts[3].trim().uppercase()) {
                        "OK" -> ProbeOutcome.OK
                        "SILENT" -> ProbeOutcome.SILENT
                        else -> ProbeOutcome.FAIL
                    }
                    sources.add(
                        SourceProbe(
                            source = parts[1].trim(),
                            rateHz = parts[2].trim().toIntOrNull() ?: 0,
                            outcome = outcome,
                            framesRead = parts[4].trim().toLongOrNull() ?: 0L,
                            error = parts.getOrNull(5)?.trim()?.takeIf { it.isNotEmpty() },
                        ),
                    )
                }
            }
        }
        return ProbeResult(uid = uid, sources = sources, raw = raw)
    }

    /**
     * Turns a probe report into the fallback chain for [CaptureCapability]:
     * VOICE_CALL (single mixed stream) → downlink+uplink (dual, composite) →
     * mic. Sources that open but return silence are reported in the notes —
     * several OEMs accept the open() and route nothing into it.
     */
    fun workingSources(result: ProbeResult): List<CaptureSource> {
        val byName = result.sources.groupBy { it.source }
        val chain = mutableListOf<CaptureSource>()

        fun bestOk(name: String): Boolean =
            byName[name]?.any { it.outcome == ProbeOutcome.OK } == true
        fun bestSilent(name: String): Boolean =
            byName[name]?.any { it.outcome == ProbeOutcome.SILENT } == true

        if (bestOk("VOICE_CALL")) chain.add(CaptureSource.VOICE_CALL)
        if (bestOk("VOICE_DOWNLINK") && bestOk("VOICE_UPLINK")) {
            chain.add(CaptureSource.VOICE_DOWNLINK_UPLINK)
        }
        if (bestOk("MIC")) chain.add(CaptureSource.MICROPHONE)

        // Devices where the privileged sources open but stay silent still get
        // the mic fallback — and the silent quirk goes to the diagnostics notes.
        if (chain.isEmpty() && (bestSilent("VOICE_CALL") || bestSilent("MIC"))) {
            chain.add(CaptureSource.MICROPHONE)
        }
        return chain
    }

    /** Preferred rate from the probe (first working entry), or 16 kHz default. */
    fun workingSampleRate(result: ProbeResult): Int =
        result.sources.firstOrNull { it.outcome == ProbeOutcome.OK }?.rateHz?.takeIf { it > 0 }
            ?: 16_000

    val DEFAULT_PROBE_RATES: List<Int> = listOf(16_000, 8_000, 44_100)

    private fun String.escapeShell(): String = replace("'", "'\\''")
}
