# Testing guide

## Unit tests (JVM, no device) — `./gradlew :app:testDebugUnitTest`
(75 tests, all passing at the time of writing.)  
*Windows machines where Gradle cannot fork JVMs* (`Unable to establish loopback connection`): run Gradle in-process
(see [RELEASE.md](RELEASE.md)), build the test classpath with `:app:dumpUnitTestClasspath`, then run
`java @args org.junit.runner.JUnitCore <all *Test classes>` with `-cp` taken from `app/build/unit-test-classpath.txt`.
`.\scripts\run_unit_tests.ps1` does the same but forks Gradle, which can hang on those machines.

| Area | File | Covers |
|---|---|---|
| Event Engine | `core/events/EventEngineTest` | one logical event, durations, debounce, cooldowns, presence/return, offline/online, low battery |
| Movement debounce | `EventEngineTest` (movement cases), `AiAndSettingsTest` (tracker, motion rotation) | twitch ≠ event, pauses stay in one event |
| Bark thresholds | `EventEngineTest` (bark/repeated/howl), `AiAndSettingsTest` (thresholds, label mapping, windower) | "3 barks in 30 s", continuous barking = 1 event |
| Pairing | `core/security/PairingTest` | token entropy, QR parse/reject, expiry, one-time use |
| Offline sync | `core/sync/EventSyncTest` | queue offline → flush in order, version guard, batch size, poison event |
| Authentication | `features/authentication/AuthViewModelTest` | validation, success/failure, reset |
| Repositories | `EventSyncTest` (fake DAO + remote), instrumented `RoomEventDaoTest` | offline-first store semantics |
| Notification logic | `core/notifications/NotificationPolicyTest` | per-type switches, cooldowns, FCM parsing |
| WebRTC lifecycle / reconnection | `core/webrtc/LiveSessionStateMachineTest` | connect, blip, bounded reconnect + back-off, failure, stop |
| Thermal / profiles / settings / routing / analytics | `AiAndSettingsTest` | AI profiles, thermal, JSON settings, router, daily stats |

## Instrumented tests (device/emulator) — `./gradlew :app:connectedDebugAndroidTest`
Compose UI tests on stateless screen content (`androidTest/.../ui`):
onboarding, **role selection**, auth + **Firebase-failure** screen, **camera dashboard** (idle/running/errors), **camera & microphone permission denial**, **viewer dashboard** (online/stale-offline/empty), **pairing** (QR, **expired QR**, scan errors, revoke), **alerts** (filters/details/empty), **unauthorized event access**, activity charts, **live screen disconnect/reconnect**, **settings** (all sections, viewer remote disabled, sign-out confirm). Plus the Room DAO test.

## Manual end-to-end checklist (two real phones)
1. Phone A: Camera Mode, sign in, START MONITORING (screen off, charging). Phone B: Viewer Mode, scan QR.
2. B on mobile data: Live shows video within ~5 s. Toggle Always-use-relay to verify TURN.
3. Make the dog (or a recording) bark → bark event within ~30 s → push on B → tap → details → VIEW LIVE.
4. Airplane mode on A for 2 min → A keeps detecting; B receives "Camera offline"; restore → "Camera online", queued events appear.
5. Deny camera permission → dashboard shows rationale; deny mic → monitoring still starts (bark detection off).
6. Expired QR (wait 5 min) / reuse a QR → clear error.
7. Revoke the viewer on A → B loses the camera and Live fails.
8. Kill the app from Recents; reboot A → "tap to resume" notification.
9. Heat: put A in a warm spot → warning appears, AI interval grows, quality drops.

## Firestore rules tests
`firebase emulators:start --only firestore` and use `@firebase/rules-unit-testing` (see [SECURITY_CHECKLIST.md](SECURITY_CHECKLIST.md) for the cases to assert).
