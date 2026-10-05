package com.laddu.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.laddu.app.core.datastore.SettingsRepository
import com.laddu.app.core.datastore.ThemeChoice
import com.laddu.app.core.notifications.DeepLinkHolder
import com.laddu.app.core.ui.navigation.LadduNavHost
import com.laddu.app.core.ui.theme.LadduTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var deepLinks: DeepLinkHolder

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinks.handle(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // reopening from Recents re-delivers the task's original (notification) intent: do not replay its deep link
        if (savedInstanceState == null && intent.flags and android.content.Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) deepLinks.handle(intent)
        setContent {
            val choice by settings.theme.collectAsState(initial = ThemeChoice.SYSTEM)
            val dark = when (choice) {
                ThemeChoice.SYSTEM -> isSystemInDarkTheme()
                ThemeChoice.LIGHT -> false
                ThemeChoice.DARK -> true
            }
            LadduTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LadduNavHost()
                }
            }
        }
    }
}
