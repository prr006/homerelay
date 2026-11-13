package com.homerelay.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/** Identifies the interface carrying the local-only hotspot (SoftAP) for proxy binding. */
object HotspotInterfaceLocator {

    data class Result(val interfaceName: String, val address: Inet4Address)

    /** Name of the interface backing the default Wi-Fi network (e.g. "wlan0"), or null. */
    fun upstreamInterfaceName(context: Context): String? {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        val active = cm.activeNetwork ?: return null
        return runCatching { cm.getLinkProperties(active)?.interfaceName }.getOrNull()
    }

    /**
     * Find the hotspot's IPv4 address. Excludes loopback, point-to-point and the upstream
     * Wi-Fi interface itself. Prefers interfaces that look like SoftAP ("ap", "swlan",
     * "softap", "wlan1") but will accept any other site-local candidate.
     */
    fun findHotspotInterface(context: Context): Result? {
        val upstream = upstreamInterfaceName(context)
        val candidates = mutableListOf<Result>()
        val ifaces = try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
        } catch (_: Exception) {
            return null
        }
        for (iface in ifaces) {
            val up = runCatching { iface.isUp }.getOrDefault(false)
            val loopback = runCatching { iface.isLoopback }.getOrDefault(true)
            val p2p = runCatching { iface.isPointToPoint }.getOrDefault(true)
            if (!up || loopback || p2p) continue
            if (iface.name.equals(upstream, ignoreCase = true)) continue
            for (addr in Collections.list(iface.inetAddresses)) {
                if (addr is Inet4Address && addr.isSiteLocalAddress) {
                    candidates += Result(iface.name, addr)
                }
            }
        }
        fun score(r: Result): Int {
            val n = r.interfaceName.lowercase()
            return when {
                n.startsWith("ap") || n.contains("softap") || n.contains("swlan") -> 0
                n == "wlan1" -> 1
                n.startsWith("wlan") -> 2
                else -> 3
            }
        }
        return candidates.sortedBy { score(it) }.firstOrNull()
    }

    /** All site-local IPv4 addresses of up, non-loopback interfaces except the upstream one. */
    fun debugCandidateNames(context: Context): List<String> {
        val upstream = upstreamInterfaceName(context)
        val names = mutableListOf<String>()
        try {
            for (iface in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                val name = iface.name
                if (name.equals(upstream, ignoreCase = true)) continue
                for (addr in Collections.list(iface.inetAddresses)) {
                    if (addr is Inet4Address && addr.isSiteLocalAddress) {
                        names += "$name=${addr.hostAddress}"
                    }
                }
            }
        } catch (_: Exception) {
        }
        return names
    }
}
