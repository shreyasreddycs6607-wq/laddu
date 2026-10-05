package com.laddu.app.features.viewer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.laddu.app.core.ui.navigation.Routes
import com.laddu.app.features.activity.ActivityScreen
import com.laddu.app.features.alerts.AlertsScreen
import com.laddu.app.features.live.LiveScreen
import com.laddu.app.features.settings.SettingsScreen

private data class Tab(val route: String, val label: String, val on: ImageVector, val off: ImageVector, val tag: String)

private val TABS = listOf(
    Tab(Routes.VIEWER_HOME, "Home", Icons.Filled.Home, Icons.Outlined.Home, "tab_home"),
    Tab(Routes.VIEWER_LIVE, "Live", Icons.Filled.PlayCircle, Icons.Outlined.PlayCircle, "tab_live"),
    Tab(Routes.VIEWER_ALERTS, "Alerts", Icons.Filled.Notifications, Icons.Outlined.Notifications, "tab_alerts"),
    Tab(Routes.VIEWER_ACTIVITY, "Activity", Icons.Filled.BarChart, Icons.Outlined.BarChart, "tab_activity"),
    Tab(Routes.VIEWER_SETTINGS, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings, "tab_settings"),
)

fun NavHostController.navigateTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

/** The viewer experience: Home, Live, Alerts, Activity, Settings behind a bottom bar. */
@Composable
fun ViewerShell(
    onAddCamera: () -> Unit,
    onOpenEvent: (String) -> Unit,
    onManageCamera: () -> Unit,
    onSignedOut: () -> Unit,
    onModeSwitched: () -> Unit,
    vm: ViewerViewModel = hiltViewModel(),
    stats: HomeStatsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val link by vm.link.collectAsState()
    val tabs = rememberNavController()
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val route = tabs.currentBackStackEntryAsState().value?.destination?.route
    val ctx = LocalContext.current

    // Alerts need the notification permission on Android 13+.
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // A tap on a push notification (or "VIEW LIVE") lands here.
    LaunchedEffect(link) {
        val l = link ?: return@LaunchedEffect
        tabs.currentBackStackEntryFlow.first() // the inner NavHost has no graph until its first composition
        l.cameraId?.let { vm.select(it) }
        vm.consumeLink()
        when {
            l.openLive -> tabs.navigateTab(Routes.VIEWER_LIVE)
            l.eventId != null -> onOpenEvent(l.eventId)
        }
    }

    val cameraId = state.selected?.cameraId
    LaunchedEffect(cameraId) { stats.setCamera(cameraId) }
    val today by stats.today.collectAsState()

    Scaffold(
        bottomBar = {
            if (!fullscreen) NavigationBar(Modifier.testTag("viewer_nav")) {
                TABS.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route,
                        onClick = { tabs.navigateTab(t.route) },
                        icon = { Icon(if (route == t.route) t.on else t.off, t.label) },
                        label = { Text(t.label) },
                        modifier = Modifier.testTag(t.tag),
                    )
                }
            }
        },
    ) { pad ->
        NavHost(tabs, startDestination = Routes.VIEWER_HOME, modifier = Modifier.padding(pad)) {
            composable(Routes.VIEWER_HOME) {
                ViewerHomeContent(
                    state = state, today = today,
                    onSelectCamera = vm::select,
                    onWatchLive = { tabs.navigateTab(Routes.VIEWER_LIVE) },
                    onAddCamera = onAddCamera,
                    onOpenAlerts = { tabs.navigateTab(Routes.VIEWER_ALERTS) },
                    onOpenActivity = { tabs.navigateTab(Routes.VIEWER_ACTIVITY) },
                )
            }
            composable(Routes.VIEWER_LIVE) {
                LiveScreen(
                    cameraId = cameraId, viewerId = state.user?.uid,
                    cameraName = state.selected?.name.orEmpty(), cameraOnline = state.online,
                    fullscreen = fullscreen, onFullscreenChange = { fullscreen = it },
                )
            }
            composable(Routes.VIEWER_ALERTS) { AlertsScreen(cameraId, onOpen = { onOpenEvent(it.eventId) }) }
            composable(Routes.VIEWER_ACTIVITY) { ActivityScreen(cameraId) }
            composable(Routes.VIEWER_SETTINGS) {
                SettingsScreen(onSignedOut = onSignedOut, onModeSwitched = onModeSwitched, onOemGuide = {}, onManageCamera = onManageCamera)
            }
        }
    }
}
