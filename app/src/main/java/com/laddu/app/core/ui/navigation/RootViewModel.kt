package com.laddu.app.core.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laddu.app.core.datastore.SettingsRepository
import com.laddu.app.core.firebase.AuthRepository
import com.laddu.app.core.firebase.AuthState
import com.laddu.app.core.model.AppMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Pure routing decision: where should the app be, given who/what the user is? null = still loading. */
fun resolveRoute(mode: AppMode?, auth: AuthState, localOnly: Boolean): String? = when {
    mode == null -> Routes.WELCOME
    auth is AuthState.Unknown -> null
    auth is AuthState.NotConfigured ->
        if (mode == AppMode.CAMERA && localOnly) Routes.CAMERA_HOME else Routes.FIREBASE_SETUP
    auth is AuthState.SignedOut -> Routes.LOGIN
    mode == AppMode.CAMERA -> Routes.CAMERA_HOME
    else -> Routes.VIEWER
}

@HiltViewModel
class RootViewModel @Inject constructor(
    private val settings: SettingsRepository,
    auth: AuthRepository,
) : ViewModel() {

    val route: StateFlow<String?> = combine(settings.appMode, auth.authState, settings.localOnly, ::resolveRoute)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun continueLocally() {
        viewModelScope.launch { settings.setLocalOnly(true) }
    }

    fun changeMode() {
        viewModelScope.launch { settings.clearAppMode() }
    }
}
