package com.homerelay.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.homerelay.app.proxy.ProxyServer
import com.homerelay.app.util.UpstreamSelection
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException

/**
 * Process-wide holder for the upstream network pinned by the active sharing session.
 * Set by SharingService when sharing starts (the network chosen *before* the Wi-Fi Direct
 * group was created) and cleared when it stops. Diagnostics use it to report the real state
 * of the pinned network instead of the (possibly different) default network.
 */
object PinnedUpstream {
    @Volatile
    var network: Network? = null
}

/**
 * Builds framework-free candidate descriptors for every visible network and picks the
 * upstream via the pure [UpstreamSelection] policy. The P2P group network carries
 * TRANSPORT_WIFI too, so it is tagged via its interface name and rejected.
 */
object UpstreamSelector {

    fun candidates(context: Context): List<UpstreamSelection.Candidate> {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        val result = mutableListOf<UpstreamSelection.Candidate>()
        for (net in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            val iface = runCatching { cm.getLinkProperties(net)?.interfaceName }.getOrNull()
            result += UpstreamSelection.Candidate(
                id = netId(net),
                wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                p2p = UpstreamSelection.isNonUpstreamInterface(iface),
                validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            )
        }
        return result
    }

    fun byId(context: Context, id: Long): Network? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
            .allNetworks.firstOrNull { netId(it) == id }

    /** Network id surrogate: the framework's Network lacks a stable Int API we can rely on publicly. */
    fun netId(net: Network): Long = net.networkHandle

    /**
     * Choose the home-router Wi-Fi network to pin. Returns null when no eligible network
     * exists (validated preferred). Excludes cellular, the P2P group and SoftAP interfaces.
     */
    fun selectPinnedUpstream(context: Context, retainedId: Long = UpstreamSelection.NONE): Network? {
        val all = context.applicationContext.getSystemService(ConnectivityManager::class.java).allNetworks
        val byId = all.associateBy(::netId)
        val chosen = UpstreamSelection.chooseUpstreamNetwork(candidates(context), retainedId)
        return if (chosen == UpstreamSelection.NONE) null else byId[chosen]
    }

    /** True iff the pinned network still exists and is still a Wi-Fi (non-P2P, non-cellular) network. */
    fun isStillEligible(context: Context, network: Network): Boolean {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(network) ?: return false
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return false
        val iface = runCatching { cm.getLinkProperties(network)?.interfaceName }.getOrNull()
        return !UpstreamSelection.isNonUpstreamInterface(iface)
    }

    fun isValidated(context: Context, network: Network): Boolean {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    fun ssidOf(context: Context, network: Network): String? {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(network) ?: return null
        val info = runCatching { caps.transportInfo as? android.net.wifi.WifiInfo }.getOrNull()
        val raw = runCatching { info?.ssid }.getOrNull() ?: return null
        if (raw.isBlank() || raw == "<unknown ssid>") return null
        return raw.trim('"').ifBlank { null }
    }
}

/**
 * Implements ProxyServer's outbound seams against ONE pinned upstream network.
 *
 * Unlike the v1.1 implementation (which re-picked "any Wi-Fi network" per call), the network
 * is pinned when the session starts — to the home router's Wi-Fi as it existed *before* the
 * Wi-Fi Direct group was created. Every socket and every DNS lookup is pinned to it. If that
 * network is lost, requests fail hard. No silent fallback to mobile data, never to the P2P
 * network, never to the default network.
 */
class UpstreamConnector(context: Context, private val pinnedNetwork: Network) :
    ProxyServer.OutboundConnector, ProxyServer.DnsResolver {

    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(ConnectivityManager::class.java)

    private fun requirePinned(): Network {
        val caps = cm.getNetworkCapabilities(pinnedNetwork)
            ?: throw IOException("The pinned upstream Wi-Fi network is gone; refusing to fall back to mobile data or the P2P group.")
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            throw IOException("The pinned upstream network is no longer Wi-Fi; refusing to route around it.")
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            throw IOException("The pinned upstream network resolves to cellular; refusing to use mobile data.")
        }
        val iface = runCatching { cm.getLinkProperties(pinnedNetwork)?.interfaceName }.getOrNull()
        if (UpstreamSelection.isNonUpstreamInterface(iface)) {
            throw IOException("The pinned upstream network is the P2P/SoftAP interface; refusing to loop proxy traffic back to the group.")
        }
        return pinnedNetwork
    }

    override fun open(host: String, port: Int, connectTimeoutMs: Int): Socket {
        val network = requirePinned()
        val socket = network.socketFactory.createSocket()
        socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        return socket
    }

    override fun resolve(host: String): Array<InetAddress> {
        val network = requirePinned()
        val addresses = try {
            network.getAllByName(host)
        } catch (e: java.net.UnknownHostException) {
            throw e
        } catch (e: Exception) {
            throw UnknownHostException("Upstream DNS failed for $host: ${e.message}")
        } ?: throw UnknownHostException("DNS returned no addresses for $host")
        if (addresses.isEmpty()) throw UnknownHostException("DNS returned no addresses for $host")
        return addresses
    }
}
