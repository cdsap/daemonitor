package io.github.cdsap.daemonitor

internal object HeadlessMacMode {
    fun configure(osName: String = System.getProperty("os.name")) {
        val normalized = osName.lowercase()
        if (normalized.contains("mac") || normalized.contains("darwin")) {
            System.setProperty("apple.awt.UIElement", "true")
            System.setProperty("apple.awt.application.name", "Daemonitor")
        }
    }
}
