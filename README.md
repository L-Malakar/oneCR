<div align="center">

<a href="https://github.com/L-Malakar/oneCR">
<img src="docs/branding/logo.svg" width="128" alt="oneCR logo" />
</a>

# oneCR

**Local-first, privacy-focused call recorder for Android with on-device AI transcription and summarization.**

Your calls never leave your phone. No accounts, no cloud, no telemetry — just a clean recording library with local whisper.cpp transcription and llama.cpp summaries.

<a href="https://github.com/L-Malakar/oneCR/releases/download/0.3.4/oneCR-v0.3.4.apk">
<img src="https://img.shields.io/badge/Download-oneCR%20v0.3.4%20APK-2ea44f?style=for-the-badge&logo=github&logoColor=white" alt="Download oneCR v0.3.4 APK" />
</a>

[![Build status](https://github.com/L-Malakar/oneCR/actions/workflows/android.yml/badge.svg)](https://github.com/L-Malakar/oneCR/actions/workflows/android.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208%2B%20%28arm64%29-green)](README.md)
[![Version](https://img.shields.io/badge/version-0.3.4-orange)](CHANGELOG.md)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](CONTRIBUTING.md)

</div>

---

## What it does

| Feature | Status |
|---|---|
| **Embedded ADB bridge** — pairs with the phone's own Wireless Debugging (TLS 1.3 + SPAKE2, the same protocol Android's `adb pair` uses); **no extra app needed** | **v0.3.0** |
| **Shizuku bridge** — alternative privilege route for those who already run Shizuku, switchable in Settings | **v0.2.0** |
| **Privilege mode picker** — pick embedded ADB or Shizuku in the wizard or Settings; live health monitoring with repair actions | **v0.3.0** |
| **Cellular call detection** — incoming/outgoing/missed events, caller ID from the call log | **v0.2.0** |
| **VoIP call detection** — debounced notification + audio heuristics for WhatsApp and friends | **v0.2.0** |
| **Light / Dark / System theme** — follows the OS by default, pinnable in Settings; the pin re-themes the whole platform layer (window, bars, dialogs) | **v0.2.1–0.2.4** |
| **Top app bar + notification center** — needs, errors and activity in one place, with an unread badge and a themed panel header | **v0.2.2 / v0.2.4** |
| **One-by-one permission wizard** — every system dialog appears only on an explicit tap | **v0.2.2** |
| **Back navigation that behaves** — back walks your screen history; at the root it asks for a second press before exiting | **v0.2.3** |
| **Modern dashboard** — one gradient command card (brand + live state + start/stop), a 2×2 stat grid, a compact activity feed and a roadmap progress card | **v0.2.5** |
| **The 24×7 watcher survives app close and reboot** — `stopWithTask=false`, notification re-asserted on task removal, boot receiver restarts detection, plus a one-tap battery-exemption card | **v0.2.6** |
| **Smooth animations everywhere + an Animations switch (default on)** — tabs, sub-pages, wizard steps, counters and progress bars move with consistent slide + fade | **v0.2.6** |
| **Modern settings panel** — icon tiles lead every card, segmented theme control, privilege-mode picker, Keep-alive battery card | **v0.2.6 / v0.3.1** |
| **In-app updater** — on every app open oneCR asks GitHub for the latest release; a newer version raises an update page above everything and a notification with **Cancel / Update** actions; Update walks the "Install unknown apps" grant, streams the APK with progress and hands it to the installer automatically | **v0.3.4** |
| **About as its own page** — cut from the Settings list to a standalone page behind the ⓘ icon beside the notification bell | **v0.3.4** |
| **Minimal UI copy** — short labels, one helper line at a time, icon-led rows across the dashboard, settings and About | **v0.3.4** |
| **About rows open their own pages** — Privacy → PRIVACY.md, License → LICENSE, Build → Releases; plus Developer and Report-a-bug rows | **v0.2.6** |
| **Top-bar home button + wizard Skip on re-run only** — the logo always returns to the Dashboard; the wizard's Skip appears only when re-run from Settings, never on first launch | **v0.2.6 / v0.3.1** |
| Two-sided cellular call recording through the shell bridge | M3 |
| VoIP call recording via playback capture + mic mix | M3 |
| Recordings library with player, tags and local full-text search | M4 |
| On-device transcription (whisper.cpp, multilingual) | M5 |
| On-device call summaries (llama.cpp, Qwen2.5-1.5B default) | M5 |
| Local export (TXT / SRT / VTT / MD / JSON / audio) | M4 |
| Model manager (download with checksum verification, or sideload via SAF) | M5 |

**v0.3.4 is still a detection-only build** — it pairs the privilege bridge with
zero extra apps, sees/labels calls, checks GitHub for its own updates and
carries the polished v0.2.6-grade UI layer. No audio is recorded until M3
(the WAV writer and recording journal landed in v0.3.4; the capture engine
is next).

## Why oneCR is different

- **Local-first by architecture.** The only network traffic is the model download *you* explicitly start. Everything else — audio, transcripts, summaries, search — stays on the device. See [PRIVACY.md](PRIVACY.md).
- **No root, no PC, no extra daemon.** Since v0.3.0 oneCR pairs with your phone's own Wireless Debugging (the Android 11+ pairing protocol, implemented on-device in Kotlin: SPAKE2 over edwards25519 + TLS 1.3 + AES-128-GCM) — the privileged route that needs no third-party app. Prefer [Shizuku](https://shizuku.rikka.app/)? Switch modes in Settings.
- **Auditable privileged surface.** The shell side is tiny and readable: a Shizuku AIDL service with `exec`/`getUid` — the embedded-ADB path runs the same minimal commands over adb.
- **One-command builds.** `build.bat` on Windows checks the environment, generates a local signing keystore on first run, compiles everything and publishes `oneCR\Android\oneCR-v<version>.apk`.
- **Tested protocol stack.** The pairing and shell protocol layers are covered by cross-validated unit tests (SPAKE2 against an independent reference implementation, RFC 5869 HKDF vectors, end-to-end TLS pairing/shell mocks) — 24 green tests.

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

1. **Android 8.0 or newer is required to install** (API 26+). The embedded-ADB privilege bridge needs **Android 11+** — the app checks that at runtime and shows a clear message, instead of refusing to install.
2. Allow **"Install unknown apps"** for the browser/file manager you install from (Settings → Apps → Special access → Install unknown apps).
3. Download the APK with a **browser**, not a chat app — WhatsApp/Telegram re-encode or truncate APK attachments, which really does produce a "problem parsing the package" error. Check the file size against the release page before installing.
4. **Updating from v0.3.x:** installs in place — the release keystore is stable from v0.3.0 onward (and versionCode 12 is higher). **Updating from v0.2.x or earlier:** that keystore was rotated at v0.3.0 (the previous one was lost with the build machine), so Android refuses an in-place update once — uninstall the old version first, then install v0.3.4. If you build from source with your own `.signing/` folder, updates install in place instead. From v0.3.4 on, the app's built-in updater can carry you to every future release by itself.
5. Verify the download if the installer says the package is corrupt: compare the file's SHA-256 with the one printed on the release page (`certutil -hashfile oneCR-v0.3.4.apk SHA256` on Windows). A truncated download is the most common cause of that error.
6. Some OEM ROMs (MIUI/ColorOS) block sideloads under "Install via USB" or Play Protect warnings — approve the install when prompted or temporarily disable Play Protect scanning (details in [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md)).

## Setting up the embedded ADB bridge

No extra app — oneCR pairs with the phone's own Wireless Debugging, exactly like `adb pair` does from a PC:

1. Open oneCR → setup wizard (or Settings → Re-run the setup wizard) → **Privilege bridge** → *Embedded ADB*.
2. Enable **Developer options → Wireless debugging**.
3. Tap **"Pair device with pairing code"** and keep the dialog open.
4. Type the 6-digit code into oneCR and press **Pair** (press **Search** first and oneCR finds the pairing port for you via mDNS).
5. Done — oneCR reconnects automatically after reboots as soon as Wireless debugging and Wi-Fi are on again. If pairing fails, type the port from the dialog manually and make sure both devices are on the same Wi-Fi.

Constraints (same as every embedded-ADB recorder): Developer options stay enabled, the phone joins Wi-Fi once after a reboot, and on OPPO/OnePlus/Realme also enable "Disable permission monitoring". See [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md).

## FAQ

**Does oneCR record calls yet?** Not until M3 — v0.3.x proves the privilege bridge and detects calls (the WAV writer and recording journal landed in v0.3.4; the capture engine is next). The roadmap is [docs/ROADMAP.md](docs/ROADMAP.md).

**What changed in v0.3.4?** The in-app updater (GitHub release check on every open, update page above everything, notification with Cancel/Update actions, APK download with automatic install), the About page moved to its own screen behind the ⓘ icon beside the bell, a minimal-copy UI restyle, the M3 recorder foundations (WAV writer + recording journal + orphan sweep), and CI hardening (the Aliyun mirror is skipped on GitHub runners, wrapper validation, job timeouts).

**What does the in-app updater send anywhere?** One read-only GET to `api.github.com/repos/L-Malakar/oneCR/releases/latest` per app open, and the APK download only after you press Update. No identifiers, no telemetry, no other endpoints. See [PRIVACY.md](PRIVACY.md).

**Root needed?** No. The embedded ADB bridge uses the same Wireless Debugging pairing your PC's `adb pair` uses; Shizuku is optional, not required.

**Is my call content sent anywhere?** No. oneCR has no servers and requests no cloud permissions; the `INTERNET` permission exists only to open local TLS sockets to your phone's own adbd (and, in M5, for model downloads you start). See [PRIVACY.md](PRIVACY.md).

**Why is the APK bigger than v0.2.x (~9.5 MB vs ~1.5 MB)?** The bundled BoringSSL (Conscrypt) ships native TLS libraries for each ABI — that is what makes the on-device pairing possible without any external app.

## Project structure

```
oneCR/
├── build.bat                  one-command Windows build
├── app/                       Compose UI, navigation, app wiring
├── core/                      shared model / utilities / design system
├── feature/                   onboarding (setup + pairing), library, settings screens
├── privilege/                 embedded-ADB bridge (v0.3.0) + Shizuku bridge (v0.2.0)
├── service/                   call detection (v0.2.0) + recording service (M3)
├── data/                      JSONL event log + settings (v0.2.0), Room (M4), model storage
├── ai/                        whisper.cpp + llama.cpp JNI wrappers, pipeline
├── third_party/               pinned native dependencies (added at M5)
├── docs/                      architecture, roadmap, compatibility, branding
│   ├── branding/              logo.svg, logo-flat.svg, PNG exports
│   └── release-notes/         GitHub release bodies, latest version only
└── CHANGELOG.md               full release history
```

The full engineering plan, privilege model and database schema live in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md); the milestone plan is in [docs/ROADMAP.md](docs/ROADMAP.md).

## Device compatibility

The APK installs on Android 8+; detection works on Android 8+, and the capture features that arrive in M3 target Android 11+ on arm64-v8a. Behavior varies by OEM — see [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md) for known constraints and how to add your device. Highlights: on OPPO/OnePlus/Realme enable "Disable permission monitoring"; on aggressive OEMs (MIUI/OxygenOS/One UI) exclude oneCR from battery optimization and allow auto-launch. Since v0.3.0 the privilege bridge needs only **Wireless debugging** — no Shizuku app required.

## Developer

Built by **Lalmohan Malakar** — [@L-Malakar](https://github.com/L-Malakar).

Found a bug? [Open an issue](https://github.com/L-Malakar/oneCR/issues/new?template=bug_report.md) (a `bug_report.md` template will guide you), or PRs are always welcome — see [CONTRIBUTING.md](CONTRIBUTING.md).

## Legal notice

Call-recording consent laws differ by country and region (one-party vs. all-party consent). oneCR is intended for **personal, lawful use** — check your local regulations before recording, and respect the privacy of the people you talk to. Nothing here is legal advice.

## Credits and inspiration

- [CallVault](https://github.com/madkongo/CallVault) / [ShizuCallRecorder](https://github.com/kitsumed/ShizuCallRecorder) — the shell-privilege call recording approach and embedded-ADB pattern
- [BCR](https://github.com/chenxiaolong/BCR) — reference for capture source behavior and per-device quirks
- [whisper.cpp](https://github.com/ggml-org/whisper.cpp) and [llama.cpp](https://github.com/ggml-org/llama.cpp) — on-device inference engines
- [Shizuku](https://shizuku.rikka.app/) — privilege bridge API
- [Conscrypt](https://github.com/google/conscrypt) — the TLS stack that exposes `SSL_export_keying_material` on-device

## License

[MIT](LICENSE) — Copyright (c) 2026 Lalmohan Malakar
