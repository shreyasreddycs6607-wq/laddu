# Required external configuration checklist

Things only you can provide (nothing is committed to git):

- [ ] **Firebase project** + Android app `com.laddu.app` → `app/google-services.json`
- [ ] Authentication → **Email/Password** enabled
- [ ] **Firestore** created; `firebase deploy --only firestore:rules,firestore:indexes`
- [ ] **Storage** created; `firebase deploy --only storage` (only if you use cloud clips)
- [ ] **Blaze plan** + `cd functions && npm install` + `firebase deploy --only functions`
- [ ] **TURN**: coturn server (or hosted) + `firebase functions:secrets:set TURN_SECRET` + `TURN_URLS` param ([TURN_SETUP.md](TURN_SETUP.md))
- [ ] **AI models**: `scripts/download_models.ps1` (or your own `dog_detector.tflite` / `bark_classifier.tflite`)
- [ ] **Release keystore** + `keystore.properties` (release builds only)
- [ ] Oppo/ColorOS battery & auto-launch settings on the camera phone ([guide](OPPO_COLOROS_SETUP.md))
- [ ] Notification permission granted on the viewer (Android 13+)
- [ ] Release SHA-1 / SHA-256 added to Firebase (release builds)
