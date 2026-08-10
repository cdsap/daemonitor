package io.github.cdsap.daemonitor.update

enum class DesktopPlatform(val metadataName: String) {
    MACOS("macos"),
    WINDOWS("windows"),
    LINUX("linux"),
    UNKNOWN("unknown"),
    ;

    val installerExtension: String
        get() = when (this) {
            MACOS -> "dmg"
            WINDOWS -> "msi"
            LINUX -> "deb"
            UNKNOWN -> ""
        }

    val updatePackageExtension: String
        get() = when (this) {
            MACOS, WINDOWS -> "zip"
            LINUX -> "tar.gz"
            UNKNOWN -> ""
        }

    /** Legacy Phase 1 suffix without architecture. */
    val legacyInstallerSuffix: String
        get() = "-$metadataName.$installerExtension"

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
