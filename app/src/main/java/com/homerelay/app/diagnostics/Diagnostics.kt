package com.homerelay.app.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import com.homerelay.app.net.UpstreamMonitor
import com.homerelay.app.state.AppState
import com.homerelay.app.state.SharingPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL

enum class CheckStatus { PASS, WARN, FAIL, INFO }

data class CheckResult(
    val id: String,
    val label: String,
    val status: CheckStatus,
    val detail: String,
)

/**
 * Real, on-device checks. Nothing is simulated. Checks that cannot run because the
 * session is idle report INFO, not PASS.
 */
class Diagnostics(private val context: Context) {

    suspend fun runAll(): List<CheckResult> = withContext(Dispatchers.IO) {
        listOf(
            checkAndroidVersion(),
            checkUpstreamPresent(),
            checkInternetValidated(),
            checkNotCellularOnly(),
            checkDnsResolution(),
            checkPermissions(),
            checkNotificationPermission(),
            checkBatteryOptimization(),
            checkHotspotActive(),
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
            else "No Wi-Fi network is active. HomeRelay shares Wi-Fi only — connect to Wi-Fi first.",
        )
    }

    private fun checkInternetValidated(): CheckResult {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
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
                "The active default network is mobile data. HomeRelay will NOT route proxy traffic over it; proxied requests will fail until Wi-Fi returns."
            else "The active default network is not mobile data.",
        )
    }

    private suspend fun checkDnsResolution(): CheckResult = withTimeoutOrNull(6000) {
        withContext(Dispatchers.IO) {
            runCatching {
                val addrs = java.net.InetAddress.getAllByName("one.one.one.one")
                CheckResult(
                    id = "dns",
                    label = "DNS resolution",
                    status = if (addrs.isNotEmpty()) CheckStatus.PASS else CheckStatus.FAIL,
                    detail = "Resolved one.one.one.one -> ${addrs.firstOrNull()?.hostAddress}",
                )
            }.getOrElse {
                CheckResult(
                    id = "dns",
                    label = "DNS resolution",
                    status = CheckStatus.FAIL,
                    detail = "DNS failed: ${it.message}",
                )
            }
        }
    } ?: CheckResult("dns", "DNS resolution", CheckStatus.FAIL, "DNS resolution timed out.")

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
        else "Location permission (Wi-Fi)"
        return CheckResult(
            id = "permissions",
            label = label,
            status = if (granted) CheckStatus.PASS else CheckStatus.FAIL,
            detail = if (granted) "Granted — the hotspot can be requested on this Android version."
            else if (Build.VERSION.SDK_INT >= 33)
                "Not granted — Android 13+ requires NEARBY_WIFI_DEVICES for the local hotspot. Grant it from the system dialog or App info → Permissions."
            else
                "Not granted — Android 12 and below refuse the hotspot unless Location (coarse or fine) is granted. HomeRelay never reads your location; the hotspot API requires the permission. Grant it from the system dialog or App info → Permissions.",
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

    private fun checkHotspotActive(): CheckResult {
        val s = AppState.state.value
        val active = s.phase == SharingPhase.Sharing && s.hotspotSsid != null
        return CheckResult(
            id = "hotspot",
            label = "Local hotspot active",
            status = when (s.phase) {
                SharingPhase.Sharing -> if (active) CheckStatus.PASS else CheckStatus.WARN
                SharingPhase.Failed -> CheckStatus.FAIL
                SharingPhase.Idle -> CheckStatus.INFO
                else -> CheckStatus.INFO
            },
            detail = when (s.phase) {
                SharingPhase.Sharing -> "Hotspot is broadcasting; proxy is listening on port ${s.proxyPort}."
                SharingPhase.Failed -> "Last error: ${s.errorMessage ?: "unknown"}"
                SharingPhase.Idle -> "Not sharing right now."
                else -> "Session is starting/stopping."
            },
        )
    }

    private suspend fun checkProxyListening(): CheckResult {
        val s = AppState.state.value
        if (s.phase != SharingPhase.Sharing) {
            return CheckResult("proxy_listen", "Proxy socket reachable on hotspot address",
                CheckStatus.INFO, "Not sharing right now.")
        }
        val host = s.bindAddress ?: return CheckResult(
            "proxy_listen", "Proxy socket reachable on hotspot address",
            CheckStatus.FAIL, "Sharing reports no bind address.")
        return withTimeoutOrNull(3000) {
            withContext(Dispatchers.IO) {
                runCatching {
                    Socket().use { it.connect(InetSocketAddress(host, s.proxyPort), 1500) }
                    CheckResult("proxy_listen", "Proxy socket reachable on hotspot address",
                        CheckStatus.PASS, "Connected to $host:${s.proxyPort}.")
                }.getOrElse {
                    CheckResult("proxy_listen", "Proxy socket reachable on hotspot address",
                        CheckStatus.FAIL, "Could not connect to $host:${s.proxyPort} (${it.message}).")
                }
            }
        } ?: CheckResult("proxy_listen", "Proxy socket reachable on hotspot address",
            CheckStatus.FAIL, "Connection timed out.")
    }

    private suspend fun checkPlainHttpViaProxy(): CheckResult = httpViaProxy(
        id = "http_via_proxy",
        label = "Plain HTTP through the proxy",
        url = "http://connectivitycheck.gstatic.com/generate_204",
    )

    private suspend fun checkHttpsViaProxy(): CheckResult = httpViaProxy(
        id = "https_via_proxy",
        label = "HTTPS through the proxy (CONNECT)",
        url = "https://connectivitycheck.gstatic.com/generate_204",
    )

    private suspend fun httpViaProxy(id: String, label: String, url: String): CheckResult {
        val s = AppState.state.value
        if (s.phase != SharingPhase.Sharing) {
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
