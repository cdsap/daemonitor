package io.github.cdsap.daemonitor.ui.common

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
internal fun horizontalScrollbarStyle() = LocalScrollbarStyle.current.copy(
    unhoverColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
    hoverColor = MaterialTheme.colorScheme.onSurface,
)
