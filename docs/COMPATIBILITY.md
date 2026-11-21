# Device Compatibility

oneCR's shell-privilege capture depends on OEM audio routing, so behavior is device-specific. This file is the field journal: **add a row for every device you test**, with what worked.

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
