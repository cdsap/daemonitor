package io.github.cdsap.daemonitor.coreipc

import io.github.cdsap.daemonitor.domain.model.FinalStatus
import io.github.cdsap.daemonitor.domain.model.ProcessType
import io.github.cdsap.daemonitor.domain.model.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoCoreSnapshotParserTest {
    @Test
    fun `parses rich process snapshot from go core json`() {
        val json = """
            {
              "sampled_at_ms": 1000,
              "processes": [
                {
                  "pid": 42,
                  "parent_pid": 1,
                  "type": "GRADLE_DAEMON",
                  "name": "java",
                  "command_line": "org.gradle.launcher.daemon.bootstrap.GradleDaemon",
                  "working_directory": "/tmp/daemon",
                  "project_path": null,
                  "rss_memory_mb": 512,
                  "cpu_percent": 12.5,
                  "max_heap_mb": 2048,
                  "min_heap_mb": 512,
                  "gc": "G1",
                  "start_time_ms": 99,
                  "status": "R",
                  "automated": false,
                  "sampled_at_ms": 1000
                },
                {
                  "pid": 7,
                  "parent_pid": 0,
                  "type": "GRADLE_WRAPPER",
                  "name": "java",
                  "command_line": "gradle-wrapper.jar",
                  "working_directory": "/Users/dev/proj",
                  "project_path": "/Users/dev/proj",
                  "rss_memory_mb": 200,
                  "cpu_percent": null,
                  "max_heap_mb": 64,
                  "min_heap_mb": null,
                  "gc": null,
                  "start_time_ms": 50,
                  "status": "R",
                  "automated": true,
                  "sampled_at_ms": 1000
                }
              ]
            }
        """.trimIndent()

        val processes = GoCoreSnapshotParser.parseProcesses(json)
        assertEquals(2, processes.size)

        val daemon = processes[0]
        assertEquals(42L, daemon.pid)
        assertEquals(1L, daemon.parentPid)
        assertEquals(ProcessType.GRADLE_DAEMON, daemon.type)
        assertEquals(512L, daemon.rssMemoryMb)
        assertEquals(12.5, daemon.cpuPercent)
        assertEquals(2048L, daemon.maxHeapMb)
        assertEquals(512L, daemon.minHeapMb)
        assertEquals("G1", daemon.gc)
        assertEquals("/tmp/daemon", daemon.workingDirectory)
        assertNull(daemon.projectPath)

        val wrapper = processes[1]
        assertEquals(ProcessType.GRADLE_WRAPPER, wrapper.type)
        assertNull(wrapper.cpuPercent)
        assertEquals(64L, wrapper.maxHeapMb)
        assertEquals("/Users/dev/proj", wrapper.projectPath)
        assertTrue(wrapper.automated)
    }

    @Test
    fun `ignores unknown process types`() {
        val json = """{"processes":[{"pid":1,"type":"NOT_A_TYPE","command_line":"x","rss_memory_mb":1,"cpu_percent":0}]}"""
        assertTrue(GoCoreSnapshotParser.parseProcesses(json).isEmpty())
    }

    @Test
    fun `parses builds snapshot from go core json`() {
        val json = """
            {
              "count": 1,
              "builds": [
                {
                  "build_id": "abc-123",
                  "daemon_pid": 30246,
                  "daemon_identity": "",
                  "command_line": null,
                  "working_directory": "/Users/dev/proj",
                  "project_path": "/Users/dev/proj",
                  "start_time_ms": 1000,
                  "end_time_ms": 2500,
                  "duration_seconds": 1.5,
                  "peak_memory_mb": 512,
                  "avg_memory_mb": 400,
                  "peak_cpu_percent": 22.5,
                  "inferred_source": "IDE",
                  "final_status": "SUCCESS",
                  "log_snippet": "BUILD SUCCESSFUL in 1s",
                  "agent": "",
                  "agent_provider": ""
                }
              ]
            }
        """.trimIndent()

        val builds = GoCoreSnapshotParser.parseBuilds(json)
        assertEquals(1, builds.size)
        val build = builds.single()
        assertEquals("abc-123", build.buildId)
        assertEquals(30246L, build.daemonPid)
        assertNull(build.daemonIdentity)
        assertNull(build.commandLine)
        assertEquals("/Users/dev/proj", build.workingDirectory)
        assertEquals(1000L, build.startTimeMs)
        assertEquals(2500L, build.endTimeMs)
        assertEquals(1.5, build.durationSeconds)
        assertEquals(512L, build.peakMemoryMb)
        assertEquals(Source.IDE, build.inferredSource)
        assertEquals(FinalStatus.SUCCESS, build.finalStatus)
        assertEquals("BUILD SUCCESSFUL in 1s", build.logSnippet)
        assertNull(build.agent)
    }
}
