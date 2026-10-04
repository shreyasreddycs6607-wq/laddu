# Roadmap

**Near term**
* Real-device tuning pass (movement thresholds, model choice for Oppo A33/A52, battery/thermal profiles).
* Firestore rules emulator tests in CI.
* App Check + email verification.
* Auto-start a low-bitrate preview on the viewer Home screen (opt-in).

**Next**
* Detection zones (bed / door / room) — `Detection.box` + zone polygons already normalised, add editor UI and zone filter in `FramePipeline`.
* Two-way audio ("talk to dog").
* Night-mode image enhancement; IR/low-light exposure tuning.
* Smart barking summaries and weekly activity reports (scheduled function → push/email).
* Audio in event clips; shorter clip upload retries via WorkManager.

**Later**
* Multiple cameras per viewer dashboard (data model already supports it) and multiple dogs / per-dog recognition.
* Remote camera configuration beyond detection settings (zoom, torch, schedule).
* iOS viewer.
