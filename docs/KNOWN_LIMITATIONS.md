# Known limitations (honest list)

**Verification status.** Debug and release (R8) builds succeed, the 72 JVM unit tests pass and the instrumented UI tests compile. It has **not** been run on a physical Oppo phone or against a live Firebase project in this environment: real-device behaviour (camera/AI speed, thermals, ColorOS battery policy, TURN reachability, FCM delivery) must be validated by you using [TESTING.md](TESTING.md).

**Android platform rules (by design, not bugs)**
* Camera/microphone foreground services cannot be started silently after reboot or from a background restart (Android 11–15). Laddu shows a *tap to resume* notification instead.
* Another app using the camera pre-empts Laddu until released.
* Oppo/ColorOS may require the manual battery/auto-launch steps ([guide](OPPO_COLOROS_SETUP.md)).

**Product**
* AI models are downloaded by script, not committed. Accuracy depends on the model; EfficientDet-Lite0 on a low-end phone runs ~1–3 inferences/s. Movement thresholds are heuristic and may need tuning per room/camera angle.
* Dog detection treats every dog in view as "the dog" (no per-dog identity). Detection zones are architecture-only (future).
* Event clips are **video-only** (no audio), 4 fps time-lapse-style, encoded after the event; clip upload retries only for events from the last 24 h.
* Live view: max 2 concurrent viewers per camera. Strict NATs need TURN. No two-way audio.
* Viewer "live preview" on Home is tap-to-start (no auto-streaming, to save the camera phone and data).
* Cloud functions need the Firebase Blaze plan. Offline-camera detection granularity is ~2 minutes.
* `deviceUsers` rules allow a viewer to read other viewer records of the same camera only via the owner; viewers see only their own record.
* Remote settings sync is last-writer-wins by timestamp.
* Gradle on some Windows machines fails to fork its daemon (Java loopback bug); this repo's `gradle.properties` is set to be friendly, but see the note in `RELEASE.md`.
