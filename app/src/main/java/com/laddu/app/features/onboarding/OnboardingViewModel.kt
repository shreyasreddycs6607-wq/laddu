package com.laddu.app.features.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laddu.app.core.datastore.SettingsRepository
import com.laddu.app.core.model.AppMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    val mode: StateFlow<AppMode?> = settings.appMode.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun selectMode(mode: AppMode) {
        viewModelScope.launch { settings.setAppMode(mode) }
    }
}
