package com.gradlewatcher

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.gradlewatcher.ui.common.AppScaffold
import com.gradlewatcher.ui.history.HistoryScreen
import com.gradlewatcher.ui.live.LiveMonitorScreen

/**
 * Application entry point (U1 scaffold, wired in U7). Opens the database, starts the polling
 * [WatcherService], and renders the tabbed UI. The Historical tab is wired in U8.
 */
fun main() = application {
    val service = remember { WatcherService.create() }

    Window(onCloseRequest = ::exitApplication, title = "Gradle Watcher") {
        // Start the polling service exactly once, tied to this composition's lifecycle.
        LaunchedEffect(Unit) { service.start(this) }

        MaterialTheme {
            Surface {
                val liveState by service.liveViewModel.state.collectAsState()
                AppScaffold(
                    liveContent = {
                        LiveMonitorScreen(
                            state = liveState,
                            onSelect = service.liveViewModel::select,
                            onClearSelection = service.liveViewModel::clearSelection,
                        )
                    },
                    historyContent = {
                        val historyState by service.historyViewModel.state.collectAsState()
                        HistoryScreen(
                            state = historyState,
                            onProject = service.historyViewModel::setProject,
                            onTimeRange = service.historyViewModel::setTimeRange,
                        )
                    },
                )
            }
        }
    }
}
