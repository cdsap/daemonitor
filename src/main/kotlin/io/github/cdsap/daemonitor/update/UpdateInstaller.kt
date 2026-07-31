package io.github.cdsap.daemonitor.update

import java.awt.Desktop
import java.net.URI

fun interface UpdateInstaller {
    fun open(candidate: UpdateCandidate)
}

class DesktopUpdateInstaller : UpdateInstaller {
    override fun open(candidate: UpdateCandidate) {
        require(Desktop.isDesktopSupported()) { "Desktop integration is not available" }
        val desktop = Desktop.getDesktop()
        require(desktop.isSupported(Desktop.Action.BROWSE)) { "Opening update downloads is not supported" }
        desktop.browse(URI(candidate.downloadUrl))
    }
}
