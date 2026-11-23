# oneCR Roadmap

Status legend: [ ] planned, [~] in progress, [x] done.

## M1 — Repo and app skeleton (v0.1.0–0.1.1)
- [x] Gradle multi-module skeleton (all modules created, boundaries fixed)
- [x] Version catalog (`gradle/libs.versions.toml`) + `gradle.properties` version plumbing
- [x] `build.bat` (env checks, first-run keystore, APK publish to `oneCR/Android/oneCR-v<version>.apk`)
- [x] Gradle wrapper (8.10.2) + CI (`.github/workflows/android.yml`) + dependabot
- [x] Repo hygiene: LICENSE (MIT), SECURITY.md, PRIVACY.md, CONTRIBUTING.md, README.md, templates
- [x] docs/: ARCHITECTURE, ROADMAP, COMPATIBILITY, BRANDING
- [x] Compose app shell: Dashboard / Library / Settings navigation, theme, adaptive icon
- [x] Official logo vectorized and shipped as adaptive icon + in-app branding (v0.1.1)
- [x] R8 code/resource shrinking enabled for release builds — APK ~1.5 MB (v0.1.1)
- [x] Toolchain-verified compile: Gradle 8.10.2, AGP 8.7.3, Kotlin 2.0.21, JDK 17 — `assembleDebug`, `assembleRelease`, `test` all green; release-signing flow verified end-to-end (apksigner + zipalign checks green)
- [ ] Verified on a real device from a clean clone via `build.bat` (owner)

## M2 — Privilege bridge and call detection (v0.2.0)
- [x] `:priv-shizuku`: bind, permission request, user service (`IShellService` AIDL), binder-death handling (v0.2.0)
- [x] `PrivilegeHealthMonitor` + status UI + repair actions (install/start/grant/verify, reboot banner) (v0.2.0)
- [x] Onboarding wizard with per-OEM guidance (OPPO/OnePlus/Realme, MIUI, One UI, vivo) (v0.2.0)
- [x] Cellular call detection state machine + caller ID (READ_PHONE_STATE / CallLog enrichment) (v0.2.0)
- [x] VoIP detection: notification listener + playback-configuration heuristics (debounced) (v0.2.0)
- [x] Permission center screen + debug Event Log screen (v0.2.0)
- [x] Detection foreground service (specialUse) + live status notification (v0.2.0)
- [x] Unit tests for the pure state machines + event store (22 green) (v0.2.0)
- [x] `:priv-adb`: wireless-debugging pairing protocol + local adb client — **shipped in M2.5 (v0.3.0)**
- [ ] Exit criteria: reliable labeled call events on the test device; graceful degradation when privileges drop (needs owner device testing)

## M2.5 — Embedded ADB bridge (v0.3.0)
- [x] Wireless-debugging pairing protocol (TLS 1.3 + SPAKE2 + 6-digit code) on-device (v0.3.0)
- [x] Local adb client: TLS connect to own adbd + CNXN + shell service (`shell,v2:` with exit codes) (v0.3.0)
- [x] adb key persistence, NSD discovery of pairing/connect services, Developer-options guard, Wi-Fi-after-reboot UX (v0.3.0)
- [x] Privilege mode picker in Settings + onboarding (Shizuku ↔ embedded ADB) (v0.3.0)
- [x] Protocol test coverage: SPAKE2 vs independent reference, HKDF RFC vectors, TLS pairing + shell e2e mocks (v0.3.0)
- [ ] Exit criteria: pairing + shell verified on the owner's device across OEMs (field journal in docs/COMPATIBILITY.md)
- [ ] `app_process` capture-dex spawn hooks (delegates to the M3 shell capture dex once it exists)

## M3 — Audio capture engine
- [ ] Shell capture dex (`probe`/`start`/`stop`, PCM over pipe FD)
- [ ] Capability probe + source fallback chain (VOICE_CALL → downlink+uplink dual → mic)
- [ ] VoIP dual-path capture (MediaProjection playback capture + mic) + mixer
- [ ] WAV writer, recording journal, orphan sweep
- [ ] Foreground service (microphone + mediaProjection types) with notification controls
- [ ] Capture diagnostics screen (working sources, sample rates, live levels)
- Exit criteria: clean two-sided recordings for cellular and at least one VoIP app; 30-min soak test with flat memory and no ANRs.

## M4 — Library, storage, search, export
- [ ] Room schema (recordings, transcripts, summaries, tags, FTS) + repositories
- [ ] AAC encoding via MediaCodec streaming (WAV retained as option)
- [ ] Recordings list, Media3 player, tags, notes
- [ ] Local full-text search over transcripts
- [ ] Export: share sheet (audio + TXT/SRT/VTT/MD/JSON) and bulk SAF zip
- [ ] Retention policy (age/size caps) + storage stats screen
- Exit criteria: full offline CRUD + search + export; reboot-mid-pipeline recovery verified.

## M5 — On-device AI
- [ ] `third_party` submodules pinned (whisper.cpp, llama.cpp) + CMake arm64-v8a builds
- [ ] whisper JNI: chunked transcription, progress callbacks, timestamps, per-stream speaker labels (cellular)
- [ ] llama JNI: summarization prompt styles (Brief / Action items / Detailed)
- [ ] Model Manager: download with resume + SHA-256, SAF sideload, delete, storage checks
- [ ] Pre-flight gates: RAM, storage, thermal, battery
- [ ] Transcript UI with tap-to-seek + summary cards
- Exit criteria: end-to-end on-device pipeline (call → recording → transcript → summary), both models loaded/freed sequentially with stable native heap.

## Beyond v0.1
- [ ] Per-recording encryption at rest
- [ ] "Airplane" build variant without the INTERNET permission (sideload-only models)
- [ ] Live partial transcription during long calls
- [ ] Spam/caller heuristics fully local
- [ ] Localization (English first; Hinglish UI strings as needed)
