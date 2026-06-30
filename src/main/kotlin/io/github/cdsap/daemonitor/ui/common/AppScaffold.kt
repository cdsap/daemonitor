package io.github.cdsap.daemonitor.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.cdsap.daemonitor.BuildInfo

/** Top-level navigation: Live Monitor and Historical tabs (U7). Resolves the navigation-model gap. */
@Composable
fun AppScaffold(
    liveContent: @Composable () -> Unit,
    historyContent: @Composable () -> Unit,
    settingsContent: @Composable () -> Unit,
    buildInfo: BuildInfo = BuildInfo.current,
) {
    var selectedTab by remember { mutableStateOf(0) }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AppHeader(buildInfo, selectedTab) { selectedTab = it }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> liveContent()
                1 -> historyContent()
                else -> settingsContent()
            }
        }
    }
}

/** A slim brand bar so the window reads as a product, not a bare tab strip. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppHeader(buildInfo: BuildInfo, selectedTab: Int, onSelectTab: (Int) -> Unit) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .height(56.dp),
    ) {
        val compact = maxWidth < 760.dp
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = Space.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Image(
                painter = painterResource("icon/daemonitor.png"),
                contentDescription = "Daemonitor logo",
                modifier = Modifier.size(28.dp),
            )
            Text(
                "Daemonitor",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.width(Space.lg))
            SingleChoiceSegmentedButtonRow {
                NAV_ITEMS.forEachIndexed { index, item ->
                    SegmentedButton(
                        selected = selectedTab == index,
                        onClick = { onSelectTab(index) },
                        shape = SegmentedButtonDefaults.itemShape(index, NAV_ITEMS.size),
                        icon = {},
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                                Icon(item.icon, contentDescription = null, modifier = Modifier.size(15.dp))
                                Text(item.label)
                            }
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            if (!compact) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.size(7.dp).background(Accent.success, androidx.compose.foundation.shape.CircleShape),
                    )
                    Text("LOCAL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.width(Space.md))
                Text(
                    "v${buildInfo.version} · ${buildInfo.commit}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class NavItem(val label: String, val icon: ImageVector)

private val NAV_ITEMS = listOf(
    NavItem("Live", Icons.Filled.Speed),
    NavItem("History", Icons.Filled.History),
    NavItem("Settings", Icons.Filled.Settings),
)
