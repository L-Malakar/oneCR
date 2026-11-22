# Privacy

oneCR is built on a simple contract: **your calls are yours and they never leave your phone.**

## What oneCR does

- Stores recordings, transcripts, summaries, tags and search indexes **only** in the app's private storage on your device.
- Processes audio and text **only** on the device, using whisper.cpp and llama.cpp compiled into the app. No cloud inference, ever.
- Exports files **only** when you explicitly share or save them.

## What oneCR does not do

- No analytics, telemetry, crash reporting or tracking SDKs.
- No accounts, no sign-in, no identifiers.
- No background network activity of any kind.

## Network access

The `INTERNET` permission is used for exactly one feature: **model downloads** you start from the Model Manager (on-device speech-to-text and summarization models). Every download shows its source URL and is verified against a pinned SHA-256 checksum before use.

If you prefer a zero-network install: sideload models onto the device and import them from storage via SAF (documented in the Model Manager). A build variant with the `INTERNET` permission stripped is planned after v0.1.

## Permissions oneCR requests (and why)

| Permission | Why | Milestone |
|---|---|---|
| `READ_PHONE_STATE` | know when a cellular call starts/ends so detection (now) and recording (M3) can follow it | **v0.2.0** |
| `READ_CALL_LOG` | resolve the phone number and cached contact name of calls after they end (newest row only, read locally) | **v0.2.0** |
| `POST_NOTIFICATIONS` | keep the low-priority detection service notification visible | **v0.2.0** |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | run the call-detection foreground service while the app is closed (the microphone-typed recorder service replaces this in M3) | **v0.2.0** |
| Notification listener access (special access) | detect ongoing WhatsApp/VoIP calls locally — oneCR reads only the category flag of ongoing call notifications from a fixed allowlist, never notification text | **v0.2.0** |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | survive OEM battery killers so detection/recording is not interrupted | **v0.2.0** |
| `RECORD_AUDIO` | capture your side of VoIP calls (near-end mic mix) | M3 |
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
