package io.github.cdsap.daemonitor.coreipc

import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreOwnsDatabaseTest {
    @Test
    fun `compares reported paths after normalization`() {
        val local = Path("/tmp/Daemonitor/../app.db")
        val default = Path("/tmp/default.db")
        assertTrue(databasePathsMatch(local, default, Path("/tmp/app.db")))
        assertFalse(databasePathsMatch(local, default, Path("/tmp/other.db")))
        assertFalse(databasePathsMatch(default, default, Path("/tmp/other.db")))
    }

    @Test
    fun `falls back to normalized default when reported path is absent`() {
        val default = Path("/tmp/Daemonitor/../app.db")
        assertTrue(databasePathsMatch(Path("/tmp/app.db"), default, null))
        assertFalse(databasePathsMatch(Path("/tmp/other.db"), default, null))
    }

    @Test
    fun `falls back to default when health request fails`() {
        val default = Path("/tmp/Daemonitor/watcher.db")
        val fetch: (java.nio.file.Path, String) -> String = { _, _ -> error("request failed") }
        assertTrue(coreOwnsDatabase(Path("/tmp/sock"), default, default, fetch))
        assertFalse(coreOwnsDatabase(Path("/tmp/sock"), Path("/tmp/other.db"), default, fetch))
    }

    @Test
    fun `matches remote health db_path`() {
        val local = Path("/tmp/Daemonitor/watcher.db").toAbsolutePath().normalize()
        val default = Path("/tmp/Daemonitor/watcher.db").toAbsolutePath().normalize()
        val owns = coreOwnsDatabase(
            coreSocket = Path("/tmp/sock"),
            localDatabase = local,
            defaultDatabase = default,
            fetch = { _, _ -> """{"status":"ok","db_path":"$local"}""" },
        )
        assertTrue(owns)
    }

    @Test
    fun `separate db keeps http import path`() {
        val local = Path("/tmp/app.db").toAbsolutePath().normalize()
        val remote = Path("/tmp/core.db").toAbsolutePath().normalize()
        val owns = coreOwnsDatabase(
            coreSocket = Path("/tmp/sock"),
            localDatabase = local,
            defaultDatabase = Path("/tmp/Daemonitor/watcher.db"),
            fetch = { _, _ -> """{"status":"ok","db_path":"$remote"}""" },
        )
        assertFalse(owns)
    }

    @Test
    fun `falls back to default path when health omits db_path`() {
        val default = Path("/tmp/Daemonitor/watcher.db").toAbsolutePath().normalize()
        assertTrue(
            coreOwnsDatabase(
                coreSocket = Path("/tmp/sock"),
                localDatabase = default,
                defaultDatabase = default,
                fetch = { _, _ -> """{"status":"ok"}""" },
            ),
        )
        assertFalse(
            coreOwnsDatabase(
                coreSocket = Path("/tmp/sock"),
                localDatabase = Path("/tmp/other.db"),
                defaultDatabase = default,
                fetch = { _, _ -> """{"status":"ok"}""" },
            ),
        )
    }
}
