# Security Policy

## Supported versions

| Version | Supported |
|---|---|
| 0.1.x | Yes (active development) |
| < 0.1 | Not supported |

## Reporting a vulnerability

Please report security issues privately via GitHub's **Private vulnerability reporting** on this repository (Security tab), or open a minimal issue only if the report cannot be made private.

Please include:

- Affected version / commit
- Device model and Android version
- Reproduction steps or proof of concept
- Impact assessment

I aim to respond within 7 days and ship a fix for confirmed issues in the next release.

## Threat model

oneCR is a **local-first** app. The design goals are:

1. **No telemetry, no analytics, no crash reporting.** The app never contacts a server on its own.
2. **Network is opt-in.** The only network activity is a model file download that the user explicitly starts from the Model Manager (with SHA-256 verification against a pinned manifest). A build variant without the `INTERNET` permission (sideload-only models) is planned.
3. **Data at rest.** Recordings, transcripts and summaries live in app-private storage (`filesDir`). They are removed by Android when the app is uninstalled. Export happens only when the user shares or copies files.
4. **Privileged surface is minimal.** The only code that runs with adb-shell (UID 2000) privileges is a small capture service that reads the call audio stream and pipes raw PCM back to the app. It performs no network I/O and writes nothing to disk.
5. **Secrets.** The local signing keystore is generated on the developer machine (`build.bat`) and stored in `.signing/`, which is gitignored. No signing material or user data ever enters the repository.

Known trust considerations (please report anything beyond these):

- Granting adb/shell privileges to oneCR (directly or via Shizuku) is an intentional act that grants significant device power; oneCR uses it exclusively for call audio capture.
- Recordings of calls are sensitive by nature; disk encryption provided by the OS protects app-private storage on modern devices. Per-recording encryption is on the roadmap (see docs/ROADMAP.md).

## Scope

In scope: the Android app, its build scripts, and the bundled native code. Out of scope: the Shizuku app itself (report upstream at rikka.shizuku), the whisper.cpp / llama.cpp upstream projects, and social-engineering of the user.
