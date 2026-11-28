package io.github.cdsap.daemonitor.infrastructure.platform

import io.github.cdsap.daemonitor.application.platform.UrlOpener
import java.awt.Desktop
import java.net.URI

/** Opens URLs through the AWT Desktop browse action. */
class DesktopUrlOpener : UrlOpener {
    override fun open(url: String) {
        val uri = URI(url)
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "Only HTTPS URLs are supported"
        }
        require(Desktop.isDesktopSupported()) { "Desktop integration is not available" }
        val desktop = Desktop.getDesktop()
        require(desktop.isSupported(Desktop.Action.BROWSE)) { "Opening release pages is not supported" }
        desktop.browse(uri)
    }
}
