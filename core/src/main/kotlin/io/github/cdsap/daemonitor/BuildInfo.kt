package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.distribution.DistributionChannel
import java.util.Properties

data class BuildInfo(
    val version: String,
    val commit: String,
    val distribution: DistributionChannel = DistributionChannel.DIRECT,
) {
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
                distribution = DistributionChannel.parse(properties.getProperty("distribution")),
            )
        }
    }
}
