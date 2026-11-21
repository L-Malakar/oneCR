<div align="center">

# oneCR

**Local-first, privacy-focused call recorder for Android 11+ with on-device AI transcription and summarization.**

Your calls never leave your phone. No accounts, no cloud, no telemetry — just a clean recording library with local whisper.cpp transcription and llama.cpp summaries.

[![Build status](https://github.com/L-Malakar/oneCR/actions/workflows/android.yml/badge.svg)](https://github.com/L-Malakar/oneCR/actions/workflows/android.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%2011%2B%20%28arm64%29-green)](README.md)
[![Version](https://img.shields.io/badge/version-0.1.0-orange)](docs/ROADMAP.md)

</div>

---

## What it does (planned)

| Feature | Status |
|---|---|
| Two-sided cellular call recording without root (embedded ADB / Shizuku shell bridge) | M2-M3 |
| VoIP call recording (WhatsApp etc.) via playback capture + mic mix | M3 |
| Recordings library with player, tags and local full-text search | M4 |
| On-device transcription (whisper.cpp, multilingual) | M5 |
| On-device call summaries (llama.cpp, Qwen2.5-1.5B default) | M5 |
| Local export (TXT / SRT / VTT / MD / JSON / audio) | M4 |
| Model manager (download with checksum verification, or sideload via SAF) | M5 |

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

## Project structure

```
oneCR/
├── build.bat                  one-command Windows build
├── app/                       Compose UI, navigation, app wiring
├── core/                      shared model / utilities / design system
├── feature/                   onboarding, library, settings screens
├── privilege/                 Shizuku + embedded-ADB shell bridges (AIDL)
├── service/                   call detection + recording foreground service
├── data/                      Room database + file/model storage
├── ai/                        whisper.cpp + llama.cpp JNI wrappers, pipeline
├── third_party/               pinned native dependencies (added at M5)
└── docs/                      architecture, roadmap, compatibility, branding
```

The full engineering plan, privilege model and database schema live in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md); the milestone plan is in [docs/ROADMAP.md](docs/ROADMAP.md).

## Device compatibility

oneCR targets Android 11+ on arm64-v8a. Behavior varies by OEM — see [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md) for known constraints and how to add your device. Highlights: on OPPO/OnePlus/Realme enable "Disable permission monitoring"; on aggressive OEMs (MIUI/OxygenOS/One UI) exclude oneCR from battery optimization and allow auto-launch.

## Legal notice

Call-recording consent laws differ by country and region (one-party vs. all-party consent). oneCR is intended for **personal, lawful use** — check your local regulations before recording, and respect the privacy of the people you talk to. Nothing here is legal advice.

## Credits and inspiration

- [CallVault](https://github.com/madkongo/CallVault) / [ShizuCallRecorder](https://github.com/kitsumed/ShizuCallRecorder) — the shell-privilege call recording approach and embedded-ADB pattern
- [BCR](https://github.com/chenxiaolong/BCR) — reference for capture source behavior and per-device quirks
- [whisper.cpp](https://github.com/ggml-org/whisper.cpp) and [llama.cpp](https://github.com/ggml-org/llama.cpp) — on-device inference engines
- [Shizuku](https://shizuku.rikka.app/) — privilege bridge API

## License

[MIT](LICENSE) — Copyright (c) 2026 Lalmohan Malakar
