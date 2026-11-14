package com.homerelay.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homerelay.app.data.Settings
import com.homerelay.app.data.SettingsStore
import com.homerelay.app.data.ThemeChoice
import com.homerelay.app.util.SharingPermissions
import com.homerelay.app.diagnostics.Diagnostics
import com.homerelay.app.net.UpstreamMonitor
import com.homerelay.app.service.SharingService
import com.homerelay.app.state.AppState
import com.homerelay.app.ui.screens.DashboardScreen
import com.homerelay.app.ui.screens.DiagnosticsScreen
import com.homerelay.app.ui.screens.GuideScreen
import com.homerelay.app.ui.screens.SettingsScreen
import com.homerelay.app.ui.theme.HomeRelayTheme

private enum class Tab(val label: String) {
    SHARE("Share"),
    GUIDE("Guide"),
    CHECKS("Checks"),
    SETTINGS("Settings"),
}

class MainActivity : ComponentActivity() {

    private lateinit var settingsStore: SettingsStore
    private lateinit var upstream: UpstreamMonitor
    private lateinit var diagnostics: Diagnostics

    private var permissionDenied by androidx.compose.runtime.mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        // Only the Wi-Fi Direct permission blocks sharing. Notification denial just means no shade UI.
        if (SharingPermissions.hasHotspotPermission(this@MainActivity)) {
            sendServiceAction(SharingService.ACTION_START)
        } else {
            permissionDenied = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(this)
        upstream = UpstreamMonitor(this)
        upstream.start()
        diagnostics = Diagnostics(this)

        setContent {
            val initialSettings = remember { settingsStore.load() }
            var themeChoice by remember { mutableStateOf(initialSettings.theme) }
            var showFirstRun by remember { mutableStateOf(!initialSettings.firstRunDone) }
            var showPassphrase by remember { mutableStateOf(initialSettings.showPassphrase) }
            var settingsVersion by remember { mutableStateOf(0) }
            val currentSettings = remember(settingsVersion) { settingsStore.load() }
            val keepScreenOn = currentSettings.keepScreenOn
            val sharingState by AppState.state.collectAsStateWithLifecycle()

            LaunchedEffect(keepScreenOn, sharingState.sharing) {
                if (keepScreenOn && sharingState.sharing) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            val darkTheme = when (themeChoice) {
                ThemeChoice.DARK -> true
                ThemeChoice.LIGHT -> false
                ThemeChoice.SYSTEM -> isSystemInDarkTheme()
            }
            HomeRelayTheme(darkTheme = darkTheme) {
                HomeRelayApp(
                    onThemeChange = { newTheme ->
                        themeChoice = newTheme
                        settingsStore.setTheme(newTheme)
                    },
                    onFirstRunDone = {
                        settingsStore.setFirstRunDone()
                        showFirstRun = false
                    },
                    showFirstRun = showFirstRun,
                    showPassphrase = showPassphrase,
                    onToggleShowPassphrase = {
                        val next = !showPassphrase
                        showPassphrase = next
                        settingsStore.setShowPassphrase(next)
                    },
                    currentSettings = currentSettings,
                    settingsStore = settingsStore,
                    upstream = upstream,
                    diagnostics = diagnostics,
                    onSettingsChanged = {
                        settingsVersion++
                        showPassphrase = settingsStore.load().showPassphrase
                    },
                    onStartRequested = { startSharingWithPermissions() },
                    onStopRequested = { sendServiceAction(SharingService.ACTION_STOP) },
                    onOpenAppDetails = { openAppDetails() },
                    permissionDenied = permissionDenied,
                    onDismissPermissionDenied = { permissionDenied = false },
                )
            }
        }
    }

    override fun onDestroy() {
        upstream.stop()
        super.onDestroy()
    }

    private fun openAppDetails() {
        startActivity(
            Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            },
        )
    }

