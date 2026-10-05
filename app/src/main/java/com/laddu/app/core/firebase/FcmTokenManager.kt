package com.laddu.app.core.firebase

import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps `users/{uid}.fcmTokens` in sync so Cloud Functions know where to push alerts. */
@Singleton
class FcmTokenManager @Inject constructor(private val fb: FirebaseProvider) {

    suspend fun registerToken(token: String? = null) {
        if (!fb.isConfigured) return
        val uid = fb.currentUid ?: return
        val t = token ?: fb.messaging.token.await()
        fb.firestore.collection(Paths.USERS).document(uid)
            .set(mapOf("fcmTokens" to FieldValue.arrayUnion(t)), com.google.firebase.firestore.SetOptions.merge())
            .awaitOrNull()
    }

    suspend fun unregisterToken() {
        if (!fb.isConfigured) return
        val uid = fb.currentUid ?: return
        val t = fb.messaging.token.awaitOrNull() ?: return
        fb.firestore.collection(Paths.USERS).document(uid)
            .update("fcmTokens", FieldValue.arrayRemove(t)).awaitOrNull()
        // The write may only be queued while offline and is dropped on sign-out; invalidating the token stops the
        // old account's pushes for certain (the next sign-in gets a fresh token).
        runCatching { fb.messaging.deleteToken().awaitOrNull() }
    }
}
