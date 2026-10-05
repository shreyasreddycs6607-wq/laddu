package com.laddu.app.core.firebase

import com.google.firebase.firestore.Query
import com.laddu.app.core.model.LadduEvent
import com.laddu.app.core.sync.EventRemote
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

const val LOCAL_OWNER = "local"

/** Camera side: uploads events written to Room. */
@Singleton
class FirestoreEventRemote @Inject constructor(private val fb: FirebaseProvider) : EventRemote {
    override suspend fun upload(event: LadduEvent): Boolean {
        if (event.ownerId == LOCAL_OWNER) return true // local-only mode: nothing to upload
        if (!fb.isConfigured) return false
        val uid = fb.currentUid ?: return false // signed out: retry after sign-in
        if (uid != event.ownerId) return true // another account's event can never upload from here; do not block the queue
        return runCatching {
            fb.firestore.collection(Paths.EVENTS).document(event.eventId).set(event.toMap()).awaitDone(20_000)
        }.getOrDefault(false)
    }
}

/** Viewer side: live event queries (Firestore's offline cache makes them work without network). */
@Singleton
class EventRepository @Inject constructor(private val fb: FirebaseProvider) {

    fun observeEvents(cameraId: String, limit: Long = 300, sinceMs: Long? = null): Flow<List<LadduEvent>> {
        if (!fb.isConfigured) return flowOf(emptyList())
        return callbackFlow {
            var q: Query = fb.firestore.collection(Paths.EVENTS).whereEqualTo("cameraId", cameraId)
            if (sinceMs != null) q = q.whereGreaterThanOrEqualTo("timestamp", sinceMs)
            val reg = q.orderBy("timestamp", Query.Direction.DESCENDING).limit(limit)
                .addSnapshotListener { snap, err ->
                    if (err != null) { trySend(emptyList()); return@addSnapshotListener }
                    trySend(snap?.documents?.mapNotNull { it.toEvent() }.orEmpty())
                }
            awaitClose { reg.remove() }
        }
    }

    fun observeEvent(eventId: String): Flow<LadduEvent?> {
        if (!fb.isConfigured) return flowOf(null)
        return callbackFlow {
            val reg = fb.firestore.collection(Paths.EVENTS).document(eventId).addSnapshotListener { snap, err ->
                trySend(if (err != null) null else snap?.toEvent())
            }
            awaitClose { reg.remove() }
        }
    }
}
