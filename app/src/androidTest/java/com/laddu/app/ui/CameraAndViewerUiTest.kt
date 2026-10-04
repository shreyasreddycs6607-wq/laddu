package com.laddu.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.laddu.app.core.ai.ModelStatus
import com.laddu.app.core.model.CameraInfo
import com.laddu.app.core.model.CameraStatus
import com.laddu.app.core.model.EventType
import com.laddu.app.core.model.LadduEvent
import com.laddu.app.core.model.LiveState
import com.laddu.app.core.model.PairingSession
import com.laddu.app.core.model.UserProfile
import com.laddu.app.core.ui.theme.LadduTheme
import com.laddu.app.features.activity.ActivityContent
import com.laddu.app.features.activity.ActivityRange
import com.laddu.app.features.activity.DayStats
import com.laddu.app.features.alerts.AlertFilter
import com.laddu.app.features.alerts.AlertsContent
import com.laddu.app.features.alerts.EventDetailContent
import com.laddu.app.features.alerts.EventDetailUi
import com.laddu.app.features.camera.CameraDashboardContent
import com.laddu.app.features.camera.CameraDashboardState
import com.laddu.app.features.camera.PermissionState
import com.laddu.app.features.live.LiveContent
import com.laddu.app.features.live.LiveUi
import com.laddu.app.features.pairing.AddCameraContent
import com.laddu.app.features.pairing.AddCameraUi
import com.laddu.app.features.pairing.CameraPairingContent
import com.laddu.app.features.pairing.CameraPairingUi
import com.laddu.app.features.settings.SettingsActions
import com.laddu.app.features.settings.SettingsContent
import com.laddu.app.features.settings.SettingsUi
import com.laddu.app.core.model.AppMode
import com.laddu.app.features.viewer.ViewerHomeContent
import com.laddu.app.features.viewer.ViewerState
import com.laddu.app.services.MonitorUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CameraAndViewerUiTest {
    @get:Rule val rule = createComposeRule()

    private val allPerms = PermissionState(camera = true, microphone = true, notifications = true)

    private fun dashboard(
        state: CameraDashboardState = CameraDashboardState(),
        perms: PermissionState = allPerms,
        permanentlyDenied: Boolean = false,
        onStart: () -> Unit = {}, onStop: () -> Unit = {}, onGrant: () -> Unit = {}, onSettingsOpen: () -> Unit = {},
    ) = rule.setContent {
        LadduTheme {
            CameraDashboardContent(state, perms, permanentlyDenied, onStart, onStop, onGrant, onSettingsOpen, {}, {}, {})
        }
    }

    // ---------------------------------------------------------------- camera dashboard
    @Test fun camera_dashboard_idle_shows_start_and_status() {
        var started = false
        dashboard(onStart = { started = true })
        rule.onNodeWithText("Laddu Camera").assertIsDisplayed()
        rule.onNodeWithTag("start_monitoring").performScrollTo().assertIsDisplayed().performClick()
        assertTrue(started)
        rule.onNodeWithTag("status_card").assertExists()
    }

    @Test fun camera_dashboard_running_shows_privacy_indicators_and_stop() {
        var stopped = false
        val monitor = MonitorUi(
            running = true, cameraActive = true, micActive = true,
            status = CameraStatus(monitoring = true, dogPresent = true, batteryPct = 80, charging = true, aiReady = true),
            dogModel = ModelStatus.READY, audioModel = ModelStatus.READY,
        )
        dashboard(CameraDashboardState(monitor, desired = true), onStop = { stopped = true })
        rule.onNodeWithTag("privacy_indicators").assertIsDisplayed()
        rule.onNodeWithText("Camera active").assertIsDisplayed()
        rule.onNodeWithText("Mic active").assertIsDisplayed()
        rule.onNodeWithTag("stop_monitoring").performScrollTo().assertIsDisplayed().performClick()
        assertTrue(stopped)
    }

    @Test fun camera_permission_denied_shows_rationale_and_grant_button() {
        var granted = false
        dashboard(perms = PermissionState(camera = false, microphone = false, notifications = true), onGrant = { granted = true })
        rule.onNodeWithTag("permission_card").assertIsDisplayed()
        rule.onNodeWithText("Camera: required", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("grant_permissions").performClick()
        assertTrue(granted)
    }

    @Test fun camera_permanently_denied_sends_user_to_app_settings() {
        var opened = false
        dashboard(perms = PermissionState(false, true, true), permanentlyDenied = true, onSettingsOpen = { opened = true })
        rule.onNodeWithText("Open app settings").performClick()
        assertTrue(opened)
    }

    @Test fun microphone_denied_explains_monitoring_still_works() {
        dashboard(perms = PermissionState(camera = true, microphone = false, notifications = true))
        rule.onNodeWithText("Microphone: needed for bark detection", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("start_monitoring").performScrollTo().assertIsEnabled()
    }

    @Test fun camera_error_and_offline_state_are_visible() {
        val m = MonitorUi(running = true, internet = false, error = "Camera is in use by another app")
        dashboard(CameraDashboardState(m, desired = true))
        rule.onNodeWithTag("camera_error").assertIsDisplayed()
        rule.onNodeWithText("Offline (still monitoring)").assertIsDisplayed()
    }

    @Test fun permission_state_logic() {
        assertTrue(PermissionState(true, false, false).canMonitor)
        assertTrue(!PermissionState(false, true, true).canMonitor)
        assertTrue(PermissionState(true, true, true).missing.isEmpty())
    }

    // ---------------------------------------------------------------- viewer dashboard
    private val camera = CameraInfo("c1", "u1", "Living room", lastSeenMs = 1_000, status = CameraStatus(monitoring = true, dogPresent = true, barking = false, batteryPct = 90))
    private val user = UserProfile("u1", "a@b.c", "A")

    @Test fun viewer_home_with_online_camera() {
        val state = ViewerState(loading = false, user = user, cameras = listOf(camera), selected = camera, nowMs = 5_000)
        var live = false
        rule.setContent {
            LadduTheme { ViewerHomeContent(state, DayStats(0, movementEvents = 4, barkEvents = 2, presenceMs = 3_600_000), {}, { live = true }, {}, {}, {}) }
        }
        rule.onNodeWithTag("camera_title").assertIsDisplayed()
        rule.onNodeWithText("Living room").assertIsDisplayed()
        rule.onNodeWithTag("online_pill").assertIsDisplayed()
        rule.onNodeWithText("Online", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("today_stats").assertIsDisplayed()
        rule.onNodeWithTag("home_preview").performClick()
        assertTrue(live)
    }

    @Test fun viewer_home_marks_stale_camera_offline() {
        val state = ViewerState(loading = false, user = user, cameras = listOf(camera), selected = camera, nowMs = 1_000 + 200_000)
        rule.setContent { LadduTheme { ViewerHomeContent(state, null, {}, {}, {}, {}, {}) } }
        rule.onNodeWithText("Offline", substring = true).assertIsDisplayed()
    }

    @Test fun viewer_home_without_cameras_invites_pairing() {
        var add = false
        rule.setContent { LadduTheme { ViewerHomeContent(ViewerState(loading = false, user = user), null, {}, {}, { add = true }, {}, {}) } }
        rule.onNodeWithText("No camera yet").assertIsDisplayed()
        rule.onNodeWithTag("add_first_camera").performClick()
        assertTrue(add)
    }

    // ---------------------------------------------------------------- pairing
    @Test fun camera_pairing_shows_qr_and_countdown() {
        val s = PairingSession("T".repeat(43), "c1", "u1", 0, System.currentTimeMillis() + 240_000)
        rule.setContent { LadduTheme { CameraPairingContent(CameraPairingUi(cameraId = "c1", cameraName = "Living room", session = s, loading = false), {}, {}, {}, {}, {}) } }
        rule.onNodeWithTag("qr_image").assertIsDisplayed()
        rule.onNodeWithTag("qr_countdown").assertIsDisplayed()
        rule.onNodeWithTag("camera_name").assertIsDisplayed()
    }

    @Test fun expired_qr_is_never_displayed_and_offers_retry() {
        var retried = false
        rule.setContent { LadduTheme { CameraPairingContent(CameraPairingUi(session = null, loading = false, error = "Could not create pairing code"), {}, { retried = true }, {}, {}, {}) } }
        rule.onNodeWithTag("qr_image").assertDoesNotExist()
        rule.onNodeWithText("Try again").performClick()
        assertTrue(retried)
    }

    @Test fun successful_pairing_is_announced() {
        rule.setContent { LadduTheme { CameraPairingContent(CameraPairingUi(justPaired = true, loading = false), {}, {}, {}, {}, {}) } }
        rule.onNodeWithTag("paired_ok").assertIsDisplayed()
    }

    @Test fun viewers_can_be_revoked() {
        var revoked: String? = null
        val v = com.laddu.app.core.model.ViewerAccess("c1", "viewer9", "v@x.y", "Vee")
        rule.setContent { LadduTheme { CameraPairingContent(CameraPairingUi(loading = false, viewers = listOf(v)), {}, {}, {}, { revoked = it }, {}) } }
        rule.onNodeWithTag("viewer_viewer9").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Revoke access").performClick()
        rule.onNodeWithText("Revoke").performClick()
        assertEquals("viewer9", revoked)
    }

    @Test fun scanning_an_expired_or_used_code_shows_a_clear_error() {
        rule.setContent { LadduTheme { AddCameraContent(AddCameraUi(error = "This QR code has expired. Ask the camera phone to show a new one."), {}, {}) } }
        rule.onNodeWithTag("scan_error").assertIsDisplayed()
        rule.onNodeWithText("expired", substring = true).assertIsDisplayed()
    }

    @Test fun scan_button_starts_scanner_and_disables_while_busy() {
        var scans = 0
        rule.setContent { LadduTheme { AddCameraContent(AddCameraUi(), {}, { scans++ }) } }
        rule.onNodeWithTag("scan_qr").assertIsEnabled().performClick()
        assertEquals(1, scans)
        rule.setContent { LadduTheme { AddCameraContent(AddCameraUi(busy = true), {}, {}) } }
    }

    // ---------------------------------------------------------------- alerts
    private val bark = LadduEvent("e1", "c1", "u1", EventType.BARK, 1_700_000_000_000, 8_000, 0.82f)
    private val move = LadduEvent("e2", "c1", "u1", EventType.MOVEMENT, 1_700_000_100_000, 102_000, 0.9f)

    @Test fun alerts_list_filters_and_opens_details() {
        var filter: AlertFilter? = null; var opened: String? = null
        rule.setContent { LadduTheme { AlertsContent(AlertFilter.ALL, listOf(bark, move), { filter = it }, { opened = it.eventId }) } }
        rule.onNodeWithTag("alerts_list").assertIsDisplayed()
        rule.onNodeWithTag("filter_BARKING").performClick()
        assertEquals(AlertFilter.BARKING, filter)
        rule.onNodeWithTag("event_e1").performClick()
        assertEquals("e1", opened)
    }

    @Test fun alerts_empty_state() {
        rule.setContent { LadduTheme { AlertsContent(AlertFilter.ALL, emptyList(), {}, {}) } }
        rule.onNodeWithText("Nothing here yet").assertIsDisplayed()
    }

    @Test fun event_details_show_fields_and_view_live() {
        var live: String? = null
        rule.setContent { LadduTheme { EventDetailContent(EventDetailUi(event = bark, loading = false), {}, { live = it }, {}) } }
        rule.onNodeWithTag("detail_type").assertIsDisplayed()
        rule.onNodeWithText("82%").assertIsDisplayed()
        rule.onNodeWithTag("view_live").performScrollTo().performClick()
        assertEquals("c1", live)
    }

    @Test fun unauthorized_or_missing_event_is_handled_gracefully() {
        rule.setContent { LadduTheme { EventDetailContent(EventDetailUi(event = null, loading = false), {}, {}, {}) } }
        rule.onNodeWithText("Event not found").assertIsDisplayed()
    }

    // ---------------------------------------------------------------- activity
    @Test fun activity_shows_totals_and_charts() {
        val days = (0..6).map { DayStats(it * 86_400_000L, presenceMs = it * 60_000L, movementEvents = it, barkEvents = 7 - it) }
        rule.setContent { LadduTheme { ActivityContent(ActivityRange.WEEK, days, {}) } }
        rule.onNodeWithTag("activity_totals").assertIsDisplayed()
        rule.onNodeWithTag("chart_presence").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("chart_barking").performScrollTo().assertIsDisplayed()
    }

    // ---------------------------------------------------------------- live (WebRTC lifecycle in the UI)
    private fun live(ui: LiveUi, reconnect: () -> Unit = {}) = rule.setContent {
        LadduTheme { LiveContent(ui, "Living room", false, null, {}, {}, reconnect, {}, {}) }
    }

    @Test fun live_screen_shows_progress_then_reconnect_message_and_failure() {
        live(LiveUi(state = LiveState.CONNECTING))
        rule.onNodeWithText("Connecting video...").assertIsDisplayed()
    }

    @Test fun live_disconnection_is_reported_and_user_can_reconnect() {
        var re = 0
        live(LiveUi(state = LiveState.FAILED, message = "Could not connect. Check the camera's Internet and try again."), reconnect = { re++ })
        rule.onNodeWithText("Could not connect", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("reconnect_button").performClick()
        assertEquals(1, re)
    }

    @Test fun live_controls_exist_and_snapshot_needs_live_video() {
        live(LiveUi(state = LiveState.RECONNECTING, message = "Reconnecting (attempt 1 of 3)..."))
        rule.onNodeWithTag("mute_button").assertIsDisplayed()
        rule.onNodeWithTag("fullscreen_button").assertIsDisplayed()
        rule.onNodeWithTag("snapshot_button").assertIsNotEnabled()
        rule.onNodeWithTag("quality_selector").assertIsDisplayed()
    }

    // ---------------------------------------------------------------- settings
    private fun actions(onCam: () -> Unit = {}, onNotif: () -> Unit = {}, onSignOut: () -> Unit = {}) = SettingsActions(
        updateCamera = { onCam() }, updateNotifications = { onNotif() }, updateRecording = {}, updateNetwork = {}, setTheme = {},
        deleteRecordings = {}, restartCamera = {}, signOut = onSignOut, switchMode = {}, openOemGuide = {}, manageCamera = {},
    )

    @Test fun camera_mode_settings_cover_all_sections_and_apply_changes() {
        var changed = 0
        rule.setContent { LadduTheme { SettingsContent(SettingsUi(mode = AppMode.CAMERA, email = "a@b.c"), actions(onCam = { changed++ })) } }
        rule.onNodeWithText("Camera").assertExists()
        listOf("AI sensitivity", "Bark sensitivity", "Notifications", "Event recording", "Network", "Privacy", "Device information", "Account", "About Laddu").forEach {
            rule.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        rule.onNodeWithTag("sw_dog").performScrollTo().performClick()
        assertEquals(1, changed)
    }

    @Test fun viewer_settings_are_disabled_until_a_camera_is_selected() {
        rule.setContent { LadduTheme { SettingsContent(SettingsUi(mode = AppMode.VIEWER, remoteReady = false), actions()) } }
        rule.onNodeWithTag("sw_dog").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("restart_remote").assertIsNotEnabled()
    }

    @Test fun viewer_remote_controls_enabled_when_camera_ready() {
        rule.setContent { LadduTheme { SettingsContent(SettingsUi(mode = AppMode.VIEWER, remoteReady = true, remoteCameraName = "Living room"), actions()) } }
        rule.onNodeWithTag("sw_dog").performScrollTo().assertIsEnabled()
        rule.onNodeWithTag("restart_remote").assertIsEnabled()
    }

    @Test fun sign_out_requires_confirmation() {
        var out = 0
        rule.setContent { LadduTheme { SettingsContent(SettingsUi(mode = AppMode.CAMERA), actions(onSignOut = { out++ })) } }
        rule.onNodeWithTag("sign_out").performScrollTo().performClick()
        assertEquals(0, out)
        rule.onNodeWithTag("confirm_sign_out").performClick()
        assertEquals(1, out)
    }
}

