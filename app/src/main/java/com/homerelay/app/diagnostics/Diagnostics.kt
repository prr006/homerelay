package com.homerelay.app.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import com.homerelay.app.net.InterfaceResolution
import com.homerelay.app.net.PinnedUpstream
import com.homerelay.app.net.P2pInterfaceResolver
import com.homerelay.app.net.UpstreamSelector
import com.homerelay.app.p2p.P2pRuntime
import com.homerelay.app.state.AppState
import com.homerelay.app.state.SharingPhase
import com.homerelay.app.util.ProxyHttpProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL

private const val PLAIN_HTTP_PROBE_URL = "http://connectivitycheck.gstatic.com/generate_204"
private const val PLAIN_HTTP_PROBE_HOST = "connectivitycheck.gstatic.com"

enum class CheckStatus { PASS, WARN, FAIL, INFO }

data class CheckResult(
    val id: String,
    val label: String,
    val status: CheckStatus,
    val detail: String,
)

/**
 * Real, on-device checks. Nothing is simulated. Checks that cannot run because the
 * session is idle report INFO, not PASS. Wi-Fi Direct facts are read from the live
 * session state (AppState / PinnedUpstream), which SharingService maintains from the
 * framework via requestGroupInfo()/requestConnectionInfo().
 */
class Diagnostics(private val context: Context) {

    suspend fun runAll(): List<CheckResult> = withContext(Dispatchers.IO) {
        listOf(
            checkAndroidVersion(),
            checkP2pHardware(),
            checkP2pEnabled(),
            checkPermissions(),
            checkNotificationPermission(),
            checkBatteryOptimization(),
            checkUpstreamPresent(),
            checkUpstreamIdentity(),
            checkInternetValidated(),
            checkNotCellularOnly(),
            checkUpstreamSurvived(),
            checkDnsResolution(),
            checkGroupCreation(),
            checkGroupOwner(),
            checkGroupInterface(),
            checkGroupIp(),
            checkCredentials(),
            checkProxyListening(),
            checkPlainHttpViaProxy(),
            checkHttpsViaProxy(),
        )
    }

