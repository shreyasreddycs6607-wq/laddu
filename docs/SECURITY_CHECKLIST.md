# Security checklist

| Control | Where | Status |
|---|---|---|
| Firebase Authentication (email/password), session persisted by SDK | `FirebaseAuthRepository` | ✅ |
| Per-user camera ownership (`devices.ownerId`, immutable) | `firestore.rules` | ✅ |
| Viewer authorisation only via `deviceUsers` doc | rules `isViewer()` | ✅ |
| Pairing: 256-bit token, 5-min TTL, **one-time** (`used` flag, atomic batch), tokens not listable, QR carries no secret | `PairingToken`, rules `pairingSessions`/`deviceUsers` | ✅ |
| Revocation: owner deletes `deviceUsers/{cam}_{uid}` (immediate); unpair deletes camera + all viewers | `DeviceRepository` | ✅ |
| Signaling restricted to owner + paired viewers; viewer must set own `viewerId` | rules `liveSessions` | ✅ |
| Media encrypted (DTLS-SRTP); optional relay-only mode | WebRTC | ✅ |
| TLS everywhere, **cleartext disabled** | `network_security_config.xml` | ✅ |
| No public camera endpoint / no unauthenticated stream | architecture | ✅ |
| No credentials in repo: `google-services.json`, keystores, service accounts, `.env`, models are git-ignored | `.gitignore` | ✅ |
| TURN secret only server-side (ephemeral HMAC credentials) | `functions/index.js` | ✅ |
| Events/clips writes limited to camera owner; Storage reads limited to owner/paired | `firestore.rules`, `storage.rules` | ✅ |
| Backups disabled (`allowBackup=false`, extraction rules exclude all) | manifest | ✅ |
| Privacy: persistent notification + in-app indicators + Stop button; no secret recording; no continuous upload | service, dashboard | ✅ |
| Local data: clips/snapshots in private app storage; user can delete all | `ClipRecorder.deleteAll` | ✅ |
| R8 minify in release, only needed classes kept | `proguard-rules.pro` | ✅ |

## Rules tests to run in the emulator
1. Anonymous / signed-out user cannot read anything.
2. User B cannot read `devices/{A's camera}` or its `events`/`liveSessions` without `deviceUsers`.
3. B cannot create `deviceUsers` without a valid, unused, unexpired `pairingSessions/{token}` for that camera; cannot reuse it; cannot create it for another uid.
4. B cannot list `pairingSessions`; B cannot update fields other than `used/usedBy/usedAt`.
5. After the owner deletes `deviceUsers/{cam}_{B}`, B loses read access.
6. Only the owner can write `events` for their camera and cannot spoof another `ownerId`.

## Residual risks / hardening ideas
* Enable **Firebase App Check** (Play Integrity) to stop non-app clients.
* Add email verification before pairing.
* Rate-limit `getTurnCredentials` per uid.
* Rotate `TURN_SECRET` periodically.
