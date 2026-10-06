# Laddu 🐕 — Smart AI Dog Monitoring

Laddu turns an **old Android phone (e.g. Oppo A33/A52) into an AI dog camera** and lets a **second phone watch it from anywhere** — over Wi-Fi *or* mobile data.

| | Camera Mode (old phone) | Viewer Mode (your phone) |
|---|---|---|
| Does | Captures video + audio, runs dog / movement / bark AI **on the device**, records event clips, sends alerts | Live video (WebRTC), alerts, activity charts, remote camera settings |
| Needs | Camera + mic permission, charger, Internet (optional for local AI) | Sign-in, QR pairing, notification permission |

```
Old phone ─ CameraX ─ AI ─ EventEngine ─ Room ─▶ Firestore ─▶ Cloud Function ─▶ FCM ─▶ Viewer phone
Old phone ◀══ WebRTC (SRTP, STUN/TURN) ══▶ Viewer phone        (Firestore = signaling only)
```

## What is in this repo

| Path | Purpose |
|---|---|
| `app/` | The Android app (Kotlin, Jetpack Compose, Hilt, Room, DataStore, CameraX, WebRTC, TFLite) |
| `functions/` | Firebase Cloud Functions: push notifications, offline detection, TURN credentials, cleanup |
| `firestore.rules`, `storage.rules`, `firestore.indexes.json`, `firebase.json` | Firebase backend config |
| `scripts/download_models.ps1` | Fetches the two on-device AI models |
| `docs/` | Architecture, setup guides, testing, limitations, checklists |

## Quick start (developer)

1. **Open** the folder in Android Studio (Ladybug+ / JDK 17+) *or* build from the command line:
   ```bash
   ./gradlew :app:assembleDebug        # Windows: gradlew.bat
   ./gradlew :app:testDebugUnitTest    # unit tests
   ```
2. **Firebase** (required for sign-in, pairing, alerts, live view): follow [docs/FIREBASE_SETUP.md](docs/FIREBASE_SETUP.md), then put `google-services.json` in `app/`. Without it the app still builds and runs: the camera can monitor **locally** ("Continue offline"), the viewer shows a setup screen.
3. **AI models**: run `scripts/download_models.ps1` (see [docs/AI_MODEL_SETUP.md](docs/AI_MODEL_SETUP.md)). Without models Laddu runs and says *"Model missing"* — it never fakes detections.
4. **TURN** (needed for strict mobile networks): [docs/TURN_SETUP.md](docs/TURN_SETUP.md).
5. Install on both phones, choose **Camera Mode** on one and **Viewer Mode** on the other.

Minimum Android: **8.0 (API 26)**. Target/compile SDK 35.

## Status

Verified on real devices (Lenovo TB-X6C6X tablet as camera, Galaxy phone as viewer) with a real Firebase project:
monitoring with on-device Dog AI, QR pairing and live video over the home Wi-Fi. The tablet gets a two-column
dashboard. Cross-network live video needs a TURN server ([docs/TURN_SETUP.md](docs/TURN_SETUP.md)); push alerts need
the Cloud Functions deployed (Blaze plan). See the [changelog](docs/CHANGELOG.md) and
[known limitations](docs/KNOWN_LIMITATIONS.md).

## Using it

* **Camera phone**: Camera Mode → sign in → *Pair viewer* (shows a one-time QR) → *START MONITORING*. Leave it plugged in. Follow the [Oppo/ColorOS guide](docs/OPPO_COLOROS_SETUP.md).
* **Viewer phone**: Viewer Mode → sign in → *Add camera* → scan the QR → **Live**.

More: [Camera Mode](docs/CAMERA_MODE.md) · [Viewer Mode](docs/VIEWER_MODE.md) · [Architecture](docs/ARCHITECTURE.md) · [WebRTC](docs/WEBRTC.md) · [FCM](docs/FCM_SETUP.md) · [Testing](docs/TESTING.md) · [Known limitations](docs/KNOWN_LIMITATIONS.md) · [Release build](docs/RELEASE.md) · [External config checklist](docs/EXTERNAL_CONFIG_CHECKLIST.md) · [Security checklist](docs/SECURITY_CHECKLIST.md) · [Roadmap](docs/ROADMAP.md)

## Privacy in one paragraph

The camera phone **always** shows when camera / microphone / monitoring are active (persistent notification with *Stop Monitoring*, plus indicators in the app). Nothing is recorded secretly or uploaded continuously. Event clips stay on the phone unless you turn on cloud upload. Live video flows directly between the phones, encrypted (DTLS-SRTP). Access is per-account, revocable, and enforced server-side by Firestore rules.
