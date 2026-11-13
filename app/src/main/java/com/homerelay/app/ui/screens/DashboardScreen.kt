package com.homerelay.app.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homerelay.app.net.UpstreamSnapshot
import com.homerelay.app.state.AppState
import com.homerelay.app.state.SharingPhase
import com.homerelay.app.ui.InfoRow
import com.homerelay.app.ui.SectionCard
import com.homerelay.app.ui.WarningBanner
import com.homerelay.app.util.FormatUtils
import kotlinx.coroutines.delay

@Composable
fun DashboardScreen(
    upstream: UpstreamSnapshot,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenGuide: () -> Unit,
    showPassphrase: Boolean,
    onToggleShowPassphrase: () -> Unit,
) {
    val state by AppState.state.collectAsStateWithLifecycle()
    var tick by remember { mutableStateOf(0L) }
    val sharing = state.phase == SharingPhase.Sharing

    LaunchedEffect(sharing) {
        while (sharing) {
            tick = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Mobile-data warning always wins attention.
        if (upstream.cellularActive && !upstream.wifiConnected) {
            item {
                WarningBanner(
                    "Mobile data is the active network. HomeRelay will not use it — " +
                        "connect to Wi-Fi to share, or proxied requests will fail.",
                )
            }
        } else if (upstream.cellularActive) {
            item {
                WarningBanner(
                    "Mobile data is also on. HomeRelay never routes sharing traffic over mobile data.",
                )
            }
        }

        item { HeroStatusCard(state = state, onStart = onStart, onStop = onStop) }

        item {
            SectionCard(title = "Upstream Wi-Fi") {
                InfoRow("Status", if (upstream.wifiConnected) "Connected" else "Not connected")
                InfoRow("SSID", upstream.upstreamSsid ?: "Unavailable")
                InfoRow(
                    "Internet",
                    if (upstream.internetValidated) "Validated" else if (upstream.internetReachable) "Not validated" else "Unavailable",
                )
                InfoRow("Metered", if (upstream.metered) "Yes" else "No")
            }
        }

        if (state.errorMessage != null && state.phase == SharingPhase.Failed) {
            item {
                SectionCard(title = "Last error") {
                    Text(
                        state.errorMessage!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        item {
            SectionCard(title = "Local hotspot") {
                if (sharing) {
                    InfoRow("SSID", state.hotspotSsid ?: "Unavailable (Android hid it)")
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Password",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (showPassphrase) (state.hotspotPassphrase ?: "Unavailable") else "••••••••••",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                            )
                            TextButton(onClick = onToggleShowPassphrase) {
                                Text(if (showPassphrase) "Hide" else "Show")
                            }
                        }
                    }
                    Text(
                        "Android generates the hotspot name and password; they cannot be chosen on stock Android 12.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Start sharing to create the local hotspot. The system assigns its name and password.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item { ProxyCard(state = state, pacEnabled = state.pacUrl != null) }

        item {
            SectionCard(title = "Usage this session") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UsageTile("Upload", FormatUtils.bytes(state.bytesUp), Modifier.weight(1f))
                    UsageTile("Download", FormatUtils.bytes(state.bytesDown), Modifier.weight(1f))
                    UsageTile("Total", FormatUtils.bytes(state.bytesUp + state.bytesDown), Modifier.weight(1f))
                }
                InfoRow("Requests", "${state.totalRequests}  (${state.successfulRequests} ok, ${state.failedRequests} failed)")
                InfoRow("HTTPS tunnels", "${state.connectTunnels}")
                InfoRow("Clients seen (10 min)", if (state.seenDevices > 0) "${state.seenDevices}" else "None yet")
                InfoRow("Active connections", "${state.activeConnections}")
                val startedAt = state.startedAtElapsedRealtimeMs
                val uptime = if (sharing && startedAt > 0L) {
                    FormatUtils.uptime(tickOrZero(tick) - startedAt)
                } else "—"
                InfoRow("Uptime", uptime)
            }
        }

        item {
            FilledTonalButton(onClick = onOpenGuide, modifier = Modifier.fillMaxWidth()) {
                Text("How to connect a device (client guide)")
            }
        }

        item {
            Text(
                "Proxy sharing is not transparent Wi-Fi bridging. Clients must set the HTTP proxy " +
                    "themselves. HTTPS stays encrypted end-to-end (CONNECT tunneling). The hotspot " +
                    "does not share internet to apps that ignore the proxy.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
    }
}

private fun tickOrZero(tick: Long): Long = if (tick > 0L) tick else SystemClock.elapsedRealtime()

@Composable
private fun HeroStatusCard(
    state: com.homerelay.app.state.SharingUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    SectionCard {
        val (title, subtitle) = when (state.phase) {
            SharingPhase.Idle -> "Home internet for nearby devices" to "Tap Start sharing. Your phone becomes the access point; clients use a local proxy."
            SharingPhase.StartingHotspot -> "Starting hotspot…" to "Asking Android for a local-only hotspot."
            SharingPhase.StartingProxy -> "Starting proxy…" to "Binding the proxy to the hotspot interface."
            SharingPhase.Sharing -> "Sharing" to "Proxy is live. Configure your client's Wi-Fi proxy to the values below."
            SharingPhase.Stopping -> "Stopping…" to "Shutting down hotspot and proxy."
            SharingPhase.Failed -> "Sharing stopped" to (state.errorMessage ?: "Unknown error")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.busy) {
                CircularProgressIndicator(Modifier.size(40.dp), strokeWidth = 4.dp)
            } else {
                Column {
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                    )
                    if (state.phase == SharingPhase.Sharing) {
                        Text(
                            "LIVE",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.phase == SharingPhase.Sharing || state.busy) {
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text("Stop sharing", fontSize = 16.sp) }
        } else {
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(if (state.phase == SharingPhase.Failed) "Try again" else "Start sharing", fontSize = 16.sp) }
        }
    }
}

@Composable
private fun UsageTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ProxyCard(state: com.homerelay.app.state.SharingUiState, pacEnabled: Boolean) {
    val clipboard = LocalClipboardManager.current
    SectionCard(title = "Client proxy settings") {
        if (state.phase == SharingPhase.Sharing && state.bindAddress != null) {
            val address = "${state.bindAddress}:${state.proxyPort}"
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
                    .padding(14.dp),
            ) {
                Column {
                    Text("Host : Port", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        address,
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            OutlinedButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(address)) },
                modifier = Modifier.fillMaxWidth()) {
                Text("Copy host:port")
            }
            if (pacEnabled && state.pacUrl != null) {
                InfoRow("PAC URL", state.pacUrl!!)
                OutlinedButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(state.pacUrl!!)) },
                    modifier = Modifier.fillMaxWidth()) {
                    Text("Copy PAC URL")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(state.bindAddress!!)) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy host")
                }
                Text(
                    "On the client: Wi-Fi → network → proxy → manual → host ${state.bindAddress}, port ${state.proxyPort}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text(
                "The host and port appear here while sharing. Clients must configure their Wi-Fi proxy manually (or use the PAC URL).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
