package com.homerelay.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.homerelay.app.proxy.ProxyServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException

/**
 * Implements ProxyServer's outbound seams against the *upstream Wi-Fi network only*.
 *
 * Every socket is created from the Wi-Fi Network's socket factory, so it cannot silently
 * fall back to mobile data or the default network. DNS goes through Network.getAllByName —
 * also pinned to the Wi-Fi network. If there is no Wi-Fi network, both fail hard.
 */
class UpstreamConnector(context: Context) : ProxyServer.OutboundConnector, ProxyServer.DnsResolver {

    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    /** The Wi-Fi network we should use, or null. Validated networks preferred. */
    private fun wifiNetwork(): Network? {
        val wifi = cm.allNetworks.filter { net ->
            cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        return wifi.firstOrNull { net ->
            cm.getNetworkCapabilities(net)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        } ?: wifi.firstOrNull()
    }

    override fun open(host: String, port: Int, connectTimeoutMs: Int): Socket {
        val network = wifiNetwork() ?: throw IOException("No upstream Wi-Fi network available")
        val socket = network.socketFactory.createSocket()
        socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        return socket
    }

    override fun resolve(host: String): Array<InetAddress> {
        val network = wifiNetwork() ?: throw UnknownHostException("No upstream Wi-Fi network available")
        val addresses = network.getAllByName(host)
            ?: throw UnknownHostException("DNS returned no addresses for $host")
        if (addresses.isEmpty()) throw UnknownHostException("DNS returned no addresses for $host")
        return addresses
    }
}
