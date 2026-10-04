# Oppo / ColorOS (and Realme / OnePlus) setup for 24/7 monitoring

Laddu uses a **legitimate foreground service** (camera + microphone) with a visible notification. Oppo's ColorOS adds extra battery managers that can still stop apps when the screen is off. Laddu **never bypasses** them — it asks you to allow it. In the app: Camera dashboard → **Keep running 24/7 (Oppo / ColorOS setup)**.

1. **Battery optimisation** — allow Laddu to run unrestricted. (The app opens Android's own confirmation dialog.)  
   Manual path: *Settings → Battery → App battery management → Laddu → Allow background activity / Don't optimise*.
2. **Auto-launch / startup manager** — *Settings → Apps → Auto-launch* (or *Privacy → Startup manager*): enable Laddu.
3. **Lock in Recent apps** — open Recent apps, pull the Laddu card down (or tap its ⋮ menu) → **Lock**. Prevents "Clear all" from killing it.
4. **Keep it charging and cool** — plug in, avoid direct sun / cases that trap heat. Laddu slows its AI when warm and warns when hot.
5. **Notifications** — allow notifications (Android 13+), keep the *Monitoring* channel enabled.
6. **Screen-off is fine** — the camera + mic keep running under the foreground service; Laddu holds a partial wake-lock and Wi-Fi lock.
7. **Data saver / Wi-Fi sleep** — turn off "Wi-Fi sleep when screen off" and Data Saver for Laddu.

## What Android will *not* allow (by design)
* After a **reboot**, a camera/microphone service cannot be started silently from the background. Laddu posts *"Laddu stopped monitoring — tap to resume"*. One tap restores it.
* If the system kills the app, `START_STICKY` restarts the service, but camera access from a background restart may be refused (Android 11+ "while-in-use" rule); the same resume notification appears.
* Another app using the camera (video call) will pre-empt Laddu; Laddu reports the error and retries (watchdog every 10 s).

Tip: dedicate the old phone to Laddu, uninstall other apps that want the camera/mic, disable auto-updates during the day.
