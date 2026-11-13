package com.homerelay.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What we know about the upstream network right now. Everything here is read from the
 * system; nothing is inferred or made up. If the system cannot tell us (e.g. the SSID),
 * the field is null and the UI shows "Unavailable".
 */
data class UpstreamSnapshot(
    val wifiConnected: Boolean = false,
    val upstreamSsid: String? = null,
    val internetValidated: Boolean = false,
    val internetReachable: Boolean = false,
    val cellularDefault: Boolean = false,
    val cellularActive: Boolean = false,
    val metered: Boolean = false,
) {
    val healthy: Boolean get() = wifiConnected && internetValidated
}

/**
 * Watches the default network and the cellular transport. Registered with the
 * ConnectivityManager; callbacks arrive on a dedicated handler.
 *
 * Tracks:
 *  - defaultNetwork: is it Wi-Fi? is it validated? is it metered?
 *  - any cellular network with internet: used only to warn the user ("mobile data is active").
 *    HomeRelay never routes proxied traffic onto the cellular network.
 */
class UpstreamMonitor(private val context: Context) {

    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val _snapshot = MutableStateFlow(UpstreamSnapshot())
    val snapshot: StateFlow<UpstreamSnapshot> = _snapshot.asStateFlow()

    private var defaultNetwork: Network? = null
    private var cellularNetworks = mutableSetOf<Network>()

    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            defaultNetwork = network
            refreshDefault()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (network == defaultNetwork) applyDefaultCapabilities(caps)
        }

        override fun onLost(network: Network) {
            if (network == defaultNetwork) {
                defaultNetwork = null
                _snapshot.value = _snapshot.value.copy(
                    wifiConnected = false,
                    upstreamSsid = null,
                    internetValidated = false,
                    internetReachable = false,
                    cellularDefault = false,
                    metered = false,
                )
            }
        }
    }

    private val cellularCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            cellularNetworks.add(network)
            _snapshot.value = _snapshot.value.copy(cellularActive = true)
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                cellularNetworks.add(network)
                _snapshot.value = _snapshot.value.copy(cellularActive = cellularNetworks.isNotEmpty())
            }
        }

        override fun onLost(network: Network) {
            cellularNetworks.remove(network)
            _snapshot.value = _snapshot.value.copy(cellularActive = cellularNetworks.isNotEmpty())
        }
    }

    private fun refreshDefault() {
        val network = defaultNetwork ?: return
        val caps = cm.getNetworkCapabilities(network) ?: return
        applyDefaultCapabilities(caps)
    }

    private fun applyDefaultCapabilities(caps: NetworkCapabilities) {
        val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val reachable = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        _snapshot.value = _snapshot.value.copy(
            wifiConnected = isWifi,
            upstreamSsid = if (isWifi) readSsid(caps) else null,
            internetValidated = validated,
            internetReachable = reachable,
            cellularDefault = isCellular,
            metered = metered,
        )
    }

    private fun readSsid(caps: NetworkCapabilities): String? {
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                caps.transportInfo as? WifiInfo
            } else {
                @Suppress("DEPRECATION")
                (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager)
                    .connectionInfo
            }
        }.getOrNull() ?: return null
        val raw = runCatching { info.ssid }.getOrNull() ?: return null
        if (raw.isBlank() || raw == "<unknown ssid>") return null
        return raw.trim('"').ifBlank { null }
    }

    fun start() {
        val handler = Handler(Looper.getMainLooper())
        cm.registerDefaultNetworkCallback(defaultCallback, handler)
        val cellularRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        runCatching {
            cm.registerNetworkCallback(cellularRequest, cellularCallback, handler)
        }
        defaultNetwork = cm.activeNetwork
        refreshDefault()
    }

    fun stop() {
        runCatching { cm.unregisterNetworkCallback(defaultCallback) }
        runCatching { cm.unregisterNetworkCallback(cellularCallback) }
        defaultNetwork = null
        cellularNetworks.clear()
    }
}
