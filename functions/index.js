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
const ANALYZER_KEY = defineSecret("ANALYZER_KEY");
const ANALYZER_URL = defineString("ANALYZER_URL", { default: "" }); // your analysis endpoint (see docs/CLOUD_AI.md)
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
  // pet-safety events share one switch
  DOG_APPROACHING_HAZARD: "hazards",
  POSSIBLE_HAZARD_INTERACTION: "hazards",
  POSSIBLE_CHEWING: "hazards",
  POSSIBLE_INGESTION: "hazards",
  HIGH_RISK_OBJECT_INTERACTION: "hazards",
  UNKNOWN_OBJECT_NEAR_MOUTH: "hazards",
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
  DOG_APPROACHING_HAZARD: "👀 Dog approaching a hazard",
  POSSIBLE_HAZARD_INTERACTION: "⚠️ Possible hazard interaction",
  POSSIBLE_CHEWING: "⚠️ Possible chewing of a hazard",
  POSSIBLE_INGESTION: "⚠️ Possible ingestion",
  HIGH_RISK_OBJECT_INTERACTION: "⚠️ High-risk object interaction",
  UNKNOWN_OBJECT_NEAR_MOUTH: "⚠️ Unidentified object near your dog",
};

// Hazard events carry their own wording (written on the camera from the evidence it actually had). Cap the length so
// a malformed client cannot push a wall of text.
function hazardText(ev, key, fallback, max) {
  const v = ev.metadata && ev.metadata[key];
  return typeof v === "string" && v.trim() ? v.trim().slice(0, max) : fallback;
}

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
      title: hazardText(ev, "title", TITLES[ev.type] || "Laddu", 80),
      body: hazardText(ev, "body", bodyFor(ev.type, ev), 300),
      cameraName: String(cam.name || "Laddu camera"),
      timestamp: String(ev.timestamp || Date.now()),
      risk: String((ev.metadata && ev.metadata.risk) || ""),
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
  // A connected stream is created once and never refreshed: only drop it after it has clearly outlived any viewing.
  const connectedCutoff = Date.now() - 12 * 60 * 60 * 1000;
  await Promise.all(
    old.docs
      .filter((d) => d.get("state") !== "connected" || d.get("createdAt").toMillis() < connectedCutoff)
      .map((d) => db.recursiveDelete(d.ref))
  );
});

/**
 * Optional hazard second opinion. The app never holds a provider key: it calls this function, which checks the caller
 * owns the camera, validates the upload, rate-limits per user, then forwards to the endpoint YOU configure
 * (ANALYZER_URL / ANALYZER_KEY). Contract: docs/CLOUD_AI.md. Not configured -> failed-precondition (the app copes).
 */
exports.analyzeHazardEvidence = onCall({ secrets: [ANALYZER_KEY], timeoutSeconds: 45, memory: "256MiB" }, async (request) => {
  if (!request.auth) throw new HttpsError("unauthenticated", "Sign in first.");
  const { eventId, cameraId, image, localSummary } = request.data || {};
  if (typeof eventId !== "string" || !eventId || eventId.length > 128) throw new HttpsError("invalid-argument", "eventId");
  if (typeof cameraId !== "string" || !cameraId || cameraId.length > 128) throw new HttpsError("invalid-argument", "cameraId");
  // ~300 KB of JPEG is ~400 KB of base64; JPEGs start with /9j/ in base64
  if (typeof image !== "string" || image.length > 420000 || !image.startsWith("/9j/")) throw new HttpsError("invalid-argument", "image must be a small JPEG");
  const summary = typeof localSummary === "string" ? localSummary.slice(0, 500) : "";

  const dev = (await db.doc(`devices/${cameraId}`).get()).data();
  if (!dev || dev.ownerId !== request.auth.uid) throw new HttpsError("permission-denied", "Not your camera.");

  // 30 analyses per user per hour
  const hour = Math.floor(Date.now() / 3600000);
  const usage = db.doc(`analysisUsage/${request.auth.uid}`);
  await db.runTransaction(async (tx) => {
    const d = (await tx.get(usage)).data() || {};
    const count = d.hour === hour ? d.count || 0 : 0;
    if (count >= 30) throw new HttpsError("resource-exhausted", "Hourly analysis limit reached.");
    tx.set(usage, { hour, count: count + 1 });
  });

  const url = ANALYZER_URL.value();
  if (!url) throw new HttpsError("failed-precondition", "Cloud analysis is not configured on the server.");
  const res = await fetch(url, {
    method: "POST",
    headers: { "content-type": "application/json", authorization: `Bearer ${ANALYZER_KEY.value()}` },
    body: JSON.stringify({ schema: "laddu-hazard-v1", eventId, image, localSummary: summary }),
    signal: AbortSignal.timeout(35000),
  });
  if (!res.ok) throw new HttpsError("unavailable", `Analyzer returned ${res.status}.`);
  const out = await res.json();
  if (!out || out.eventId !== eventId) throw new HttpsError("internal", "Analyzer response did not match the request.");
  return out; // the app validates the schema again before storing anything
});
