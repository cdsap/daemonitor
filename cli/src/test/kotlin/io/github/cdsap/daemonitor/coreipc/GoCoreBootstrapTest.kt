package io.github.cdsap.daemonitor.coreipc

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.test.Test
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
            startCore = { _, _, _ -> error("should not start") },
        )
        assertNull(result.wiring.processSource)
        assertFalse(result.startedCore)
    }

    @Test
    fun `prefer go uses healthy socket without starting`() {
        val dir = Files.createTempDirectory("cored-healthy")
        val sock = dir.resolve("cored.sock")
        Files.createFile(sock)
        val result = resolveGoCore(
            preference = GoCorePreference.PreferGo,
            databasePath = Path("/tmp/watcher.db"),
            defaultDatabase = Path("/tmp/watcher.db"),
            defaultSocket = sock,
            fetch = { _, _ -> """{"status":"ok","db_path":"/tmp/watcher.db"}""" },
            findBinary = { error("should not look up binary") },
            startCore = { _, _, _ -> error("should not start") },
        )
        assertNotNull(result.wiring.processSource)
        assertTrue(result.wiring.banner!!.contains("Go core"))
        assertFalse(result.startedCore)
    }

    @Test
    fun `prefer go starts cored then attaches`() {
        val error = ByteArrayOutputStream()
        var started = false
        val sock = Files.createTempDirectory("cored-boot").resolve("cored.sock")
        val result = resolveGoCore(
            preference = GoCorePreference.PreferGo,
            databasePath = Path("/tmp/watcher.db"),
            defaultDatabase = Path("/tmp/other.db"),
            defaultSocket = sock,
            error = PrintStream(error),
            fetch = { path, _ ->
                if (!started || path != sock) error("unreachable")
                """{"status":"ok"}"""
            },
            findBinary = { Path("/usr/bin/true") },
            startCore = { _, socket, _ ->
                Files.createFile(socket)
                started = true
                true
            },
            waitHealthyMs = 500,
        )
        assertNotNull(result.wiring.processSource)
        assertTrue(result.startedCore)
        assertTrue(error.toString().contains("Starting daemonitor-cored"))
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
            startCore = { _, _, _ -> error("should not start") },
        )
        assertNull(result.wiring.processSource)
        assertTrue(error.toString().contains("JVM collector"))
    }
}
