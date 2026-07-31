package io.github.cdsap.daemonitor.update

enum class DesktopPlatform(val assetSuffix: String) {
    MACOS("-macos.dmg"),
    WINDOWS("-windows.msi"),
    LINUX("-linux.deb"),
    UNKNOWN(""),
    ;

    companion object {
        fun current(osName: String = System.getProperty("os.name")): DesktopPlatform {
            val normalized = osName.lowercase()
            return when {
                normalized.contains("mac") || normalized.contains("darwin") -> MACOS
                normalized.contains("win") -> WINDOWS
                normalized.contains("linux") -> LINUX
                else -> UNKNOWN
            }
        }
    }
}
