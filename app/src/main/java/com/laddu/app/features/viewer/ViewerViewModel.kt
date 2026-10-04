package com.laddu.app.features.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laddu.app.core.datastore.SettingsRepository
import com.laddu.app.core.firebase.AuthRepository
import com.laddu.app.core.firebase.AuthState
import com.laddu.app.core.firebase.DeviceRepository
import com.laddu.app.core.firebase.EventRepository
import com.laddu.app.core.firebase.FcmTokenManager
import com.laddu.app.core.notifications.DeepLink
import com.laddu.app.core.notifications.DeepLinkHolder
import com.laddu.app.core.model.CameraInfo
import com.laddu.app.core.model.UserProfile
import com.laddu.app.features.activity.ActivityStats
import com.laddu.app.features.activity.DayStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ViewerState(
    val loading: Boolean = true,
    val user: UserProfile? = null,
    val cameras: List<CameraInfo> = emptyList(),
    val selected: CameraInfo? = null,
    val nowMs: Long = System.currentTimeMillis(),
) {
    val online: Boolean get() = selected?.isOnline(nowMs) == true
    val isOwner: Boolean get() = selected != null && user != null && selected.ownerId == user.uid
}

/** Who is signed in, which cameras they can see, and which one is selected. Shared by all viewer tabs. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ViewerViewModel @Inject constructor(
    private val auth: AuthRepository,
    devices: DeviceRepository,
    private val settings: SettingsRepository,
    private val deviceRepo: DeviceRepository,
    private val deepLinks: DeepLinkHolder,
    private val fcm: FcmTokenManager,
) : ViewModel() {

    val link: StateFlow<DeepLink?> = deepLinks.link
    fun consumeLink() = deepLinks.consume()
    /** "VIEW LIVE" from an alert: tell the viewer shell to select the camera and open the Live tab. */
    fun requestLive(cameraId: String) = deepLinks.request(DeepLink(null, cameraId, true))

    init { viewModelScope.launch { runCatching { fcm.registerToken() } } } // token may have rotated

    private val clock = flow { while (true) { emit(System.currentTimeMillis()); delay(10_000) } }

    private val user: StateFlow<UserProfile?> = auth.authState
        .map { (it as? AuthState.SignedIn)?.user }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<ViewerState> = combine(
        user.flatMapLatest { u -> if (u == null) flowOf(null to emptyList()) else devices.observeAccessibleCameras(u.uid).map { u to it } },
        settings.selectedCameraId,
        clock,
    ) { (u, cams), selId, now ->
        ViewerState(
            loading = false, user = u, cameras = cams, nowMs = now,
            selected = cams.firstOrNull { it.cameraId == selId } ?: cams.firstOrNull(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ViewerState())

    fun select(id: String) { viewModelScope.launch { settings.setSelectedCamera(id) } }

    fun leaveCamera(cameraId: String) {
        val uid = user.value?.uid ?: return
        viewModelScope.launch { deviceRepo.leaveCamera(cameraId, uid); settings.setSelectedCamera(null) }
    }
}

/** "Today" numbers for the viewer home screen. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeStatsViewModel @Inject constructor(private val events: EventRepository) : ViewModel() {
    private val camera = MutableStateFlow<String?>(null)
    fun setCamera(id: String?) { camera.value = id }

    val today: StateFlow<DayStats?> = camera.flatMapLatest { id ->
        if (id == null) flowOf(null)
        else events.observeEvents(id, 500, ActivityStats.startOfDay(System.currentTimeMillis()))
            .map { ActivityStats.compute(it, System.currentTimeMillis(), 1).first() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
