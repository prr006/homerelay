package com.homerelay.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.net.wifi.WifiManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.homerelay.app.MainActivity
import com.homerelay.app.R
import com.homerelay.app.data.SettingsStore
import com.homerelay.app.net.InterfaceResolution
import com.homerelay.app.net.PinnedUpstream
import com.homerelay.app.net.P2pInterfaceResolver
import com.homerelay.app.net.UpstreamConnector
import com.homerelay.app.net.UpstreamSelector
import com.homerelay.app.p2p.P2pGroupController
import com.homerelay.app.p2p.P2pGroupState
import com.homerelay.app.util.GroupCreationMode
import com.homerelay.app.proxy.ProxyServer
import com.homerelay.app.state.AppState
import com.homerelay.app.state.SharingPhase
import com.homerelay.app.util.HostResources
import com.homerelay.app.util.SessionGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service that owns the whole sharing session:
 *   pin the home Wi-Fi upstream  ->  Wi-Fi Direct group (Group Owner)
 *   ->  proxy bound to the group's interface only, egress pinned to the upstream network
 *
 * Design rules honoured here:
 *  - Outbound sockets go ONLY to the upstream home Wi-Fi network pinned *before* the group
 *    is created (see UpstreamConnector). Never mobile data, never the P2P network.
 *  - The proxy binds ONLY to the group's interface address — never 0.0.0.0, never upstream.
 *  - If the upstream network cannot be pinned, no group is created and sharing does not start.
 *  - If creating the group kills the upstream Wi-Fi (firmware without STA+P2P concurrency),
 *    the session fails clearly and Diagnostics records the evidence. No false success.
 *  - "Sharing" is never displayed after the group disappears.
 */
class SharingService : Service() {

    private lateinit var p2p: P2pGroupController
    private var proxy: ProxyServer? = null
    private var tickJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var upstreamCallback: ConnectivityManager.NetworkCallback? = null
    private var pinnedNetwork: Network? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val session = SessionGuard()
    private var tickCount = 0

