package io.github.cdsap.daemonitor.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.derivedStateOf
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Scrollable monospace log view. With [autoScroll] the view pins to the bottom and follows new
 * lines; when the user scrolls up it stops following and shows a "Jump to latest" affordance,
 * resolving the live-tail scroll-model gap (U7). Reused for the Historical build-log snippet (U8)
 * with [autoScroll] = false.
 */
@Composable
fun LogView(lines: List<String>, modifier: Modifier = Modifier, autoScroll: Boolean = true) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val atBottom by remember(lines) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            last == null || last.index >= lines.lastIndex
        }
    }

    if (autoScroll) {
        // Key on the list itself (content equality), not its size — once the tail buffer is full
        // the size stops changing but the content still does, and the live tail must keep following.
        LaunchedEffect(lines) {
            if (atBottom && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
        }
    }

    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(8.dp)) {
            items(lines) { line ->
                Text(
                    line,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
        }
        if (autoScroll && !atBottom) {
            Button(
                onClick = { scope.launch { if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex) } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            ) { Text("Jump to latest") }
        }
    }
}