    private fun checkAndroidVersion() = CheckResult(
        id = "android_version",
        label = "Android version",
        status = CheckStatus.INFO,
        detail = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}). Minimum supported: API 26 (Android 8.0). Target: API 36.",
    )

    private fun checkP2pHardware(): CheckResult {
        val supported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
        return CheckResult(
            id = "p2p_hardware",
            label = "Wi-Fi Direct hardware support",
            status = if (supported) CheckStatus.PASS else CheckStatus.FAIL,
            detail = if (supported) "The device reports android.hardware.wifi.direct."
            else "The device does NOT report Wi-Fi Direct support. HomeRelay cannot create the sharing group on it.",
        )
    }

    private fun checkP2pEnabled(): CheckResult = CheckResult(
        id = "p2p_enabled",
        label = "Wi-Fi P2P enabled state",
        status = when (P2pRuntime.lastP2pEnabledState) {
            P2pRuntime.STATE_ENABLED -> CheckStatus.PASS
            P2pRuntime.STATE_DISABLED -> CheckStatus.FAIL
            null -> CheckStatus.INFO
            else -> CheckStatus.WARN
        },
        detail = "Android does not expose P2P on/off synchronously; this is the last WIFI_P2P_STATE broadcast observed this process lifetime: ${P2pRuntime.stateName(P2pRuntime.lastP2pEnabledState)}. " +
            "If group creation fails with P2P_UNSUPPORTED or ERROR, enable Wi-Fi (P2P rides on it) and retry.",
    )

    private fun checkUpstreamPresent(): CheckResult {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        val wifi = cm.allNetworks.any { net ->
            cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        return CheckResult(
            id = "upstream_wifi",
            label = "Upstream Wi-Fi network",
            status = if (wifi) CheckStatus.PASS else CheckStatus.FAIL,
            detail = if (wifi) "A Wi-Fi network is available as an upstream source."
            else "No Wi-Fi network is active. HomeRelay shares Wi-Fi only — connect to Wi-Fi first; mobile data is never used.",
        )
    }

    private fun checkUpstreamIdentity(): CheckResult {
        val pinned = PinnedUpstream.network
        if (pinned == null) {
            val best = UpstreamSelector.selectPinnedUpstream(context)
            return CheckResult(
                id = "upstream_identity",
                label = "Upstream network identity",
                status = CheckStatus.INFO,
                detail = if (best != null) {
                    "No session active. The current best eligible upstream is ${UpstreamSelector.ssidOf(context, best) ?: "Wi-Fi (SSID hidden)"} — pinned when sharing starts."
                } else {
                    "No eligible upstream Wi-Fi right now (cellular and the P2P group are never upstream)."
                },
            )
        }
        val ssid = UpstreamSelector.ssidOf(context, pinned)
        val eligible = UpstreamSelector.isStillEligible(context, pinned)
        return CheckResult(
            id = "upstream_identity",
            label = "Upstream network identity (pinned)",
            status = if (eligible) CheckStatus.PASS else CheckStatus.FAIL,
            detail = (if (eligible) "Pinned to ${ssid ?: "Wi-Fi (SSID hidden)"} — the exact network that was upstream when the group was created."
                else "The pinned upstream network is gone or no longer Wi-Fi. HomeRelay will not fall back to mobile data or the P2P group.") +
                " Cellular is never used as upstream.",
        )
    }

    private fun checkInternetValidated(): CheckResult {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        val pinned = PinnedUpstream.network
        if (pinned != null) {
            val caps = cm.getNetworkCapabilities(pinned)
            val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val reachable = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            return CheckResult(
                id = "internet_validated",
                label = "Upstream validated internet (pinned)",
                status = when {
                    validated -> CheckStatus.PASS
                    reachable -> CheckStatus.WARN
                    else -> CheckStatus.FAIL
                },
                detail = when {
                    validated -> "Android validated that the pinned home Wi-Fi reaches the internet."
                    reachable -> "The pinned Wi-Fi reports INTERNET but is not validated right now (captive portal or re-check in progress)."
                    else -> "The pinned home Wi-Fi does not report internet access. Proxied requests will fail."
                },
            )
        }
        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        return CheckResult(
            id = "internet_validated",
            label = "Internet reachability (validated)",
            status = when {
                validated -> CheckStatus.PASS
                wifi -> CheckStatus.WARN
                active == null -> CheckStatus.FAIL
                else -> CheckStatus.WARN
            },
            detail = when {
                validated -> "Android validated that the active network reaches the internet."
                wifi -> "Wi-Fi is connected but Android has not validated internet access (captive portal or restriction?)."
                active == null -> "No active network."
                else -> "Active network is not Wi-Fi. HomeRelay does not use mobile data."
            },
        )
    }

    private fun checkNotCellularOnly(): CheckResult {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        val cellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        return CheckResult(
            id = "not_cellular",
            label = "Mobile data not in use for sharing",
            status = if (cellular) CheckStatus.WARN else CheckStatus.PASS,
            detail = if (cellular)
                "The active default network is mobile data. HomeRelay will NOT route proxy traffic over it; proxied requests fail until Wi-Fi returns."
            else "The active default network is not mobile data.",
        )
    }

    private fun checkUpstreamSurvived(): CheckResult {
        val survived = AppState.state.value.upstreamSurvivedGroupCreation
        return CheckResult(
            id = "upstream_survived",
            label = "Upstream survived group creation",
            status = when (survived) {
                true -> CheckStatus.PASS
                false -> CheckStatus.FAIL
                null -> CheckStatus.INFO
            },
            detail = when (survived) {
                true -> "The pinned home Wi-Fi was still present and Wi-Fi after the group was created — this firmware runs STA+P2P concurrently."
                false -> "Creating the Wi-Fi Direct group DISCONNECTED the upstream home Wi-Fi. This firmware cannot hold STA+P2P at once. " +
                    "No internet can be relayed — HomeRelay refuses to fake it. Capture: `adb shell ip link`, `adb shell dumpsys connectivity`, `adb shell dumpsys wifi`."
                null -> "Not tested yet — run a sharing session first."
            },
        )
    }

    private suspend fun checkDnsResolution(): CheckResult {
        val pinned = PinnedUpstream.network ?: UpstreamSelector.selectPinnedUpstream(context)
        if (pinned == null) {
            return CheckResult("dns", "DNS resolution (via home Wi-Fi)", CheckStatus.FAIL,
                "No eligible Wi-Fi upstream — DNS will not be attempted over mobile data.")
        }
        return withTimeoutOrNull(6000) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val addrs = pinned.getAllByName("one.one.one.one")
                    CheckResult(
                        id = "dns",
                        label = "DNS resolution (via home Wi-Fi)",
                        status = if (addrs.isNotEmpty()) CheckStatus.PASS else CheckStatus.FAIL,
                        detail = "Resolved one.one.one.one -> ${addrs.firstOrNull()?.hostAddress} (pinned network).",
                    )
                }.getOrElse {
                    CheckResult(
                        id = "dns",
                        label = "DNS resolution (via home Wi-Fi)",
                        status = CheckStatus.FAIL,
                        detail = "DNS failed on the pinned Wi-Fi: ${it.message}",
                    )
                }
            }
        } ?: CheckResult("dns", "DNS resolution (via home Wi-Fi)", CheckStatus.FAIL, "DNS resolution timed out.")
    }

    private fun checkGroupCreation(): CheckResult {
        val s = AppState.state.value
        return CheckResult(
            id = "group_created",
            label = "Wi-Fi Direct group creation",
            status = when (s.phase) {
                SharingPhase.Sharing, SharingPhase.UpstreamUnavailable -> CheckStatus.PASS
                SharingPhase.Starting, SharingPhase.Stopping -> CheckStatus.INFO
                SharingPhase.Failed -> CheckStatus.FAIL
                SharingPhase.Idle -> CheckStatus.INFO
            },
            detail = when (s.phase) {
                SharingPhase.Sharing, SharingPhase.UpstreamUnavailable ->
                    "Group formed: ${s.p2pSsid ?: "SSID hidden by Android"} on ${s.groupInterfaceName ?: "unknown interface"}."
                SharingPhase.Starting, SharingPhase.Stopping -> "Group creation in progress…"
                SharingPhase.Failed -> "Last error: ${s.errorMessage ?: "unknown"}"
                SharingPhase.Idle -> "Not sharing right now."
            },
        )
    }

    private fun checkGroupOwner(): CheckResult {
        val s = AppState.state.value
        return CheckResult(
            id = "group_owner",
            label = "Group Owner status",
            status = when (s.groupIsGroupOwner) {
                true -> CheckStatus.PASS
                false -> CheckStatus.FAIL
                null -> CheckStatus.INFO
            },
            detail = when (s.groupIsGroupOwner) {
                true -> "requestConnectionInfo() reports this device IS the group owner (clients join it)."
                false -> "requestConnectionInfo() reports this device is NOT the group owner — the group is unusable for sharing. Report a bug."
                null -> "Unknown — no session has reported group ownership yet."
            },
        )
    }

    private fun checkGroupInterface(): CheckResult {
        val s = AppState.state.value
        val iface = s.groupInterfaceName
        if (iface == null) {
            return CheckResult(
                id = "group_interface",
                label = "P2P interface (discovered)",
                status = CheckStatus.INFO,
                detail = "Not sharing right now — the interface is discovered from requestGroupInfo() each session, never assumed.",
            )
        }
        val live = P2pInterfaceResolver.resolve(iface)
        val liveDetail = when (live) {
            is InterfaceResolution.Found -> "Live enumeration: ${live.addresses.joinToString(", ")} → using ${live.ipv4}."
            is InterfaceResolution.InterfaceNotFound -> "WARNING: interface '$iface' no longer exists (visible: ${live.visibleInterfaces.joinToString(", ").ifEmpty { "none" }})."
            is InterfaceResolution.NoUsableAddress -> "WARNING: interface '$iface' has no usable IPv4 right now: ${live.addresses.joinToString(", ").ifEmpty { "none" }}."
            is InterfaceResolution.NoInterfaceReported -> "WARNING: no interface name available."
        }
        return CheckResult(
            id = "group_interface",
            label = "P2P interface (discovered)",
            status = if (live is InterfaceResolution.Found) CheckStatus.PASS else CheckStatus.WARN,
            detail = "Group reported interface '$iface' (frequency ${s.groupFrequencyMhz?.let { f -> "$f MHz" } ?: "unknown"}, security ${s.groupSecurityType ?: "unknown"}). $liveDetail The proxy binds to this interface's IPv4 only — never 192.168.49.1 by assumption and never WifiP2pInfo.groupOwnerAddress (anonymised for Group Owners).",
        )
    }

    private fun checkGroupIp(): CheckResult {
        val s = AppState.state.value
        val host = s.bindAddress
        if (host == null) {
            return CheckResult(
                id = "group_ip",
                label = "P2P group IPv4 address (discovered)",
                status = CheckStatus.INFO,
                detail = "Not sharing right now.",
            )
        }
        val live = P2pInterfaceResolver.resolve(s.groupInterfaceName)
        val matches = live is InterfaceResolution.Found && live.ipv4 == host
        return CheckResult(
            id = "group_ip",
            label = "P2P group IPv4 address (discovered)",
            status = when {
                matches -> CheckStatus.PASS
                live is InterfaceResolution.Found -> CheckStatus.WARN
                else -> CheckStatus.WARN
            },
            detail = "Proxy bound to $host — resolved by enumerating the group interface (${s.groupInterfaceName ?: "unknown"}), NOT from WifiP2pInfo.groupOwnerAddress (anonymised for this device as Group Owner)." +
                (if (live is InterfaceResolution.Found) " Re-check now: ${live.ipv4}." else " Interface no longer resolves to a usable IPv4 — the group may be gone; check group_created."),
        )
    }

    private fun checkCredentials(): CheckResult {
        val s = AppState.state.value
        val nameOk = !s.p2pSsid.isNullOrBlank()
        val passOk = !s.p2pPassphrase.isNullOrBlank()
        return CheckResult(
            id = "credentials",
            label = "Group credentials available",
            status = when {
                s.phase == SharingPhase.Idle || s.phase == SharingPhase.Starting -> CheckStatus.INFO
                nameOk && passOk -> CheckStatus.PASS
                else -> CheckStatus.WARN
            },
            detail = when {
                s.phase == SharingPhase.Idle || s.phase == SharingPhase.Starting -> "Not sharing right now."
                nameOk && passOk -> "SSID '${s.p2pSsid}' and a passphrase are available (generated fresh per session). Shown on the dashboard."
                else -> "Android formed the group but did not report full credentials (older framework or hidden fields). Clients may need the system's own prompt."
            },
        )
    }

    private fun checkPermissions(): CheckResult {
        val fine = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val nearby = Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.NEARBY_WIFI_DEVICES) ==
            PackageManager.PERMISSION_GRANTED
        val granted = com.homerelay.app.util.HotspotPolicy.hotspotPermissionGranted(
            Build.VERSION.SDK_INT, fine, coarse, nearby,
        )
        val label = if (Build.VERSION.SDK_INT >= 33) "Nearby Wi-Fi devices permission"
        else "Location permission (Wi-Fi Direct)"
        return CheckResult(
            id = "permissions",
            label = label,
            status = if (granted) CheckStatus.PASS else CheckStatus.FAIL,
            detail = if (granted) "Granted — a Wi-Fi Direct group can be requested on this Android version."
            else if (Build.VERSION.SDK_INT >= 33)
                "Not granted — Android 13+ requires NEARBY_WIFI_DEVICES for Wi-Fi Direct. Grant it from the system dialog or App info → Permissions."
            else
                "Not granted — Android 12 and below refuse Wi-Fi Direct groups unless Location (coarse or fine) is granted. HomeRelay never reads your location; the P2P API requires the permission. Grant it from the system dialog or App info → Permissions.",
        )
    }

    private fun checkNotificationPermission(): CheckResult {
        if (Build.VERSION.SDK_INT < 33) {
            return CheckResult(
                id = "notifications",
                label = "Notification permission",
                status = CheckStatus.INFO,
                detail = "Not required before Android 13.",
            )
        }
        val granted = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return CheckResult(
            id = "notifications",
            label = "Notification permission",
            status = if (granted) CheckStatus.PASS else CheckStatus.WARN,
            detail = if (granted) "Granted — the persistent sharing notification can be shown and stopped from the shade."
            else "Not granted — the persistent notification (and its STOP action) will not appear. Grant it in App info → Notifications.",
        )
    }

    private fun checkBatteryOptimization(): CheckResult {
        val pm = context.getSystemService(PowerManager::class.java)
        val exempt = pm.isIgnoringBatteryOptimizations(context.packageName)
        return CheckResult(
            id = "battery_optimization",
            label = "Battery optimization exemption",
            status = if (exempt) CheckStatus.PASS else CheckStatus.WARN,
            detail = if (exempt) "HomeRelay is exempt from battery optimization — the sharing service is less likely to be deferred."
            else "Not exempt. One UI may defer or kill the service in the background. Use Settings → Battery optimization to exempt HomeRelay (system dialog; user-approved).",
        )
    }

    private suspend fun checkProxyListening(): CheckResult {
        val s = AppState.state.value
        if (!s.sharing) {
            return CheckResult("proxy_listen", "Proxy socket reachable on group address",
                CheckStatus.INFO, "Not sharing right now.")
        }
        val host = s.bindAddress ?: return CheckResult(
            "proxy_listen", "Proxy socket reachable on group address",
            CheckStatus.FAIL, "Sharing reports no bind address.")
        return withTimeoutOrNull(3000) {
            withContext(Dispatchers.IO) {
                runCatching {
                    Socket().use { it.connect(InetSocketAddress(host, s.proxyPort), 1500) }
                    CheckResult("proxy_listen", "Proxy socket reachable on group address",
                        CheckStatus.PASS, "Connected to $host:${s.proxyPort} (group interface only).")
                }.getOrElse {
                    CheckResult("proxy_listen", "Proxy socket reachable on group address",
                        CheckStatus.FAIL, "Could not connect to $host:${s.proxyPort} (${it.message}).")
                }
            }
        } ?: CheckResult("proxy_listen", "Proxy socket reachable on group address",
            CheckStatus.FAIL, "Connection timed out.")
    }

    /**
     * Plain HTTP through the proxy.
     *
     * API 36 blocks app-wide cleartext HTTP (`usesCleartextTraffic` defaults to false and
     * HomeRelay does NOT opt out), so `HttpURLConnection` to an http:// URL throws before a
     * single byte leaves the device. Instead we speak to the LOCAL proxy ourselves over a
     * raw socket in absolute-form — exactly what a browser configured to use the proxy does
     * — and the proxy fetches the target over the pinned upstream Wi-Fi. No cleartext
     * policy is weakened; HTTPS handling is unchanged.
     */
    private suspend fun checkPlainHttpViaProxy(): CheckResult {
        val s = AppState.state.value
        val id = "http_via_proxy"
        val label = "Plain HTTP through the proxy"
        if (!s.sharing) {
            return CheckResult(id, label, CheckStatus.INFO, "Not sharing right now.")
        }
        val host = s.bindAddress
        if (host == null) {
            return CheckResult(id, label, CheckStatus.FAIL, "No bind address.")
        }
        val port = s.proxyPort
        return withTimeoutOrNull(10_000) {
            withContext(Dispatchers.IO) {
                // gstatic connectivity check endpoint: http:// prefix is what makes this an
                // absolute-form proxy request rather than origin-form.
                // positional args: ProxyHttpProbe is a Java class (named args are prohibited)
                val result = ProxyHttpProbe.probeGet(host, port, PLAIN_HTTP_PROBE_URL, PLAIN_HTTP_PROBE_HOST, 6_000, 6_000)
                if (result.success) {
                    CheckResult(
                        id, label, CheckStatus.PASS,
                        "Proxy answered ${result.statusCode} for $PLAIN_HTTP_PROBE_URL " +
                            "(absolute-form via $host:$port).",
                    )
                } else {
                    CheckResult(
                        id, label, CheckStatus.FAIL,
                        "No usable answer from the proxy: ${result.error}",
                    )
                }
            }
        } ?: CheckResult(id, label, CheckStatus.FAIL, "Timed out through the proxy.")
    }

    private suspend fun checkHttpsViaProxy(): CheckResult = httpsViaProxy(
        id = "https_via_proxy",
        label = "HTTPS through the proxy (CONNECT)",
        url = "https://connectivitycheck.gstatic.com/generate_204",
    )

    private suspend fun httpsViaProxy(id: String, label: String, url: String): CheckResult = httpViaProxy(id, label, url)

    private suspend fun httpViaProxy(id: String, label: String, url: String): CheckResult {
        val s = AppState.state.value
        if (!s.sharing) {
            return CheckResult(id, label, CheckStatus.INFO, "Not sharing right now.")
        }
        val host = s.bindAddress ?: return CheckResult(id, label, CheckStatus.FAIL, "No bind address.")
        return withTimeoutOrNull(9000) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val target = URL(url)
                    val via = Proxy(Proxy.Type.HTTP, InetSocketAddress(host, s.proxyPort))
                    val conn = (target.openConnection(via) as HttpURLConnection).apply {
                        connectTimeout = 7000
                        readTimeout = 7000
                        instanceFollowRedirects = false
                        requestMethod = "GET"
                    }
                    val code = conn.responseCode
                    conn.errorStream?.close()
                    conn.inputStream?.close()
                    // gstatic returns 204; treat any real HTTP response as proof the proxy path works.
                    CheckResult(id, label, CheckStatus.PASS, "Proxy answered $code for $url.")
                }.getOrElse {
                    CheckResult(id, label, CheckStatus.FAIL, "Failed: ${describe(it)}")
                }
            }
        } ?: CheckResult(id, label, CheckStatus.FAIL, "Timed out through the proxy.")
    }

    private fun describe(e: Throwable): String {
        val msg = e.message ?: e.javaClass.simpleName
        return msg.take(160)
    }
}
