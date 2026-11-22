<div align="center">

<img src="docs/branding/logo.svg" width="128" alt="oneCR logo" />

# oneCR

**Local-first, privacy-focused call recorder for Android 11+ with on-device AI transcription and summarization.**

Your calls never leave your phone. No accounts, no cloud, no telemetry — just a clean recording library with local whisper.cpp transcription and llama.cpp summaries.

<!-- Download button: on every release, bump the version in the link AND in the button label. -->
<p>
  <a href="https://github.com/L-Malakar/oneCR/releases/download/0.2.0/oneCR-v0.2.0.apk">
    <img src="https://img.shields.io/badge/Download-oneCR%20v0.2.0%20APK-2ea44f?style=for-the-badge&logo=github&logoColor=white" alt="Download oneCR v0.2.0 APK"/>
  </a>
  &nbsp;
  <a href="https://github.com/L-Malakar/oneCR/releases">
    <img src="https://img.shields.io/badge/All%20releases-24292e?style=for-the-badge&logo=github&logoColor=white" alt="All oneCR releases"/>
  </a>
</p>

[![Build status](https://github.com/L-Malakar/oneCR/actions/workflows/android.yml/badge.svg)](https://github.com/L-Malakar/oneCR/actions/workflows/android.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208%2B%20%28arm64%29-green)](README.md)
[![Version](https://img.shields.io/badge/version-0.2.0-orange)](CHANGELOG.md)

</div>

---

## What it does

| Feature | Status |
|---|---|
| **Privilege bridge via Shizuku** — tiny AIDL shell service running as UID 2000, health monitoring, one-tap repair | **v0.2.0** |
| **Cellular call detection** — incoming/outgoing/missed events, caller ID from the call log | **v0.2.0** |
| **VoIP call detection** — debounced notification + audio heuristics for WhatsApp and friends | **v0.2.0** |
| **Onboarding wizard, permission center, local event log** | **v0.2.0** |
| Embedded ADB bridge (no extra app; wireless-debugging pairing on-device) | v0.3.0 (M2.5) |
| Two-sided cellular call recording through the shell bridge | M3 |
| VoIP call recording via playback capture + mic mix | M3 |
| Recordings library with player, tags and local full-text search | M4 |
| On-device transcription (whisper.cpp, multilingual) | M5 |
| On-device call summaries (llama.cpp, Qwen2.5-1.5B default) | M5 |
| Local export (TXT / SRT / VTT / MD / JSON / audio) | M4 |
| Model manager (download with checksum verification, or sideload via SAF) | M5 |

**v0.2.0 is a detection-only build** — it sees and labels calls, proves the shell
bridge, and logs everything locally. No audio is recorded until M3.

## Why oneCR is different

- **Local-first by architecture.** The only network traffic is the model download *you* explicitly start. Everything else — audio, transcripts, summaries, search — stays on the device. See [PRIVACY.md](PRIVACY.md).
- **No root, no PC, no extra daemon.** oneCR borrows adb shell privileges through on-device Wireless Debugging pairing (CallVault-style embedded ADB) or through [Shizuku](https://shizuku.rikka.app/) if you already use it. The privileged surface is a tiny, auditable capture service that streams PCM into the app.
- **One-command builds.** `build.bat` on Windows checks the environment, generates a local signing keystore on first run, compiles everything and publishes `oneCR\Android\oneCR-v<version>.apk`.

## Quick start (Windows)

```bat
git clone https://github.com/L-Malakar/oneCR.git
cd oneCR
build.bat
```

Prerequisites: **JDK 17+** (`JAVA_HOME` set or `java` on PATH) and the **Android SDK** (installed by Android Studio, or `ANDROID_HOME`). The first run generates `.signing\onecr.jks` — keep that folder; it is what lets future builds install as updates instead of reinstalls.

Options:

```bat
build.bat --clean     :: wipe outputs before compiling
build.bat --debug     :: build the debug APK
build.bat --install   :: install the finished APK over adb
build.bat --help
```

Linux/macOS: `./gradlew :app:assembleDebug`.

## Installing a prebuilt APK

Grab `oneCR-v<version>.apk` from [Releases](https://github.com/L-Malakar/oneCR/releases) (or the `oneCR/Android/` folder after a local build) and open it on your phone:

1. **Android 8.0 or newer is required to install** (API 26+). The capture features that ship from M2 onward need **Android 11+** — the app checks that at runtime and shows a clear message, instead of refusing to install.
2. Allow **"Install unknown apps"** for the browser/file manager you install from (Settings → Apps → Special access → Install unknown apps).
3. Download the APK with a **browser**, not a chat app — WhatsApp/Telegram re-encode or truncate APK attachments, which really does produce a "problem parsing the package" error. Check the file size against the release page before installing.
4. **Play Protect may stop the install with "This app can request access to sensitive data."** That warning is automatic for *any* sideloaded app that requests Call Log / Phone permissions — it is a heuristic caution, not a malware verdict (the code is public in this repo and each release lists its SHA-256). Tap **More details → Install anyway (unsafe)** to continue. To stop the prompts for good: Play Store → profile icon → **Play Protect** → ⚙ **Settings** → turn off **Scan apps with Play Protect** (and **Require Play Protect review for apps** on Android 15+). It cannot be avoided in code — see [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md#play-protect-and-sideloading).
5. **Updating from the v0.1.2 pre-release:** v0.2.0 was signed with a fresh release keystore (the original was lost with the build machine), so Android will refuse an in-place update once — **uninstall v0.1.2 first, then install v0.2.0**. If you still have the `.signing/` folder from the v0.1.2 source zip, build v0.2.0 yourself with it (`build.bat` picks it up automatically) and the update installs in place instead. From v0.2.0 onward the shipped keystore stays stable.
6. Verify the download if the installer says the package is corrupt: compare the file's SHA-256 with the one printed on the release page (`certutil -hashfile oneCR-v0.2.0.apk SHA256` on Windows). A truncated download is the most common cause of that error.
7. Some OEM ROMs (MIUI/ColorOS) also block sideloads under "Install via USB" — approve the install when prompted, or enable that toggle in Developer options.

## Project structure

```
oneCR/
├── build.bat                  one-command Windows build
├── app/                       Compose UI, navigation, app wiring
├── core/                      shared model / utilities / design system
├── feature/                   onboarding, library, settings screens
├── privilege/                 Shizuku bridge (v0.2.0) + embedded-ADB (v0.3.0)
├── service/                   call detection (v0.2.0) + recording service (M3)
├── data/                      JSONL event log + settings (v0.2.0), Room (M4), model storage
├── ai/                        whisper.cpp + llama.cpp JNI wrappers, pipeline
├── third_party/               pinned native dependencies (added at M5)
├── docs/                      architecture, roadmap, compatibility, branding
│   ├── branding/              logo.svg, logo-flat.svg, PNG exports
│   └── release-notes/         GitHub release bodies, one file per version
└── CHANGELOG.md               release history
```

The full engineering plan, privilege model and database schema live in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md); the milestone plan is in [docs/ROADMAP.md](docs/ROADMAP.md).

## Device compatibility

The APK installs on Android 8+; detection works on Android 8+, and the capture features that arrive in M3 target Android 11+ on arm64-v8a. Behavior varies by OEM — see [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md) for known constraints and how to add your device. Highlights: on OPPO/OnePlus/Realme enable "Disable permission monitoring"; on aggressive OEMs (MIUI/OxygenOS/One UI) exclude oneCR from battery optimization and allow auto-launch. For the v0.2.0 privilege bridge you need the free [Shizuku](https://shizuku.rikka.app/) app started (once per reboot); the embedded-ADB bridge that removes even that requirement lands in v0.3.0.

## Legal notice

Call-recording consent laws differ by country and region (one-party vs. all-party consent). oneCR is intended for **personal, lawful use** — check your local regulations before recording, and respect the privacy of the people you talk to. Nothing here is legal advice.

## Credits and inspiration

- [CallVault](https://github.com/madkongo/CallVault) / [ShizuCallRecorder](https://github.com/kitsumed/ShizuCallRecorder) — the shell-privilege call recording approach and embedded-ADB pattern
- [BCR](https://github.com/chenxiaolong/BCR) — reference for capture source behavior and per-device quirks
- [whisper.cpp](https://github.com/ggml-org/whisper.cpp) and [llama.cpp](https://github.com/ggml-org/llama.cpp) — on-device inference engines
- [Shizuku](https://shizuku.rikka.app/) — privilege bridge API

## License

[MIT](LICENSE) — Copyright (c) 2026 Lalmohan Malakar
