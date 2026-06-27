package io.github.cdsap.daemonitor

import java.util.Properties

data class BuildInfo(val version: String, val commit: String) {
    companion object {
        val current: BuildInfo by lazy {
            val properties = Properties()
            val resource = requireNotNull(BuildInfo::class.java.getResourceAsStream("/daemonitor-build.properties")) {
                "Missing Daemonitor build metadata"
            }
            resource.use(properties::load)
            BuildInfo(
                version = properties.getProperty("version", "unknown"),
                commit = properties.getProperty("commit", "unknown"),
            )
        }
    }
}
