package com.laddu.app.core.firebase

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.storage.FirebaseStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lazy access to Firebase. Laddu builds and runs without `google-services.json`; in that case
 * [isConfigured] is false and the app shows the setup screen instead of crashing.
 */
@Singleton
class FirebaseProvider @Inject constructor(@ApplicationContext private val ctx: Context) {

    val isConfigured: Boolean by lazy {
        runCatching { FirebaseApp.getApps(ctx).isNotEmpty() || FirebaseApp.initializeApp(ctx) != null }
            .getOrDefault(false)
    }

    private fun <T> guarded(block: () -> T): T {
        check(isConfigured) { "Firebase is not configured (missing google-services.json)" }
        return block()
    }

    val auth: FirebaseAuth get() = guarded { FirebaseAuth.getInstance() }
    val firestore: FirebaseFirestore get() = guarded { FirebaseFirestore.getInstance() }
    val messaging: FirebaseMessaging get() = guarded { FirebaseMessaging.getInstance() }
    val storage: FirebaseStorage get() = guarded { FirebaseStorage.getInstance() }
    val functions: FirebaseFunctions get() = guarded { FirebaseFunctions.getInstance() }

    val currentUid: String? get() = if (isConfigured) auth.currentUser?.uid else null
}

/** Firestore collection / field names in one place. */
object Paths {
    const val USERS = "users"
    const val DEVICES = "devices"
    const val DEVICE_USERS = "deviceUsers"
    const val EVENTS = "events"
    const val SETTINGS = "settings"
    const val PAIRING = "pairingSessions"
    const val NOTIFICATION_PREFS = "notificationPreferences"
    const val LIVE_SESSIONS = "liveSessions"
    const val CAMERA_CANDIDATES = "cameraCandidates"
    const val VIEWER_CANDIDATES = "viewerCandidates"

    fun deviceUserId(cameraId: String, uid: String) = "${cameraId}_$uid"
    fun clipPath(cameraId: String, eventId: String) = "clips/$cameraId/$eventId.mp4"
    fun snapshotPath(cameraId: String, eventId: String) = "clips/$cameraId/$eventId.jpg"
}

/** Network calls that may block forever while offline are bounded. Returns null on timeout. */
suspend fun <T> Task<T>.awaitOrNull(timeoutMs: Long = 15_000): T? = withTimeoutOrNull(timeoutMs) { await() }
