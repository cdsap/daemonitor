package io.github.cdsap.daemonitor.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.cdsap.daemonitor.config.RetentionPolicy
import io.github.cdsap.daemonitor.persistence.AppearancePreference
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
    onMcpEnabled: (Boolean) -> Unit = {},
    onCheckForUpdates: () -> Unit = {},
    onOpenUpdate: (io.github.cdsap.daemonitor.update.UpdateCandidate) -> Unit = {},
    onRestartAndUpdate: () -> Unit = {},
    onOpenManualDownload: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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
            modifier = Modifier.fillMaxWidth().height(210.dp).padding(horizontal = Space.lg),
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
                if (state.updateState != UpdateUiState.ManagedByAppStore) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = onCheckForUpdates,
                            enabled = state.updateState != UpdateUiState.Checking &&
                                state.updateState !is UpdateUiState.Downloading,
                            shape = RoundedCornerShape(Radius.sm),
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                            Spacer(Modifier.width(Space.xs))
                            Text(if (state.updateState == UpdateUiState.Checking) "Checking" else "Check for updates")
                        }
                        when (val updateState = state.updateState) {
                            is UpdateUiState.Available -> {
                                Button(
                                    onClick = { onOpenUpdate(updateState.candidate) },
                                    shape = RoundedCornerShape(Radius.sm),
                                ) {
                                    Icon(Icons.Filled.Download, contentDescription = null)
                                    Spacer(Modifier.width(Space.xs))
                                    Text("Download Update")
                                }
                            }
                            is UpdateUiState.ReadyToInstall -> {
                                if (updateState.candidate.installMode == io.github.cdsap.daemonitor.update.UpdateInstallMode.Automatic &&
                                    updateState.staged != null
                                ) {
                                    Button(
                                        onClick = onRestartAndUpdate,
                                        shape = RoundedCornerShape(Radius.sm),
                                    ) {
                                        Icon(Icons.Filled.Refresh, contentDescription = null)
                                        Spacer(Modifier.width(Space.xs))
                                        Text("Restart and Update")
                                    }
                                } else {
                                    Button(
                                        onClick = { onOpenUpdate(updateState.candidate) },
                                        shape = RoundedCornerShape(Radius.sm),
                                    ) {
                                        Icon(Icons.Filled.Download, contentDescription = null)
                                        Spacer(Modifier.width(Space.xs))
                                        Text("Open again")
                                    }
                                }
                            }
                            is UpdateUiState.Failed -> {
                                if (updateState.releaseUrl != null) {
                                    OutlinedButton(
                                        onClick = onOpenManualDownload,
                                        shape = RoundedCornerShape(Radius.sm),
                                    ) {
                                        Icon(Icons.Filled.Download, contentDescription = null)
                                        Spacer(Modifier.width(Space.xs))
                                        Text("Manual download")
                                    }
                                }
                            }
                            else -> Unit
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
                        "Range ${RetentionPolicy.DEFAULT.minDays}–${RetentionPolicy.DEFAULT.maxDays} days; default ${RetentionPolicy.DEFAULT.defaultDays}.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Space.md))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    RetentionPolicy.DEFAULT.presets.forEach { days ->
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
        Spacer(Modifier.height(Space.md))
        SectionCard(
            "MCP",
            modifier = Modifier.fillMaxWidth().height(230.dp).padding(horizontal = Space.lg),
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = state.mcpEnabled,
                            role = Role.Switch,
                            onValueChange = onMcpEnabled,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Icon(Icons.Filled.Hub, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Enable MCP", style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(
                        checked = state.mcpEnabled,
                        onCheckedChange = null,
                    )
                }
                Spacer(Modifier.height(Space.sm))
                Text(
                    state.mcpState.message(state.mcpPort),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.mcpEnabled) {
                    Spacer(Modifier.height(Space.sm))
                    Text(
                        "URL: ${state.mcpEndpoint}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        "Token: ${state.mcpToken}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private val AppearancePreference.displayName: String
    get() = name.lowercase().replaceFirstChar(Char::uppercase)

private val SettingsUiState.mcpEndpoint: String
    get() = (mcpState as? McpUiState.Running)?.endpoint ?: "http://127.0.0.1:$mcpPort/mcp"

private fun McpUiState.message(port: Int): String = when (this) {
    McpUiState.Stopped -> "Expose read-only build history and current Gradle processes to local MCP clients."
    McpUiState.Starting -> "Starting local MCP server on 127.0.0.1:$port..."
    is McpUiState.Running -> "Running on $endpoint. Keep Daemonitor open while your MCP client is connected."
    is McpUiState.Failed -> "MCP could not start: $message"
}

private val UpdateUiState.message: String
    get() = when (this) {
        UpdateUiState.NotChecked -> "Check GitHub Releases for a newer Daemonitor version. Updates are never installed without your approval."
        UpdateUiState.Checking -> "Checking GitHub Releases..."
        is UpdateUiState.UpToDate -> "Daemonitor is up to date at v$version."
        is UpdateUiState.Available -> when (candidate.installMode) {
            io.github.cdsap.daemonitor.update.UpdateInstallMode.Automatic ->
                "v${candidate.version} is available for ${candidate.platform.metadataName}/${candidate.architecture.token}. Download stages the update while Daemonitor keeps running."
            io.github.cdsap.daemonitor.update.UpdateInstallMode.Manual ->
                "v${candidate.version} is available. Automatic installation is not supported for this install; Daemonitor can download and open ${candidate.assetName}."
        }
        is UpdateUiState.Downloading -> progress?.let { "Downloading ${candidate.assetName}: ${(it * 100).toInt()}%." }
            ?: "Downloading ${candidate.assetName}..."
        is UpdateUiState.ReadyToInstall -> when {
            candidate.installMode == io.github.cdsap.daemonitor.update.UpdateInstallMode.Automatic && staged != null ->
                "Daemonitor ${candidate.version} is ready to install."
            else ->
                "Downloaded and opened ${candidate.assetName}. Finish the installer to update Daemonitor."
        }
        UpdateUiState.ManagedByAppStore ->
            "This Mac App Store build is updated by Apple. The GitHub Releases updater is disabled."
        is UpdateUiState.Failed -> message
    }
