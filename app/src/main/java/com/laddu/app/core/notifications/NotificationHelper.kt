package com.laddu.app.core.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.laddu.app.MainActivity
import com.laddu.app.R
import com.laddu.app.core.model.EventType
import com.laddu.app.services.CameraMonitoringService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

object Channels {
    const val MONITORING = "monitoring"
    const val BARK = "alerts_bark"
    const val MOVEMENT = "alerts_movement"
    const val DOG = "alerts_dog"
    const val SYSTEM = "alerts_system"

    fun forType(t: EventType) = when (t) {
        EventType.BARK, EventType.REPEATED_BARK, EventType.HOWL -> BARK
        EventType.MOVEMENT -> MOVEMENT
        EventType.DOG_PRESENCE -> DOG
        else -> SYSTEM
    }
}

@Singleton
class NotificationHelper @Inject constructor(@ApplicationContext private val ctx: Context) {

    companion object {
        const val EXTRA_EVENT_ID = "laddu.eventId"
        const val EXTRA_CAMERA_ID = "laddu.cameraId"
        const val EXTRA_OPEN_LIVE = "laddu.openLive"
        const val ID_MONITORING = 1001
        const val ID_RESUME = 1002
        private const val ID_ALERT_BASE = 2000
    }

    init { createChannels() }

    private fun createChannels() {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        fun ch(id: String, name: Int, desc: Int?, imp: Int) = NotificationChannel(id, ctx.getString(name), imp).apply {
            if (desc != null) description = ctx.getString(desc)
            if (id == Channels.MONITORING) { setShowBadge(false) }
        }
        nm.createNotificationChannels(
            listOf(
                ch(Channels.MONITORING, R.string.channel_monitoring, R.string.channel_monitoring_desc, NotificationManager.IMPORTANCE_LOW),
                ch(Channels.BARK, R.string.channel_bark, null, NotificationManager.IMPORTANCE_HIGH),
                ch(Channels.MOVEMENT, R.string.channel_movement, null, NotificationManager.IMPORTANCE_DEFAULT),
                ch(Channels.DOG, R.string.channel_dog, null, NotificationManager.IMPORTANCE_DEFAULT),
                ch(Channels.SYSTEM, R.string.channel_system, null, NotificationManager.IMPORTANCE_HIGH),
            )
        )
    }

    private fun openApp(extras: Intent.() -> Unit = {}, requestCode: Int = 0): PendingIntent =
        PendingIntent.getActivity(
            ctx, requestCode,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).apply(extras),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** The persistent notification required for the camera/microphone foreground service. */
    fun monitoringNotification(text: String = ctx.getString(R.string.monitoring_text)): Notification {
        val stop = PendingIntent.getService(
            ctx, 1, Intent(ctx, CameraMonitoringService::class.java).setAction(CameraMonitoringService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(ctx, Channels.MONITORING)
            .setSmallIcon(R.drawable.ic_laddu_paw)
            .setContentTitle(ctx.getString(R.string.monitoring_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp())
            .addAction(0, ctx.getString(R.string.action_open), openApp(requestCode = 2))
            .addAction(0, ctx.getString(R.string.action_stop), stop)
            .build()
    }

    fun updateMonitoring(text: String) {
        if (canPost()) NotificationManagerCompat.from(ctx).notify(ID_MONITORING, monitoringNotification(text))
    }

    /** Shown after reboot / process death: Android does not allow a silent camera restart. */
    fun showResumeNeeded() {
        if (!canPost()) return
        val n = NotificationCompat.Builder(ctx, Channels.SYSTEM)
            .setSmallIcon(R.drawable.ic_laddu_paw)
            .setContentTitle("Laddu stopped monitoring")
            .setContentText("Tap to resume watching your dog")
            .setAutoCancel(true)
            .setContentIntent(openApp({ putExtra(EXTRA_OPEN_LIVE, false) }, 3))
            .build()
        NotificationManagerCompat.from(ctx).notify(ID_RESUME, n)
    }

    fun clearResumeNeeded() = NotificationManagerCompat.from(ctx).cancel(ID_RESUME)

    /** Viewer-side alert. Tapping opens the event details; the action jumps straight to live. */
    fun showAlert(p: AlertPayload) {
        if (!canPost()) return
        val id = ID_ALERT_BASE + (p.eventId.hashCode() and 0xFFFF)
        val details = openApp({ putExtra(EXTRA_EVENT_ID, p.eventId); putExtra(EXTRA_CAMERA_ID, p.cameraId) }, id)
        val live = openApp({ putExtra(EXTRA_EVENT_ID, p.eventId); putExtra(EXTRA_CAMERA_ID, p.cameraId); putExtra(EXTRA_OPEN_LIVE, true) }, id + 70000)
        val n = NotificationCompat.Builder(ctx, Channels.forType(p.type))
            .setSmallIcon(R.drawable.ic_laddu_paw)
            .setContentTitle(p.title)
            .setContentText(p.body)
            .setSubText(p.cameraName)
            .setStyle(NotificationCompat.BigTextStyle().bigText(p.body))
            .setWhen(p.timestampMs)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(details)
            .addAction(0, "VIEW LIVE", live)
            .build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
