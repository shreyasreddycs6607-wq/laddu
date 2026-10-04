/**
 * Laddu Cloud Functions.
 *
 *  - onEventCreated        push (FCM, data-only) to the owner + paired viewers when the camera
 *                          writes a new event.
 *  - checkOfflineCameras   every 2 min: cameras whose heartbeat stopped get a CAMERA_OFFLINE event
 *                          (a camera that lost power/Internet cannot report that itself).
 *  - getTurnCredentials    callable: short-lived TURN credentials (coturn REST auth), so no TURN
 *                          secret is ever shipped in the APK.
 *  - cleanupStaleSessions  hourly: delete abandoned WebRTC signaling sessions.
 *
 * Requires the Blaze plan. See docs/FIREBASE_SETUP.md and docs/TURN_SETUP.md.
 */
const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { defineSecret, defineString } = require("firebase-functions/params");
const admin = require("firebase-admin");
const crypto = require("crypto");

admin.initializeApp();
const db = admin.firestore();

const TURN_SECRET = defineSecret("TURN_SECRET");
const TURN_URLS = defineString("TURN_URLS", { default: "" }); // comma separated, e.g. turn:turn.example.com:3478?transport=udp,turns:turn.example.com:5349

const PREF_KEY = {
  BARK: "barking",
  HOWL: "barking",
  REPEATED_BARK: "repeatedBarking",
  MOVEMENT: "movement",
  DOG_PRESENCE: "dogReturned",
  CAMERA_OFFLINE: "cameraOffline",
  CAMERA_ONLINE: "cameraOnline",
  LOW_BATTERY: "lowBattery",
};

const TITLES = {
  BARK: "🔊 Laddu detected barking",
  REPEATED_BARK: "⚠️ Repeated barking",
  HOWL: "🐺 Laddu heard howling",
  MOVEMENT: "🐕 Movement detected",
  DOG_PRESENCE: "🐶 Your dog is back",
  CAMERA_OFFLINE: "📴 Camera offline",
  CAMERA_ONLINE: "✅ Camera online",
  LOW_BATTERY: "🔋 Camera battery low",
};

function bodyFor(type, ev) {
  const secs = Math.round((ev.durationMs || 0) / 1000);
  const time = new Date(ev.timestamp || Date.now()).toLocaleTimeString("en-IN", {
    hour: "2-digit", minute: "2-digit", timeZone: "Asia/Kolkata",
  });
  switch (type) {
    case "BARK": return secs > 0 ? `Your dog has been barking for ${secs} seconds.` : "Your dog is barking.";
    case "REPEATED_BARK": return `${(ev.metadata && ev.metadata.count) || "Several"} barking events in the last few minutes.`;
    case "HOWL": return "Your dog is howling.";
    case "MOVEMENT": return `Your dog started moving at ${time}.`;
    case "DOG_PRESENCE": return `Your dog was spotted at ${time}.`;
    case "CAMERA_OFFLINE": return "Your Laddu camera has disconnected from the Internet.";
    case "CAMERA_ONLINE": return "Your Laddu camera is back online.";
    case "LOW_BATTERY": return `Camera battery is at ${(ev.metadata && ev.metadata.battery) || "a low"}%.`;
    default: return "Laddu event";
  }
}

/** Owner + every paired viewer. */
async function recipientsFor(cameraId, ownerId) {
  const ids = new Set([ownerId]);
  const snap = await db.collection("deviceUsers").where("cameraId", "==", cameraId).get();
  snap.forEach((d) => ids.add(d.get("userId")));
  return [...ids].filter(Boolean);
}

async function tokensFor(uids, type) {
  const out = [];
  await Promise.all(uids.map(async (uid) => {
    const prefs = (await db.doc(`notificationPreferences/${uid}`).get()).data() || {};
    const key = PREF_KEY[type];
    if (key && prefs[key] === false) return; // user muted this category
    const user = (await db.doc(`users/${uid}`).get()).data() || {};
    for (const t of user.fcmTokens || []) out.push({ uid, token: t });
  }));
  return out;
}

