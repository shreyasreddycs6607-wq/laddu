package com.laddu.app.core.firebase

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.laddu.app.core.model.CameraInfo
import com.laddu.app.core.model.CameraSettings
import com.laddu.app.core.model.CameraStatus
import com.laddu.app.core.model.PairingSession
import com.laddu.app.core.model.UserProfile
import com.laddu.app.core.model.ViewerAccess
import com.laddu.app.core.security.PairingCheck
import com.laddu.app.core.security.PairingToken
import com.laddu.app.core.security.PairingValidator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/** "Laddu Camera (Lenovo TB-X6C6X)": many phones already start the model name with the manufacturer, so do not repeat it. */
fun defaultCameraName(): String {
    val maker = android.os.Build.MANUFACTURER.lowercase().replaceFirstChar { it.uppercase() }
    val model = android.os.Build.MODEL
    val device = if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model"
    return "Laddu Camera ($device)"
}

@Singleton
class DeviceRepository @Inject constructor(private val fb: FirebaseProvider) {

    // ---- camera side ----------------------------------------------------------------------
    /** Creates the camera record the first time this phone is used as a camera for [ownerId]. */
    suspend fun registerCamera(cameraId: String, ownerId: String, defaultName: String): Result<Unit> = runCatching {
        val ref = fb.firestore.collection(Paths.DEVICES).document(cameraId)
        val existing = ref.get().awaitOrNull()
        if (existing?.exists() == true && existing.getString("ownerId") != ownerId) {
            error("This phone is already registered to another Laddu account. Unpair it there first.")
        }
        ref.set(
            mapOf(
                "ownerId" to ownerId,
                "name" to (existing?.getString("name") ?: defaultName),
                "createdAt" to (existing?.getTimestamp("createdAt") ?: FieldValue.serverTimestamp()),
                "platform" to "android",
            ),
            SetOptions.merge(),
        ).awaitDone().let { ok -> if (!ok) error("Could not reach Firebase") }
    }

    /**
     * Registers this phone for [ownerId]. If the saved camera id is owned by another account (the user switched
     * accounts), moves to a fresh id from [rotate]. Returns the id in use.
     */
    suspend fun ensureCamera(current: String, ownerId: String, name: String, rotate: suspend () -> String): Result<String> {
        var id = current
        var r = registerCamera(id, ownerId, name)
        val msg = r.exceptionOrNull()?.message.orEmpty()
        if (msg.contains("PERMISSION_DENIED") || msg.contains("already registered")) {
            id = rotate(); r = registerCamera(id, ownerId, name)
        }
        return r.map { id }
    }

    suspend fun heartbeat(cameraId: String, status: CameraStatus) {
        if (!fb.isConfigured || fb.currentUid == null) return
        runCatching {
            fb.firestore.collection(Paths.DEVICES).document(cameraId).update(
                mapOf(
                    "lastSeen" to FieldValue.serverTimestamp(),
                    "status" to status.toMap(),
                    "offlineNotified" to false,
                )
            ).awaitOrNull(10_000)
        }
    }

    // ---- reading cameras ------------------------------------------------------------------
    fun observeCamera(cameraId: String): Flow<CameraInfo?> {
        if (!fb.isConfigured) return flowOf(null)
        return callbackFlow {
            val reg = fb.firestore.collection(Paths.DEVICES).document(cameraId).addSnapshotListener { s, e ->
                trySend(if (e != null || s == null || !s.exists()) null else s.toCamera())
            }
            awaitClose { reg.remove() }
        }
    }

    private fun observeOwned(uid: String): Flow<List<CameraInfo>> = callbackFlow {
        val reg = fb.firestore.collection(Paths.DEVICES).whereEqualTo("ownerId", uid).addSnapshotListener { s, e ->
            trySend(if (e != null) emptyList() else s?.documents?.mapNotNull { it.toCamera() }.orEmpty())
        }
        awaitClose { reg.remove() }
    }

