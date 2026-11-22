# oneCR Architecture

Target: Android 11+ (API 30+), arm64-v8a. Kotlin + Jetpack Compose; C/C++ (NDK) for whisper.cpp / llama.cpp. Local-first: no telemetry, network only for user-initiated model downloads.

## 1. Platform ground truth

The call-audio capture trick this design relies on (same family as CallVault / ShizuCallRecorder / BCR):

- `AudioRecord` sources `VOICE_CALL`, `VOICE_DOWNLINK`, `VOICE_UPLINK` are guarded by `android.permission.CAPTURE_AUDIO_OUTPUT`, which is `signature|privileged` — a normal app can never hold it.
- Processes running as the **adb shell user (UID 2000)** *do* hold it. So oneCR runs a thin capture process as shell, obtained either through the **Shizuku** app or through an **embedded ADB client** that pairs with the phone's own Wireless Debugging (Android 11+ pairing protocol) — no PC, no extra app.
- VoIP calls (WhatsApp etc.) are captured in-app: `MediaProjection` + `AudioPlaybackCapture` (far end) plus the microphone (near end).

## 2. Module map

```
oneCR/
├── app/                 :app                 Application, DI container, navigation, dashboard
├── core/
│   ├── common/          :core-common         dispatchers, time, logging (pure JVM)
│   ├── model/           :core-model          domain enums/data classes (pure JVM)
│   └── designsystem/    :core-designsystem   theme + shared composables (Compose)
├── feature/
│   ├── onboarding/      :feature-onboarding  privilege setup wizard, permission center
│   ├── library/         :feature-library     list, player, transcript/summary viewer
│   └── settings/        :feature-settings    models, storage, export, privacy toggles
├── privilege/
│   ├── api/             :priv-api            PrivilegeProvider interface + IShellCaptureService AIDL
│   ├── shizuku/         :priv-shizuku        Shizuku bind/permission/user-service impl
│   └── embedded-adb/    :priv-adb            on-device wireless-debugging pairing + local adb client
├── service/
│   ├── detection/       :svc-detection       telephony listener, VoIP heuristics, notification listener
│   └── recorder/        :svc-recorder        FGS, capture orchestration, mixing, encoder
├── data/
│   ├── db/              :data-db             Room entities/DAOs/FTS
│   └── files/           :data-files          recording storage, model store, SAF export
├── ai/
│   ├── whisper/         :ai-whisper          JNI + CMake wrapper over whisper.cpp
│   ├── llm/             :ai-llm              JNI + CMake wrapper over llama.cpp
│   └── pipeline/        :ai-pipeline         post-call state machine
└── third_party/         pinned git submodules (whisper.cpp, llama.cpp — added at M5)
```

Boundary rules: UI only in `:app`/`:feature:*`; privileged code only in `:privilege:*` (minimal); audio/encoding only in `:svc-recorder`; no network code anywhere except the Model Manager in `:data-files`.

**UI shell & navigation model (v0.2.3).** `OneCRApp` is a single-activity,
top-level-tab shell (Dashboard / Library / Settings) with no navigation
library. System back is coordinated by one shell `BackHandler` plus per-screen
handlers (later registrations win): the notification center, settings
sub-pages and onboarding steps intercept first; the shell then pops a
`rememberSaveable` visited-tab history; at the root a second press within
~2.2 s confirms the exit. All colors come from the complete Material 3 scheme
in `:core-designsystem` (`theme/Color.kt` is the only place hex values live).

## 3. Privilege layer

`PrivilegeProvider` (in `:priv-api`) has two implementations behind one AIDL surface:

| Mode | How | Notes |
|---|---|---|
| Embedded ADB (default) | implements the Android 11+ wireless-debugging pairing protocol on-device (TLS, port + 6-digit code), then `adb connect` to local adbd, spawn capture dex via `app_process` | self-contained; needs Wi-Fi once after reboot; Developer options must stay on; Android 11 keeps-screen-unlocked limitation |
| Shizuku | user installs Shizuku, pairs, oneCR requests permission and binds a user service | battle-tested, simplest fallback |

```java
interface IShellService {
    String exec(String command);   // v0.2.0 — proves the shell bridge (runs `id`)
    int getUid();                  // expect 2000 (shell)
    // M3 adds: probe() / start(audioSource, pipeFd, sampleRate) / stop()
}
```

