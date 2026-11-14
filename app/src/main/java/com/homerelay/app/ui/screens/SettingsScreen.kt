package com.homerelay.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import com.homerelay.app.data.Settings
import com.homerelay.app.data.ThemeChoice
import com.homerelay.app.data.Validation
import com.homerelay.app.ui.InfoRow
import com.homerelay.app.ui.SectionCard
import com.homerelay.app.ui.WarningBanner

@Composable
fun SettingsScreen(
    settings: Settings,
    onThemeChange: (ThemeChoice) -> Unit,
    onProxyPortChange: (Int) -> Unit,
    onPacEnabledChange: (Boolean) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onShowPassphraseChange: (Boolean) -> Unit,
    onClearData: () -> Unit,
    onOpenAppDetails: () -> Unit,
) {
    val context = LocalContext.current
    var portText by remember(settings.proxyPort) { mutableStateOf(settings.proxyPort.toString()) }
    val portError: String? = remember(portText) {
        if (portText.trim().isEmpty()) null
        else if (Validation.parseProxyPort(portText) == -1) "Enter a port between ${Validation.MIN_PROXY_PORT} and ${Validation.MAX_PROXY_PORT}"
        else null
    }
    var showClearConfirm by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(title = "Proxy") {
                OutlinedTextField(
                    value = portText,
                    onValueChange = { new ->
                        val digits = new.filter { it.isDigit() }.take(5)
                        portText = digits
                        val parsed = Validation.parseProxyPort(digits)
                        if (parsed != -1) onProxyPortChange(parsed)
                    },
                    label = { Text("Proxy port") },
                    supportingText = { Text(portError ?: "Clients will connect to this port on the hotspot address.") },
                    isError = portError != null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ListItem(
                    headlineContent = { Text("PAC auto-config endpoint") },
                    supportingContent = { Text("Serves /proxy.pac on the hotspot address. Turn off to hide the PAC file (manual setup still works).") },
                    trailingContent = {
                        SwitchChecked(settings.pacEnabled, onPacEnabledChange)
                    },
                )
            }
        }

        item {
            SectionCard(title = "Appearance") {
                ThemeChoice.values().forEach { choice ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onThemeChange(choice) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = settings.theme == choice,
                            onClick = { onThemeChange(choice) },
                        )
                        Text(
                            when (choice) {
                                ThemeChoice.SYSTEM -> "System theme"
                                ThemeChoice.LIGHT -> "Light"
                                ThemeChoice.DARK -> "Dark"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                ListItem(
                    headlineContent = { Text("Keep screen on while sharing") },
                    supportingContent = { Text("Useful when watching which clients connect. Costs battery; off by default.") },
                    trailingContent = { SwitchChecked(settings.keepScreenOn, onKeepScreenOnChange) },
                )
                ListItem(
                    headlineContent = { Text("Show hotspot password") },
                    supportingContent = { Text("Off hides it behind bullets until you tap Show.") },
                    trailingContent = { SwitchChecked(settings.showPassphrase, onShowPassphraseChange) },
                )
            }
        }

        item {
            SectionCard(title = "One UI battery & background limits") {
                Text(
                    "Samsung One UI can defer or stop background sharing. For the best chance of staying alive:\n" +
                        "1. Exempt HomeRelay from battery optimization (button below).\n" +
                        "2. Device care → Battery → Background usage limits → make sure HomeRelay is not in “Sleeping apps” or “Deep sleeping apps”.\n" +
                        "3. Add HomeRelay to “Never sleeping apps”.\n" +
                        "These are system dialogs; HomeRelay cannot and does not bypass them.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                FilledTonalButton(
                    onClick = {
                        val intent = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        runCatching { context.startActivity(intent) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open battery optimization exemption dialog") }
                OutlinedButton(
                    onClick = onOpenAppDetails,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open App info (notifications, permissions)") }
            }
        }

        item {
            SectionCard(title = "Security & privacy") {
                Text(
                    "• No analytics, telemetry or ads — ever.\n" +
                        "• All sharing traffic stays on your LAN via the on-device proxy; nothing is sent to HomeRelay servers because none exist.\n" +
                        "• HTTPS traffic is end-to-end encrypted and never decrypted by the proxy.\n" +
                        "• The hotspot uses WPA2/WPA3 as configured by Android; Android assigns the credentials.\n" +
                        "• Anyone on the hotspot can use the proxy while sharing is on. Stop sharing when you are done (or use the persistent notification).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                HorizontalDivider()
                Text(
                    "HomeRelay: proxy-based internet sharing over a local-only hotspot. Same-name/same-password Wi-Fi is not mesh, not roaming, not a transparent repeater.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(title = "About") {
                InfoRow("Version", "1.1.0 (build 2)")
                InfoRow("Package", "com.homerelay.app")
                InfoRow("License", "MIT")
                InfoRow("Source", "github.com/prr006/homerelay")
                InfoRow("Android", "API 26+ (targets API 36)")
                Text(
                    "Not affiliated with Samsung or Google. Tested configurations and known limitations are listed in the project documentation — see BUILDING.md and TROUBLESHOOTING.md in the repository.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(title = "Danger zone") {
                Text(
                    "Clear stored settings (port, theme, flags). Does not affect Android, Wi-Fi or other apps.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { showClearConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Clear stored settings", color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear settings?") },
            text = { Text("HomeRelay will forget its port, theme and toggles. Android itself is untouched.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    onClearData()
                }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SwitchChecked(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(checked = checked, onCheckedChange = onChange)
}
