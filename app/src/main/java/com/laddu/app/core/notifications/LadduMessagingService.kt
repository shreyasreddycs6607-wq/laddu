package com.laddu.app.core.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.laddu.app.core.datastore.SettingsRepository
import com.laddu.app.core.firebase.FcmTokenManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onNewToken(token: String) {
        scope.launch { runCatching { tokens.registerToken(token) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val payload = AlertPayload.fromData(message.data) ?: return
        scope.launch {
            val prefs = settings.notificationPrefs.first()
            if (policy.shouldShow(payload, prefs, System.currentTimeMillis())) helper.showAlert(payload)
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
