package io.github.cdsap.daemonitor

import java.awt.Taskbar
import javax.imageio.ImageIO

internal object DesktopDockIcon {
    fun configure(osName: String = System.getProperty("os.name")) {
        if (!osName.lowercase().contains("mac")) return
        runCatching {
            if (!Taskbar.isTaskbarSupported()) return
            val taskbar = Taskbar.getTaskbar()
            if (!taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) return
            val iconUrl = checkNotNull(javaClass.classLoader.getResource("icon/daemonitor.png"))
            taskbar.iconImage = ImageIO.read(iconUrl)
        }
    }
}
