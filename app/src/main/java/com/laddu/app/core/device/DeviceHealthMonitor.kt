package com.laddu.app.core.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.laddu.app.core.model.ThermalLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

data class DeviceHealth(
    val batteryPct: Int = -1,
    val charging: Boolean = false,
    val temperatureC: Float? = null,
    val thermal: ThermalLevel = ThermalLevel.NORMAL,
)

/**
 * Combines Android's own thermal status (API 29+) with battery temperature. We never fight the
 * OS thermal protection: when it reports WARM/HOT the caller *reduces* work.
 */
fun computeThermal(batteryTempC: Float?, osThermalStatus: Int): ThermalLevel {
    // PowerManager.THERMAL_STATUS_*: NONE=0 LIGHT=1 MODERATE=2 SEVERE=3 CRITICAL=4 EMERGENCY=5 SHUTDOWN=6
    val byOs = when {
        osThermalStatus >= 3 -> ThermalLevel.HOT
        osThermalStatus == 2 -> ThermalLevel.WARM
        else -> ThermalLevel.NORMAL
    }
    val byTemp = when {
        batteryTempC == null -> ThermalLevel.NORMAL
        batteryTempC >= 45f -> ThermalLevel.HOT
        batteryTempC >= 41f -> ThermalLevel.WARM
        else -> ThermalLevel.NORMAL
    }
    return if (byOs.ordinal >= byTemp.ordinal) byOs else byTemp
}

@Singleton
class DeviceHealthMonitor @Inject constructor(@ApplicationContext private val ctx: Context) {

    private data class Batt(val pct: Int, val charging: Boolean, val tempC: Float?)

    private fun parse(i: Intent?): Batt {
        if (i == null) return Batt(-1, false, null)
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return Batt(
            pct = if (level >= 0 && scale > 0) level * 100 / scale else -1,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            tempC = if (temp == Int.MIN_VALUE) null else temp / 10f,
        )
    }

    private val battery: Flow<Batt> = callbackFlow {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { trySend(parse(i)) }
        }
        val sticky = ctx.registerReceiver(r, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        trySend(parse(sticky))
        awaitClose { runCatching { ctx.unregisterReceiver(r) } }
    }

    private val osThermal: Flow<Int> = callbackFlow {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val l = PowerManager.OnThermalStatusChangedListener { trySend(it) }
            trySend(pm.currentThermalStatus)
            pm.addThermalStatusListener(l)
            awaitClose { pm.removeThermalStatusListener(l) }
        } else {
            trySend(0)
            awaitClose { }
        }
    }

    val health: Flow<DeviceHealth> = combine(battery, osThermal) { b, t ->
        DeviceHealth(b.pct, b.charging, b.tempC, computeThermal(b.tempC, t))
    }.distinctUntilChanged()
}
