# Privacy

oneCR is built on a simple contract: **your calls are yours and they never leave your phone.**

## What oneCR does

- Stores recordings, transcripts, summaries, tags and search indexes **only** in the app's private storage on your device.
- Processes audio and text **only** on the device, using whisper.cpp and llama.cpp compiled into the app. No cloud inference, ever.
- Exports files **only** when you explicitly share or save them.

## What oneCR does not do

- No analytics, telemetry, crash reporting or tracking SDKs.
- No accounts, no sign-in, no identifiers.
- No background network activity of any kind — the update check runs only while the app is open, at most once per hour.

## Network access

The `INTERNET` permission covers exactly three features:

1. **Local TLS sockets to this phone's own adbd** — the embedded wireless-debugging bridge (v0.3.0). Nothing leaves the device.
2. **Update checker (v0.3.3)** — while the app is open, at most once per hour, oneCR fetches a single public URL (`api.github.com/repos/L-Malakar/oneCR/releases/latest`) with a generic `oneCR-update-check` User-Agent and no other payload. If you tap **Update**, it downloads the release APK from GitHub and verifies it against the release's published SHA-256 before handing it to the system installer.
3. **Model downloads** you start from the Model Manager (M5, on-device speech-to-text and summarization models). Every download shows its source URL and is verified against a pinned SHA-256 checksum before use.

If you prefer a zero-network install: sideload models onto the device and import them from storage via SAF (documented in the Model Manager), and ignore the update prompts — the check happens only while the app is open and can be snoozed. A build variant with the `INTERNET` permission stripped is planned after v0.1.

## Permissions oneCR requests (and why)

| Permission | Why | Milestone |
|---|---|---|
| `READ_PHONE_STATE` | know when a cellular call starts/ends so detection (now) and recording (M3) can follow it | **v0.2.0** |
| `READ_CALL_LOG` | resolve the phone number and cached contact name of calls after they end (newest row only, read locally) | **v0.2.0** |
| `POST_NOTIFICATIONS` | keep the low-priority detection service notification visible | **v0.2.0** |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | run the call-detection foreground service while the app is closed | **v0.2.0** |
| Notification listener access (special access) | detect ongoing WhatsApp/VoIP calls locally — oneCR reads only the category flag of ongoing call notifications from a fixed allowlist, never notification text | **v0.2.0** |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | survive OEM battery killers so detection/recording is not interrupted | **v0.2.0** |
| `RECORD_AUDIO` + `FOREGROUND_SERVICE_MICROPHONE` | record calls through the probe-verified fallback chain (capture engine, M3); requested when you tap Record now, run the probe, or enable auto-record | **v0.3.3** |
| `REQUEST_INSTALL_PACKAGES` | let the system install the update APK oneCR downloaded from GitHub at your explicit Update tap; the per-app "Install unknown apps" grant is asked at that moment | **v0.3.3** |
| `READ_CONTACTS` (optional) | show contact names instead of raw numbers | M4 |
| MediaProjection consent (per session) | capture the remote party's VoIP audio (playback capture) | M3 |

Everything is requested in context, with an explanation, and can be declined (the
related feature simply stays off — the Permission center in Settings shows live
status for every one of them). There is no `QUERY_ALL_PACKAGES`; oneCR only
declares the few VoIP packages it can watch. The Shizuku bridge needs the
[Shizuku](https://shizuku.rikka.app/) app; oneCR's privileged surface there is a
tiny AIDL shell service (`exec`/`getUid`) you can audit in the source.

## Data removal

Uninstalling the app deletes everything (recordings, transcripts, models, settings). A "delete all data" action in Settings does the same without uninstalling.

## Legal

Recording laws vary by jurisdiction (one-party vs. all-party consent). Use oneCR lawfully and tell the people on the call when you record them. This is not legal advice.
