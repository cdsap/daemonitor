package io.github.cdsap.daemonitor.coreipc

import io.github.cdsap.daemonitor.domain.model.ProcessType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoCoreSnapshotParserTest {
    @Test
    fun `parses process snapshot from go core json`() {
        val json = """
            {
              "sampled_at_ms": 1000,
              "processes": [
                {
                  "pid": 42,
                  "type": "GRADLE_DAEMON",
                  "name": "java",
                  "command_line": "org.gradle.launcher.daemon.bootstrap.GradleDaemon",
                  "rss_memory_mb": 512.4,
                  "cpu_percent": 12.5,
                  "sampled_at_ms": 1000
                },
                {
                  "pid": 7,
                  "type": "KOTLIN_DAEMON",
                  "name": "java",
                  "command_line": "KotlinCompileDaemon",
                  "rss_memory_mb": 200,
                  "cpu_percent": 1.0,
                  "sampled_at_ms": 1000
                }
              ]
            }
        """.trimIndent()

        val processes = GoCoreSnapshotParser.parseProcesses(json)
        assertEquals(2, processes.size)
        assertEquals(42L, processes[0].pid)
        assertEquals(ProcessType.GRADLE_DAEMON, processes[0].type)
        assertEquals(512L, processes[0].rssMemoryMb)
        assertEquals(12.5, processes[0].cpuPercent)
        assertEquals(ProcessType.KOTLIN_DAEMON, processes[1].type)
    }

    @Test
    fun `ignores unknown process types`() {
        val json = """{"processes":[{"pid":1,"type":"NOT_A_TYPE","command_line":"x","rss_memory_mb":1,"cpu_percent":0}]}"""
        assertTrue(GoCoreSnapshotParser.parseProcesses(json).isEmpty())
    }
}
