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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
