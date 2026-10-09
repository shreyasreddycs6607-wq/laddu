package com.laddu.app.features.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.ui.unit.dp
import com.laddu.app.core.model.AppMode
import com.laddu.app.core.ui.components.LadduLogo

@Composable
fun SplashContent(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().testTag("splash"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LadduLogo(128.dp)
        Spacer(Modifier.height(20.dp))
        Text("Laddu", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
        Text(
            "Smart AI dog monitoring",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun WelcomeContent(onGetStarted: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp).testTag("welcome"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LadduLogo(120.dp)
        Spacer(Modifier.height(24.dp))
        Text("Meet Laddu", style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Turn an old phone into a smart dog camera, and keep an eye on your best friend from anywhere.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Feature(androidx.compose.material.icons.Icons.Filled.Videocam, "Watch live", "See your dog in real time from your own phone.")
        Feature(androidx.compose.material.icons.Icons.Filled.Notifications, "Know what happens", "Alerts for barking, movement and possible hazards.")
        Feature(androidx.compose.material.icons.Icons.Filled.Shield, "Stay private", "Detection runs on the camera phone. Nothing is recorded in secret.")
        Spacer(Modifier.height(32.dp))
        Button(onClick = onGetStarted, modifier = Modifier.fillMaxWidth().height(56.dp).testTag("get_started")) {
            Text("Get started", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(
            Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ModeSelectContent(
    selected: AppMode?,
    onSelect: (AppMode) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "How will this phone be used?",
) {
    Column(
        modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp).testTag("mode_select"),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "You can switch later in Settings. Install Laddu on both phones: one is the camera, the other is the viewer.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        ModeCard(
            icon = Icons.Filled.CameraAlt,
            emoji = "📷",
            title = "Camera Mode",
            body = "Turn this phone into an AI-powered dog monitor.",
            selected = selected == AppMode.CAMERA,
            tag = "mode_camera",
            onClick = { onSelect(AppMode.CAMERA) },
        )
        Spacer(Modifier.height(16.dp))
        ModeCard(
            icon = Icons.Filled.Tv,
            emoji = "📺",
            title = "Viewer Mode",
            body = "Watch and control your dog camera remotely.",
            selected = selected == AppMode.VIEWER,
            tag = "mode_viewer",
            onClick = { onSelect(AppMode.VIEWER) },
        )
    }
}

@Composable
private fun ModeCard(
    icon: ImageVector,
    emoji: String,
    title: String,
    body: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag(tag),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(56.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.background.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) Icon(androidx.compose.material.icons.Icons.Filled.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary)
        }
    }
}
