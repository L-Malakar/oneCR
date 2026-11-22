# Device Compatibility

oneCR's shell-privilege capture depends on OEM audio routing, so behavior is device-specific. This file is the field journal: **add a row for every device you test**, with what worked.

## Install floor vs. feature floor

- **Installing:** the APK installs on **Android 8.0+ (API 26+)**. If a phone shows "There was a problem parsing the package", the download is corrupt or truncated — not a version issue.
- **Recording (M2+):** cellular capture via the embedded-ADB bridge needs **Android 11+ (API 30+)** wireless-debugging pairing; the app detects the Android version at runtime and explains what is missing. Shizuku mode works on older versions if Shizuku itself runs there.

## Play Protect and sideloading

oneCR is distributed as a signed APK from GitHub Releases — not through the Play Store. Google Play Protect reacts to sideloaded apps with warnings like **"This app can request access to sensitive data"** or **"Unsafe app blocked."** Here is what is actually going on and what to do about it.

**Why it triggers on oneCR:**

- Play Protect flags *any* app installed outside the Play Store that requests sensitive permission groups. oneCR needs `READ_CALL_LOG` and `READ_PHONE_STATE` to detect calls at all — the warning is driven by that permission list plus the "unknown publisher" signal (self-signed key, not Play App Signing), not by anything the app has done. No code change can remove it while call detection is the product.
- It is a heuristic caution, not a malware verdict: the full source is in this repo, builds are reproducible with `build.bat`, and every release publishes the APK's SHA-256. Verify the file; don't just trust the warning — or us.
- Shipping on the Play Store would not fix it either — Google Play policy reserves call-recording and Call Log permissions for the device's default dialer, so open-source call recorders (BCR, CallVault, ShizuCallRecorder, oneCR) are sideloaded by nature and all show the same warning.
- What oneCR already does to stay on the right side of the heuristics: release-key signing (v1 + v2 + v3, no debug keys), one stable keystore across releases from v0.2.0 on, and only the permissions the current milestone actually uses (e.g. no microphone permission until M3 recording ships).

**To install anyway (once per install):** on the Play Protect dialog tap **More details → Install anyway (unsafe)**. On some OEM builds the button only appears after you expand the details; on Android 15 there may be a short countdown before it unlocks.

**To turn the warnings off (per device):** open the Play Store → profile icon → **Play Protect** → ⚙ **Settings** → disable **Scan apps with Play Protect** and, if present, **Require Play Protect review for apps**. This also stops Play Protect from asking to re-scan sideloaded apps weekly.

> Note: turning scanning off lowers your protection against genuinely malicious sideloads. If you'd rather keep it on, just tap through the warning each time oneCR installs an update — that is what most users of this category of app do.

## Known platform constraints (from the CallVault / ShizuCallRecorder field notes)

| Constraint | Detail | Workaround |
|---|---|---|
| Android 11 | Screen must stay unlocked during calls in embedded-ADB mode | Keep screen unlocked while recording on API 30 devices |
| Reboot | Shell privileges die on reboot; embedded-ADB mode needs the phone to join Wi-Fi once before recording | Follow the repair banner after reboot |
| Developer options | Must remain enabled in embedded-ADB mode | Onboarding warns if they get toggled off |
| Wireless pairing | Wireless debugging pairing only completes on Wi-Fi networks | Pair once on home Wi-Fi |
| VoLTE / VoWiFi | Carrier Wi-Fi-calling paths may not route call audio into a capturable stream | oneCR detects and warns; recording may be partial |
| Bluetooth | Classic BT headsets (incl. AirPods) record fine; LE Audio untested | Report your result |
| OPPO / OnePlus / Realme | "Disable permission monitoring / system optimization" must be enabled in Developer options | Onboarding step |
| Aggressive OEMs (MIUI, OxygenOS, One UI, etc.) | Background killers can interrupt the recorder | Exclude oneCR from battery optimization, enable auto-launch |
| WhatsApp | VoIP capture depends on WhatsApp not opting out of playback capture; a future update could change this | oneCR detects silent far-end and falls back to mic + loudspeaker |

## Tested devices

| Device | Android | Privilege mode | Cellular VOICE_CALL | VoIP (WhatsApp) | Notes | Tester |
|---|---|---|---|---|---|---|
| *(add your device here)* | | | | | | |

## How to add your result

1. Open oneCR → Debug → Capture diagnostics (available from M3) and note which sources pass the probe.
2. Make one cellular call and one WhatsApp call; note both sides audible or not.
3. Add a row above with your findings and open a PR (or an issue if you are not sure what you saw).
