package com.laddu.app.services

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.laddu.app.core.ai.BoundingBox
import com.laddu.app.core.ai.ModelStatus
import com.laddu.app.core.datastore.SettingsRepository
import com.laddu.app.core.model.CameraStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the camera dashboard shows, published by the monitoring service. */
data class MonitorUi(
    val running: Boolean = false,
    val starting: Boolean = false,
    val error: String? = null,
    val warning: String? = null,
    val status: CameraStatus = CameraStatus(),
    val internet: Boolean = true,
    val cameraActive: Boolean = false,
    val micActive: Boolean = false,
    val resolution: String = "",
    val liveViewers: Int = 0,
    val pendingSync: Int = 0,
    val dogBox: BoundingBox? = null,
    val inferenceMs: Long = 0,
    val dogModel: ModelStatus = ModelStatus.NOT_LOADED,
    val audioModel: ModelStatus = ModelStatus.NOT_LOADED,
    val lastEvent: String? = null,
)

/** Process-wide bridge between the service (writer) and the UI (reader). */
@Singleton
class MonitoringStateHolder @Inject constructor() {
    private val _ui = MutableStateFlow(MonitorUi())
    val ui: StateFlow<MonitorUi> = _ui
    fun update(f: (MonitorUi) -> MonitorUi) = _ui.update(f)
    fun reset() { _ui.value = MonitorUi() }
}

/** UI-side entry point for starting / stopping the foreground service. */
@Singleton
class MonitoringController @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val settings: SettingsRepository,
) {
    suspend fun start() {
        settings.setMonitoringDesired(true)
        ContextCompat.startForegroundService(ctx, Intent(ctx, CameraMonitoringService::class.java).setAction(CameraMonitoringService.ACTION_START))
    }

    suspend fun stop() {
        settings.setMonitoringDesired(false)
        ctx.startService(Intent(ctx, CameraMonitoringService::class.java).setAction(CameraMonitoringService.ACTION_STOP))
    }

    /** Re-initialise camera, audio and AI without leaving monitoring (used by "restart" from a viewer). */
    fun restart() {
        ContextCompat.startForegroundService(ctx, Intent(ctx, CameraMonitoringService::class.java).setAction(CameraMonitoringService.ACTION_RESTART))
    }
}
