package com.laddu.app.features.authentication

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.laddu.app.core.ui.components.ErrorBanner
import com.laddu.app.core.ui.components.InfoCard
import com.laddu.app.core.ui.components.LadduLogo

enum class AuthMode { LOGIN, SIGN_UP, RESET }

@Composable
fun AuthContent(
    mode: AuthMode,
    state: AuthUiState,
    onSubmit: (name: String, email: String, password: String) -> Unit,
    onSwitchMode: (AuthMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp)
            .testTag("auth_${mode.name.lowercase()}"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LadduLogo(88.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            when (mode) { AuthMode.LOGIN -> "Welcome back"; AuthMode.SIGN_UP -> "Create your account"; AuthMode.RESET -> "Reset password" },
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            when (mode) {
                AuthMode.LOGIN -> "Log in to see your dog."
                AuthMode.SIGN_UP -> "It takes a minute. Use the same account on both phones."
                AuthMode.RESET -> "We will email you a link to choose a new password."
            },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(24.dp))

        state.error?.let { ErrorBanner(it, Modifier.testTag("auth_error")); Spacer(Modifier.height(12.dp)) }
        state.info?.let { InfoCard(Modifier.testTag("auth_info")) { Text(it) }; Spacer(Modifier.height(12.dp)) }

        if (mode == AuthMode.SIGN_UP) {
            OutlinedTextField(
                name, { name = it }, label = { Text("Your name") }, singleLine = true,
                leadingIcon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Person, null) },
                isError = state.nameError != null, supportingText = state.nameError?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth().testTag("field_name"),
            )
        }
        OutlinedTextField(
            email, { email = it }, label = { Text("Email") }, singleLine = true,
            leadingIcon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Email, null) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            isError = state.emailError != null, supportingText = state.emailError?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth().testTag("field_email"),
        )
        if (mode != AuthMode.RESET) {
            OutlinedTextField(
                password, { password = it }, label = { Text("Password") }, singleLine = true,
                leadingIcon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Lock, null) },
                trailingIcon = {
                    androidx.compose.material3.IconButton({ showPassword = !showPassword }) {
                        androidx.compose.material3.Icon(
                            if (showPassword) androidx.compose.material.icons.Icons.Filled.VisibilityOff else androidx.compose.material.icons.Icons.Filled.Visibility,
                            if (showPassword) "Hide password" else "Show password",
                        )
                    }
                },
                visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = state.passwordError != null, supportingText = state.passwordError?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth().testTag("field_password"),
            )
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onSubmit(name, email, password) },
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth().height(56.dp).testTag("auth_submit"),
        ) {
            if (state.loading) CircularProgressIndicator(Modifier.height(22.dp), strokeWidth = 2.dp)
            else Text(when (mode) { AuthMode.LOGIN -> "Log in"; AuthMode.SIGN_UP -> "Sign up"; AuthMode.RESET -> "Send reset email" })
        }
        Spacer(Modifier.height(8.dp))
        when (mode) {
            AuthMode.LOGIN -> {
                TextButton({ onSwitchMode(AuthMode.RESET) }, Modifier.testTag("to_reset")) { Text("Forgot password?") }
                OutlinedButton({ onSwitchMode(AuthMode.SIGN_UP) }, Modifier.fillMaxWidth().testTag("to_signup")) { Text("Create an account") }
            }
            AuthMode.SIGN_UP -> TextButton({ onSwitchMode(AuthMode.LOGIN) }, Modifier.testTag("to_login")) { Text("I already have an account") }
            AuthMode.RESET -> TextButton({ onSwitchMode(AuthMode.LOGIN) }, Modifier.testTag("to_login")) { Text("Back to log in") }
        }
    }
}

/** Shown when the developer has not added google-services.json yet. */
@Composable
fun FirebaseSetupContent(
    canContinueLocally: Boolean,
    onContinueLocally: () -> Unit,
    onChangeMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp).testTag("firebase_setup"),
        verticalArrangement = Arrangement.Center,
    ) {
        LadduLogo(80.dp)
        Spacer(Modifier.height(16.dp))
        Text("Firebase is not set up yet", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        InfoCard {
            Text(
                "Laddu uses Firebase for sign-in, pairing and alerts. To enable it:\n\n" +
                    "1. Create a Firebase project and add an Android app with package com.laddu.app\n" +
                    "2. Download google-services.json into the app/ folder\n" +
                    "3. Rebuild the app\n\n" +
                    "Full steps are in docs/FIREBASE_SETUP.md.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(20.dp))
        if (canContinueLocally) {
            Button(onContinueLocally, Modifier.fillMaxWidth().height(52.dp).testTag("continue_local")) {
                Text("Continue offline (local monitoring only)")
            }
            Spacer(Modifier.height(8.dp))
        }
        OutlinedButton(onChangeMode, Modifier.fillMaxWidth()) { Text("Change mode") }
    }
}
