package com.laddu.app.core.notifications

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** A notification tap, waiting to be consumed by the UI once the user is signed in as a viewer. */
data class DeepLink(val eventId: String?, val cameraId: String?, val openLive: Boolean)

@Singleton
class DeepLinkHolder @Inject constructor() {
    private val _link = MutableStateFlow<DeepLink?>(null)
    val link: StateFlow<DeepLink?> = _link

    fun handle(intent: Intent?) {
        intent ?: return
        val eventId = intent.getStringExtra(NotificationHelper.EXTRA_EVENT_ID)
        val cameraId = intent.getStringExtra(NotificationHelper.EXTRA_CAMERA_ID)
        if (eventId == null && cameraId == null) return
        _link.value = DeepLink(eventId?.takeIf { it.isNotBlank() }, cameraId, intent.getBooleanExtra(NotificationHelper.EXTRA_OPEN_LIVE, false))
        // consume so rotation / re-delivery does not re-trigger
        intent.removeExtra(NotificationHelper.EXTRA_EVENT_ID)
        intent.removeExtra(NotificationHelper.EXTRA_CAMERA_ID)
        intent.removeExtra(NotificationHelper.EXTRA_OPEN_LIVE)
    }

    fun request(link: DeepLink) { _link.value = link }
    fun consume() { _link.value = null }
}
