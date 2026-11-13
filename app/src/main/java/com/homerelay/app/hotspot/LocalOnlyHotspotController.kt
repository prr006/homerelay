package com.homerelay.app.hotspot

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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

class LocalOnlyHotspotController(private val context: Context) {

    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val handler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<HotspotState>(HotspotState.Idle)
    val state: StateFlow<HotspotState> = _state.asStateFlow()

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    fun start() {
        if (_state.value is HotspotState.Starting || _state.value is HotspotState.Active) return
        _state.value = HotspotState.Starting
        try {
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    this@LocalOnlyHotspotController.reservation = reservation
                    _state.value = readReservation(reservation)
                }

                override fun onStopped() {
                    reservation = null
                    if (_state.value !is HotspotState.Failed) {
                        _state.value = HotspotState.Idle
                    }
                }

                override fun onFailed(reason: Int) {
                    reservation = null
                    _state.value = HotspotState.Failed(describeFailure(reason))
                }
            }, handler)
        } catch (e: SecurityException) {
            _state.value = HotspotState.Failed(
                "Android refused the hotspot request (missing permission): ${e.message}",
            )
        } catch (e: RuntimeException) {
            _state.value = HotspotState.Failed("Could not start the hotspot: ${e.message}")
        }
    }

    fun stop() {
        try {
            reservation?.close()
        } catch (_: Exception) {
            // best effort — the reservation may already be gone
        }
        reservation = null
        _state.value = HotspotState.Idle
    }

    private fun readReservation(reservation: WifiManager.LocalOnlyHotspotReservation): HotspotState.Active {
        return runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                val soft = reservation.softApConfiguration
                HotspotState.Active(
                    ssid = soft.ssid?.takeIf { it.isNotBlank() },
                    passphrase = soft.passphrase?.takeIf { it.isNotBlank() },
                    bssid = soft.bssid?.toString()?.takeIf { it.isNotBlank() },
                    security = securityTypeName(soft.securityType),
                )
            } else {
                @Suppress("DEPRECATION")
                val wifiConfig: WifiConfiguration? = reservation.wifiConfiguration
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
            "Android could not start the hotspot (generic error). Another Wi-Fi connection may be active, or the hotspot channel is busy."
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL ->
            "No Wi-Fi channel is available for the hotspot. Turn Wi-Fi off and on, or move off the current band, then try again."
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE ->
            "The hotspot cannot run in the current mode (e.g. Wi-Fi Direct or tethering already active). Stop those and retry."
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED ->
            "This device's admin/carrier profile does not allow tethering (ERROR_TETHERING_DISALLOWED). HomeRelay cannot override that."
        else -> "Hotspot failed with error code $reason"
    }
}
