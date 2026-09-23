package io.github.cdsap.daemonitor.coreipc

import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreOwnsDatabaseTest {
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
