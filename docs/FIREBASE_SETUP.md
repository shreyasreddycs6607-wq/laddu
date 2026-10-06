# Firebase setup

Laddu needs a Firebase project. **Nothing secret is committed** — you add your own `google-services.json`.

## 1. Create the project
1. https://console.firebase.google.com → *Add project*.
2. *Add app → Android*, package name **`com.laddu.app`**. (For a release build also add the SHA-1/SHA-256 of your release key.)
3. Download **`google-services.json`** → put it in `app/google-services.json` (git-ignored).

## 2. Enable products
| Product | Setting |
|---|---|
| Authentication | Sign-in method → **Email/Password** |
| Firestore | Create database (production mode, region near you) |
| Storage | Create default bucket (only needed for optional cloud clips) |
| Cloud Messaging | enabled by default |
| Functions | requires the **Blaze** (pay-as-you-go) plan — the free tier is plenty for a home setup |

## 3. Deploy rules, indexes, functions
```bash
npm i -g firebase-tools
firebase login
firebase use <your-project-id>
firebase deploy --only firestore:rules,firestore:indexes,storage
cd functions && npm install && cd ..
# getTurnCredentials declares a TURN_SECRET secret; the deploy fails without it. Any placeholder works if you do not use TURN yet:
firebase functions:secrets:set TURN_SECRET
firebase deploy --only functions
```
* `firestore.rules` — ownership + paired-viewer authorisation, one-time pairing tokens (see [SECURITY_CHECKLIST.md](SECURITY_CHECKLIST.md)).
* `storage.rules` — clips readable only by owner / paired viewers.
* `functions/index.js` — `onEventCreated` (push), `checkOfflineCameras`, `getTurnCredentials`, `cleanupStaleSessions`.

## 4. Indexes
`firestore.indexes.json` creates the `events (cameraId ASC, timestamp DESC)` composite index and enables a collection-group index on `liveSessions.createdAt`. If you skip the deploy, the first alerts query logs a link to create it.

## 5. Verify
1. Install on both phones, sign up on each (or the same account).
2. Camera: *Pair viewer* → QR. Viewer: *Add camera* → scan. In the console you should see `devices/{id}`, `deviceUsers/{id}_{uid}` and the `pairingSessions/{token}` document marked `used: true`.
3. Camera: *START MONITORING*; `devices/{id}.lastSeen` should refresh every ~30 s.

## Troubleshooting
* **"Firebase is not set up yet"** → `google-services.json` missing/misplaced. Rebuild after adding.
* **PERMISSION_DENIED on pairing** → rules not deployed, or the QR expired/was already used.
* **No push** → see [FCM_SETUP.md](FCM_SETUP.md).
