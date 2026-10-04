# WebRTC architecture

Requirement: camera (home Wi-Fi) ↔ viewer (5G / college Wi-Fi) must work. Video is **never** sent through Firestore.

```
 Viewer                       Firestore (signaling only)                  Camera
   │ create liveSessions/{id}  state=requested ─────────────────────────▶ │ coordinator sees request
   │                                                                       │ CameraPeer: addTrack(video, audio), createOffer
   │ ◀──────────────────────── offer {sdp}, state=offered ──────────────── │
   │ setRemote(offer), createAnswer ── answer {sdp}, state=answered ─────▶ │ setRemote(answer)
   │ ◀══════ trickle ICE via cameraCandidates / viewerCandidates ═════════▶│
   │ ◀════════════ SRTP media (DTLS-encrypted), STUN / TURN relay ═════════│
```

## ICE servers
* STUN: Google public STUN (hole punching works for most home/mobile NATs).
* **TURN** (relay, needed for symmetric NAT / some carriers / campus networks): short-lived credentials from the `getTurnCredentials` Cloud Function (coturn REST secret). **No TURN secret is in the app.** Dev fallback: `laddu.turn.*` in `local.properties`. See [TURN_SETUP.md](TURN_SETUP.md).
* Optional "Always use relay" setting forces `IceTransportsType.RELAY`.

## Camera side (`LiveStreamCoordinator` + `CameraPeer`)
* Listens to `devices/{cameraId}/liveSessions`; accepts `state=requested` sessions younger than 2 min, max **2** viewers, rejects others (`failed`).
* One shared `VideoSource`/`VideoTrack` fed by `CameraVideoFeeder` (CameraX frame → I420). Frames are converted **only while a viewer is connected**.
* Per-viewer limits via `RtpSender` parameters (max bitrate, fps, scale-down). Source resolution = best quality requested, capped by thermal state. WebRTC's congestion control adapts bitrate/resolution automatically (`BALANCED` degradation).
* Quality presets: Low 320×240@10 / 250 kbps · Medium 640×480@15 / 800 kbps · High 1280×720@24 / 2 Mbps.
* Audio: WebRTC's audio module opens the mic for the stream and feeds the same samples (resampled to 16 kHz mono) to bark detection; the app's own `AudioRecord` pauses meanwhile.

## Viewer side (`ViewerSession`)
`REQUESTING → CONNECTING → LIVE → RECONNECTING → FAILED/ENDED`, implemented as the pure `LiveSessionStateMachine` (unit-tested): ICE `DISCONNECTED` → 8 s grace → up to 3 automatic reconnects with 1/2/4 s back-off (each = a brand-new session), then *FAILED* with a manual **Reconnect**. 30 s timeout if the camera never answers.

## Cleanup (no leaks)
* Both peers: `close()` is idempotent → `PeerConnection.close()+dispose()`, session doc deleted.
* Camera: stale sessions (>10 min) deleted on start and every 5 min; Cloud Function sweeps hourly (and sub-collections).
* Tracks/sources disposed when the last viewer leaves or monitoring stops; `SurfaceViewRenderer` released with its composable.

## Security
* Signaling documents are readable/writable only by the camera owner and **paired** viewers (Firestore rules). A viewer must be in `deviceUsers` — obtainable only via a one-time QR token.
* Media is DTLS-SRTP end-to-end between the phones (a TURN relay only forwards ciphertext).
* No public stream URL exists anywhere.
