package io.github.cdsap.daemonitor.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.cdsap.daemonitor.domain.model.BazelWindowSummary
import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.domain.model.BuildTool
import io.github.cdsap.daemonitor.domain.model.ProcessType
import io.github.cdsap.daemonitor.domain.model.Source
import java.util.Locale
import io.github.cdsap.daemonitor.ui.common.Cell
import io.github.cdsap.daemonitor.ui.common.CellSlot
import io.github.cdsap.daemonitor.ui.common.Col
import io.github.cdsap.daemonitor.ui.common.ProcessTypeIcon
import io.github.cdsap.daemonitor.ui.common.EmptyState
import io.github.cdsap.daemonitor.ui.common.LogView
import io.github.cdsap.daemonitor.ui.common.PrivacyNotice
import io.github.cdsap.daemonitor.ui.common.Radius
import io.github.cdsap.daemonitor.ui.common.SectionCard
import io.github.cdsap.daemonitor.ui.common.ScreenHeader
import io.github.cdsap.daemonitor.ui.common.Space
import io.github.cdsap.daemonitor.ui.common.SourcePill
import io.github.cdsap.daemonitor.ui.common.StatusPill
import io.github.cdsap.daemonitor.ui.common.TableHeader
import io.github.cdsap.daemonitor.ui.common.cycleIndex
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val COLS = listOf(
    Col("Started", 1.55f),
    Col("Project", 1.15f),
    Col("Duration", 0.75f, end = true),
    Col("Peak RSS", 1.0f, end = true),
    Col("Status", 1.35f),
    Col("Source", 1.15f),
    Col("Agent", 0.95f),
)

@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onProject: (String?) -> Unit,
    onTimeRange: (TimeRange) -> Unit,
    onTool: (ToolFilter) -> Unit = {},
) {
    var selected by remember { mutableStateOf<Build?>(null) }
    val listFocus = remember { FocusRequester() }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Filters(state, onProject, onTimeRange, onTool)
        if (state.isEmptyResult) {
            EmptyState(
                when (state.filter.tool) {
                    ToolFilter.BAZEL ->
                        "No Bazel builds match the current filters. Bazel history appears when command.profile.gz or BEP is in reach (Daemonitor never enables BEP)."
                    ToolFilter.GRADLE ->
                        "No Gradle builds match the current filters. History is filled from Gradle daemon logs."
                    ToolFilter.ALL ->
                        "No builds match the current filters. Gradle rows come from daemon logs; Bazel rows appear when command.profile.gz or BEP is in reach."
                },
                modifier = Modifier.weight(1f),
            )
        } else {
            // Request once when the table appears — filter refreshes keep keyboard focus via row clicks.
            LaunchedEffect(Unit) { listFocus.requestFocus() }
            fun moveSelection(delta: Int) {
                val currentIndex = selected?.let { sel -> state.builds.indexOfFirst { it.buildId == sel.buildId } }
                    ?.takeIf { it >= 0 }
                val next = cycleIndex(state.builds.size, currentIndex, delta) ?: return
                selected = state.builds[next]
            }
            Row(modifier = Modifier.weight(1f).padding(start = Space.lg, end = Space.lg, bottom = Space.lg)) {
                Surface(
                    modifier = Modifier.weight(1.5f).fillMaxSize(),
                    shape = RoundedCornerShape(Radius.md),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRequester(listFocus)
                            .focusable()
                            .testTag("history-build-list")
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.DirectionDown -> { moveSelection(1); true }
                                    Key.DirectionUp -> { moveSelection(-1); true }
                                    else -> false
                                }
                            },
                    ) {
                        TableHeader(COLS)
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(state.builds, key = { it.buildId }) { b ->
                                BuildRow(b, b.buildId == selected?.buildId) {
                                    selected = b
                                    listFocus.requestFocus()
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
                Spacer(Modifier.width(Space.lg))
                SectionCard(
                    when (selected?.tool) {
                        BuildTool.BAZEL -> "Bazel build detail"
                        else -> "Gradle build detail"
                    },
                    modifier = Modifier.weight(1f).fillMaxSize(),
                ) {
                    HistoryDetail(selected)
                }
            }
        }
        PrivacyNotice()
    }
}

