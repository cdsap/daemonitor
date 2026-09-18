package io.github.cdsap.daemonitor.platform

import io.github.cdsap.daemonitor.distribution.DistributionChannel
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GradleDaemonHomeAccessTest {
    @Test
    fun `readable gradle home is accessible`(@TempDir tmp: Path) {
        val home = tmp.resolve(".gradle").also { Files.createDirectories(it) }
        Files.createDirectories(home.resolve("daemon"))

        val result = GradleDaemonHomeAccess.probe(home)

        assertIs<GradleDaemonHomeAccess.Result.Accessible>(result)
        assertFalse(
            GradleDaemonHomeAccess.requiresSecurityScopedBookmark(DistributionChannel.DIRECT, result),
        )
        assertFalse(
            GradleDaemonHomeAccess.requiresSecurityScopedBookmark(DistributionChannel.APP_STORE, result),
        )
    }

    @Test
    fun `missing gradle home is blocked and requires bookmark on app store`(@TempDir tmp: Path) {
        val home = tmp.resolve("missing-gradle")

        val result = GradleDaemonHomeAccess.probe(home)

        assertIs<GradleDaemonHomeAccess.Result.Blocked>(result)
        assertEquals(home, result.gradleUserHome)
        assertTrue(result.reason.contains("does not exist"))
        assertFalse(
            GradleDaemonHomeAccess.requiresSecurityScopedBookmark(DistributionChannel.DIRECT, result),
        )
        assertTrue(
            GradleDaemonHomeAccess.requiresSecurityScopedBookmark(DistributionChannel.APP_STORE, result),
        )
    }

    @Test
    fun `unreadable daemon directory is blocked`(@TempDir tmp: Path) {
        val home = tmp.resolve(".gradle").also { Files.createDirectories(it) }
        val daemon = home.resolve("daemon").also { Files.createDirectories(it) }
        // Best-effort: if the platform cannot flip readability, skip rather than flake.
        val madeUnreadable = daemon.toFile().setReadable(false, false) &&
            daemon.toFile().setReadable(false, true)
        if (!madeUnreadable || Files.isReadable(daemon)) {
            daemon.toFile().setReadable(true, true)
            return
        }
        try {
            val result = GradleDaemonHomeAccess.probe(home)
            assertIs<GradleDaemonHomeAccess.Result.Blocked>(result)
            assertTrue(result.reason.contains("daemon directory is not readable"))
        } finally {
            daemon.toFile().setReadable(true, true)
        }
    }
}