    override fun onCreate() {
        super.onCreate()
        p2p = P2pGroupController(this)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSharing()
            ACTION_STOP -> stopSharing()
            else -> stopSelfAndClean()
        }
        // Never auto-restart after being killed; the user decides when to share again.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopSharing()
        p2p.dispose()
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep sharing in the foreground service even if the activity is swiped away;
        // the notification stays up with a STOP action, which is the user's handle on it.
        super.onTaskRemoved(rootIntent)
    }

    /* ---------------- session control ---------------- */

    private fun startSharing() {
        val current = AppState.state.value
        if (current.phase == SharingPhase.Sharing || current.phase == SharingPhase.UpstreamUnavailable || current.busy) return

        // Keep the host awake for the whole session — including the Starting phase —
        // so the screen may turn off at any time without the relay dropping.
        acquireResourceLocks()

        // Never ask the system for a group we are not allowed to have — fail fast with a clear message.
        if (!com.homerelay.app.util.SharingPermissions.hasHotspotPermission(this)) {
            val label = if (Build.VERSION.SDK_INT >= 33) "Nearby devices (Wi-Fi)" else "Location"
            AppState.update {
                it.copy(
                    phase = SharingPhase.Failed,
                    errorMessage = "Android requires the $label permission for Wi-Fi Direct. " +
                        "Grant it in the system dialog or App info → Permissions, then press Start sharing again.",
                )
            }
            startForegroundCompat(buildNotification(getString(R.string.notification_title),
                "Sharing not started — permission missing"))
            scope.launch {
                delay(4_000)
                stopSelfAndClean()
            }
            return
        }

        // Pin the upstream BEFORE the group exists. This is the whole trick: this exact
        // Network object remains the only route proxy traffic may take.
        val upstream = UpstreamSelector.selectPinnedUpstream(this)
        if (upstream == null) {
            AppState.update {
                it.copy(
                    phase = SharingPhase.Failed,
                    errorMessage = "No upstream Wi-Fi network was found. Connect the phone to your home Wi-Fi first — " +
                        "HomeRelay shares Wi-Fi, never mobile data, and it cannot create the group without the network it must relay.",
                )
            }
            startForegroundCompat(buildNotification(getString(R.string.notification_title),
                "Sharing not started — no upstream Wi-Fi"))
            scope.launch {
                delay(4_000)
                stopSelfAndClean()
            }
            return
        }

        // Remove any group left over from a previous session BEFORE creating another —
        // switching the creation mode must never produce competing groups.
        p2p.stop()
        val sessionId = session.bump()
        pinnedNetwork = upstream
        PinnedUpstream.network = upstream
        val settings = SettingsStore(this).load()
        AppState.update {
            it.copy(
                phase = SharingPhase.Starting,
                errorMessage = null,
                proxyPort = settings.proxyPort,
                p2pSsid = null,
                p2pPassphrase = null,
                groupInterfaceName = null,
                groupFrequencyMhz = null,
                groupSecurityType = null,
                groupIsGroupOwner = null,
                upstreamSurvivedGroupCreation = null,
                clientsConnected = 0,
                bindAddress = null,
                pacUrl = null,
                groupCreationMode = settings.groupCreationMode.describe(),
            )
        }
        startForegroundCompat(buildNotification(getString(R.string.notification_title),
            "Starting Wi-Fi Direct group…"))

        scope.launch { runSession(settings, sessionId, upstream) }
    }

    private suspend fun runSession(settings: com.homerelay.app.data.Settings, sessionId: Long, upstream: Network) {
        // 1. Wi-Fi Direct group (Group Owner) — mode chosen in Settings (v1.2.4 A/B experiment)
        p2p.start(settings.groupCreationMode)
        val groupState: P2pGroupState? = withTimeoutOrNull(20_000) {
            var result: P2pGroupState? = null
            while (result == null) {
                if (!session.isCurrent(sessionId)) break
                p2p.refresh()
                when (val s = p2p.state.value) {
                    is P2pGroupState.Active, is P2pGroupState.Failed -> result = s
                    P2pGroupState.Idle -> break // user pressed Stop
                    else -> delay(500)
                }
            }
            result
        }
        if (!session.isCurrent(sessionId)) return // a newer session took over while we waited
        if (groupState == null && p2p.state.value == P2pGroupState.Idle) return // user pressed Stop

        val active = when (val g = groupState) {
            is P2pGroupState.Active -> g
            is P2pGroupState.Failed -> { fail(g.reason, sessionId); return }
            null -> {
                fail(
                    "The Wi-Fi Direct group did not form within 20 seconds. Turn Wi-Fi off and on, ensure no " +
                        "other P2P app is active, and try again. If it persists, this firmware may not support " +
                        "group creation while on Wi-Fi — see Diagnostics.",
                    sessionId,
                )
                return
            }
            else -> { fail("Wi-Fi Direct group did not start.", sessionId); return }
        }
        if (!session.isCurrent(sessionId)) return // a newer session took over while we waited
        if (!active.isGroupOwner) {
            fail(
                "Android created the group but does not report this device as its owner " +
                    "(requestConnectionInfo → isGroupOwner=false). HomeRelay cannot share through it.",
                sessionId,
            )
            return
        }

        // 2. Did the upstream home Wi-Fi survive the group being created? This is the
        //    make-or-break question on firmware that cannot run STA+P2P concurrently.
        val upstreamSurvived = UpstreamSelector.isStillEligible(this, upstream)
        AppState.update { it.copy(upstreamSurvivedGroupCreation = upstreamSurvived) }
        if (!upstreamSurvived) {
            fail(
                "Creating the Wi-Fi Direct group disconnected the upstream home Wi-Fi — this firmware " +
                    "cannot hold Wi-Fi STA and a P2P group at the same time. HomeRelay will not show a fake " +
                    "“sharing” state or fall back to mobile data. Evidence: the pinned network is gone or no " +
                    "longer a Wi-Fi network after group creation. See Diagnostics and NETWORK_ARCHITECTURE.md.",
                sessionId,
            )
            return
        }

        // 3. Bind address — the group's REAL local IPv4, discovered from the interface the
        //    framework reported (WifiP2pGroup.getInterface() → NetworkInterface.getByName →
        //    usable IPv4). Android anonymises WifiP2pInfo.groupOwnerAddress for Group Owners
        //    (0.0.0.0 without the privileged LOCAL_MAC_ADDRESS permission, which HomeRelay
        //    never uses), so it is NOT a valid bind source. IPv4 assignment lags group
        //    formation on some firmwares, so we retry with a bounded timeout.
        val resolution = P2pInterfaceResolver.await(active.interfaceName, timeoutMs = 10_000, pollMs = 250)
        val bindAddress = when (resolution) {
            is InterfaceResolution.Found -> resolution.ipv4
            is InterfaceResolution.NoInterfaceReported -> {
                fail(
                    "The group formed but Android did not report its interface name " +
                        "(requestGroupInfo → getInterface()). ${resolution.detail} " +
                        "Refusing to bind the proxy to all interfaces.",
                    sessionId,
                )
                return
            }
            is InterfaceResolution.InterfaceNotFound -> {
                fail(
                    "The group reported interface '${resolution.interfaceName}' but NetworkInterface.getByName " +
                        "returned null. Visible IPv4 interfaces right now: " +
                        resolution.visibleInterfaces.joinToString(", ").ifEmpty { "none" } + ". " +
                        "Refusing to bind the proxy to all interfaces.",
                    sessionId,
                )
                return
            }
            is InterfaceResolution.NoUsableAddress -> {
                fail(
                    "Interface '${resolution.interfaceName}' has no usable IPv4 — discovered: " +
                        resolution.addresses.joinToString(", ").ifEmpty { "none" } +
                        " (loopback, wildcard, multicast, link-local and non-IPv4 are ignored). " +
                        "This can mean the group formed but the kernel has not assigned it an address yet, " +
                        "or the firmware does not support legacy clients. See Diagnostics.",
                    sessionId,
                )
                return
            }
        }
        AppState.update {
            it.copy(
                phase = SharingPhase.Starting,
                bindAddress = bindAddress,
                groupInterfaceName = active.interfaceName,
            )
        }

        // 4. Proxy — configured port first, automatic free-port fallback; egress pinned upstream.
        val config = ProxyServer.Config().apply {
            pacHost = bindAddress
            pacPort = settings.proxyPort
            viaHeader = "HomeRelay"
        }
        val connector = UpstreamConnector(this, upstream)
        val logger = object : ProxyServer.Logger {
            override fun d(message: String) { android.util.Log.d(TAG, message) }
            override fun w(message: String) { android.util.Log.w(TAG, message) }
        }
        val server = ProxyServer(config, connector, connector, logger)
        val boundPort: Int = try {
            server.start(listOf(bindAddress), settings.proxyPort)
            settings.proxyPort
        } catch (e: Exception) {
            try {
                server.start(listOf(bindAddress), 0)
                server.boundPort()
            } catch (e2: Exception) {
                fail("Proxy could not bind to $bindAddress (the group interface): ${e2.message}", sessionId)
                return
            }
        }
        config.pacPort = boundPort
        if (!session.isCurrent(sessionId)) {
            // User pressed Stop while we were binding: tear the fresh proxy down and leave.
            runCatching { server.stop() }
            return
        }
        // Confirm the group is still formed right now — the bind path must never advertise
        // a proxy on top of a vanished group. On failure: stop the fresh proxy, remove the
        // group, surface a precise error. No phantom group, no stale "Active" UI.
        if (!p2p.verifyGroupFormed(3_000)) {
            runCatching { server.stop() }
            fail(
                "The Wi-Fi Direct group disappeared while the proxy was binding " +
                    "(requestGroupInfo returned no group, or the framework no longer reports a formed GO group). " +
                    "Nothing is listening. Press Start sharing again.",
                sessionId,
            )
            return
        }
        proxy = server

        registerPinnedNetworkCallback(upstream, sessionId)

        val pacUrl = if (settings.pacEnabled) "http://$bindAddress:$boundPort/proxy.pac" else null
        AppState.update {
            it.copy(
                phase = SharingPhase.Sharing,
                p2pSsid = active.networkName,
                p2pPassphrase = active.passphrase,
                groupInterfaceName = active.interfaceName,
                groupFrequencyMhz = active.frequencyMhz,
                groupSecurityType = active.securityType,
                groupIsGroupOwner = active.isGroupOwner,
                groupCreationMode = settings.groupCreationMode.describe(),
                upstreamSurvivedGroupCreation = true,
                clientsConnected = active.clientCount,
                proxyPort = boundPort,
                bindAddress = bindAddress,
                pacUrl = pacUrl,
                startedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                startedAtWallClockMs = System.currentTimeMillis(),
                bytesUp = 0, bytesDown = 0,
                totalRequests = 0, successfulRequests = 0, failedRequests = 0,
                connectTunnels = 0,
                seenDevices = 0, activeConnections = 0,
            )
        }
        updateNotification(getString(R.string.notification_title),
            "Group active — waiting for a client · proxy $bindAddress:$boundPort")
        startTickLoop(server, boundPort, sessionId)
    }

    private fun registerPinnedNetworkCallback(upstream: Network, sessionId: Long) {
        unregisterPinnedNetworkCallback()
        val pinnedHandle = upstream.networkHandle
        // No public API watches one specific Network; request all Wi-Fi networks and
        // filter by networkHandle. Nothing about other networks affects the session.
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (network.networkHandle != pinnedHandle) return
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                applyUpstreamState(validated = validated, sessionId = sessionId)
            }

            override fun onLost(network: Network) {
                if (network.networkHandle != pinnedHandle) return
                applyUpstreamState(validated = false, sessionId = sessionId, lost = true)
            }
        }
        upstreamCallback = cb
        val cm = getSystemService(ConnectivityManager::class.java)
        val request = android.net.NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching { cm.registerNetworkCallback(request, cb) }
    }

    private fun unregisterPinnedNetworkCallback() {
        upstreamCallback?.let { cb ->
            val cm = getSystemService(ConnectivityManager::class.java)
            runCatching { cm.unregisterNetworkCallback(cb) }
        }
        upstreamCallback = null
    }

    /** Flip between Sharing and UpstreamUnavailable. Never falls back to any other network. */
    private fun applyUpstreamState(validated: Boolean, sessionId: Long, lost: Boolean = false) {
        if (!session.isCurrent(sessionId)) return
        AppState.update { s ->
            when {
                lost -> s.phase.takeIf { it == SharingPhase.Sharing || it == SharingPhase.UpstreamUnavailable }
                    ?.let {
                        s.copy(
                            phase = SharingPhase.UpstreamUnavailable,
                            errorMessage = "The home Wi-Fi network was lost — reconnect the phone to it. " +
                                "HomeRelay will not use mobile data; sharing resumes automatically when Wi-Fi returns.",
                        )
                    } ?: s
                validated -> s.phase.takeIf { it == SharingPhase.UpstreamUnavailable }
                    ?.let { s.copy(phase = SharingPhase.Sharing, errorMessage = null) } ?: s
                else -> s.phase.takeIf { it == SharingPhase.Sharing }
                    ?.let { s.copy(phase = SharingPhase.UpstreamUnavailable) } ?: s
            }
        }
    }

    private fun startTickLoop(server: ProxyServer, port: Int, sessionId: Long) {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                p2p.refresh()
                if (!session.isCurrent(sessionId)) break
                if (p2p.state.value is P2pGroupState.Failed) {
                    fail((p2p.state.value as P2pGroupState.Failed).reason, sessionId)
                    break
                }
                AppState.applySnapshot(server.stats().snapshot(), port)
                tickCount++
                if (tickCount % 2 == 0) {
                    val active = p2p.state.value as? P2pGroupState.Active
                    if (active != null) {
                        AppState.update { it.copy(clientsConnected = active.clientCount) }
                    }
                }
                val s = AppState.state.value
                updateNotification(getString(R.string.notification_title), statusLine(s, port))
                delay(1000)
            }
        }
    }

    private fun clientPresent(s: com.homerelay.app.state.SharingUiState): Boolean =
        s.clientsConnected > 0 || s.seenDevices > 0

    private fun statusLine(s: com.homerelay.app.state.SharingUiState, port: Int): String {
        val up = com.homerelay.app.util.FormatUtils.bytes(s.bytesUp)
        val down = com.homerelay.app.util.FormatUtils.bytes(s.bytesDown)
        return when (s.phase) {
            SharingPhase.Starting -> "Starting Wi-Fi Direct group…"
            SharingPhase.Sharing ->
                if (clientPresent(s))
                    "Client connected · proxy ${s.bindAddress}:$port · up $up down $down · ${s.totalRequests} requests"
                else
                    "Group active, waiting for client · proxy ${s.bindAddress}:$port"
            SharingPhase.UpstreamUnavailable ->
                "Upstream unavailable — reconnect to home Wi-Fi · proxy ${s.bindAddress}:$port"
            SharingPhase.Failed -> "Sharing stopped"
            else -> "Proxy ${s.bindAddress}:$port · up $up down $down · ${s.totalRequests} requests"
        }
    }

    private fun fail(message: String, sessionId: Long? = null) {
        if (sessionId != null && !session.isCurrent(sessionId)) return // stale session; a newer one is running
        tickJob?.cancel()
        tickJob = null
        AppState.update { it.copy(phase = SharingPhase.Failed, errorMessage = message, clientsConnected = 0, pacUrl = null) }
        proxy?.let { runCatching { it.stop() } }
        proxy = null
        p2p.stop()
        releaseResourceLocks()
        unregisterPinnedNetworkCallback()
        pinnedNetwork = null
        PinnedUpstream.network = null
        updateNotification(
            getString(R.string.notification_title),
            "Sharing stopped: $message",
        )
        val owner = sessionId ?: session.current()
        scope.launch {
            delay(4000)
            if (session.isCurrent(owner)) stopSelfAndClean()
        }
    }

    fun stopSharing() {
        session.invalidate() // retire pending fail-cleanups and session waits
        tickJob?.cancel()
        tickJob = null
        proxy?.let { p ->
            AppState.applySnapshot(p.stats().snapshot(), AppState.state.value.proxyPort)
            p.stop()
        }
        proxy = null
        p2p.stop()
        releaseResourceLocks()
        unregisterPinnedNetworkCallback()
        pinnedNetwork = null
        PinnedUpstream.network = null
        AppState.reset()
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopSelfAndClean() {
        session.invalidate()
        tickJob?.cancel()
        tickJob = null
        proxy?.let { runCatching { it.stop() } }
        proxy = null
        p2p.stop()
        releaseResourceLocks()
        unregisterPinnedNetworkCallback()
        pinnedNetwork = null
        PinnedUpstream.network = null
        stopForegroundCompat()
        stopSelf()
    }

    /* ---------------- notification ---------------- */

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val stopIntent = Intent(this, SharingService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openIntent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openPending = PendingIntent.getActivity(
            this, 2, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_homerelay)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.notification_stop), stopPending)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(title: String, text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    /* ---------------- wake / Wi-Fi locks ---------------- */

    /**
     * Acquire the locks that keep the relay alive with the screen off:
     *  - a PARTIAL_WAKE_LOCK (CPU stays available for the proxy threads and the 1 s tick loop);
     *  - a WIFI_MODE_FULL_HIGH_PERF WifiLock, which stops the Wi-Fi radio entering power
     *    save (the reason groups typically drop a few minutes after lock).
     * Both are reference-count-free, hard-capped at 8 h, released on every exit path.
     * If the Wi-Fi lock cannot be taken (firmware refused), sharing continues with the
     * wake lock and Diagnostics reports the WARN — never a silent downgrade.
     */
    private fun acquireResourceLocks() {
        acquireWakeLock()
        acquireWifiLock()
    }

    private fun acquireWakeLock() {
        if (!HostResources.guard.acquire(HostResources.WAKE_LOCK)) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val acquired = runCatching {
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "homerelay:sharing").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS) // hard cap; released on stop
            }
        }.getOrNull()
        if (acquired == null) {
            HostResources.guard.release(HostResources.WAKE_LOCK)
            wakeLock = null
        } else {
            wakeLock = acquired
        }
    }

    private fun acquireWifiLock() {
        if (!HostResources.guard.acquire(HostResources.WIFI_LOCK)) return
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val acquired = runCatching {
            @Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF is deprecated but still honoured on API 36
            wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "homerelay:sharing")?.apply {
                setReferenceCounted(false)
                acquire() // released on stop; WifiLock has no timeout overload
            }
        }.getOrNull()
        if (acquired == null) {
            HostResources.guard.release(HostResources.WIFI_LOCK)
            wifiLock = null
        } else {
            wifiLock = acquired
        }
    }

    private fun releaseResourceLocks() {
        if (HostResources.guard.release(HostResources.WAKE_LOCK)) {
            try {
                if (wakeLock?.isHeld == true) wakeLock?.release()
            } catch (_: Exception) {
            }
        }
        wakeLock = null
        if (HostResources.guard.release(HostResources.WIFI_LOCK)) {
            try {
                if (wifiLock?.isHeld == true) wifiLock?.release()
            } catch (_: Exception) {
            }
        }
        wifiLock = null
    }

    /* ---------------- helpers ---------------- */

    companion object {
        const val ACTION_START = "com.homerelay.app.action.START"
        const val ACTION_STOP = "com.homerelay.app.action.STOP"
        private const val WAKE_LOCK_TIMEOUT_MS = 8L * 60 * 60 * 1000L // 8 h hard cap
        private const val CHANNEL_ID = "home_relay_sharing"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "HomeRelay"
    }
}
