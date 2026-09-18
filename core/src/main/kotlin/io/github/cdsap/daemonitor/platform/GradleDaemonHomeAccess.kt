package io.github.cdsap.daemonitor.platform

import io.github.cdsap.daemonitor.distribution.DistributionChannel
import java.nio.file.Files
import java.nio.file.Path

/**
 * Probes whether Daemonitor can read Gradle daemon logs under [gradleUserHome].
 *
 * Direct (non-sandboxed) builds normally see `~/.gradle`. App Store sandbox builds typically need a
 * user-selected security-scoped bookmark before [daemonRoot] is readable; this probe surfaces that
 * requirement without implementing native bookmark APIs yet.
 */
object GradleDaemonHomeAccess {
    sealed interface Result {
        val gradleUserHome: Path
        val daemonRoot: Path

        data class Accessible(
            override val gradleUserHome: Path,
            override val daemonRoot: Path,
        ) : Result

        data class Blocked(
            override val gradleUserHome: Path,
            override val daemonRoot: Path,
            val reason: String,
        ) : Result
    }

    fun probe(gradleUserHome: Path = AppDirectories.system.gradleUserHome): Result {
        val daemonRoot = gradleUserHome.resolve("daemon")
        return when {
            !Files.exists(gradleUserHome) -> Result.Blocked(
                gradleUserHome = gradleUserHome,
                daemonRoot = daemonRoot,
                reason = "Gradle user home does not exist at $gradleUserHome",
            )
            !Files.isReadable(gradleUserHome) -> Result.Blocked(
                gradleUserHome = gradleUserHome,
                daemonRoot = daemonRoot,
                reason = "Gradle user home is not readable",
            )
            Files.exists(daemonRoot) && !Files.isReadable(daemonRoot) -> Result.Blocked(
                gradleUserHome = gradleUserHome,
                daemonRoot = daemonRoot,
                reason = "Gradle daemon directory is not readable",
            )
            else -> Result.Accessible(gradleUserHome = gradleUserHome, daemonRoot = daemonRoot)
        }
    }

    /** True when an App Store build should prompt for a security-scoped bookmark grant. */
    fun requiresSecurityScopedBookmark(
        channel: DistributionChannel,
        result: Result = probe(),
    ): Boolean = channel == DistributionChannel.APP_STORE && result is Result.Blocked
}
