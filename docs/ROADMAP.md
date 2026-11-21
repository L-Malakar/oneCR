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

## M2 — Privilege bridge and call detection
- [ ] `:priv-shizuku`: bind, permission request, user service, binder-death handling
- [ ] `:priv-adb`: wireless-debugging pairing protocol + local adb client (may land as M2.5)
- [ ] `PrivilegeHealthMonitor` + status UI + repair screens (pairing lost, dev options off, reboot)
- [ ] Onboarding wizard with per-OEM guidance (OPPO/OnePlus/Realme, MIUI, One UI battery steps)
- [ ] Cellular call detection state machine + caller ID (READ_PHONE_STATE / CallLog)
- [ ] VoIP detection: notification listener + playback-configuration + recording-configuration heuristics
- [ ] Permission center screen + debug Event Log screen
- Exit criteria: reliable labeled call events on the test device; graceful degradation when privileges drop.

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
