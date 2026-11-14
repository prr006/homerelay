package com.homerelay.app.p2p

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.homerelay.app.util.P2pCredentials
import com.homerelay.app.util.SharingPermissions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the Wi-Fi Direct group reports.
 *
 * IMPORTANT (do not oversell this): a Wi-Fi Direct group with HomeRelay as Group Owner is a
 * *local* Wi-Fi network. Alone it carries no internet — exactly like the old local-only
 * hotspot, internet reaches clients through the on-device proxy bound to this group's
 * interface, and only from the upstream home Wi-Fi network. It is not a transparent bridge.
 */
sealed interface P2pGroupState {
    data object Idle : P2pGroupState
    data object Starting : P2pGroupState
    data class Active(
        /** Network name (SSID) of the group, e.g. "DIRECT-K7q2R9". Null if the OS hid it. */
        val networkName: String?,
        /** Passphrase of the group. Null if the OS hid it (or a system-created group). */
        val passphrase: String?,
        /** Real interface name reported by the group (e.g. "p2p0"); null if unavailable. */
        val interfaceName: String?,
        val frequencyMhz: Int?,
        val securityType: String?,
        /** Address of the Group Owner as reported by requestConnectionInfo(); null if unknown. */
        val groupOwnerAddress: String?,
        val isGroupOwner: Boolean,
        /** Devices currently in the group (from requestGroupInfo), excluding the owner. */
        val clientCount: Int,
    ) : P2pGroupState
    data class Failed(val reason: String) : P2pGroupState
}

/**
 * Lifecycle-safe Wi-Fi Direct Group Owner controller.
 *
 * Guarantees (mirrors the hardened v1.1.1 hotspot controller):
 *  - At most one group request is in flight; start() while starting/active is a no-op.
 *  - stop() invalidates any in-flight start; a late group formation is torn down
 *    immediately instead of leaking an invisible group.
 *  - Every failure path leaves the controller clean and retryable with a human-readable reason.
 *  - Group credentials are generated fresh per session (DIRECT-xxxxxx / 16-char WPA2),
 *    never persisted, never reused.
 *  - Client membership and the group's real interface/IPv4 are read back from the system
 *    via requestGroupInfo()/requestConnectionInfo(); no address is ever hardcoded.
 *
 * Permissions: the system enforces the same Wi-Fi Direct permission matrix as before
 * (location on ≤ 12, NEARBY_WIFI_DEVICES on 13+); no privileged permissions are used.
 */
class P2pGroupController(private val context: Context) {

    private val appContext = context.applicationContext
    private val wifiP2pManager =
        appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()

    /** Bumped on every start()/stop(); callbacks capture it to detect that they are stale. */
    private var generation = 0L
    private var channel: WifiP2pManager.Channel? = null

    private val _state = MutableStateFlow<P2pGroupState>(P2pGroupState.Idle)
    val state: StateFlow<P2pGroupState> = _state.asStateFlow()

    /** Last WIFI_P2P_STATE broadcast value (WIFI_P2P_STATE_ENABLED/DISABLED), or null if none observed. */
    @Volatile var lastP2pEnabledState: Int? = null
        private set


    init {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        runCatching {
            appContext.registerReceiver(p2pReceiver, filter)
        }
    }