@Composable
private fun Filters(
    state: HistoryUiState,
    onProject: (String?) -> Unit,
    onTimeRange: (TimeRange) -> Unit,
    onTool: (ToolFilter) -> Unit,
) {
    Column {
        ScreenHeader("Build history")
        Text(
            "Gradle from daemon logs; Bazel when command.profile.gz or BEP is in reach (Daemonitor never enables BEP).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Space.lg, end = Space.lg, bottom = Space.sm),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = Space.lg, end = Space.lg, bottom = Space.md),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("TOOL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ToolFilter.entries.forEach { tool ->
                FilterChip(
                    selected = state.filter.tool == tool,
                    onClick = { onTool(tool) },
                    label = { Text(tool.label) },
                    shape = RoundedCornerShape(Radius.sm),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
            Spacer(Modifier.width(Space.sm))
            Text("RANGE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TimeRange.entries.forEach { range ->
                FilterChip(
                    selected = state.filter.timeRange == range,
                    onClick = { onTimeRange(range) },
                    label = { Text(range.label) },
                    shape = RoundedCornerShape(Radius.sm),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
            Spacer(Modifier.width(Space.sm))
            Text("PROJECT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ProjectDropdown(state.projects, state.filter.projectPath, onProject)
        }
    }
}

@Composable
private fun ProjectDropdown(projects: List<String>, selected: String?, onProject: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    AssistChip(
        onClick = { expanded = true },
        label = {
            Text(
                selected
                    ?.substringAfterLast('/')
                    ?.substringAfterLast('\\')
                    ?.let { "Project: $it" }
                    ?: "All projects",
            )
        },
        shape = RoundedCornerShape(Radius.sm),
    )
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(text = { Text("All projects") }, onClick = { onProject(null); expanded = false })
        projects.forEach { p ->
            DropdownMenuItem(text = { Text(p) }, onClick = { onProject(p); expanded = false })
        }
    }
}

@Composable
private fun BuildRow(b: Build, selected: Boolean, onSelect: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f) else MaterialTheme.colorScheme.surface
    val agent = b.displayAgent()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable { onSelect() }
            .padding(horizontal = Space.md, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CellSlot(COLS[0]) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                ProcessTypeIcon(
                    when (b.tool) {
                        BuildTool.BAZEL -> ProcessType.BAZEL_SERVER
                        BuildTool.GRADLE -> ProcessType.GRADLE_DAEMON
                    },
                    size = 15.dp,
                )
                Text(
                    formatTime(b.startTimeMs),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("history-started-${b.buildId}"),
                )
            }
        }
        Cell(
            b.projectPath?.substringAfterLast('/')?.substringAfterLast('\\') ?: "—",
            COLS[1],
            muted = b.projectPath == null,
        )
        Cell(formatDuration(b.durationSeconds), COLS[2])
        Cell(b.peakMemoryMb?.let { "$it MB" } ?: "not sampled", COLS[3], muted = b.peakMemoryMb == null)
        CellSlot(COLS[4]) { StatusPill(b.finalStatus) }
        CellSlot(COLS[5]) { SourcePill(b.inferredSource) }
        CellSlot(COLS[6]) { AgentLabel(agent) }
    }
}

/** Agent cell: a small AI glyph + the agent name when one was detected; a muted dash otherwise. */
@Composable
private fun AgentLabel(agent: String?) {
    if (agent == null) {
        Text("—", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        Icon(
            Icons.Filled.SmartToy,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp),
        )
        Text(agent, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HistoryDetail(build: Build?) {
    if (build == null) {
        Text("Select a build to see details.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        return
    }
    val agent = build.displayAgent()
    val bazel = build.tool == BuildTool.BAZEL
    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            ProcessTypeIcon(
                if (bazel) ProcessType.BAZEL_SERVER else ProcessType.GRADLE_DAEMON,
                size = 18.dp,
            )
            Text(
                if (bazel) "Bazel build ${build.buildId}" else "Gradle build ${build.buildId}",
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).testTag("history-detail-title"),
            )
            StatusPill(build.finalStatus)
        }
        Spacer(Modifier.padding(Space.xs))
        DetailRow("Project", build.projectPath ?: "—")
        DetailRow("Duration", formatDuration(build.durationSeconds))
        DetailRow("Peak memory", build.peakMemoryMb?.let { "$it MB" } ?: "not sampled (<2s)")
        DetailRow("Avg memory", build.avgMemoryMb?.let { "$it MB" } ?: "not sampled (<2s)")
        DetailRow("Peak CPU", build.peakCpuPercent?.let { "%.0f%%".format(Locale.ROOT, it) } ?: "not sampled (<2s)")
        DetailRow("Source", build.inferredSource.name.lowercase())
        DetailRow("Tool", build.tool.name.lowercase())
        if (!bazel) {
            DetailRow("Agent", agent ?: "not detected")
            DetailRow("LLM provider", agent?.let { build.agentProvider ?: "unknown" } ?: "—")
        } else if (agent != null) {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Agent", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Row(modifier = Modifier.weight(2f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    AgentLabel(agent)
                    build.agentProvider?.let { provider ->
                        Text("($provider)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.padding(Space.xs))
        if (bazel) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .testTag("bazel-history-detail-scroll"),
            ) {
                val details = build.bazelDetails
                val profile = build.bazelProfile
                val bazelVersion = details?.bazelVersion ?: profile?.bazelVersion
                if (bazelVersion != null) {
                    DetailRow("Bazel version", bazelVersion)
                }
                if (details?.hasTiming == true || profile?.phaseSeconds?.isNotEmpty() == true || profile?.criticalPathSeconds != null) {
                    HistorySection("Phase timings")
                    details?.analysisPhaseMs?.let { DetailRow("Analysis", formatMillis(it)) }
                    details?.executionPhaseMs?.let { DetailRow("Execution", formatMillis(it)) }
                    details?.wallTimeMs?.let { DetailRow("Wall time", formatMillis(it)) }
                    details?.cpuTimeMs?.let { DetailRow("CPU time", formatMillis(it)) }
                    if (details?.hasTiming != true) {
                        profile?.phaseSeconds?.entries?.sortedBy { it.key }?.forEach { (phase, sec) ->
                            DetailRow(phase, formatDuration(sec))
                        }
                    }
                    profile?.criticalPathSeconds?.let { sec ->
                        val comp = profile.criticalPathComponents?.let { " ($it components)" } ?: ""
                        DetailRow("Critical path", "${formatDuration(sec)}$comp")
                    }
                }
                if (details?.hasActions == true) {
                    HistorySection("Action cache")
                    details.actionsExecuted?.let { DetailRow("Actions executed", it.toString()) }
                    details.actionsCreated?.let { DetailRow("Actions created", it.toString()) }
                    details.remoteCacheHits?.let { DetailRow("Remote cache hits", it.toString()) }
                    details.localCacheHits?.let { DetailRow("Local cache hits", it.toString()) }
                    details.localCacheMisses?.let { DetailRow("Local cache misses", it.toString()) }
                }
                val serverWindow = selectBazelServerWindow(build.windowSummaries)
                if (serverWindow != null || details?.hasMemory == true) {
                    HistorySection("Memory & GC deltas")
                    serverWindow?.let { w ->
                        DetailRow("Server RSS", "${w.firstRssMb} → ${w.lastRssMb} MB (${formatSignedMb(w.deltaRssMb)}, peak ${w.peakRssMb} MB)")
                        val firstHeap = w.firstHeapUsedMb
                        val lastHeap = w.lastHeapUsedMb
                        val deltaHeap = w.deltaHeapUsedMb
                        if (firstHeap != null && lastHeap != null && deltaHeap != null) {
                            DetailRow("Server heap", "$firstHeap → $lastHeap MB (${formatSignedMb(deltaHeap)})")
                        }
                        w.deltaTotalGcCount?.let { gc ->
                            val gcTime = listOfNotNull(
                                w.deltaYoungGcTimeMs?.let { "young +${it} ms" },
                                w.deltaOldGcTimeMs?.let { "old +${it} ms" },
                            ).joinToString(", ")
                            val suffix = if (gcTime.isNotEmpty()) " ($gcTime)" else ""
                            DetailRow("GC delta", "${formatSignedCount(gc)} collections$suffix")
                        }
                    }
                    details?.usedHeapPostBuildBytes?.let { DetailRow("Post-build heap", formatBytes(it)) }
                    details?.peakPostGcHeapBytes?.let { DetailRow("Peak post-GC heap", formatBytes(it)) }
                }
                if (details?.workerCount != null || details?.testsTotal != null) {
                    HistorySection("Workers & tests")
                    details.workerCount?.let { DetailRow("Workers reported", it.toString()) }
                    details.testsTotal?.let { total ->
                        val passed = details.testsPassed ?: 0
                        val failed = details.testsFailed ?: 0
                        val cached = details.testsCached ?: 0
                        DetailRow("Tests", "$total total ($passed passed, $failed failed, $cached cached)")
                    }
                }
                Spacer(Modifier.padding(Space.xs))
                Text(
                    "Retained from command.profile.gz or opportunistic BEP (Daemonitor never enables BEP). No Gradle daemon log for Bazel.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Space.xs),
                )
                val snippet = build.logSnippet
                if (snippet.isNullOrBlank()) {
                    Text(
                        "No failure snippet retained for this Bazel build.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    LogView(remember(snippet) { snippet.lines() }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp), autoScroll = false)
                }
            }
        } else {
            Text(
                "Gradle daemon log excerpt — captured during the build window",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Space.xs),
            )
            val snippet = build.logSnippet
            if (snippet.isNullOrBlank()) {
                Text(
                    "No Gradle daemon log captured for this build window.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                LogView(remember(snippet) { snippet.lines() }, modifier = Modifier.weight(1f).fillMaxWidth(), autoScroll = false)
            }
        }
    }
}

@Composable
private fun HistorySection(label: String) {
    Text(
        label,
        modifier = Modifier.padding(top = Space.sm, bottom = Space.xs),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(value, modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun selectBazelServerWindow(summaries: List<BazelWindowSummary>): BazelWindowSummary? =
    summaries.firstOrNull { it.processType.equals("BAZEL_SERVER", ignoreCase = true) } ?: summaries.firstOrNull()

private fun formatMillis(ms: Long): String =
    if (ms < 1_000L) "$ms ms" else String.format(Locale.ROOT, "%.1f s", ms / 1000.0)

private fun formatSignedMb(delta: Long): String = when {
    delta > 0 -> "+$delta MB"
    delta < 0 -> "$delta MB"
    else -> "0 MB"
}

private fun formatSignedCount(delta: Long): String = if (delta > 0) "+$delta" else delta.toString()

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private val TIME_FMT = DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneId.systemDefault())
private fun Build.displayAgent(): String? = agent.takeUnless { inferredSource == Source.IDE }
private fun formatTime(ms: Long): String = TIME_FMT.format(Instant.ofEpochMilli(ms))
private fun formatDuration(seconds: Double?): String =
    seconds?.let { if (it < 1) "%.0f ms".format(Locale.ROOT, it * 1000) else "%.1f s".format(Locale.ROOT, it) } ?: "—"
