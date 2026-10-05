package com.laddu.app.core.firebase

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/** States stored in `devices/{cameraId}/liveSessions/{id}.state`. */
object SessionState {
    const val REQUESTED = "requested"
    const val OFFERED = "offered"
    const val ANSWERED = "answered"
    const val CONNECTED = "connected"
    const val ENDED = "ended"
    const val FAILED = "failed"
    val ACTIVE = setOf(REQUESTED, OFFERED, ANSWERED, CONNECTED)
}

data class SessionDescriptionDoc(val type: String, val sdp: String)

data class LiveSessionDoc(
    val id: String,
    val cameraId: String,
    val viewerId: String,
    val state: String,
    val quality: String,
    val offer: SessionDescriptionDoc?,
    val answer: SessionDescriptionDoc?,
    val createdAtMs: Long,
)

data class IceCandidateDoc(val sdpMid: String?, val sdpMLineIndex: Int, val candidate: String)

/**
 * WebRTC signaling over Firestore. Only SDP/ICE *signaling* goes through Firestore - never media.
 * Access is limited to the camera owner and paired viewers by firestore.rules.
 */
@Singleton
class SignalingRepository @Inject constructor(private val fb: FirebaseProvider) {

    private fun sessions(cameraId: String) =
        fb.firestore.collection(Paths.DEVICES).document(cameraId).collection(Paths.LIVE_SESSIONS)

    private fun com.google.firebase.firestore.DocumentSnapshot.toSession(cameraId: String): LiveSessionDoc? {
        if (!exists()) return null
        fun desc(key: String) = (get(key) as? Map<*, *>)?.let {
            val sdp = it["sdp"] as? String; val type = it["type"] as? String
            if (sdp != null && type != null) SessionDescriptionDoc(type, sdp) else null
        }
        return LiveSessionDoc(
            id = id, cameraId = cameraId, viewerId = getString("viewerId").orEmpty(),
            state = getString("state").orEmpty(), quality = getString("quality") ?: "MEDIUM",
            offer = desc("offer"), answer = desc("answer"),
            createdAtMs = getTimestamp("createdAt")?.toDate()?.time ?: System.currentTimeMillis(),
        )
    }

    // ---------------------------------------------------------------- viewer
    suspend fun createSession(cameraId: String, viewerId: String, quality: String): String {
        val ref = sessions(cameraId).document()
        ref.set(
            mapOf(
                "viewerId" to viewerId,
                "state" to SessionState.REQUESTED,
                "quality" to quality,
                "createdAt" to FieldValue.serverTimestamp(),
            )
        ).await()
        return ref.id
    }

    fun observeSession(cameraId: String, sessionId: String): Flow<LiveSessionDoc?> {
        if (!fb.isConfigured) return flowOf(null)
        return callbackFlow {
            val reg = sessions(cameraId).document(sessionId).addSnapshotListener { s, e ->
                if (e != null) android.util.Log.w("Laddu", "session listener failed", e)
                trySend(if (e != null || s == null) null else s.toSession(cameraId))
            }
            awaitClose { reg.remove() }
        }
    }

    suspend fun setAnswer(cameraId: String, sessionId: String, answer: SessionDescriptionDoc) {
        sessions(cameraId).document(sessionId).update(
            mapOf("answer" to mapOf("type" to answer.type, "sdp" to answer.sdp), "state" to SessionState.ANSWERED)
        ).await()
    }

    suspend fun setQuality(cameraId: String, sessionId: String, quality: String) {
        runCatching { sessions(cameraId).document(sessionId).update("quality", quality).awaitOrNull(5_000) }
    }

    // ---------------------------------------------------------------- camera
    /** Sessions that still need work. Includes the doc id so the camera can track each one. */
    fun observeActiveSessions(cameraId: String): Flow<List<LiveSessionDoc>> {
        if (!fb.isConfigured) return flowOf(emptyList())
        return callbackFlow {
            val reg = sessions(cameraId).addSnapshotListener { s, e ->
                if (e != null) { android.util.Log.w("Laddu", "signaling listener failed", e); close(e); return@addSnapshotListener }
                trySend(s?.documents?.mapNotNull { it.toSession(cameraId) }.orEmpty())
            }
            awaitClose { reg.remove() }
        }
    }

    suspend fun setOffer(cameraId: String, sessionId: String, offer: SessionDescriptionDoc) {
        sessions(cameraId).document(sessionId).update(
            mapOf("offer" to mapOf("type" to offer.type, "sdp" to offer.sdp), "state" to SessionState.OFFERED)
        ).await()
    }

    suspend fun setState(cameraId: String, sessionId: String, state: String) {
        runCatching { sessions(cameraId).document(sessionId).update("state", state).awaitOrNull(5_000) }
    }

    // ---------------------------------------------------------------- both
    private fun candidates(cameraId: String, sessionId: String, fromCamera: Boolean) =
        sessions(cameraId).document(sessionId).collection(if (fromCamera) Paths.CAMERA_CANDIDATES else Paths.VIEWER_CANDIDATES)

    suspend fun addCandidate(cameraId: String, sessionId: String, fromCamera: Boolean, c: IceCandidateDoc) {
        runCatching {
            candidates(cameraId, sessionId, fromCamera).add(
                mapOf("sdpMid" to c.sdpMid, "sdpMLineIndex" to c.sdpMLineIndex, "candidate" to c.candidate)
            ).awaitOrNull(10_000)
        }
    }

    /** Emits each remote ICE candidate once. [fromCamera] = true listens to the camera's candidates. */
    fun observeCandidates(cameraId: String, sessionId: String, fromCamera: Boolean): Flow<IceCandidateDoc> {
        if (!fb.isConfigured) return flowOf()
        return callbackFlow {
            val reg = candidates(cameraId, sessionId, fromCamera).addSnapshotListener { s, e ->
                if (e != null || s == null) return@addSnapshotListener
                for (ch in s.documentChanges) if (ch.type == DocumentChange.Type.ADDED) {
                    val d = ch.document
                    trySend(IceCandidateDoc(d.getString("sdpMid"), (d.getLong("sdpMLineIndex") ?: 0L).toInt(), d.getString("candidate").orEmpty()))
                }
            }
            awaitClose { reg.remove() }
        }
    }

    /** Mark ended and remove (candidates are cleaned by the hourly Cloud Function). */
    suspend fun endSession(cameraId: String, sessionId: String) {
        runCatching {
            sessions(cameraId).document(sessionId).update("state", SessionState.ENDED).awaitOrNull(5_000)
            sessions(cameraId).document(sessionId).delete().awaitOrNull(5_000)
        }
    }

    /** Camera housekeeping: drop sessions nobody finished. */
    suspend fun deleteStale(cameraId: String, olderThanMs: Long) {
        runCatching {
            val cutoff = Timestamp(Date(System.currentTimeMillis() - olderThanMs))
            val old = sessions(cameraId).whereLessThan("createdAt", cutoff).get().await()
            old.documents.forEach { it.reference.delete() }
        }
    }
}
