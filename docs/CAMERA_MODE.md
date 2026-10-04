# Camera Mode (the old phone)

## First run
1. Choose **📷 Camera Mode** → sign up / log in (or *Continue offline* if Firebase is not configured: local monitoring only, no pairing/live/alerts).
2. Camera dashboard → **Grant permissions** (Camera required; Microphone for bark detection; Notifications on Android 13+).
3. **Pair viewer** → a one-time QR (valid 5 min, regenerates automatically).
4. **START MONITORING**.

## Dashboard
Laddu Camera · online/offline · live preview (with dog box) · Dog AI status · dog / movement / barking · Internet · battery + charging · temperature · live viewers · events waiting to sync · **START / STOP MONITORING**.

Privacy indicators ("Camera active", "Mic active", "Monitoring") are always visible while running; the notification has **Stop Monitoring** and **Open Laddu**.

## Settings (Camera Mode)
Camera (rear/front, 480p/720p/1080p, AI performance) · detection toggles (dog / movement / bark / howl) · dog & bark sensitivity (+ "N barks in W seconds") · notifications · recording (clip lengths, cloud upload, Wi-Fi only, quota, retention, delete all) · network · privacy · device info · account · about. A viewer can change the camera-related options remotely (Viewer → Settings).

## Event clips
Rolling 5 s buffer in RAM (JPEG frames, ~4 fps) → on an event, MP4 = 5 s before + up to 10 s during + 5 s after (configurable). Stored in the app's private storage, auto-deleted by retention / storage quota. Upload to Firebase Storage only if you enable it. Video only (no audio in clips).

## Offline behaviour
Internet lost → local AI keeps running, events accumulate in Room, "waiting to sync" counter rises, the camera logs `CAMERA_OFFLINE` locally. Internet back → `CAMERA_ONLINE` (with outage length) and queued events sync in order. The server detects a silent camera within ~2 min and notifies viewers.

## Unpair / revoke
*Pair viewer* screen: rename camera, revoke any viewer, or **Unpair camera** (removes it and all access).
