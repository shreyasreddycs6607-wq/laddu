package com.laddu.app.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectivityMonitor @Inject constructor(@ApplicationContext private val ctx: Context) {
    private val cm get() = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun NetworkCapabilities?.usable() =
        this != null && hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    fun isOnlineNow(): Boolean = cm.getNetworkCapabilities(cm.activeNetwork).usable()

    fun isWifiNow(): Boolean {
        val c = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** Emits true when the active network can actually reach the Internet (validated). */
    val isOnline: Flow<Boolean> = callbackFlow {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { trySend(caps.usable()) }
            override fun onLost(network: Network) { trySend(isOnlineNow()) }
            override fun onUnavailable() { trySend(false) }
        }
        trySend(isOnlineNow())
        cm.registerDefaultNetworkCallback(cb)
        awaitClose { runCatching { cm.unregisterNetworkCallback(cb) } }
    }.distinctUntilChanged()
}
