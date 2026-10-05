package com.laddu.app.core.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.laddu.app.core.datastore.SettingsRepository
import com.laddu.app.core.firebase.FcmTokenManager
import dagger.hilt.android.AndroidEntryPoint
import com.laddu.app.core.model.AppMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Receives FCM *data* messages (works in foreground and background) and turns them into
 * notifications that obey the viewer's own preferences.
 */
@AndroidEntryPoint
class LadduMessagingService : FirebaseMessagingService() {
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var tokens: FcmTokenManager
    @Inject lateinit var helper: NotificationHelper
    @Inject lateinit var policy: NotificationPolicy

    // Both callbacks already run on FCM's background executor and the service stops as soon as they return,
    // so the work must finish inside them (a launched coroutine would be cancelled in onDestroy).
    override fun onNewToken(token: String) {
        runBlocking { withTimeoutOrNull(10_000) { runCatching { tokens.registerToken(token) } } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val payload = AlertPayload.fromData(message.data) ?: return
        runBlocking {
            withTimeoutOrNull(8_000) {
                if (settings.appMode.first() == AppMode.CAMERA) return@withTimeoutOrNull // the camera phone is not an alert viewer
                val prefs = settings.notificationPrefs.first()
                if (policy.shouldShow(payload, prefs, System.currentTimeMillis())) helper.showAlert(payload)
            }
        }
    }
}
