package io.github.cdsap.daemonitor.coreipc

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoCoreDaemonLogSourceTest {
    @Test
    fun `parses daemon log discovery from go core json`() {
        val json = """
            {
              "logs": [
                {
                  "pid": 12914,
                  "gradle_version": "8.9",
                  "path": "/Users/dev/.gradle/daemon/8.9/daemon-12914.out.log"
                },
                {
                  "pid": 7,
                  "gradle_version": "8.10",
                  "path": "/tmp/daemon-7.out.log"
                }
              ]
            }
        """.trimIndent()

        val logs = GoCoreSnapshotParser.parseDaemonLogs(json)
        assertEquals(2, logs.size)
        assertEquals(12914L, logs[0].pid)
        assertEquals("8.9", logs[0].gradleVersion)
        assertEquals(Path.of("/Users/dev/.gradle/daemon/8.9/daemon-12914.out.log"), logs[0].path)
        assertEquals(7L, logs[1].pid)
    }

    @Test
    fun `parses redacted tail lines`() {
        val json = """
            {
              "pid": 42,
              "gradle_version": "8.10",
              "path": "/tmp/daemon-42.out.log",
              "lines": [
                "hello",
                "running with -Ptoken=*** now",
                "BUILD SUCCESSFUL in 1s"
              ]
            }
        """.trimIndent()

        val lines = GoCoreSnapshotParser.parseDaemonLogTail(json)
        assertEquals(3, lines.size)
        assertEquals("running with -Ptoken=*** now", lines[1])
        assertTrue(lines.none { it.contains("topsecret") })
    }

    @Test
    fun `diffs ring tails for newly appended lines`() {
        assertEquals(
            listOf("a", "b"),
            GoCoreLogDelta.newLinesSince(previous = emptyList(), current = listOf("a", "b")),
        )
        assertEquals(
            emptyList(),
            GoCoreLogDelta.newLinesSince(previous = listOf("a", "b"), current = listOf("a", "b")),
        )
        assertEquals(
            listOf("c"),
            GoCoreLogDelta.newLinesSince(previous = listOf("a", "b"), current = listOf("a", "b", "c")),
        )
        assertEquals(
            listOf("d"),
            GoCoreLogDelta.newLinesSince(previous = listOf("a", "b", "c"), current = listOf("b", "c", "d")),
        )
    }

    @Test
    fun `daemon log source discovers and tails through fetch`() {
        val responses = mapOf(
            "/v1/daemon-logs" to """
                {"logs":[{"pid":9,"gradle_version":"8.10","path":"/tmp/daemon-9.out.log"}]}
            """.trimIndent(),
            "/v1/daemon-logs/9/tail" to """
                {"pid":9,"gradle_version":"8.10","path":"/tmp/daemon-9.out.log","lines":["one","two"]}
            """.trimIndent(),
        )
        val socket = kotlin.io.path.createTempFile(prefix = "go-core-log-", suffix = ".sock")
        val source = GoCoreDaemonLogSource(socketPath = socket) { _, path ->
            responses.getValue(path)
        }

        val logs = source.discover()
        assertEquals(1, logs.size)
        assertEquals(9L, logs[0].pid)
        assertEquals(listOf("one", "two"), source.tailFor(logs[0]))

        val first = source.readNewLines(logs[0])
        assertEquals(listOf("one", "two"), first.map { it.text })

        val second = source.readNewLines(logs[0])
        assertTrue(second.isEmpty())
    }
}
