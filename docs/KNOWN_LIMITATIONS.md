# Known limitations (honest list)

**Verification status.** Debug and release (R8) builds succeed, the 75 JVM unit tests pass and the instrumented UI tests compile. It has been run on a Lenovo TB-X6C6X tablet (camera mode) and a Galaxy phone (viewer mode) against a real Firebase project (Firestore rules/indexes deployed): monitoring, Dog AI, pairing and live video over the home Wi-Fi work. **Not verified:** an Oppo/ColorOS phone, FCM push delivery (Cloud Functions are not deployed), TURN/relay across different networks, and the dog/bark detectors on real dog input. See [CHANGELOG.md](CHANGELOG.md) for what was fixed and [TESTING.md](TESTING.md) for the checklist.

**Open findings** (see CHANGELOG): service start during shutdown (F19/F20), dog box overlay/orientation (F28/F33), permanently-denied permission detection (F30), 720p crop and mic hand-off to live view (F43/F44), server-side push throttling (F57), 16 KB page alignment of native libraries (F58), and a few low-severity items.

**Android platform rules (by design, not bugs)**
* Camera/microphone foreground services cannot be started silently after reboot or from a background restart (Android 11–15). Laddu shows a *tap to resume* notification instead.
* Another app using the camera pre-empts Laddu until released.
* Oppo/ColorOS may require the manual battery/auto-launch steps ([guide](OPPO_COLOROS_SETUP.md)).

**Product**
* AI models are downloaded by script, not committed. Accuracy depends on the model; the bundled SSD MobileNet v1 dog detector is small and fast but less accurate than EfficientDet, and on a low-end phone runs ~1–3 inferences/s. Movement thresholds are heuristic and may need tuning per room/camera angle.
* Dog detection treats every dog in view as "the dog" (no per-dog identity). Detection zones are architecture-only (future).
* Event clips are **video-only** (no audio), 4 fps time-lapse-style, encoded after the event; clip upload retries only for events from the last 24 h.
* Live view: max 2 concurrent viewers per camera. Away from the home Wi-Fi (mobile data / strict NAT) a **TURN server is required** and is not set up by default ([TURN_SETUP.md](TURN_SETUP.md)); the "Always use relay" switch is ignored until one exists. No two-way audio.
* Viewer "live preview" on Home is tap-to-start (no auto-streaming, to save the camera phone and data).
* Cloud functions need the Firebase Blaze plan. Offline-camera detection granularity is ~2 minutes.
* `deviceUsers` rules allow a viewer to read other viewer records of the same camera only via the owner; viewers see only their own record.
* Remote settings sync is last-writer-wins by timestamp.
* Gradle on some Windows machines fails to fork its daemon (Java loopback bug); this repo's `gradle.properties` is set to be friendly, but see the note in `RELEASE.md`.
