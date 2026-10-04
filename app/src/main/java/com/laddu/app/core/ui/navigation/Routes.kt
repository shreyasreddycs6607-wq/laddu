package com.laddu.app.core.ui.navigation

object Routes {
    const val SPLASH = "splash"
    const val WELCOME = "welcome"
    const val MODE_SELECT = "mode_select"

    // authentication
    const val LOGIN = "auth/login"
    const val SIGN_UP = "auth/signup"
    const val RESET = "auth/reset"
    const val FIREBASE_SETUP = "auth/firebase_setup"

    // camera mode
    const val CAMERA_HOME = "camera/home"
    const val CAMERA_SETTINGS = "camera/settings"
    const val CAMERA_PAIRING = "camera/pairing"
    const val OEM_GUIDE = "camera/oem_guide"

    // viewer mode
    const val VIEWER = "viewer"
    const val VIEWER_HOME = "viewer/home"
    const val VIEWER_LIVE = "viewer/live"
    const val VIEWER_ALERTS = "viewer/alerts"
    const val VIEWER_ACTIVITY = "viewer/activity"
    const val VIEWER_SETTINGS = "viewer/settings"
    const val ADD_CAMERA = "viewer/add_camera"
    const val MANAGE_CAMERA = "viewer/manage_camera"
    const val EVENT_DETAIL = "viewer/event/{eventId}"
    fun eventDetail(id: String) = "viewer/event/$id"

    // shared
    const val SETTINGS = "settings"
}
