package io.github.cdsap.daemonitor.coreipc

import io.github.cdsap.daemonitor.config.RetentionPolicy
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoCoreBootstrapTest {
    @Test
    fun `jvm collector preference skips go`() {
        val result = resolveGoCore(
            preference = GoCorePreference.JvmCollector,
            databasePath = Path("/tmp/watcher.db"),
            findBinary = { error("should not look up binary") },
            startCore = { _, _, _, _ -> error("should not start") },
        )
        assertNull(result.wiring.processSource)
        assertFalse(result.startedCore)
    }

    @Test
    fun `prefer go uses healthy socket without starting when retention matches`() {
        val dir = Files.createTempDirectory("cored-healthy")
        val sock = dir.resolve("cored.sock")
        Files.createFile(sock)
        val days = 15L
        val retentionMs = days * RetentionPolicy.MILLIS_PER_DAY
        val paths = mutableListOf<String>()
        val result = resolveGoCore(
            preference = GoCorePreference.PreferGo,
            databasePath = Path("/tmp/watcher.db"),
            retentionDays = days,
            defaultDatabase = Path("/tmp/watcher.db"),
            defaultSocket = sock,
            fetch = { _, path ->
                paths += path
                """{"status":"ok","db_path":"/tmp/watcher.db","retention_ms":$retentionMs,"pid":4242}"""
            },
            findBinary = { error("should not look up binary") },
            startCore = { _, _, _, _ -> error("should not start") },
            stopCore = { _, _ -> error("should not stop") },
        )
        val processSource = assertNotNull(result.wiring.processSource)
        processSource.currentProcesses()
        assertTrue(result.wiring.banner!!.contains("Go core"))
        assertFalse(result.startedCore)
        assertTrue(paths.containsAll(listOf("/v1/health", "/v1/processes")))
    }

    @Test
    fun `prefer go starts cored with settings retention then attaches`() {
        val error = ByteArrayOutputStream()
        var started = false
        var startedDays: Long? = null
        val sock = Files.createTempDirectory("cored-boot").resolve("cored.sock")
        val result = resolveGoCore(
            preference = GoCorePreference.PreferGo,
            databasePath = Path("/tmp/watcher.db"),
            retentionDays = 30,
            defaultDatabase = Path("/tmp/other.db"),
            defaultSocket = sock,
            error = PrintStream(error),
            fetch = { path, _ ->
                if (!started || path != sock) error("unreachable")
                """{"status":"ok","retention_ms":${30 * RetentionPolicy.MILLIS_PER_DAY},"pid":1}"""
            },
            findBinary = { Path("/usr/bin/true") },
            startCore = { _, socket, _, days ->
                Files.createFile(socket)
                started = true
                startedDays = days
                true
            },
            waitHealthyMs = 500,
        )
        assertNotNull(result.wiring.processSource)
        assertTrue(result.startedCore)
        assertEquals(30L, startedDays)
        assertTrue(error.toString().contains("Starting daemonitor-cored"))
    }

    @Test
    fun `prefer go restarts healthy cored when retention mismatches settings`() {
        val error = ByteArrayOutputStream()
        val sock = Files.createTempDirectory("cored-mismatch").resolve("cored.sock")
        Files.createFile(sock)
        var stoppedPid: Long? = null
        var startedDays: Long? = null
        var healthy = true
        val result = resolveGoCore(
            preference = GoCorePreference.PreferGo,
            databasePath = Path("/tmp/watcher.db"),
            retentionDays = 15,
            defaultDatabase = Path("/tmp/watcher.db"),
            defaultSocket = sock,
            error = PrintStream(error),
            fetch = { _, _ ->
                if (!healthy) error("stopped")
                if (startedDays != null) {
                    """{"status":"ok","db_path":"/tmp/watcher.db","retention_ms":${15 * RetentionPolicy.MILLIS_PER_DAY},"pid":100}"""
                } else {
                    // Old 1.3.0 cored: 24h, no retention_ms field → treat as mismatch
                    """{"status":"ok","db_path":"/tmp/watcher.db","pid":99}"""
                }
            },
            findBinary = { Path("/usr/bin/true") },
            startCore = { _, _, _, days ->
                startedDays = days
                healthy = true
                true
            },
            stopCore = { _, pid ->
                stoppedPid = pid
                healthy = false
                true
            },
            waitHealthyMs = 500,
        )
        assertNotNull(result.wiring.processSource)
        assertTrue(result.startedCore)
        assertEquals(99L, stoppedPid)
        assertEquals(15L, startedDays)
        assertTrue(error.toString().contains("retention mismatch"))
    }

    @Test
    fun `prefer go falls back to jvm when binary missing`() {
        val error = ByteArrayOutputStream()
        val sock = Path("/tmp/missing-cored-${System.nanoTime()}.sock")
        val result = resolveGoCore(
            preference = GoCorePreference.PreferGo,
            databasePath = Path("/tmp/watcher.db"),
            defaultSocket = sock,
            error = PrintStream(error),
            fetch = { _, _ -> error("no socket") },
            findBinary = { null },
            startCore = { _, _, _, _ -> error("should not start") },
        )
        assertNull(result.wiring.processSource)
        assertTrue(error.toString().contains("JVM collector"))
    }
}
