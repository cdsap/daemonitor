package io.github.cdsap.daemonitor.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

/**
 * Loads a bitmap resource from the Java classpath into a remembered [Painter].
 * Replaces Compose Desktop's deprecated `androidx.compose.ui.res.painterResource`.
 */
@Composable
internal fun classpathBitmapPainter(resourcePath: String): Painter = remember(resourcePath) {
    val bytes = checkNotNull(
        Thread.currentThread().contextClassLoader?.getResourceAsStream(resourcePath)
            ?: object {}.javaClass.classLoader.getResourceAsStream(resourcePath),
    ) { "Classpath resource not found: $resourcePath" }.use { it.readBytes() }
    BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
}