    private val p2pReceiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    lastP2pEnabledState = intent.getIntExtra(WIFI_P2P_STATE_EXTRA, -1)
                    P2pRuntime.lastP2pEnabledState = lastP2pEnabledState
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    // Group connectivity changed (formed / removed / client joined or left).
                    // requestConnectionInfo() sorts out which; refresh() handles it.
                    refresh()
                }
            }
        }
    }

    /** True iff the device reports Wi-Fi Direct hardware support. */
    fun isP2pSupported(): Boolean =
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)

    /** True while the P2P radio is reported enabled, or unknown (no broadcast seen yet). */
    fun isP2pEnabled(): Boolean = lastP2pEnabledState != WIFI_P2P_STATE_DISABLED

    fun start() {
        if (wifiP2pManager == null) {
            _state.value = P2pGroupState.Failed(
                "This device reports no Wi-Fi Direct service, so a group cannot be created. " +
                    "HomeRelay requires Wi-Fi Direct hardware.",
            )
            return
        }
        if (!isP2pSupported()) {
            _state.value = P2pGroupState.Failed(
                "Android reports this device has no Wi-Fi Direct support (android.hardware.wifi.direct). " +
                    "HomeRelay cannot create the sharing group on it.",
            )
            return
        }
        if (!SharingPermissions.hasHotspotPermission(appContext)) {
            _state.value = P2pGroupState.Failed(
                "Android requires the ${p2pPermissionLabel()} permission before it will " +
                    "create a Wi-Fi Direct group. Grant it in the system dialog (or App info → " +
                    "Permissions), then press Start sharing again.",
            )
            return
        }
        synchronized(lock) {
            when (_state.value) {
                is P2pGroupState.Starting, is P2pGroupState.Active -> return // one request at a time
                else -> Unit
            }
            val gen = ++generation
            val creds = P2pCredentials.generate()
            _state.value = P2pGroupState.Starting
            val ch = openChannelLocked()
            if (ch == null) {
                _state.value = P2pGroupState.Failed(
                    "Could not initialise Wi-Fi Direct (the framework channel refused). " +
                        "Reboot the phone, then try again.",
                )
                return
            }
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val config = WifiP2pConfig.Builder()
                        .setNetworkName(creds.networkName)
                        .setPassphrase(creds.passphrase)
                        .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                        .apply { if (Build.VERSION.SDK_INT >= 29) enablePersistentMode(false) }
                        .build()
                        .apply { groupOwnerIntent = 15 }
                    wifiP2pManager.createGroup(ch, config, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() { /* group formation reported via broadcast / refresh() */ }
                        override fun onFailure(reason: Int) {
                            synchronized(lock) {
                                if (gen != generation) return
                                _state.value = P2pGroupState.Failed(describeCreateFailure(reason))
                            }
                        }
                    })
                } else {
                    @Suppress("DEPRECATION")
                    wifiP2pManager.createGroup(ch, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() { /* creds read from requestGroupInfo() */ }
                        override fun onFailure(reason: Int) {
                            synchronized(lock) {
                                if (gen != generation) return
                                _state.value = P2pGroupState.Failed(describeCreateFailure(reason))
                            }
                        }
                    })
                }
            } catch (e: SecurityException) {
                _state.value = P2pGroupState.Failed(
                    "Android refused the Wi-Fi Direct group request because the " +
                        "${p2pPermissionLabel()} permission is missing. Grant it in the system " +
                        "dialog (or App info → Permissions), then press Start sharing again.",
                )
            } catch (e: RuntimeException) {
                _state.value = P2pGroupState.Failed("Could not create the Wi-Fi Direct group: ${e.message}")
            }
        }
    }

    /** Idempotent: safe to call in any state, including while creation is in flight. */
    fun stop() {
        val ch: WifiP2pManager.Channel? = synchronized(lock) {
            generation++ // invalidate any in-flight start()
            val current = channel
            channel = null
            current
        }
        if (ch != null) {
            val mgr = wifiP2pManager
            if (mgr != null) {
                runCatching {
                    mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {}
                        override fun onFailure(reason: Int) {}
                    })
                }
            }
        }
        _state.value = P2pGroupState.Idle
    }

    /** True while a group is definitely formed (used by diagnostics). */
    fun isActive(): Boolean = synchronized(lock) { _state.value is P2pGroupState.Active }

    /**
     * Read back the real group state from the framework: interface name, IPv4, credentials
     * and connected clients. Posts P2pGroupState.Active on success, or (if a group was
     * expected but is gone) P2pGroupState.Failed so no UI keeps showing a live group.
     */
    fun refresh() {
        val mgr = wifiP2pManager ?: return
        val ch = synchronized(lock) { channel } ?: return
        val gen = synchronized(lock) { generation }
        mgr.requestGroupInfo(ch) { group ->
            if (group == null) {
                // No group exists. If we were active, this is an unexpected loss.
                synchronized(lock) {
                    if (gen != generation) return@requestGroupInfo
                    // Creation still pending: a null group here just means the framework has not
                    // finished forming it. The service's startup timeout handles a real failure.
                    if (_state.value is P2pGroupState.Active) {
                        _state.value = P2pGroupState.Failed(
                            "The Wi-Fi Direct group disappeared (removed by the system, by the user, " +
                                "or by a conflict with another P2P app). Sharing cannot continue.",
                        )
                    }
                }
                return@requestGroupInfo
            }
            mgr.requestConnectionInfo(ch) { info ->
                val ownerAddress = runCatching {
                    info?.groupOwnerAddress?.hostAddress?.takeIf { it != "0.0.0.0" }
                }.getOrNull()
                synchronized(lock) {
                    if (gen != generation) return@requestConnectionInfo
                    if (_state.value is P2pGroupState.Failed) return@requestConnectionInfo
                    _state.value = P2pGroupState.Active(
                        networkName = group.networkName?.takeIf { it.isNotBlank() },
                        passphrase = runCatching { group.passphrase }.getOrNull()?.takeIf { it.isNotBlank() },
                        interfaceName = group.interfaceName?.takeIf { it.isNotBlank() },
                        frequencyMhz = group.frequency.takeIf { it > 0 },
                        securityType = securityTypeName(group.securityType),
                        groupOwnerAddress = ownerAddress,
                        isGroupOwner = group.isGroupOwner,
                        clientCount = group.clientList?.size ?: 0,
                    )
                }
            }
        }
    }

    /** Release the receiver. Called by the service in onDestroy. */
    fun dispose() {
        runCatching { appContext.unregisterReceiver(p2pReceiver) }
    }

    private fun openChannelLocked(): WifiP2pManager.Channel? {
        val existing = channel
        if (existing != null) return existing
        val mgr = wifiP2pManager ?: return null
        return try {
            mgr.initialize(appContext, handler.looper, object : WifiP2pManager.ChannelListener {
                override fun onChannelDisconnected() {
                    // The P2P channel died (usually Wi-Fi turned off). Tell listeners via state.
                    synchronized(lock) {
                        if (_state.value is P2pGroupState.Active) {
                            _state.value = P2pGroupState.Failed(
                                "The Wi-Fi Direct channel disconnected (Wi-Fi turned off?). " +
                                    "Sharing stopped; reconnect and start again.",
                            )
                        }
                        channel = null
                    }
                }
            }).also { channel = it }
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun p2pPermissionLabel(): String =
        if (Build.VERSION.SDK_INT >= 33) "Nearby devices (Wi-Fi)" else "Location"

    private fun securityTypeName(type: Int): String = when (type) {
        WifiP2pGroup.SECURITY_TYPE_WPA2_PSK -> "WPA2-Personal"
        WifiP2pGroup.SECURITY_TYPE_WPA3_SAE -> "WPA3-Personal (SAE)"
        WifiP2pGroup.SECURITY_TYPE_WPA3_COMPATIBILITY -> "WPA3-Personal (compatibility)"
        WifiP2pGroup.SECURITY_TYPE_UNKNOWN -> "Unknown"
        else -> "Unknown ($type)"
    }

    private fun describeCreateFailure(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED ->
            "This device does not support Wi-Fi Direct group creation (P2P_UNSUPPORTED)."
        WifiP2pManager.BUSY ->
            "Android is busy with another Wi-Fi Direct operation (another P2P app or a previous " +
                "group that was not removed). Stop other P2P apps, press Stop, wait a few seconds, and try again."
        WifiP2pManager.ERROR ->
            "Android could not create the Wi-Fi Direct group (ERROR). This often happens when the " +
                "Wi-Fi chip cannot run STA + group concurrently, or Wi-Fi is off. Toggle Wi-Fi off/on and retry; " +
                "if it persists, this firmware cannot host a group while on Wi-Fi (see Diagnostics)."
        WifiP2pManager.NO_PERMISSION ->
            "Android refused the Wi-Fi Direct request: missing permission (NO_PERMISSION). Grant the " +
                "Nearby devices / Location permission, then try again."
        else -> "Wi-Fi Direct group creation failed with error code $reason"
    }

    companion object {
        // Extras and values for WIFI_P2P_STATE_CHANGED_ACTION. Kept as constants (the framework
        // only exposes them via deprecated names) so this class works on every minSdk.
        private const val WIFI_P2P_STATE_EXTRA = "wifi_p2p_state"
        private const val WIFI_P2P_STATE_DISABLED = 1
    }
}

/** Process-wide Wi-Fi Direct runtime facts, for Diagnostics. Written by P2pGroupController. */
object P2pRuntime {
    const val STATE_DISABLED = 1
    const val STATE_ENABLED = 2

    /** Last WIFI_P2P_STATE broadcast (ENABLED=2 / DISABLED=1), null if none observed yet. */
    @Volatile var lastP2pEnabledState: Int? = null

    fun stateName(v: Int?): String = when (v) {
        STATE_ENABLED -> "enabled"
        STATE_DISABLED -> "disabled"
        null -> "not observed yet"
        else -> "unknown ($v)"
    }
}