    private fun observeSharedIds(uid: String): Flow<List<String>> = callbackFlow {
        val reg = fb.firestore.collection(Paths.DEVICE_USERS).whereEqualTo("userId", uid).addSnapshotListener { s, e ->
            trySend(if (e != null) emptyList() else s?.documents?.mapNotNull { it.getString("cameraId") }.orEmpty())
        }
        awaitClose { reg.remove() }
    }

    /** Cameras the user owns plus cameras shared with them via pairing. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeAccessibleCameras(uid: String): Flow<List<CameraInfo>> {
        if (!fb.isConfigured) return flowOf(emptyList())
        val shared = observeSharedIds(uid).flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyList())
            else combine(ids.map { observeCamera(it) }) { arr -> arr.filterNotNull() }
        }
        return combine(observeOwned(uid), shared) { own, sh -> (own + sh).distinctBy { it.cameraId }.sortedBy { it.name } }
    }

    // ---- management -----------------------------------------------------------------------
    suspend fun rename(cameraId: String, name: String): Result<Unit> = runCatching {
        fb.firestore.collection(Paths.DEVICES).document(cameraId).update("name", name.trim()).await()
    }

    /** Owner removes the camera and every viewer's access. */
    suspend fun unpairCamera(cameraId: String): Result<Unit> = runCatching {
        val db = fb.firestore
        val viewers = db.collection(Paths.DEVICE_USERS).whereEqualTo("cameraId", cameraId).get().await()
        val batch = db.batch()
        viewers.documents.forEach { batch.delete(it.reference) }
        batch.delete(db.collection(Paths.SETTINGS).document(cameraId))
        batch.delete(db.collection(Paths.DEVICES).document(cameraId))
        batch.commit().await()
    }

    fun observeViewers(cameraId: String): Flow<List<ViewerAccess>> {
        if (!fb.isConfigured) return flowOf(emptyList())
        return callbackFlow {
            val reg = fb.firestore.collection(Paths.DEVICE_USERS).whereEqualTo("cameraId", cameraId)
                .addSnapshotListener { s, e ->
                    trySend(if (e != null) emptyList() else s?.documents?.mapNotNull { it.toViewerAccess() }.orEmpty())
                }
            awaitClose { reg.remove() }
        }
    }

    suspend fun revokeViewer(cameraId: String, userId: String): Result<Unit> = runCatching {
        fb.firestore.collection(Paths.DEVICE_USERS).document(Paths.deviceUserId(cameraId, userId)).delete().await()
    }

    /** A viewer removes a camera from their own account. */
    suspend fun leaveCamera(cameraId: String, userId: String): Result<Unit> = revokeViewer(cameraId, userId)
}

@Singleton
class PairingRepository @Inject constructor(private val fb: FirebaseProvider) {

    /** Camera side: create a fresh, short-lived, one-time pairing session. */
    suspend fun createSession(cameraId: String, ownerId: String): Result<PairingSession> = runCatching {
        val now = System.currentTimeMillis()
        val token = PairingToken.generate()
        val s = PairingSession(token, cameraId, ownerId, now, now + PairingToken.TTL_MS)
        fb.firestore.collection(Paths.PAIRING).document(token).set(
            mapOf(
                "cameraId" to cameraId,
                "ownerId" to ownerId,
                "createdAt" to Timestamp(Date(now)),
                "expiresAt" to Timestamp(Date(s.expiresAtMs)),
                "used" to false,
            )
        ).await()
        s
    }

    /** Camera side: emits true once the session has been redeemed by a viewer. */
    fun observeUsed(token: String): Flow<Boolean> = callbackFlow {
        val reg = fb.firestore.collection(Paths.PAIRING).document(token).addSnapshotListener { s, _ ->
            trySend(s?.getBoolean("used") == true)
        }
        awaitClose { reg.remove() }
    }

    suspend fun invalidate(token: String) {
        runCatching { fb.firestore.collection(Paths.PAIRING).document(token).delete().awaitOrNull() }
    }

