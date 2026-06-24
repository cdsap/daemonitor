package com.gradlewatcher

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

/**
 * Application entry point (U1 scaffold). The real UI shell (tabs, Live Monitor, Historical)
 * is introduced in U7/U8; for now this is a launchable empty window so the module assembles
 * and `./gradlew run` opens a window.
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Gradle Watcher",
    ) {
        App()
    }
}

@Composable
fun App() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Gradle Watcher — starting up…")
            }
        }
    }
}