    private fun startSharingWithPermissions() {
        if (SharingPermissions.hasHotspotPermission(this)) {
            sendServiceAction(SharingService.ACTION_START)
            return
        }
        val toRequest = buildList {
            addAll(SharingPermissions.missingHotspotPermissions(this@MainActivity))
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.distinct()
        if (toRequest.isEmpty()) { // sharing permission satisfied after all (race)
            sendServiceAction(SharingService.ACTION_START)
            return
        }
        // The service is started ONLY from the launcher callback, after the user answers.
        permissionLauncher.launch(toRequest.toTypedArray())
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, SharingService::class.java).setAction(action)
        if (action == SharingService.ACTION_STOP) {
            startService(intent)
        } else {
            startForegroundService(intent)
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeRelayApp(
    onThemeChange: (ThemeChoice) -> Unit,
    onFirstRunDone: () -> Unit,
    showFirstRun: Boolean,
    showPassphrase: Boolean,
    onToggleShowPassphrase: () -> Unit,
    currentSettings: Settings,
    settingsStore: SettingsStore,
    upstream: UpstreamMonitor,
    diagnostics: Diagnostics,
    onSettingsChanged: () -> Unit,
    onStartRequested: () -> Unit,
    onStopRequested: () -> Unit,
    onOpenAppDetails: () -> Unit,
    permissionDenied: Boolean,
    onDismissPermissionDenied: () -> Unit,
) {
    var tab by remember { mutableStateOf(Tab.SHARE) }
    val upstreamSnapshot by upstream.snapshot.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("HomeRelay") }) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    val icon = when (item) {
                        Tab.SHARE -> Icons.Filled.WifiTethering
                        Tab.GUIDE -> Icons.Filled.MenuBook
                        Tab.CHECKS -> Icons.Filled.BugReport
                        Tab.SETTINGS -> Icons.Filled.Settings
                    }
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        Surface(Modifier.padding(padding)) {
            when (tab) {
                Tab.SHARE -> DashboardScreen(
                    upstream = upstreamSnapshot,
                    onStart = onStartRequested,
                    onStop = onStopRequested,
                    onOpenGuide = { tab = Tab.GUIDE },
                    showPassphrase = showPassphrase,
                    onToggleShowPassphrase = onToggleShowPassphrase,
                )
                Tab.GUIDE -> GuideScreen()
                Tab.CHECKS -> DiagnosticsScreen(diagnostics)
                Tab.SETTINGS -> SettingsScreen(
                    settings = currentSettings,
                    onThemeChange = onThemeChange,
                    onProxyPortChange = { port ->
                        settingsStore.setProxyPort(port)
                        onSettingsChanged()
                    },
                    onPacEnabledChange = { enabled ->
                        settingsStore.setPacEnabled(enabled)
                        onSettingsChanged()
                    },
                    onKeepScreenOnChange = { enabled ->
                        settingsStore.setKeepScreenOn(enabled)
                        onSettingsChanged()
                    },
                    onShowPassphraseChange = { enabled ->
                        settingsStore.setShowPassphrase(enabled)
                        onSettingsChanged()
                    },
                    onClearData = {
                        settingsStore.clearAll()
                        onThemeChange(ThemeChoice.SYSTEM)
                        onSettingsChanged()
                    },
                    onOpenAppDetails = onOpenAppDetails,
                )
            }
        }
    }

    if (permissionDenied) {
        AlertDialog(
            onDismissRequest = onDismissPermissionDenied,
            title = { Text("Permission needed") },
            text = {
                Text(
                    "Android refuses to create the Wi-Fi Direct group unless HomeRelay holds the " +
                        (if (Build.VERSION.SDK_INT >= 33)
                            "Nearby devices (Wi-Fi)"
                        else
                            "Location") +
                        " permission. This is an Android requirement for all apps — HomeRelay does " +
                        "not use your location at all; it only asks because the Wi-Fi Direct API demands it.\n\n" +
                        "If the system dialog no longer appears, open App info → Permissions and grant it manually.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDismissPermissionDenied()
                    onOpenAppDetails()
                }) { Text("Open App info") }
            },
            dismissButton = {
                TextButton(onClick = onDismissPermissionDenied) { Text("Cancel") }
            },
        )
    }

    if (showFirstRun) {
        AlertDialog(
            onDismissRequest = onFirstRunDone,
            title = { Text("What HomeRelay does") },
            text = {
                Text(
                    "HomeRelay shares your phone's Wi-Fi internet with nearby devices through a " +
                        "Wi-Fi Direct group plus an on-device web proxy.\n\n" +
                        "• Clients must set their Wi-Fi proxy manually (or use the PAC URL).\n" +
                        "• HTTPS stays encrypted end-to-end.\n" +
                        "• No ads, no analytics, no accounts — nothing leaves your phone except the share itself.\n" +
                        "• HomeRelay generates a fresh group name and password per session.\n\n" +
                        "Same Wi-Fi name/password on two devices is not mesh, not roaming, and not a repeater. " +
                        "This is proxy sharing, honest and explicit.\n\n" +
                        "For One UI: exempt HomeRelay from battery optimization (Settings screen) so the session stays alive.",
                )
            },
            confirmButton = { TextButton(onClick = onFirstRunDone) { Text("Got it") } },
        )
    }
}
