package com.gradlewatcher.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/** Top-level navigation: Live Monitor and Historical tabs (U7). Resolves the navigation-model gap. */
@Composable
fun AppScaffold(liveContent: @Composable () -> Unit, historyContent: @Composable () -> Unit) {
    var selectedTab by remember { mutableStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Live Monitor") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Historical") })
        }
        Column(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> liveContent()
                else -> historyContent()
            }
        }
    }
}
