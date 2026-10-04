package com.laddu.app.features.authentication

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laddu.app.core.firebase.AuthRepository
import com.laddu.app.core.firebase.authErrorMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

object AuthValidator {
    private val EMAIL = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    fun email(v: String): String? = if (EMAIL.matches(v.trim())) null else "Enter a valid email address"
    fun password(v: String): String? = if (v.length >= 6) null else "Password must be at least 6 characters"
    fun name(v: String): String? = if (v.trim().length >= 2) null else "Enter your name"
}

data class AuthUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    val success: Boolean = false,
    val emailError: String? = null,
    val passwordError: String? = null,
    val nameError: String? = null,
)

@HiltViewModel
class AuthViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state

    fun clear() = _state.update { AuthUiState() }

    fun signIn(email: String, password: String) {
        val e = AuthValidator.email(email); val p = AuthValidator.password(password)
        if (e != null || p != null) { _state.value = AuthUiState(emailError = e, passwordError = p); return }
        run { auth.signIn(email, password) }
    }

    fun signUp(name: String, email: String, password: String) {
        val n = AuthValidator.name(name); val e = AuthValidator.email(email); val p = AuthValidator.password(password)
        if (n != null || e != null || p != null) {
            _state.value = AuthUiState(nameError = n, emailError = e, passwordError = p); return
        }
        run { auth.signUp(name, email, password) }
    }

    fun reset(email: String) {
        val e = AuthValidator.email(email)
        if (e != null) { _state.value = AuthUiState(emailError = e); return }
        viewModelScope.launch {
            _state.value = AuthUiState(loading = true)
            auth.resetPassword(email)
                .onSuccess { _state.value = AuthUiState(info = "Password reset email sent. Check your inbox.") }
                .onFailure { _state.value = AuthUiState(error = authErrorMessage(it)) }
        }
    }

    private fun run(block: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            _state.value = AuthUiState(loading = true)
            block()
                .onSuccess { _state.value = AuthUiState(success = true) }
                .onFailure { _state.value = AuthUiState(error = authErrorMessage(it)) }
        }
    }
}