The v0.2.0 shell-side class is deliberately tiny: it runs `sh -c` for the verify
step and reports its UID — nothing else. The audio-capture methods (open an
`AudioRecord`, stream raw PCM into a pipe FD) join it in M3. Encoding/storage/AI/UI
run in the normal app process. `PrivilegeHealthMonitor` publishes
`StateFlow<PrivilegeHealth>` (`state + detail + action`) with ping (30 s) + binder
death + reboot detection (`Settings.Global.BOOT_COUNT`) and one-tap repair actions.

Cellular call detection (**shipped in v0.2.0**): `CallStateMachine` (pure, unit-tested) fed by `TelephonyCallback` (31+) / `PhoneStateListener` (26–30) → RINGING/OFFHOOK/IDLE events; incoming/outgoing/missed labeling; numbers resolved post-call from the newest CallLog row (bounded window, newest entry only).
VoIP detection (**shipped in v0.2.0**, local only): (1) `NotificationListenerService` ongoing `CATEGORY_CALL` notifications from a package allowlist, (2) `AudioManager.registerAudioPlaybackCallback` with `USAGE_VOICE_COMMUNICATION` active. Trigger = 1 AND 2, debounced 1.5 s both ways (`VoipHeuristic`, pure, unit-tested); manual record button as universal fallback (M3). Detection events land in the in-app Event Log (`files/logs/events.jsonl`).

## 4. Capture pipeline

```
CallStart event
   ├─ CELLULAR: shell capture (VOICE_CALL → fallback VOICE_DOWNLINK+UPLINK dual → MIC)
   └─ VOIP: MediaProjection + AudioPlaybackCapture (far end) + MIC/VOICE_COMMUNICATION (near end)
        ↓
PCM → preallocated direct-buffer pool (32 × 8 KB) → bounded queue (~10 s) → encoder thread
        ↓
recordings/<uuid>/raw/{downlink,uplink}.wav     per-stream (enables ME/THEM speaker labels later)
recordings/<uuid>/final/audio.m4a               mixed AAC (WAV until M4)
recordings/<uuid>/journal.json                  crash-recovery journal
```

Rules: capture on its own thread, blocking `read()`, zero per-frame allocations; UI only sees `StateFlow<RecorderState>`; queue saturation is an error state, never silent loss; AAC via `MediaCodec` streaming from M4; orphan sweep at boot finalizes partial recordings.

## 5. Storage (Room)

```
recordings(id UUID PK, startedAt, endedAt, durationMs, direction, phone?, contactName?,
           audioPath, format, sizeBytes, sampleRate, captureSource, status, createdAt, updatedAt)
transcripts(id PK, recordingId FK, modelId, language, createdAt)
transcript_segments(id PK, transcriptId FK, idx, startMs, endMs, text, speaker ME|THEM|UNKNOWN)
summaries(id PK, recordingId FK, modelId, style, text, createdAt)
tags(id PK, name UNIQUE) + recording_tags(recordingId, tagId)
transcript_fts (@Fts4 recordingId + text)   ← local full-text search
```

Models under `filesDir/models/`. Defaults: whisper `ggml-base-q5_1` multilingual (~57 MB); llama.cpp `Qwen2.5-1.5B-Instruct Q4_K_M` (~1.0 GB). Downloads: resume + SHA-256 against pinned manifest; sideload via SAF always available. Pre-flight gates: free storage ≥ model × 1.5, RAM guard (warn < 6 GB, block on `isLowMemoryDevice`), thermal (defer if `MODERATE`+), battery threshold setting. Never hold whisper and llama contexts at the same time.

Export: share sheet per recording (audio + TXT/SRT/VTT/MD/JSON), bulk zip to any SAF folder.

## 6. Milestones

See docs/ROADMAP.md for the live status. Order: M1 skeleton → M2 privilege+detection → M3 capture → M4 library/storage → M5 AI.

## 7. Key risks and mitigations

| Risk | Mitigation |
|---|---|
| OEM `VOICE_CALL` routing quirks | probe() capability chain, per-device DB, docs/COMPATIBILITY.md |
| VoLTE/VoWiFi calls not routed into capturable streams | detect + warn; record what works; mark partial |
| Android 11 screen-unlock limitation | document + pre-call hint |
| Privileges lost on reboot | health monitor, BOOT_COUNT detection, guided re-pair |
| VoIP apps opting out of playback capture | zero-signal detector, mic+speaker fallback, user notice |
| Android 14+ FGS mic/projection launch restrictions | persistent detection FGS started from foreground; shell path unaffected (different uid) |
| OEM battery killers | battery-optimization exemption, auto-launch guidance, OEM-specific onboarding steps |
| LLM RAM pressure on 4-6 GB devices | 0.5B model default under 6 GB, hard gate on low-memory devices |
