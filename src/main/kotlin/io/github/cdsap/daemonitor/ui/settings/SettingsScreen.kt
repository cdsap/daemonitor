package io.github.cdsap.daemonitor.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.cdsap.daemonitor.Defaults
import io.github.cdsap.daemonitor.store.AppearancePreference
import io.github.cdsap.daemonitor.ui.common.SectionCard
import io.github.cdsap.daemonitor.ui.common.ScreenHeader
import io.github.cdsap.daemonitor.ui.common.Radius
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChipDefaults
import io.github.cdsap.daemonitor.ui.common.Space

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onRetentionDays: (Long) -> Unit,
    onAppearance: (AppearancePreference) -> Unit = {},
    onCheckForUpdates: () -> Unit = {},
    onOpenUpdate: (io.github.cdsap.daemonitor.update.UpdateCandidate) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        ScreenHeader("Settings")
        SectionCard(
            "Appearance",
            modifier = Modifier.fillMaxWidth().height(160.dp).padding(horizontal = Space.lg),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Icon(Icons.Filled.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Color theme", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(Space.sm))
                Text(
                    "System follows your operating system appearance.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Space.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    AppearancePreference.entries.forEach { appearance ->
                        FilterChip(
                            selected = state.appearance == appearance,
                            onClick = { onAppearance(appearance) },
                            label = { Text(appearance.displayName) },
                            shape = RoundedCornerShape(Radius.sm),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(Space.md))
        SectionCard(
            "Updates",
            modifier = Modifier.fillMaxWidth().height(180.dp).padding(horizontal = Space.lg),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Application updates", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(Space.sm))
                Text(
                    state.updateState.message,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val downloading = state.updateState as? UpdateUiState.Downloading
                if (downloading != null) {
                    Spacer(Modifier.height(Space.sm))
                    LinearProgressIndicator(
                        progress = { downloading.progress?.coerceIn(0.0, 1.0)?.toFloat() ?: 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(Space.md))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = onCheckForUpdates,
                        enabled = state.updateState != UpdateUiState.Checking,
                        shape = RoundedCornerShape(Radius.sm),
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Spacer(Modifier.width(Space.xs))
                        Text(if (state.updateState == UpdateUiState.Checking) "Checking" else "Check for updates")
                    }
                    val available = state.updateState as? UpdateUiState.Available
                    val ready = state.updateState as? UpdateUiState.ReadyToInstall
                    val candidate = available?.candidate ?: ready?.candidate
                    if (candidate != null) {
                        Button(
                            onClick = { onOpenUpdate(candidate) },
                            enabled = state.updateState !is UpdateUiState.Downloading,
                            shape = RoundedCornerShape(Radius.sm),
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null)
                            Spacer(Modifier.width(Space.xs))
                            Text(if (ready != null) "Open again" else "Download and open")
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(Space.md))
        SectionCard(
            "History retention",
            modifier = Modifier.fillMaxWidth().height(190.dp).padding(horizontal = Space.lg),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Icon(Icons.Filled.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Keep build & process history for", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${state.retentionDays} days",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(Space.sm))
                Text(
                    "Entries older than this are removed on startup and immediately when you lower it. " +
                        "Range ${Defaults.MIN_RETENTION_DAYS}–${Defaults.MAX_RETENTION_DAYS} days; default ${Defaults.DEFAULT_RETENTION_DAYS}.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Space.md))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Defaults.RETENTION_PRESETS.forEach { days ->
                        FilterChip(
                            selected = state.retentionDays == days,
                            onClick = { onRetentionDays(days) },
                            label = { Text("$days days") },
                            shape = RoundedCornerShape(Radius.sm),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        )
                    }
                }
            }
        }
    }
}

private val AppearancePreference.displayName: String
    get() = name.lowercase().replaceFirstChar(Char::uppercase)

private val UpdateUiState.message: String
    get() = when (this) {
        UpdateUiState.NotChecked -> "Check GitHub Releases for a newer Daemonitor installer. Nothing is installed without approval."
        UpdateUiState.Checking -> "Checking GitHub Releases..."
        is UpdateUiState.UpToDate -> "Daemonitor is up to date at v$version."
        is UpdateUiState.Available -> "v${candidate.version} is available. Daemonitor will download and verify ${candidate.assetName} before opening it."
        is UpdateUiState.Downloading -> progress?.let { "Downloading ${candidate.assetName}: ${(it * 100).toInt()}%." }
            ?: "Downloading ${candidate.assetName}..."
        is UpdateUiState.ReadyToInstall -> "Downloaded and opened ${candidate.assetName}. Finish the installer to update Daemonitor."
        is UpdateUiState.Failed -> message
    }
