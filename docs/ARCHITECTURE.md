# Architecture

Single-module Android app (`:app`), Clean-architecture-style packages, Hilt for DI.

```
com.laddu.app
├── core/
│   ├── model/          domain types (events, camera, settings)
│   ├── database/       Room (events = offline-first source of truth on the camera)
│   ├── datastore/      DataStore preferences (mode, settings as JSON blobs)
│   ├── firebase/       Auth, devices, pairing, signaling, events, media repositories
│   ├── camera/         CameraEngine (single camera owner, frame fan-out)
│   ├── audio/          AudioCapture, AudioWindower, BarkDetectionEngine (TFLite)
│   ├── ai/             DogDetectionEngine (TFLite), DogTracker, MotionDetector, FramePipeline
│   ├── events/         EventEngine (pure, deterministic), EventProcessor (Room→sync)
│   ├── recording/      ClipRecorder (pre-event ring buffer → MP4), quota/retention
│   ├── webrtc/         factory, ICE/TURN, CameraPeer, ViewerSession, state machine
│   ├── sync/           EventSyncManager + WorkManager SyncWorker
│   ├── network|device/ connectivity, battery & thermal monitoring
│   ├── notifications/  channels, policy (anti-spam), FCM receiver, deep links
│   ├── security/       pairing tokens + validator
│   ├── di/ ui/         Hilt modules; theme, components, navigation
├── features/           onboarding, authentication, camera, viewer, live, alerts, activity, pairing, settings
└── services/           CameraMonitoringService, LiveStreamCoordinator, BootReceiver, monitoring state
```

## Camera pipeline (one camera owner, many consumers)

```
CameraX (Preview + ImageAnalysis, YUV_420_888)
   └─ CameraEngine.dispatch(frame)
        ├─ FramePipeline ─ MotionDetector (48×36 luma diff, ~1.7k reads)
        │                 └─ if due/motion: bitmap(≤320px, upright) → DogDetectionEngine (TFLite, 1 inference at a time)
        │                       → DogTracker (IoU) → "dog moved?" → EngineInput.{DogSeen, DogAbsent, MovementSample}
        ├─ ClipRecorder   (JPEG ring buffer, 4 fps → encodes MP4 on events)
        └─ CameraVideoFeeder (I420 → WebRTC VideoSource, only while a viewer is connected)
Microphone → AudioCapture → AudioWindower(0.975 s) → BarkDetectionEngine (YAMNet via the TFLite Interpreter, own inference thread) → BarkPipeline → EngineInput.{BarkSample, HowlSample}
                       (while a viewer streams, WebRTC's audio module owns the mic and feeds the same windower)
All inputs → Channel → EventEngine (single consumer) → EventProcessor → Room → Firestore (+ WorkManager retry)
```

Why one camera owner: Android allows one camera client; CameraX binds Preview + ImageAnalysis once and everything else consumes frames.

## EventEngine (the "one logical event" guarantee)

Pure Kotlin, takes explicit timestamps, therefore fully unit-tested. Per signal: start only after debounce (movement: 3 confirmed samples in 5 s; bark: N barks in W seconds, default 3/30 s), keep the *same* event while activity continues (pauses < 10 s), complete it once with its real duration (e.g. "moved 1 m 42 s" = one event). Notification rate limits live in the event (`notify` flag) and again at the viewer (`NotificationPolicy`).

## Data & sync

* **Camera side**: every event is written to **Room first** (`synced = 0`). `EventSyncManager` uploads in chronological order; an event modified during upload is *not* marked synced (version guard). If the network is down, WorkManager retries with back-off; local AI keeps running offline.
* **Firestore collections**: `users`, `devices` (+`liveSessions` + candidate sub-collections), `deviceUsers`, `events`, `settings`, `pairingSessions`, `notificationPreferences`.
* **Viewer side**: Firestore snapshot listeners (the SDK's disk cache gives offline reading).

## Monitoring service

`CameraMonitoringService` is a `LifecycleService` foreground service of type `camera|microphone`, with a persistent notification (*Open Laddu*, *Stop Monitoring*), partial wake-lock + Wi-Fi lock, `START_STICKY`, watchdog for stalled camera, thermal-aware AI profile. It does not bypass Android restrictions — see [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md) for reboot/background rules.

## Live streaming

See [WEBRTC.md](WEBRTC.md). Camera = offerer, viewer = answerer, signaling via Firestore, media via WebRTC (STUN + TURN).

## Thermal / performance

`DeviceHealthMonitor` merges `PowerManager` thermal status and battery temperature → `ThermalLevel`. WARM ×2 / HOT ×4 inference interval, fewer threads, lower clip-frame rate, stream quality capped (MEDIUM / LOW), user warning. Android's own thermal protection is never bypassed.
