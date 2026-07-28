package io.github.cdsap.daemonitor

import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.io.PrintStream

internal interface HeadlessTrayHandle : AutoCloseable

internal object NoHeadlessTrayHandle : HeadlessTrayHandle {
    override fun close() = Unit
}

internal object HeadlessTray {
    fun install(
        onOpen: () -> Unit,
        onQuit: () -> Unit,
        error: PrintStream = System.err,
        environment: TrayEnvironment = AwtTrayEnvironment,
    ): HeadlessTrayHandle {
        if (!environment.isSupported()) return NoHeadlessTrayHandle

        return runCatching {
            val image = environment.loadImage("icon/daemonitor.png")
            val popup = PopupMenu().apply {
                add(MenuItem("Open Daemonitor").apply {
                    addActionListener { onOpen() }
                })
                add(MenuItem("Quit Daemonitor").apply {
                    addActionListener { onQuit() }
                })
            }
            val icon = TrayIcon(image, "Daemonitor is collecting Gradle activity", popup).apply {
                isImageAutoSize = true
            }
            environment.add(icon)
            object : HeadlessTrayHandle {
                override fun close() {
                    environment.remove(icon)
                }
            }
        }.getOrElse {
            error.println("Daemonitor tray icon unavailable: ${it.message}")
            NoHeadlessTrayHandle
        }
    }
}

internal interface TrayEnvironment {
    fun isSupported(): Boolean
    fun loadImage(resourcePath: String): Image
    fun add(icon: TrayIcon)
    fun remove(icon: TrayIcon)
}

internal object AwtTrayEnvironment : TrayEnvironment {
    override fun isSupported(): Boolean =
        runCatching { SystemTray.isSupported() }.getOrDefault(false)

    override fun loadImage(resourcePath: String): Image {
        val resource = requireNotNull(Thread.currentThread().contextClassLoader.getResource(resourcePath)) {
            "Missing tray icon resource: $resourcePath"
        }
        return Toolkit.getDefaultToolkit().getImage(resource)
    }

    override fun add(icon: TrayIcon) {
        SystemTray.getSystemTray().add(icon)
    }

    override fun remove(icon: TrayIcon) {
        SystemTray.getSystemTray().remove(icon)
    }
}