async function pushEvent(eventId, ev) {
  if (ev.notify === false) return;
  // events synced after a long offline period are history, not news
  if (ev.type !== "CAMERA_ONLINE" && Date.now() - (ev.timestamp || 0) > 15 * 60 * 1000) return;
  const uids = await recipientsFor(ev.cameraId, ev.ownerId);
  const targets = await tokensFor(uids, ev.type);
  if (!targets.length) return;

  const cam = (await db.doc(`devices/${ev.cameraId}`).get()).data() || {};
  const message = {
    tokens: targets.map((t) => t.token),
    // data-only: the app builds the notification so it can apply the viewer's own preferences
    data: {
      eventId,
      cameraId: ev.cameraId,
      type: ev.type,
      title: TITLES[ev.type] || "Laddu",
      body: bodyFor(ev.type, ev),
      cameraName: String(cam.name || "Laddu camera"),
      timestamp: String(ev.timestamp || Date.now()),
    },
    android: { priority: "high", ttl: 5 * 60 * 1000 },
  };
  const res = await admin.messaging().sendEachForMulticast(message);

  // drop dead tokens
  const dead = [];
  res.responses.forEach((r, i) => {
    const code = r.error && r.error.code;
    if (code === "messaging/registration-token-not-registered" || code === "messaging/invalid-registration-token") {
      dead.push(targets[i]);
    }
  });
  await Promise.all(dead.map((d) =>
    db.doc(`users/${d.uid}`).update({ fcmTokens: admin.firestore.FieldValue.arrayRemove(d.token) }).catch(() => {})));
}

exports.onEventCreated = onDocumentCreated("events/{eventId}", async (event) => {
  const ev = event.data && event.data.data();
  if (!ev) return;
  await pushEvent(event.params.eventId, ev);
});

exports.checkOfflineCameras = onSchedule("every 2 minutes", async () => {
  const cutoff = admin.firestore.Timestamp.fromMillis(Date.now() - 2 * 60 * 1000);
  const stale = await db.collection("devices").where("lastSeen", "<", cutoff).get();
  for (const doc of stale.docs) {
    const d = doc.data();
    if (!(d.status && d.status.monitoring) || d.offlineNotified === true) continue;
    const lastSeenMs = d.lastSeen.toMillis();
    await db.doc(`events/offline_${doc.id}_${lastSeenMs}`).set({
      eventId: `offline_${doc.id}_${lastSeenMs}`,
      cameraId: doc.id,
      ownerId: d.ownerId,
      type: "CAMERA_OFFLINE",
      timestamp: lastSeenMs,
      durationMs: 0,
      confidence: 1,
      ongoing: false,
      notify: true,
      metadata: { source: "cloud" },
    });
    await doc.ref.update({ offlineNotified: true });
  }
});

exports.getTurnCredentials = onCall({ secrets: [TURN_SECRET] }, async (request) => {
  if (!request.auth) throw new HttpsError("unauthenticated", "Sign in first.");
  const urls = TURN_URLS.value().split(",").map((s) => s.trim()).filter(Boolean);
  if (!urls.length) throw new HttpsError("failed-precondition", "TURN is not configured.");
  const ttl = 6 * 60 * 60; // 6h
  const username = `${Math.floor(Date.now() / 1000) + ttl}:${request.auth.uid}`;
  const credential = crypto.createHmac("sha1", TURN_SECRET.value()).update(username).digest("base64");
  return { urls, username, credential, ttl };
});

exports.cleanupStaleSessions = onSchedule("every 60 minutes", async () => {
  const cutoff = admin.firestore.Timestamp.fromMillis(Date.now() - 10 * 60 * 1000);
  const old = await db.collectionGroup("liveSessions").where("createdAt", "<", cutoff).get();
  await Promise.all(old.docs.map((d) => db.recursiveDelete(d.ref)));
});
