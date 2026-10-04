package com.laddu.app.features.camera

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.laddu.app.core.ui.components.InfoCard
import com.laddu.app.core.ui.components.StatusPill
import com.laddu.app.core.ui.components.Tone

object OemSetup {
    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean =
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

    /** The system's own dialog asks the user; we never change this silently. */
    fun requestBatteryExemption(ctx: Context) {
        val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
        launch(ctx, i) { launch(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) {} }
    }

    fun openAppInfo(ctx: Context) =
        launch(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))) {}

    /** ColorOS/Realme/OnePlus "Auto-launch / startup manager" screens, tried in order; falls back to app info. */
    fun openAutoStart(ctx: Context) {
        val candidates = listOf(
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.oplus.battery", "com.oplus.startupapp.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        )
        for (c in candidates) {
            var ok = true
            launch(ctx, Intent().setComponent(c)) { ok = false }
            if (ok) return
        }
        openAppInfo(ctx)
    }

    private fun launch(ctx: Context, i: Intent, onFail: () -> Unit) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) { onFail() } catch (_: SecurityException) { onFail() }
    }

    val isOppoFamily: Boolean
        get() = Build.MANUFACTURER.lowercase() in setOf("oppo", "realme", "oneplus")
}

@Composable
fun OemGuideScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var exempt by remember { mutableStateOf(OemSetup.isIgnoringBatteryOptimizations(ctx)) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp).testTag("oem_guide")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Keep Laddu running 24/7", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Phone makers add their own battery savers on top of Android. On Oppo / ColorOS phones they can stop " +
                "Laddu when the screen is off. Laddu cannot change these for you; please set them once:",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        InfoCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("1. Battery optimisation", style = MaterialTheme.typography.titleMedium)
                StatusPill(if (exempt) "Unrestricted" else "Restricted", if (exempt) Tone.GOOD else Tone.WARN)
            }
            Spacer(Modifier.height(4.dp))
            Text("Allow Laddu to run without battery restrictions. Android will ask you to confirm.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Button({ OemSetup.requestBatteryExemption(ctx); exempt = OemSetup.isIgnoringBatteryOptimizations(ctx) }, Modifier.testTag("request_battery_exemption")) {
                Text("Allow background activity")
            }
            TextAfterRefresh { exempt = OemSetup.isIgnoringBatteryOptimizations(ctx) }
        }
        Spacer(Modifier.height(12.dp))
        InfoCard {
            Text("2. Auto-launch / startup manager (Oppo, Realme, OnePlus)", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("Turn ON \"Allow auto-launch\" and \"Allow background activity\" for Laddu.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedButton({ OemSetup.openAutoStart(ctx) }) { Text("Open startup manager") }
        }
        Spacer(Modifier.height(12.dp))
        InfoCard {
            Text("3. Lock Laddu in Recent apps", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Open Recent apps, pull down on the Laddu card (or tap its menu) and choose \"Lock\". " +
                    "This stops \"clear all\" from closing it.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(12.dp))
        InfoCard {
            Text("4. App settings", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "In Battery: choose \"Don't optimise\" / \"Allow background activity\". Keep the phone plugged in " +
                    "and out of direct heat. Laddu slows its AI automatically if the phone gets warm.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton({ OemSetup.openAppInfo(ctx) }) { Text("Open app settings") }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Android may still stop camera access after a reboot or if the system kills the app. Laddu will then " +
                "show a notification: tap it to resume monitoring.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TextAfterRefresh(onRefresh: () -> Unit) {
    OutlinedButton(onRefresh, Modifier.padding(top = 4.dp)) { Text("Check again") }
}
