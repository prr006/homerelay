package com.homerelay.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.homerelay.app.diagnostics.CheckResult
import com.homerelay.app.diagnostics.CheckStatus
import com.homerelay.app.diagnostics.Diagnostics
import com.homerelay.app.ui.SectionCard
import kotlinx.coroutines.launch

@Composable
fun DiagnosticsScreen(diagnostics: Diagnostics) {
    var results by androidx.compose.runtime.mutableStateOf<List<CheckResult>?>(null)
    var running by androidx.compose.runtime.mutableStateOf(false)
    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(title = "Live diagnostics") {
                Text(
                    "These checks run on this phone against the real network and the real proxy. " +
                        "Nothing is simulated. Checks that need an active session report INFO when idle.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        running = true
                        scope.launch {
                            results = diagnostics.runAll()
                            running = false
                        }
                    },
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (running) "Running checks…" else if (results == null) "Run all checks" else "Re-run checks") }
                if (running) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    }
                }
                results?.let { list ->
                    val passed = list.count { it.status == CheckStatus.PASS }
                    val warned = list.count { it.status == CheckStatus.WARN }
                    val failed = list.count { it.status == CheckStatus.FAIL }
                    Text(
                        "Summary: $passed passed · $warned warnings · $failed failed",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
        results?.forEach { result ->
            item(key = result.id) { CheckRow(result) }
        }
    }
}

@Composable
private fun CheckRow(result: CheckResult) {
    val (container, content, label) = when (result.status) {
        CheckStatus.PASS -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            "PASS",
        )
        CheckStatus.WARN -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            "WARN",
        )
        CheckStatus.FAIL -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            "FAIL",
        )
        CheckStatus.INFO -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "INFO",
        )
    }
    SectionCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(result.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            SuggestionChip(
                onClick = {},
                label = { Text(label, color = content) },
                colors = SuggestionChipDefaults.suggestionChipColors(containerColor = container),
            )
        }
        Text(
            result.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
