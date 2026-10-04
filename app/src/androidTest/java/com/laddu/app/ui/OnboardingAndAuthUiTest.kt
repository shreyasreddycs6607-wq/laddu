package com.laddu.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.laddu.app.core.model.AppMode
import com.laddu.app.core.ui.theme.LadduTheme
import com.laddu.app.features.authentication.AuthContent
import com.laddu.app.features.authentication.AuthMode
import com.laddu.app.features.authentication.AuthUiState
import com.laddu.app.features.authentication.FirebaseSetupContent
import com.laddu.app.features.onboarding.ModeSelectContent
import com.laddu.app.features.onboarding.SplashContent
import com.laddu.app.features.onboarding.WelcomeContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OnboardingAndAuthUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun splash_shows_branding() {
        rule.setContent { LadduTheme { SplashContent() } }
        rule.onNodeWithTag("splash").assertIsDisplayed()
        rule.onNodeWithText("Laddu").assertIsDisplayed()
    }

    @Test fun welcome_get_started_navigates() {
        var started = false
        rule.setContent { LadduTheme { WelcomeContent(onGetStarted = { started = true }) } }
        rule.onNodeWithText("Welcome to Laddu").assertIsDisplayed()
        rule.onNodeWithTag("get_started").performClick()
        assertTrue(started)
    }

    @Test fun role_selection_offers_camera_and_viewer_and_reports_choice() {
        var picked: AppMode? = null
        rule.setContent { LadduTheme { ModeSelectContent(selected = null, onSelect = { picked = it }) } }
        rule.onNodeWithText("Camera Mode", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Viewer Mode", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("mode_camera").performClick()
        assertEquals(AppMode.CAMERA, picked)
        rule.onNodeWithTag("mode_viewer").performClick()
        assertEquals(AppMode.VIEWER, picked)
    }

    @Test fun login_form_submits_entered_values() {
        var email = ""; var pw = ""
        rule.setContent { LadduTheme { AuthContent(AuthMode.LOGIN, AuthUiState(), { _, e, p -> email = e; pw = p }, {}) } }
        rule.onNodeWithTag("field_email").performTextInput("dog@laddu.app")
        rule.onNodeWithTag("field_password").performTextInput("secret1")
        rule.onNodeWithTag("auth_submit").performClick()
        assertEquals("dog@laddu.app", email); assertEquals("secret1", pw)
    }

    @Test fun auth_errors_and_validation_messages_are_visible() {
        rule.setContent {
            LadduTheme { AuthContent(AuthMode.LOGIN, AuthUiState(error = "Email or password is incorrect.", emailError = "Enter a valid email address"), { _, _, _ -> }, {}) }
        }
        rule.onNodeWithTag("auth_error").assertIsDisplayed()
        rule.onNodeWithText("Enter a valid email address").assertIsDisplayed()
    }

    @Test fun signup_and_reset_variants_render_their_fields() {
        rule.setContent { LadduTheme { AuthContent(AuthMode.SIGN_UP, AuthUiState(), { _, _, _ -> }, {}) } }
        rule.onNodeWithTag("field_name").assertIsDisplayed()
        rule.onNodeWithText("Sign up").assertIsDisplayed()
    }

    @Test fun firebase_failure_state_explains_setup_and_allows_local_camera() {
        var local = false
        rule.setContent { LadduTheme { FirebaseSetupContent(canContinueLocally = true, onContinueLocally = { local = true }, onChangeMode = {}) } }
        rule.onNodeWithText("Firebase is not set up yet").assertIsDisplayed()
        rule.onNodeWithTag("continue_local").performClick()
        assertTrue(local)
    }

    @Test fun viewer_cannot_continue_without_firebase() {
        rule.setContent { LadduTheme { FirebaseSetupContent(canContinueLocally = false, onContinueLocally = {}, onChangeMode = {}) } }
        rule.onNodeWithTag("continue_local").assertDoesNotExistCompat()
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistCompat() = assertDoesNotExist()
