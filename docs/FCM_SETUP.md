# Push notifications (FCM)

Clients cannot send FCM safely, so **a Cloud Function sends it**:

```
camera writes events/{id} ─▶ onEventCreated ─▶ looks up owner + paired viewers (deviceUsers)
   ─▶ reads users/{uid}.fcmTokens + notificationPreferences/{uid} ─▶ FCM *data* message
viewer: LadduMessagingService.onMessageReceived ─▶ NotificationPolicy (per-type switches + cooldown) ─▶ notification
```

Why *data-only* messages: the viewer app decides how/if to show them (preferences, spam control) in foreground **and** background. They are `priority: high`.

## Requirements
* `google-services.json` in `app/` and the functions deployed (`firebase deploy --only functions`).
* Viewer grants **Notifications** permission (Android 13+); the app asks on first open.
* The viewer phone must have opened Laddu while signed in at least once (registers its FCM token in `users/{uid}.fcmTokens`; refreshed automatically).

## Notification content
| Event | Title / body (example) |
|---|---|
| Barking | 🔊 Laddu detected barking — "Your dog has been barking for 8 seconds." |
| Repeated barking | ⚠️ Repeated barking — "N barking events in the last few minutes." |
| Movement | 🐕 Movement detected — "Your dog started moving at 6:42 PM." |
| Dog returned | 🐶 Your dog is back |
| Camera offline / online | 📴 / ✅ (offline is raised by the **server** when the heartbeat stops) |
| Low battery | 🔋 Camera battery low |

Tapping opens **Alert details** → **VIEW LIVE**; the notification also has a *VIEW LIVE* action.

## Anti-spam layers
1. Camera: debounce + one logical event per episode, movement notify cooldown (5 min), howl cooldown (5 min).
2. Server: skips categories the user muted (`notificationPreferences`).
3. Viewer: `NotificationPolicy` per camera+type cooldown (default 120 s; repeated-barking 10 min; low-battery 30 min).

## Channels
`monitoring` (low), `alerts_bark` (high), `alerts_movement`, `alerts_dog`, `alerts_system` (high).

## Troubleshooting
* Check Functions logs: `firebase functions:log`.
* Token not stored → open the viewer app while online; check `users/{uid}`.
* Oppo/ColorOS may delay pushes for apps that are "restricted" — see [OPPO_COLOROS_SETUP.md](OPPO_COLOROS_SETUP.md) (also applies to the viewer phone).
