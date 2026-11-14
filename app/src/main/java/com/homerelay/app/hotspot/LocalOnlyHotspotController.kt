package com.homerelay.app.hotspot

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.homerelay.app.util.HotspotPermissions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the local-only hotspot reports.
 *
 * IMPORTANT (do not oversell this): a LocalOnlyHotspot creates a Wi-Fi network that is
 * NOT connected to the internet by the system. HomeRelay layers its own HTTP proxy on top
 * so that clients get internet through the proxy only when their HTTP(S) stack is pointed
 * at it. It is not a transparent bridge/repeater. The SSID and passphrase are generated
 * by Android (randomized on Android 12+); we cannot promise a user-chosen name/password.
 */
sealed interface HotspotState {
    data object Idle : HotspotState
    data object Starting : HotspotState
    data class Active(
        val ssid: String?,
        val passphrase: String?,
        val bssid: String?,
        val security: String?,
    ) : HotspotState
    data class Failed(val reason: String) : HotspotState
}

/**
 * Lifecycle-safe LocalOnlyHotspot controller.
 *
 * Guarantees:
 *  - At most one reservation exists at a time, and at most one system request is in flight.
 *  - A start() issued while starting/active is a no-op (no duplicate system requests —
 *    this is what produced "Caller already has an active localonly hotspot request").
 *  - stop() invalidates any in-flight start; a late onStarted is closed immediately instead
 *    of leaking a hotspot nobody can see or stop.
 *  - Every failure path (missing permission, SecurityException, onFailed, timeout) leaves
 *    the controller in a clean, retryable state with a human-readable reason.
 */
class LocalOnlyHotspotController(private val context: Context) {

    private val appContext = context.applicationContext
    private val wifiManager =
        appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()

    /** Bumped on every start()/stop(); callbacks capture it to detect that they are stale. */
    private var generation = 0L
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    private val _state = MutableStateFlow<HotspotState>(HotspotState.Idle)
    val state: StateFlow<HotspotState> = _state.asStateFlow()

    fun start() {
        // Defense in depth: the service checks too, but never trust the caller.
        if (!HotspotPermissions.hasHotspotPermission(appContext)) {
            _state.value = HotspotState.Failed(
                "Android requires the ${hotspotPermissionLabel()} permission before it will " +
                    "grant a hotspot. Grant it in the system dialog (or App info → Permissions), " +
                    "then press Start sharing again.",
            )
            return
        }
        synchronized(lock) {
            when (_state.value) {
                is HotspotState.Starting, is HotspotState.Active -> return // one request at a time
                else -> Unit
            }
            val gen = ++generation
            _state.value = HotspotState.Starting
            try {
                wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                        val stale = synchronized(lock) {
                            if (gen != generation || _state.value !is HotspotState.Starting) {
                                true // stop() was called while the system was still starting
                            } else {
                                reservation = res
                                _state.value = readReservation(res)
                                false
                            }
                        }
                        if (stale) {
                            // Do not leak a hotspot the user asked to stop.
                            runCatching { res.close() }
                        }
                    }

                    override fun onStopped() {
                        synchronized(lock) {
                            reservation = null
                            if (gen == generation && _state.value !is HotspotState.Failed) {
                                _state.value = HotspotState.Idle
                            }
                        }
                    }

                    override fun onFailed(reason: Int) {
                        synchronized(lock) {
                            reservation = null
                            if (gen != generation) return
                            _state.value = HotspotState.Failed(describeFailure(reason))
                        }
                    }
                }, handler)
            } catch (e: SecurityException) {
                _state.value = HotspotState.Failed(
                    "Android refused the hotspot request because the ${hotspotPermissionLabel()} " +
                        "permission is missing. Grant it in the system dialog (or App info → " +
                        "Permissions), then press Start sharing again.",
                )
            } catch (e: RuntimeException) {
                _state.value = HotspotState.Failed("Could not start the hotspot: ${e.message}")
            }
        }
    }

    /** Idempotent: safe to call in any state, including before onStarted arrives. */
    fun stop() {
        val toClose: WifiManager.LocalOnlyHotspotReservation? = synchronized(lock) {
            generation++ // invalidate any in-flight start()
            val current = reservation
            reservation = null
            current
        }
        runCatching { toClose?.close() }
        _state.value = HotspotState.Idle
    }

    /** True while a reservation is definitely held (used by diagnostics). */
    fun isActive(): Boolean = synchronized(lock) { reservation != null }

    private fun hotspotPermissionLabel(): String =
        if (Build.VERSION.SDK_INT >= 33) "Nearby devices (Wi-Fi)" else "Location"

    private fun readReservation(res: WifiManager.LocalOnlyHotspotReservation): HotspotState.Active {
        return runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                val soft = res.softApConfiguration
                HotspotState.Active(
                    ssid = soft.ssid?.takeIf { it.isNotBlank() },
                    passphrase = soft.passphrase?.takeIf { it.isNotBlank() },
                    bssid = soft.bssid?.toString()?.takeIf { it.isNotBlank() },
                    security = securityTypeName(soft.securityType),
                )
            } else {
                @Suppress("DEPRECATION")
                val wifiConfig: WifiConfiguration? = res.wifiConfiguration
                HotspotState.Active(
                    ssid = wifiConfig?.SSID?.trim('"')?.takeIf { it.isNotBlank() },
                    passphrase = wifiConfig?.preSharedKey?.trim('"')?.takeIf { it.isNotBlank() },
                    bssid = null,
                    security = null,
                )
            }
        }.getOrElse {
            // API surface differs across vendors; report what we can.
            HotspotState.Active(ssid = null, passphrase = null, bssid = null, security = null)
        }
    }

    private fun securityTypeName(type: Int): String = when (type) {
        android.net.wifi.SoftApConfiguration.SECURITY_TYPE_OPEN -> "Open"
        android.net.wifi.SoftApConfiguration.SECURITY_TYPE_WPA2_PSK -> "WPA2-Personal"
        android.net.wifi.SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION -> "WPA2/WPA3-Personal (transition)"
        android.net.wifi.SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> "WPA3-Personal (SAE)"
        android.net.wifi.SoftApConfiguration.SECURITY_TYPE_WPA3_OWE_TRANSITION -> "OWE transition"
        android.net.wifi.SoftApConfiguration.SECURITY_TYPE_WPA3_OWE -> "Enhanced Open (OWE)"
        else -> "Unknown ($type)"
    }

    private fun describeFailure(reason: Int): String = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC ->
            "Android could not start the hotspot (generic error). If you see “Caller already " +
                "has an active localonly hotspot request” in the logs, a previous start was " +
                "never closed — press Stop, wait a few seconds, and start again."
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL ->
            "No Wi-Fi channel is available for the hotspot. Turn Wi-Fi off and on, or move off the current band, then try again."
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE ->
            "The hotspot cannot run in the current mode (another hotspot, Wi-Fi Direct or tethering is already active, or your app already holds an active local-only hotspot request). Stop the other mode — or press Stop in HomeRelay and start again — then retry."
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED ->
            "This device's admin/carrier profile does not allow tethering (ERROR_TETHERING_DISALLOWED). HomeRelay cannot override that."
        else -> "Hotspot failed with error code $reason"
    }
}
