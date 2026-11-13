package com.homerelay.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.homerelay.app.MainActivity
import com.homerelay.app.R
import com.homerelay.app.data.SettingsStore
import com.homerelay.app.hotspot.HotspotState
import com.homerelay.app.hotspot.LocalOnlyHotspotController
import com.homerelay.app.net.HotspotInterfaceLocator
import com.homerelay.app.net.UpstreamConnector
import com.homerelay.app.proxy.ProxyServer
import com.homerelay.app.state.AppState
import com.homerelay.app.state.SharingPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service that owns the whole sharing session:
 *   local-only hotspot  ->  find its interface  ->  proxy bound to that interface only
 *
 * Design rules honoured here:
 *  - Outbound sockets go ONLY to the upstream Wi-Fi network (see UpstreamConnector).
 *  - The proxy binds ONLY to the hotspot interface — never to the upstream network.
 *  - If the hotspot interface cannot be identified, the proxy is not started.
 *  - The service is started by the user and stopped by the user (or when the system kills it).
 *    It never restarts after reboot and never runs without a visible notification.
 */
class SharingService : Service() {

    private lateinit var hotspot: LocalOnlyHotspotController
    private var proxy: ProxyServer? = null
    private var tickJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        hotspot = LocalOnlyHotspotController(this)
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
        if (current.phase == SharingPhase.Sharing || current.busy) return

        val settings = SettingsStore(this).load()
        AppState.update {
            it.copy(
                phase = SharingPhase.StartingHotspot,
                errorMessage = null,
                proxyPort = settings.proxyPort,
                hotspotSsid = null,
                hotspotPassphrase = null,
                bindAddress = null,
                pacUrl = null,
            )
        }
        startForegroundCompat(buildNotification(getString(R.string.notification_title),
            getString(R.string.notification_channel_name)))

        scope.launch { runSession(settings) }
    }

    private suspend fun runSession(settings: com.homerelay.app.data.Settings) {
        // 1. Hotspot
        hotspot.start()
        val terminal = withTimeoutOrNull(15_000) {
            hotspot.state.first { it is HotspotState.Active || it is HotspotState.Failed }
        }
        when (terminal) {
            null -> fail("Hotspot did not start within 15 seconds. Try toggling Wi-Fi off and on.")
            is HotspotState.Failed -> fail(terminal.reason)
            is HotspotState.Active -> { }
            else -> fail("Hotspot did not start.")
        }
        val active = hotspot.state.value as? HotspotState.Active ?: return

        // 2. Bind address — the hotspot interface, never the upstream network.
        val locator = HotspotInterfaceLocator.findHotspotInterface(this)
        if (locator == null) {
            fail(
                "Could not identify the hotspot's interface. Candidates: " +
                    HotspotInterfaceLocator.debugCandidateNames(this).joinToString(", ").ifEmpty { "none" } +
                    ". Refusing to bind the proxy to all interfaces.",
            )
            return
        }
        val bindAddress = locator.address.hostAddress ?: "unavailable"
        AppState.update { it.copy(phase = SharingPhase.StartingProxy, bindAddress = bindAddress) }

        // 3. Proxy — configured port first, automatic free-port fallback.
        val config = ProxyServer.Config().apply {
            pacHost = bindAddress
            pacPort = settings.proxyPort
            viaHeader = "HomeRelay"
        }
        val connector = UpstreamConnector(this)
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
                fail("Proxy could not bind to $bindAddress: ${e2.message}")
                hotspot.stop()
                return
            }
        }
        config.pacPort = boundPort
        proxy = server

        val pacUrl = if (settings.pacEnabled) "http://$bindAddress:$boundPort/proxy.pac" else null
        AppState.update {
            it.copy(
                phase = SharingPhase.Sharing,
                hotspotSsid = active.ssid,
                hotspotPassphrase = active.passphrase,
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
        acquireWakeLock()
        updateNotification(getString(R.string.notification_title),
            "Clients set their proxy to $bindAddress:$boundPort")
        startTickLoop(server, boundPort)
    }

    private fun startTickLoop(server: ProxyServer, port: Int) {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                AppState.applySnapshot(server.stats().snapshot(), port)
                val s = AppState.state.value
                val up = com.homerelay.app.util.FormatUtils.bytes(s.bytesUp)
                val down = com.homerelay.app.util.FormatUtils.bytes(s.bytesDown)
                updateNotification(
                    getString(R.string.notification_title),
                    "Proxy ${s.bindAddress}:$port  ·  up $up  down $down  ·  ${s.totalRequests} requests",
                )
                delay(1000)
            }
        }
    }

    private fun fail(message: String) {
        AppState.update { it.copy(phase = SharingPhase.Failed, errorMessage = message) }
        hotspot.stop()
        updateNotification(
            getString(R.string.notification_title),
            "Sharing stopped: $message",
        )
        scope.launch {
            delay(4000)
            stopSelfAndClean()
        }
    }

    fun stopSharing() {
        tickJob?.cancel()
        tickJob = null
        proxy?.let { p ->
            AppState.applySnapshot(p.stats().snapshot(), AppState.state.value.proxyPort)
            p.stop()
        }
        proxy = null
        hotspot.stop()
        releaseWakeLock()
        AppState.reset()
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopSelfAndClean() {
        tickJob?.cancel()
        tickJob = null
        proxy?.stop()
        proxy = null
        hotspot.stop()
        releaseWakeLock()
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
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    /* ---------------- wakelock ---------------- */

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "homerelay:sharing").apply {
            setReferenceCounted(false)
            acquire(8 * 60 * 60 * 1000L) // 8h hard cap; released on stop
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    /* ---------------- helpers ---------------- */


    companion object {
        const val ACTION_START = "com.homerelay.app.action.START"
        const val ACTION_STOP = "com.homerelay.app.action.STOP"
        private const val CHANNEL_ID = "home_relay_sharing"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "HomeRelay"
    }
}
