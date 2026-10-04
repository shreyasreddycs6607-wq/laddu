package com.laddu.app.core.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.laddu.app.core.model.AppMode
import com.laddu.app.features.alerts.EventDetailScreen
import com.laddu.app.features.authentication.AuthContent
import com.laddu.app.features.authentication.AuthMode
import com.laddu.app.features.authentication.AuthViewModel
import com.laddu.app.features.authentication.FirebaseSetupContent
import com.laddu.app.features.camera.CameraDashboardScreen
import com.laddu.app.features.camera.OemGuideScreen
import com.laddu.app.features.onboarding.ModeSelectContent
import com.laddu.app.features.onboarding.OnboardingViewModel
import com.laddu.app.features.onboarding.SplashContent
import com.laddu.app.features.onboarding.WelcomeContent
import com.laddu.app.features.pairing.AddCameraScreen
import com.laddu.app.features.pairing.CameraPairingScreen
import com.laddu.app.features.settings.SettingsScreen
import com.laddu.app.features.viewer.ManageCameraScreen
import com.laddu.app.features.viewer.ViewerShell
import com.laddu.app.features.viewer.ViewerViewModel
import kotlinx.coroutines.delay

/** Re-run the router (after login, logout, mode change). */
fun NavHostController.restartFromRoot() = navigate(Routes.SPLASH) { popUpTo(0) { inclusive = true } }

@Composable
fun LadduNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.SPLASH, modifier = Modifier.fillMaxSize()) {
        composable(Routes.SPLASH) {
            val root: RootViewModel = hiltViewModel()
            val dest by root.route.collectAsState()
            SplashContent()
            LaunchedEffect(dest) {
                val route = dest ?: return@LaunchedEffect
                delay(700)
                nav.navigate(route) { popUpTo(Routes.SPLASH) { inclusive = true } }
            }
        }
        composable(Routes.WELCOME) {
            WelcomeContent(onGetStarted = { nav.navigate(Routes.MODE_SELECT) })
        }
        composable(Routes.MODE_SELECT) {
            val vm: OnboardingViewModel = hiltViewModel()
            val mode by vm.mode.collectAsState()
            ModeSelectContent(selected = mode, onSelect = { m ->
                vm.selectMode(m)
                nav.restartFromRoot()
            })
        }
        composable(Routes.FIREBASE_SETUP) {
            val root: RootViewModel = hiltViewModel()
            val mode by hiltViewModel<OnboardingViewModel>().mode.collectAsState()
            FirebaseSetupContent(
                canContinueLocally = mode == AppMode.CAMERA,
                onContinueLocally = { root.continueLocally(); nav.restartFromRoot() },
                onChangeMode = { root.changeMode(); nav.restartFromRoot() },
            )
        }
        authGraph(nav)
        cameraGraph(nav)
        viewerGraph(nav)
    }
}

private fun NavGraphBuilder.authGraph(nav: NavHostController) {
    fun authRoute(route: String, mode: AuthMode) = composable(route) {
        val vm: AuthViewModel = hiltViewModel()
        val state by vm.state.collectAsState()
        LaunchedEffect(state.success) { if (state.success) nav.restartFromRoot() }
        AuthContent(
            mode = mode,
            state = state,
            onSubmit = { name, email, pw ->
                when (mode) {
                    AuthMode.LOGIN -> vm.signIn(email, pw)
                    AuthMode.SIGN_UP -> vm.signUp(name, email, pw)
                    AuthMode.RESET -> vm.reset(email)
                }
            },
            onSwitchMode = { m ->
                vm.clear()
                nav.navigate(when (m) { AuthMode.LOGIN -> Routes.LOGIN; AuthMode.SIGN_UP -> Routes.SIGN_UP; AuthMode.RESET -> Routes.RESET }) {
                    popUpTo(Routes.LOGIN) { inclusive = m == AuthMode.LOGIN }
                    launchSingleTop = true
                }
            },
        )
    }
    authRoute(Routes.LOGIN, AuthMode.LOGIN)
    authRoute(Routes.SIGN_UP, AuthMode.SIGN_UP)
    authRoute(Routes.RESET, AuthMode.RESET)
}

private fun NavGraphBuilder.cameraGraph(nav: NavHostController) {
    composable(Routes.CAMERA_HOME) {
        CameraDashboardScreen(
            onPairing = { nav.navigate(Routes.CAMERA_PAIRING) },
            onSettings = { nav.navigate(Routes.CAMERA_SETTINGS) },
            onOemGuide = { nav.navigate(Routes.OEM_GUIDE) },
        )
    }
    composable(Routes.CAMERA_PAIRING) { CameraPairingScreen(onBack = { nav.popBackStack() }) }
    composable(Routes.OEM_GUIDE) { OemGuideScreen(onBack = { nav.popBackStack() }) }
    composable(Routes.CAMERA_SETTINGS) {
        BackColumn("Camera settings", onBack = { nav.popBackStack() }) {
            SettingsScreen(
                onSignedOut = { nav.restartFromRoot() },
                onModeSwitched = { nav.restartFromRoot() },
                onOemGuide = { nav.navigate(Routes.OEM_GUIDE) },
                onManageCamera = { nav.navigate(Routes.CAMERA_PAIRING) },
            )
        }
    }
}

private fun NavGraphBuilder.viewerGraph(nav: NavHostController) {
    composable(Routes.VIEWER) {
        ViewerShell(
            onAddCamera = { nav.navigate(Routes.ADD_CAMERA) },
            onOpenEvent = { id -> nav.navigate(Routes.eventDetail(id)) },
            onManageCamera = { nav.navigate(Routes.MANAGE_CAMERA) },
            onSignedOut = { nav.restartFromRoot() },
            onModeSwitched = { nav.restartFromRoot() },
        )
    }
    composable(Routes.ADD_CAMERA) {
        AddCameraScreen(onBack = { nav.popBackStack() }, onPaired = { nav.popBackStack() })
    }
    composable(Routes.MANAGE_CAMERA) { ManageCameraScreen(onBack = { nav.popBackStack() }) }
    composable(Routes.EVENT_DETAIL, arguments = listOf(navArgument("eventId") { type = NavType.StringType })) { entry ->
        val id = entry.arguments?.getString("eventId").orEmpty()
        val viewer: ViewerViewModel = hiltViewModel()
        EventDetailScreen(
            eventId = id,
            onBack = { nav.popBackStack() },
            onViewLive = { cameraId -> viewer.requestLive(cameraId); nav.popBackStack(Routes.VIEWER, inclusive = false) },
        )
    }
}

@Composable
private fun BackColumn(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        content()
    }
}
