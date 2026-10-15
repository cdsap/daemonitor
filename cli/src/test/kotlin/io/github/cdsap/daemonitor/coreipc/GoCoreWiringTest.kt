package io.github.cdsap.daemonitor.coreipc

import io.github.cdsap.daemonitor.application.MonitoringMode
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoCoreWiringTest {
    @Test
    fun `null socket keeps jvm collector defaults`() {
        val wiring = wireGoCore(null, Path("/tmp/watcher.db"))
        assertNull(wiring.processSource)
        assertNull(wiring.logSource)
        assertTrue(wiring.mode is MonitoringMode.Local)
        assertNull(wiring.banner)
    }

    @Test
    fun `matching default db skips build import and sample writes when health unreachable`() {
        val db = Path("/tmp/Daemonitor/watcher.db").toAbsolutePath().normalize()
        val wiring = wireGoCore(
            coreSocket = Path("/tmp/missing-cored.sock"),
            databasePath = db,
            defaultDatabase = db,
            fetch = { _, _ -> error("health unreachable") },
        )
        assertNotNull(wiring.processSource)
        assertNotNull(wiring.logSource)
        assertTrue(wiring.mode is MonitoringMode.SharedCoreOwnedPersistence)
        val sharedBanner = assertNotNull(wiring.banner)
        assertTrue(sharedBanner.contains("shared DB"))
        assertFalse(sharedBanner.contains("unavailable on Go path"))
    }

    @Test
    fun `separate db keeps http build import when health unreachable`() {
        val local = Path("/tmp/app.db").toAbsolutePath().normalize()
        val wiring = wireGoCore(
            coreSocket = Path("/tmp/missing-cored.sock"),
            databasePath = local,
            defaultDatabase = Path("/tmp/Daemonitor/watcher.db"),
            fetch = { _, _ -> error("health unreachable") },
        )
        assertNotNull(wiring.processSource)
        assertTrue(wiring.mode is MonitoringMode.RemoteBuilds)
        val banner = assertNotNull(wiring.banner)
        assertFalse(banner.contains("shared DB"))
        assertFalse(banner.contains("unavailable on Go path"))
    }

    @Test
    fun `supplied transport is used by health and every wired source`() {
        val socket = Files.createTempFile("go-core-wiring", ".sock")
        val paths = mutableListOf<String>()
        try {
            val wiring = wireGoCore(
                coreSocket = socket,
                databasePath = Path("/tmp/app.db"),
                defaultDatabase = Path("/tmp/Daemonitor/watcher.db"),
                fetch = { _, path ->
                    paths += path
                    when (path) {
                        "/v1/health" -> """{"status":"ok","db_path":"/tmp/core.db"}"""
                        "/v1/processes", "/v1/daemon-logs", "/v1/builds?limit=50" -> "{}"
                        else -> error("unexpected path: $path")
                    }
                },
            )

            assertTrue(wiring.mode is MonitoringMode.RemoteBuilds)
            wiring.processSource!!.currentProcesses()
            wiring.logSource!!.discover()
            wiring.mode.source.recentBuilds(50)
        } finally {
            socket.deleteIfExists()
        }

        assertEquals(
            listOf("/v1/health", "/v1/processes", "/v1/daemon-logs", "/v1/builds?limit=50"),
            paths,
        )
    }
}
