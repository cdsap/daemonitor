@file:JvmName("Daemonitor")

package io.github.cdsap.daemonitor

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.cdsap.daemonitor.ui.common.AppScaffold
import io.github.cdsap.daemonitor.ui.common.WatcherTheme
import io.github.cdsap.daemonitor.ui.history.HistoryScreen
import io.github.cdsap.daemonitor.ui.live.LiveMonitorScreen
import io.github.cdsap.daemonitor.ui.settings.SettingsScreen

/**
 * Application entry point (U1 scaffold, wired in U7). Opens the database, starts the polling
 * [WatcherService], and renders the tabbed UI. The Historical tab is wired in U8.
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "--headless") {
        val exitCode = HeadlessLauncher.run(args.drop(1).toTypedArray())
        if (exitCode != 0) kotlin.system.exitProcess(exitCode)
        return
    }
    launchDesktop()
}

private fun launchDesktop() = application {
    val service = remember { WatcherService.create() }
    val windowState = rememberWindowState(
        size = DpSize(1180.dp, 760.dp),
        position = WindowPosition(Alignment.Center),
    )

    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "Daemonitor",
        icon = painterResource("icon/daemonitor.png"),
    ) {
        // Start the polling service exactly once, tied to this composition's lifecycle.
        LaunchedEffect(Unit) { service.start(this) }

        WatcherTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
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
                    settingsContent = {
                        val settingsState by service.settingsViewModel.state.collectAsState()
                        SettingsScreen(
                            state = settingsState,
                            onRetentionDays = service.settingsViewModel::setRetentionDays,
                        )
                    },
                )
            }
        }
    }
}