    /**
     * Viewer side: validate the scanned token and atomically (batched write) mark it used and
     * grant this account access. Firestore rules re-check everything server-side.
     */
    suspend fun redeem(token: String, user: UserProfile): Result<String> = runCatching {
        val db = fb.firestore
        val ref = db.collection(Paths.PAIRING).document(token)
        val snap = ref.get().await()
        val session = snap.takeIf { it.exists() }?.let {
            PairingSession(
                token = token,
                cameraId = it.getString("cameraId").orEmpty(),
                ownerId = it.getString("ownerId").orEmpty(),
                createdAtMs = it.getTimestamp("createdAt")?.millis() ?: 0,
                expiresAtMs = it.getTimestamp("expiresAt")?.millis() ?: 0,
                used = it.getBoolean("used") ?: false,
            )
        }
        val check = PairingValidator.check(session, System.currentTimeMillis())
        if (check != PairingCheck.Valid) error(check.message())
        val cameraId = session!!.cameraId
        db.batch().apply {
            update(ref, mapOf("used" to true, "usedBy" to user.uid, "usedAt" to FieldValue.serverTimestamp()))
            set(
                db.collection(Paths.DEVICE_USERS).document(Paths.deviceUserId(cameraId, user.uid)),
                mapOf(
                    "cameraId" to cameraId,
                    "userId" to user.uid,
                    "email" to user.email,
                    "displayName" to user.displayName,
                    "role" to "VIEWER",
                    "pairingToken" to token,
                    "createdAt" to FieldValue.serverTimestamp(),
                ),
            )
        }.commit().await()
        cameraId
    }
}

/** Remote camera settings: viewers write `settings/{cameraId}`, the camera applies them. */
@Singleton
class RemoteSettingsRepository @Inject constructor(private val fb: FirebaseProvider) {

    fun observe(cameraId: String): Flow<Pair<CameraSettings, Long>?> {
        if (!fb.isConfigured) return flowOf(null)
        return callbackFlow {
            val reg = fb.firestore.collection(Paths.SETTINGS).document(cameraId).addSnapshotListener { s, e ->
                @Suppress("UNCHECKED_CAST")
                val map = s?.get("camera") as? Map<String, Any?>
                trySend(
                    if (e != null || s == null || !s.exists() || map == null) null
                    else CameraSettings.fromMap(map) to (s.getLong("restartRequestedAt") ?: 0L)
                )
            }
            awaitClose { reg.remove() }
        }
    }

    /** Publishes the camera's own settings once so paired viewers can edit them; never overwrites existing ones. */
    suspend fun ensure(cameraId: String, settings: CameraSettings) {
        runCatching {
            val ref = fb.firestore.collection(Paths.SETTINGS).document(cameraId)
            if (ref.get().awaitOrNull()?.get("camera") == null) {
                ref.set(mapOf("camera" to settings.toMap()), SetOptions.merge()).awaitDone()
            }
        }
    }

    suspend fun push(cameraId: String, settings: CameraSettings): Result<Unit> = runCatching {
        fb.firestore.collection(Paths.SETTINGS).document(cameraId).set(
            mapOf("camera" to settings.copy(updatedAtMs = System.currentTimeMillis()).toMap()),
            SetOptions.merge(),
        ).await()
    }

    /** Mirror of the viewer's notification switches so the server can skip muted categories. */
    suspend fun pushNotificationPrefs(uid: String, prefs: com.laddu.app.core.model.NotificationPrefs): Result<Unit> = runCatching {
        fb.firestore.collection(Paths.NOTIFICATION_PREFS).document(uid).set(prefs.toMap()).awaitOrNull(10_000)
        Unit
    }

    suspend fun requestRestart(cameraId: String): Result<Unit> = runCatching {
        fb.firestore.collection(Paths.SETTINGS).document(cameraId)
            .set(mapOf("restartRequestedAt" to System.currentTimeMillis()), SetOptions.merge()).await()
    }
}
