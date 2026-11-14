package com.homerelay.app.net

import com.homerelay.app.util.P2pAddressSelector
import kotlinx.coroutines.delay
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/** Outcome of resolving this device's local P2P (Group Owner) interface address. */
sealed interface InterfaceResolution {
    /** The interface exists and carries a usable IPv4. */
    data class Found(val interfaceName: String, val ipv4: String, val addresses: List<String>) : InterfaceResolution

    /** The framework did not report an interface name at all (requestGroupInfo returned none). */
    data class NoInterfaceReported(val detail: String) : InterfaceResolution

    /** The named interface does not exist in NetworkInterface enumeration. */
    data class InterfaceNotFound(val interfaceName: String, val visibleInterfaces: List<String>) : InterfaceResolution

    /** The interface exists but every address is loopback/wildcard/multicast/link-local/non-IPv4. */
    data class NoUsableAddress(val interfaceName: String, val addresses: List<String>) : InterfaceResolution
}

/**
 * Finds the Group Owner's real local IPv4 by enumerating the interface the framework
 * reported via `WifiP2pGroup.getInterface()`. This is the ONLY source used for the proxy
 * bind address — `WifiP2pInfo.groupOwnerAddress` is anonymised for Group Owners (0.0.0.0)
 * unless the caller holds the privileged LOCAL_MAC_ADDRESS permission, which HomeRelay
 * never uses.
 */
object P2pInterfaceResolver {

    fun resolve(interfaceName: String?): InterfaceResolution {
        val name = interfaceName?.trim()?.takeIf { it.isNotEmpty() }
            ?: return InterfaceResolution.NoInterfaceReported(
                "requestGroupInfo() reported no interface name (WifiP2pGroup.getInterface() returned null/empty). " +
                    "The group may not be fully formed in the framework yet.",
            )

        val iface = try {
            NetworkInterface.getByName(name)
        } catch (e: Exception) {
            null
        } ?: return InterfaceResolution.InterfaceNotFound(name, visibleInterfaceSummaries())

        val addrs: List<InetAddress> = try {
            Collections.list(iface.inetAddresses)
        } catch (e: Exception) {
            emptyList()
        }
        val described = addrs.map { "${it.hostAddress} (${P2pAddressSelector.classify(it)})" }

        val chosen = P2pAddressSelector.selectLocalIpv4(addrs)
            ?: return InterfaceResolution.NoUsableAddress(name, described)
        return InterfaceResolution.Found(name, chosen.hostAddress!!, described)
    }

    /**
     * Poll [resolve] until it finds a usable IPv4 or [timeoutMs] elapses. IPv4 assignment on
     * p2p interfaces lags group formation by a beat on some firmwares — hence the retry.
     * Returns the last (failing) resolution on timeout.
     */
    suspend fun await(interfaceName: String?, timeoutMs: Long, pollMs: Long = 250): InterfaceResolution {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = resolve(interfaceName)
        while (System.currentTimeMillis() < deadline) {
            if (last is InterfaceResolution.Found) return last
            delay(pollMs)
            last = resolve(interfaceName)
        }
        return last
    }

    /** All up-interfaces with site-local IPv4, for failure diagnostics (never used for binding). */
    fun visibleInterfaceSummaries(): List<String> {
        val result = mutableListOf<String>()
        try {
            for (iface in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                val v4 = try {
                    Collections.list(iface.inetAddresses)
                } catch (e: Exception) {
                    emptyList()
                }.filterIsInstance<java.net.Inet4Address>()
                if (v4.isNotEmpty()) {
                    result += "${iface.name}=[${v4.joinToString(", ") { it.hostAddress }}]"
                }
            }
        } catch (_: Exception) {
        }
        return result
    }
}
