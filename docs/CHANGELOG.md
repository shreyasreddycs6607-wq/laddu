# Changelog

## Real-device hardening pass (Lenovo TB-X6C6X camera + Galaxy phone viewer, real Firebase project)

Found by a 90-finding audit and verified/fixed on real devices. About 75 are fixed; each fix is its own commit
(search the git log for the finding id, e.g. `F42`).

### Crashes and start-up
* Native crash on Android 12 loading the TFLite models: disabled heap pointer tagging, replaced the Task **Audio**
  library (Scudo abort) with the plain TFLite `Interpreter` for YAMNet, bundled `bark_labels.txt`.
* Dog detector swapped to the Task-compatible **SSD MobileNet v1** (the MediaPipe EfficientDet file has 2 outputs and
  can never load in `ObjectDetector`).
* Monitoring resumes when the dashboard opens if it was wanted but the process died (F07); camera service worker
  failures stop monitoring cleanly instead of crashing (F18); no sticky foreground-service restart from the
  background on API 30+ (F23).

### Pairing and live video
* Event/camera writes were treated as failed because `Task<Void>.awaitOrNull()` returns null on success (F01, F02).
* Camera registers its own `devices/{id}` record, rotates its id if another account owns it, and publishes
  `settings/{id}.camera` (F03, F06, F50, F55).
* Signaling listener retries after errors; "Always use relay" is ignored unless a TURN server exists (it produced zero
  ICE candidates); signaling writes time out instead of hanging (F47); candidates are never dropped (F42).
* WebRTC close/dispose off native callbacks, serialized/deduplicated reconnects, stop-vs-begin races (F09, F10, F11, F41, F72).
* Live sessions are no longer deleted while connected; ICE candidate docs are cleaned with their session (F08, F71).
* Rotation no longer restarts the stream (F17); fullscreen is scoped to the Live tab (F51); deep links wait for the
  camera selection (F05, F82, F83).

### Alerts, sync and data
* FCM alerts finish inside `onMessageReceived`; the camera phone no longer shows its own alerts; logout invalidates the
  token (F04, F14, F15).
* Sync is serialized, re-reads rows, drains large backlogs and is not blocked by another account's events
  (F12, F36, F38); media is merged under the row lock (F35); events left "ongoing" by a crash are closed (F37).
* "Camera back online" only notifies after a long outage (F39); remote settings are applied by stamp, not by comparing
  two phones' clocks (F27).

### Security / backend
* Firestore rules enforce one-time pairing tokens (F49); index overrides keep collection scope (F48); the cleanup
  function leaves connected sessions alone; dev TURN credentials are never compiled into release builds (F89);
  phone-state/storage permissions merged in by TFLite are removed (F87).

### UI
* Two-column camera dashboard on tablets; readable-width Settings; clearly ticked option chips; tidy default camera
  name; long names no longer hide the live state pill; loaders instead of "No camera yet" flashes; accessibility labels.

### Still open
F19, F20 (service start during shutdown), F28/F33 (dog box overlay/orientation), F30 (permanently-denied detection),
F43/F44 (720p crop, mic hand-off to live view), F46 (TURN needed off the home network), F52, F56, F57 (server-side push
throttling), F58 (16 KB page alignment), F60, F63, F66, F84.
