package com.gradlewatcher.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Persistent, unobtrusive disclosure shown on screens that display command lines / logs (U9 /
 * Privacy). Makes clear that captured data may be sensitive and stays local — the placement the
 * review flagged as undefined.
 */
@Composable
fun PrivacyNotice(modifier: Modifier = Modifier) {
    Text(
        text = "Command lines and daemon logs may contain sensitive data. Everything stays " +
            "local on this machine; secrets are best-effort redacted before storage.",
        color = Color(0xFF666666),
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFFF2F2F2))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}
