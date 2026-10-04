package com.laddu.app.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.laddu.app.core.datastore.SettingsRepository
import com.laddu.app.core.notifications.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * After a reboot Android does NOT allow starting a camera/microphone foreground service from the
 * background. So we do not try: if monitoring was on, we post a notification and the user taps it
 * to resume (one tap, then it runs again 24/7).
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var notifier: NotificationHelper

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (settings.monitoringDesired.first()) notifier.showResumeNeeded()
            } finally {
                pending.finish()
            }
        }
    }
}
