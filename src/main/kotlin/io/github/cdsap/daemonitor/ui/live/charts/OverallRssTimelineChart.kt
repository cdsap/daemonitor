package io.github.cdsap.daemonitor.ui.live.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.cdsap.daemonitor.ui.common.LocalAccentColors
import io.github.cdsap.daemonitor.ui.common.Radius
import io.github.cdsap.daemonitor.ui.common.SectionCard
import io.github.cdsap.daemonitor.ui.common.Space
import io.github.cdsap.daemonitor.ui.live.RssTimelineChartData
import io.github.cdsap.daemonitor.ui.live.RssTimelinePoint
import io.github.cdsap.daemonitor.ui.live.TimelineMetric
import io.github.cdsap.daemonitor.ui.live.TimelineSeries
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private data class HoverLegend(
    val point: RssTimelinePoint,
    val xPx: Float,
    val markerYs: List<Pair<Color, Float>>,
)

private const val DEFAULT_VISIBLE_PROCESS_LIMIT = 4

@Composable
fun OverallRssTimelineChart(
    chart: RssTimelineChartData,
    currentTotalRssMb: Long,
    selectedPid: Long?,
    onSelectProcess: (Long) -> Unit,
    modifier: Modifier = Modifier,
    rangeLabel: String = "Live",
    statusText: String? = null,
) {
    val accents = LocalAccentColors.current
    val palette = remember(accents) {
        listOf(
            accents.warn,
            accents.brand,
            accents.success,
            accents.danger,
            accents.neutral,
            Color(0xFF7E57C2),
            Color(0xFF00897B),
            Color(0xFF5C6BC0),
        )
    }
    val seriesColors = remember(chart.series, palette, accents) {
        val processColorByPid = linkedMapOf<Long, Color>()
        var nextPaletteIndex = 0
        chart.series.forEach { series ->
            val pid = series.pid ?: return@forEach
            if (pid !in processColorByPid) {
                processColorByPid[pid] = palette[nextPaletteIndex % palette.size]
                nextPaletteIndex += 1
            }
        }
        chart.series.associate { series ->
            val color = when {
                series.isTotal && series.metric == TimelineMetric.RSS -> accents.info
                series.isTotal && series.metric == TimelineMetric.HEAP -> accents.info.copy(alpha = 0.85f)
                else -> processColorByPid.getValue(series.pid!!)
            }
            series.id to color
        }
    }
    val totalColor = seriesColors[chart.series.firstOrNull { it.isTotal && it.metric == TimelineMetric.RSS }?.id]
        ?: accents.info
    val totalFill = totalColor.copy(alpha = 0.18f)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val crosshairColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    val points = chart.points
    val series = chart.series
    var visibleIds by remember(series.map { it.id }, selectedPid) {
        mutableStateOf(defaultVisibleSeriesIds(series, selectedPid))
    }
    var hover by remember(points, visibleIds) { mutableStateOf<HoverLegend?>(null) }
    var plotSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val visibleSeries = series.filter { it.id in visibleIds }
    val processCount = series.mapNotNull { it.pid }.distinct().size

    SectionCard("RSS & Heap", modifier) {
        Column(
            modifier = Modifier.fillMaxSize().padding(Space.sm),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Text(
                "$rangeLabel · Total $currentTotalRssMb MB RSS · $processCount processes",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                listOfNotNull(
                    "Solid = RSS, dashed = configured heap limit (-Xmx). Click a series to show or hide it.",
                    statusText,
                ).joinToString(" "),
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                series.forEach { item ->
                    val visible = item.id in visibleIds
                    val selected = item.pid != null && item.pid == selectedPid
                    LegendChip(
                        label = item.label,
                        color = seriesColors.getValue(item.id),
                        visible = visible,
                        selected = selected,
                        dashed = item.dashed,
                        onClick = {
                            val pid = item.pid
                            if (pid != null && pid != selectedPid) {
                                onSelectProcess(pid)
                                visibleIds = visibleIds + item.id
                            } else {
                                val nextVisible = if (visible) {
                                    if (visibleIds.size == 1) visibleIds else visibleIds - item.id
                                } else {
                                    visibleIds + item.id
                                }
                                visibleIds = nextVisible
                                pid?.let(onSelectProcess)
                            }
                        },
                    )
                }
            }

            if (points.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Collecting samples...", color = labelColor, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                val maxMb = max(
                    1L,
                    points.maxOf { point ->
                        visibleSeries.maxOfOrNull { item -> point.valuesBySeriesId[item.id] ?: 0L } ?: 0L
                    },
                )
                val yTicks = listOf(0L, maxMb / 2, maxMb)
                val xLabels = listOf(
                    formatClock(points.first().atMs),
                    formatClock(points[points.size / 2].atMs),
                    formatClock(points.last().atMs),
                )

                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxHeight().padding(end = Space.xs),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        yTicks.asReversed().forEach { tick ->
                            Text(
                                "$tick MB",
                                style = MaterialTheme.typography.labelSmall,
                                color = labelColor,
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f).fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            Canvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .onSizeChanged { plotSize = it }
                                    .pointerInput(points, visibleSeries, maxMb, seriesColors) {
                                        awaitPointerEventScope {
                                            while (true) {
                                                val event = awaitPointerEvent(PointerEventPass.Main)
                                                val change = event.changes.firstOrNull() ?: continue
                                                val x = change.position.x
                                                val width = size.width.toFloat()
                                                val height = size.height.toFloat()
                                                if (x < 0f || x > width || change.position.y < 0f || change.position.y > height) {
                                                    hover = null
                                                    continue
                                                }
                                                val index = nearestIndex(points.size, width, x)
                                                val point = points[index]
                                                val xPx = if (points.size == 1) {
                                                    width / 2f
                                                } else {
                                                    index * (width / (points.size - 1).toFloat())
                                                }
                                                val markerYs = visibleSeries.map { item ->
                                                    val value = point.valuesBySeriesId[item.id] ?: 0L
                                                    val y = height - (value.toFloat() / maxMb.toFloat()) * height
                                                    seriesColors.getValue(item.id) to y
                                                }
                                                hover = HoverLegend(point, xPx, markerYs)
                                            }
                                        }
                                    },
                            ) {
                                val width = size.width
                                val height = size.height
                                yTicks.forEach { tick ->
                                    val y = height - (tick.toFloat() / maxMb.toFloat()) * height
                                    drawLine(
                                        color = gridColor,
                                        start = Offset(0f, y),
                                        end = Offset(width, y),
                                        strokeWidth = 1f,
                                    )
                                }

                                visibleSeries.forEach { item ->
                                    val color = seriesColors.getValue(item.id)
                                    val path = smoothedPathFor(points, width, height, maxMb) {
                                        (it.valuesBySeriesId[item.id] ?: 0L).toFloat()
                                    }
                                    if (item.isTotal && item.metric == TimelineMetric.RSS) {
                                        val fillPath = Path().apply {
                                            addPath(path)
                                            lineTo(width, height)
                                            lineTo(0f, height)
                                            close()
                                        }
                                        drawPath(fillPath, color = totalFill)
                                    }
                                    drawPath(
                                        path,
                                        color = color,
                                        style = Stroke(
                                            width = when {
                                                item.isTotal && item.metric == TimelineMetric.RSS -> 2.8.dp.toPx()
                                                item.isTotal -> 2.2.dp.toPx()
                                                else -> 2.dp.toPx()
                                            },
                                            cap = StrokeCap.Round,
                                            join = StrokeJoin.Round,
                                            pathEffect = if (item.dashed) {
                                                PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                                            } else {
                                                null
                                            },
                                        ),
                                    )
                                }

                                hover?.let { marker ->
                                    drawLine(
                                        color = crosshairColor,
                                        start = Offset(marker.xPx, 0f),
                                        end = Offset(marker.xPx, height),
                                        strokeWidth = 1.dp.toPx(),
                                    )
                                    marker.markerYs.forEach { (color, y) ->
                                        drawCircle(color = color, radius = 3.5.dp.toPx(), center = Offset(marker.xPx, y))
                                    }
                                }
                            }

                            hover?.let { marker ->
                                val tooltipX = with(density) {
                                    val raw = marker.xPx.toDp()
                                    val maxX = plotSize.width.toDp() - 220.dp
                                    raw.coerceIn(0.dp, maxOf(0.dp, maxX))
                                }
                                val tooltipY = with(density) {
                                    val firstY = marker.markerYs.minOfOrNull { it.second } ?: 0f
                                    minOf(firstY.toDp(), (plotSize.height.toDp() - 120.dp).coerceAtLeast(0.dp))
                                }
                                HoverLegendCard(
                                    point = marker.point,
                                    series = visibleSeries,
                                    seriesColors = seriesColors,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .offset { IntOffset(tooltipX.roundToPx(), tooltipY.roundToPx()) },
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = Space.xs),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            xLabels.forEach { label ->
                                Text(label, style = MaterialTheme.typography.labelSmall, color = labelColor)
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun defaultVisibleSeriesIds(series: List<TimelineSeries>, selectedPid: Long?): Set<String> {
    val totalIds = series.filter { it.isTotal }.map { it.id }
    val processIds = series
        .mapNotNull { it.pid }
        .distinct()
        .take(DEFAULT_VISIBLE_PROCESS_LIMIT)
        .let { ids ->
            if (selectedPid != null && selectedPid !in ids && series.any { it.pid == selectedPid }) {
                ids + selectedPid
            } else {
                ids
            }
        }
    val selectedProcessSeriesIds = series
        .filter { it.pid in processIds }
        .map { it.id }
    return (totalIds + selectedProcessSeriesIds).toSet()
}

@Composable
private fun LegendChip(
    label: String,
    color: Color,
    visible: Boolean,
    selected: Boolean,
    dashed: Boolean,
    onClick: () -> Unit,
) {
    val border = when {
        selected -> color
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Row(
        modifier = Modifier
            .alpha(if (visible) 1f else 0.45f)
            .border(1.dp, border, RoundedCornerShape(Radius.sm))
            .background(
                if (selected) color.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(Radius.sm),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = Space.sm, vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Box(
            modifier = Modifier
                .size(width = 14.dp, height = 10.dp)
                .drawBehind {
                    val y = size.height / 2f
                    drawLine(
                        color = color,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = if (dashed) {
                            PathEffect.dashPathEffect(floatArrayOf(6f, 5f))
                        } else {
                            null
                        },
                    )
                },
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HoverLegendCard(
    point: RssTimelinePoint,
    series: List<TimelineSeries>,
    seriesColors: Map<String, Color>,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .widthIn(min = 160.dp, max = 280.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Radius.sm)),
        shape = RoundedCornerShape(Radius.sm),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Space.sm, vertical = Space.xs),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Text(
                formatClock(point.atMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            series.forEach { item ->
                val value = point.valuesBySeriesId[item.id] ?: 0L
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 14.dp, height = 10.dp)
                            .drawBehind {
                                val y = size.height / 2f
                                drawLine(
                                    color = seriesColors.getValue(item.id),
                                    start = Offset(0f, y),
                                    end = Offset(size.width, y),
                                    strokeWidth = 3.dp.toPx(),
                                    cap = StrokeCap.Round,
                                    pathEffect = if (item.dashed) {
                                        PathEffect.dashPathEffect(floatArrayOf(6f, 5f))
                                    } else {
                                        null
                                    },
                                )
                            },
                    )
                    Text(
                        "${item.label} $value MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun nearestIndex(pointCount: Int, width: Float, x: Float): Int {
    if (pointCount <= 1) return 0
    val step = width / (pointCount - 1).toFloat()
    return (x / step).roundToInt().coerceIn(0, pointCount - 1)
}

private fun smoothedPathFor(
    points: List<RssTimelinePoint>,
    width: Float,
    height: Float,
    maxMb: Long,
    valueOf: (RssTimelinePoint) -> Float,
): Path {
    val path = Path()
    if (points.isEmpty()) return path
    val xStep = if (points.size == 1) 0f else width / (points.size - 1).toFloat()
    val offsets = points.mapIndexed { index, point ->
        val x = index * xStep
        val y = height - (valueOf(point) / maxMb.toFloat()) * height
        Offset(x, y.coerceIn(0f, height))
    }
    path.moveTo(offsets.first().x, offsets.first().y)
    if (offsets.size == 1) return path
    if (offsets.size == 2) {
        path.lineTo(offsets.last().x, offsets.last().y)
        return path
    }

    for (index in 0 until offsets.lastIndex) {
        val previous = offsets.getOrElse(index - 1) { offsets[index] }
        val current = offsets[index]
        val next = offsets[index + 1]
        val following = offsets.getOrElse(index + 2) { next }
        val control1 = Offset(
            x = current.x + (next.x - previous.x) / 6f,
            y = (current.y + (next.y - previous.y) / 6f).coerceBetween(current.y, next.y),
        )
        val control2 = Offset(
            x = next.x - (following.x - current.x) / 6f,
            y = (next.y - (following.y - current.y) / 6f).coerceBetween(current.y, next.y),
        )
        path.cubicTo(control1.x, control1.y, control2.x, control2.y, next.x, next.y)
    }
    return path
}

private fun Float.coerceBetween(a: Float, b: Float): Float =
    coerceIn(min(a, b), max(a, b))

private fun formatClock(atMs: Long): String =
    SimpleDateFormat("h:mm:ss a", Locale.US).format(Date(atMs))
