package io.github.cdsap.daemonitor.coreipc

import io.github.cdsap.daemonitor.domain.model.FinalStatus
import io.github.cdsap.daemonitor.domain.model.ProcessType
import io.github.cdsap.daemonitor.domain.model.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoCoreSnapshotParserTest {
    @Test
    fun `retains exact longs and scientific metrics without matching nested fields`() {
        val process = GoCoreSnapshotParser.parseProcesses("""{"processes":[{
            "nested":{"pid":99},"pid":9007199254740993,"type":"GRADLE_DAEMON",
            "start_time_ms":9223372036854775807,"cpu_percent":1.25e-3,
            "command_line":"\u263a \b\f",
            "classes":{"loaded":9007199254740993},
            "memory_pools":[{"name":"pool } [","used":{"value":1.5e2,"unit":"bytes"}}]
        }]}""").single()
        assertEquals(9007199254740993L, process.pid)
        assertEquals(Long.MAX_VALUE, process.startTimeMs)
        assertEquals(0.00125, process.cpuPercent)
        assertEquals("☺ \b\u000c", process.commandLine)
        assertEquals(9007199254740993L, process.classes?.loaded)
        assertEquals(150.0, process.memoryPools.single().used?.value)
        val host = GoCoreSnapshotParser.parseHost("""{
            "sampled_at_ms":9007199254740993,
            "logical_processors":{"availability":"available","value":8},
            "cpu_usage_percent":{"availability":"available","value":0},
            "memory_used_bytes":{"availability":"unsupported","value":100},
            "memory_total_bytes":{"availability":"available","value":9007199254740993}
        }""")
        assertEquals(8L, host.logicalProcessors)
        assertEquals(0.0, host.cpuPercent)
        assertNull(host.memoryUsedBytes)
        assertEquals(9007199254740993L, host.memoryTotalBytes)
    }


    @Test
    fun `parses complete health snapshot`() {
        val health = GoCoreSnapshotParser.parseHealth(
            """{"status":"ok","db_path":"/tmp/watcher.db","retention_ms":2592000000,"pid":4242}""",
        )

        assertEquals("/tmp/watcher.db", health.databasePath)
        assertEquals(2_592_000_000L, health.retentionMs)
        assertEquals(4_242L, health.pid)
    }

    @Test
    fun `keeps missing and invalid optional health fields nullable`() {
        val partial = GoCoreSnapshotParser.parseHealth(
            """{"status":"ok","db_path":"","retention_ms":"invalid"}""",
        )

        assertNull(partial.databasePath)
        assertNull(partial.retentionMs)
        assertNull(partial.pid)
    }

    fun `host preserves zero and unavailable metrics and capabilities`() {
        val host = GoCoreSnapshotParser.parseHost("""{"sampled_at_ms": 100, "logical_processors":{"availability":"available","value":8}, "cpu_usage_percent":{"availability":"available","value":0}, "memory_used_bytes":{"availability":"unsupported","value":100}}""")
        assertEquals(8L, host.logicalProcessors)
        assertEquals(0.0, host.cpuPercent)
        assertNull(host.memoryUsedBytes)
        val caps = GoCoreSnapshotParser.parseCapabilities("""{"metrics":[{"metric_name":"host.cpu.usage_percent","availability":"available"},{"metric_name":"host.memory.swap","availability":"unsupported"}]}""")
        assertEquals(listOf("available", "unsupported"), caps.map { it.availability })
        assertEquals(emptyList(), GoCoreSnapshotParser.parseCapabilities("""{"metrics":[]}"""))
        kotlin.test.assertFails { GoCoreSnapshotParser.parseCapabilities("{}") }
    }

    @Test
    fun `parses pools and jit without inventing missing values`() {
        val process = GoCoreSnapshotParser.parseProcesses("""
            {"processes":[{"pid":1,"type":"GRADLE_DAEMON",
              "memory_pools":[{"name":"Eden","source":"jstat","used":{"value":0,"unit":"K"},"committed":{"value":12.5,"unit":"M"},"max":null}],
              "code_cache":[{"name":"CodeHeap","used":{"value":2,"unit":"M"},"max":{"value":120,"unit":"M"},"peak":{"value":3,"unit":"M"}}],
              "jit":{"compiled_count":0,"failed_count":null,"compilation_time_seconds":1.25},
              "classes":{"loaded":17,"unloaded_total":0,"loaded_total":20,"time_seconds":0.123456789}}]}
        """).single()
        assertEquals(0.0, process.memoryPools.single().used?.value)
        assertEquals("K", process.memoryPools.single().used?.unit)
        assertEquals(12.5, process.memoryPools.single().committed?.value)
        assertNull(process.memoryPools.single().max)
        assertEquals("CodeHeap", process.codeCache.single().name)
        assertNull(process.codeCache.single().committed)
        assertEquals(120.0, process.codeCache.single().max?.value)
        assertEquals(3.0, process.codeCache.single().peak?.value)
        assertEquals("M", process.codeCache.single().peak?.unit)
        assertNull(process.memoryPools.single().peak)
        assertEquals(0L, process.jit?.compiledCount)
        assertNull(process.jit?.failedCount)
        assertEquals(1.25, process.jit?.compilationTimeSeconds)
        assertEquals(17L, process.classes?.loaded)
        assertEquals(0L, process.classes?.unloadedTotal)
        assertEquals(20L, process.classes?.loadedTotal)
        assertEquals(0.123456789, process.classes?.timeSeconds)

        val empty = GoCoreSnapshotParser.parseProcesses("""
            {"processes":[{"pid":2,"type":"JAVA_GRADLE_RELATED","memory_pools":null,
              "code_cache":[],"jit":null}]}
        """).single()
        assertTrue(empty.memoryPools.isEmpty())
        assertTrue(empty.codeCache.isEmpty())
        assertNull(empty.jit)
        assertNull(empty.classes)
    }
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
                  "cpu_user_seconds_total": 10.25,
                  "cpu_system_seconds_total": 2.75,
                  "cpu_total_seconds_total": 13.0,
                  "cpu_cores_used": 1.5,
                  "logical_processor_count": 8,
                  "resource_scope": "process",
                  "max_heap_mb": 2048,
                  "min_heap_mb": 512,
                  "gc": "G1",
                  "start_time_ms": 99,
                  "status": "R",
                  "automated": false,
                  "sampled_at_ms": 1000,
                  "heap_used_mb": 800,
                  "heap_committed_mb": 1024,
                  "heap_max_mb": null,
                  "heap_sampled_at_ms": 1000,
                  "heap_available": true,
                  "virtual_memory_mb": 4096,
                  "swap_memory_mb": 12,
                  "proportional_bytes": 123,
                  "private_committed_bytes": 0,
                  "physical_footprint_bytes": 1048576,
                  "peak_resident_bytes": 2097152,
                  "thread_count": 84,
                  "read_bytes": 1048576,
                  "write_bytes": 2048,
                  "minor_page_faults": 11,
                  "major_page_faults": 2,
                  "voluntary_context_switches": 30,
                  "open_file_descriptors": 41,
                  "metaspace_used_mb": 72,
                  "young_gc_count": 8,
                  "young_gc_time_ms": 120,
                  "young_gc_time_seconds": 1.234,
                  "full_gc_time_seconds": 2.345,
                  "concurrent_gc_time_seconds": 3.456,
                  "total_gc_time_seconds": 7.035,
                  "java_version": "21.0.8",
                  "java_runtime_version": "21.0.8+9-LTS",
                  "java_vendor": "Eclipse Adoptium",
                  "java_vm_name": "OpenJDK 64-Bit Server VM",
                  "java_vm_version": "21.0.8+9-LTS",
                  "os_name": "Linux",
                  "os_arch": "amd64",
                  "active_processor_count": 8
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
                  "proportional_bytes": null,
                  "physical_footprint_bytes": null,
                  "cpu_percent": null,
                  "max_heap_mb": 64,
                  "min_heap_mb": null,
                  "gc": null,
                  "start_time_ms": 50,
                  "status": "R",
                  "automated": true,
                  "sampled_at_ms": 1000,
                  "heap_used_mb": null,
                  "heap_committed_mb": null,
                  "heap_max_mb": null,
                  "heap_sampled_at_ms": 1000,
                  "heap_available": false
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
        assertEquals(10.25, daemon.cpuUserSecondsTotal)
        assertEquals(2.75, daemon.cpuSystemSecondsTotal)
        assertEquals(13.0, daemon.cpuTotalSecondsTotal)
        assertEquals(1.5, daemon.cpuCoresUsed)
        assertEquals(8L, daemon.logicalProcessorCount)
        assertEquals("process", daemon.resourceScope)
        assertNull(processes[1].resourceScope)
        assertEquals(2048L, daemon.maxHeapMb)
        assertEquals(512L, daemon.minHeapMb)
        assertEquals("G1", daemon.gc)
        assertEquals("21.0.8", daemon.javaVersion)
        assertEquals("21.0.8+9-LTS", daemon.javaRuntimeVersion)
        assertEquals("Eclipse Adoptium", daemon.javaVendor)
        assertEquals("OpenJDK 64-Bit Server VM", daemon.javaVmName)
        assertEquals("21.0.8+9-LTS", daemon.javaVmVersion)
        assertEquals("Linux", daemon.osName)
        assertEquals("amd64", daemon.osArch)
        assertEquals(8L, daemon.activeProcessorCount)
        assertEquals("/tmp/daemon", daemon.workingDirectory)
        assertNull(daemon.projectPath)
        val live = assertNotNull(daemon.liveHeap)
        assertTrue(live.available)
        assertEquals(800L, live.usedMb)
        assertEquals(1024L, live.committedMb)
        assertNull(live.maxMb)
        assertEquals(1000L, live.sampledAtMs)
        assertEquals(4096L, daemon.virtualMemoryMb)
        assertEquals(12L, daemon.swapMemoryMb)
        assertEquals(123L, daemon.proportionalBytes)
        assertEquals(0L, daemon.privateCommittedBytes)
        assertEquals(1048576L, daemon.physicalFootprintBytes)
        assertEquals(2097152L, daemon.peakResidentBytes)
        assertEquals(84L, daemon.threadCount)
        assertEquals(1048576L, daemon.readBytes)
        assertEquals(2048L, daemon.writeBytes)
        assertEquals(11L, daemon.minorPageFaults)
        assertEquals(2L, daemon.majorPageFaults)
        assertEquals(30L, daemon.voluntaryContextSwitches)
        assertEquals(41L, daemon.openFileDescriptors)
        assertEquals(72L, daemon.metaspaceUsedMb)
        assertEquals(8L, daemon.youngGcCount)
        assertEquals(120L, daemon.youngGcTimeMs)
        assertEquals(1.234, daemon.youngGcTimeSeconds)
        assertEquals(2.345, daemon.fullGcTimeSeconds)
        assertEquals(3.456, daemon.concurrentGcTimeSeconds)
        assertEquals(7.035, daemon.totalGcTimeSeconds)
        assertEquals("21.0.8", daemon.javaVersion)
        assertEquals("Eclipse Adoptium", daemon.javaVendor)
        assertEquals(8L, daemon.activeProcessorCount)

        val wrapper = processes[1]
        assertNull(wrapper.proportionalBytes)
        assertNull(wrapper.privateCommittedBytes)
        assertNull(wrapper.physicalFootprintBytes)
        assertNull(wrapper.peakResidentBytes)
        assertEquals(ProcessType.GRADLE_WRAPPER, wrapper.type)
        assertNull(wrapper.cpuPercent)
        assertEquals(64L, wrapper.maxHeapMb)
        assertEquals("/Users/dev/proj", wrapper.projectPath)
        assertTrue(wrapper.automated)
        val wrapperHeap = assertNotNull(wrapper.liveHeap)
        assertFalse(wrapperHeap.available)
        assertNull(wrapperHeap.usedMb)
        assertNull(wrapperHeap.committedMb)
    }

    @Test
    fun `maps heap_available false to unavailable without inventing zeros`() {
        val json = """
            {"processes":[{"pid":1,"parent_pid":0,"type":"GRADLE_DAEMON","command_line":"GradleDaemon",
            "rss_memory_mb":100,"cpu_percent":1.0,"start_time_ms":1,"status":"R","automated":false,
            "heap_available":false,"heap_sampled_at_ms":55,"heap_used_mb":null,"heap_committed_mb":null}]}
        """.trimIndent()
        val heap = assertNotNull(GoCoreSnapshotParser.parseProcesses(json).single().liveHeap)
        assertFalse(heap.available)
        assertNull(heap.usedMb)
        assertNull(heap.committedMb)
        assertEquals(55L, heap.sampledAtMs)
    }

    @Test
    fun `omits live heap when go core omits heap fields`() {
        val json = """
            {"processes":[{"pid":1,"parent_pid":0,"type":"GRADLE_DAEMON","command_line":"GradleDaemon",
            "rss_memory_mb":100,"cpu_percent":1.0,"start_time_ms":1,"status":"R","automated":false}]}
        """.trimIndent()
        assertNull(GoCoreSnapshotParser.parseProcesses(json).single().liveHeap)
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

    @Test
    fun `parses multi-kilobyte command lines without stack overflow`() {
        val longCmd = "java " + (1..800).joinToString(" ") { "-Dprop$it=value$it" } +
            " org.gradle.launcher.daemon.bootstrap.GradleDaemon"
        val json = """
            {"processes":[{"pid":1,"parent_pid":0,"type":"GRADLE_DAEMON","command_line":"$longCmd",
            "rss_memory_mb":100,"cpu_percent":1.0,"start_time_ms":1,"status":"R","automated":false}]}
        """.trimIndent()
        val processes = GoCoreSnapshotParser.parseProcesses(json)
        assertEquals(1, processes.size)
        assertEquals(longCmd, processes.single().commandLine)
    }

    @Test
    fun `parses bazel ownership and inclusive rollups`() {
        val process = GoCoreSnapshotParser.parseProcesses("""{"processes":[{
            "pid":10,"type":"BAZEL_SERVER","owner_pid":9,"owner_start_time_ms":100,
            "subtree_rss_memory_mb":500,"subtree_cpu_percent":25.5,
            "active_worker_count":2,"active_sandboxed_action_count":3,
            "bazel_output_base":"/tmp/bazel/outbase","bazel_install_base":"/tmp/bazel/installbase"
        }]}""").single()
        assertEquals(9L, process.ownerPid)
        assertEquals(100L, process.ownerStartTimeMs)
        assertEquals(500L, process.subtreeRssMemoryMb)
        assertEquals(25.5, process.subtreeCpuPercent)
        assertEquals(2L, process.activeWorkerCount)
        assertEquals(3L, process.activeSandboxedActionCount)
        assertEquals("/tmp/bazel/outbase", process.bazelOutputBase)
        assertEquals("/tmp/bazel/installbase", process.bazelInstallBase)
    }

    @Test
    fun `parses bazel builds with tool profile details and window summaries`() {
        val json = """
            {
              "count": 1,
              "builds": [
                {
                  "build_id": "bz-42",
                  "daemon_pid": 777,
                  "start_time_ms": 1000,
                  "end_time_ms": 5000,
                  "duration_seconds": 4.0,
                  "peak_memory_mb": 1200,
                  "inferred_source": "BEP",
                  "final_status": "SUCCESS",
                  "tool": "BAZEL",
                  "agent": "Claude Code",
                  "agent_provider": "Anthropic",
                  "bazel_profile": {
                    "bazel_version": "8.1.1",
                    "phase_seconds": {"analysis": 1.2, "execution": 2.5},
                    "critical_path_seconds": 1.9,
                    "critical_path_components": 6
                  },
                  "bazel_details": {
                    "schema_version": 1,
                    "bazel_version": "8.1.1",
                    "source_format": "json",
                    "collection_state": "sealed",
                    "metrics": {
                      "timing": {"analysis_phase_ms": 1200, "execution_phase_ms": 2500},
                      "actions": {"executed": 30, "remote_cache_hits": 20, "local_cache_hits": 5, "local_cache_misses": 5},
                      "workers": [{"process_id": 1001, "mnemonic": "Javac"}],
                      "test_totals": {"total": 4, "passed": 3, "failed": 1, "cached": 2}
                    }
                  },
                  "window_summaries": [
                    {"pid": 777, "process_type": "BAZEL_SERVER", "sample_count": 3, "first_rss_mb": 800, "peak_rss_mb": 1200, "last_rss_mb": 950, "delta_rss_mb": 150, "first_heap_used_mb": 300, "last_heap_used_mb": 420, "delta_heap_used_mb": 120, "delta_total_gc_count": 3}
                  ]
                }
              ]
            }
        """.trimIndent()

        val build = GoCoreSnapshotParser.parseBuilds(json).single()
        assertEquals(io.github.cdsap.daemonitor.domain.model.BuildTool.BAZEL, build.tool)
        assertEquals("8.1.1", build.bazelProfile?.bazelVersion)
        assertEquals(1.9, build.bazelProfile?.criticalPathSeconds)
        assertEquals(1200L, build.bazelDetails?.analysisPhaseMs)
        assertEquals(20L, build.bazelDetails?.remoteCacheHits)
        assertEquals(1, build.bazelDetails?.workerCount)
        assertEquals(4, build.bazelDetails?.testsTotal)
        assertEquals(150L, build.windowSummaries.single().deltaRssMb)
        assertEquals(120L, build.windowSummaries.single().deltaHeapUsedMb)
    }

    @Test
    fun `parses daemon log tail with unbalanced brackets escaped quotes long lines and null lines`() {
        val longPayload = "x".repeat(12_000)
        val json = """
            {
              "pid": 50527,
              "gradle_version": "9.6.1",
              "lines": [
                "2026-11-25 [DEBUG] [org.gradle.cache.internal] truncated [unbalanced bracket",
                "2026-11-25 [INFO] quoted \"hello [world]\" and \\path\\file",
                "2026-11-25 [DEBUG] $longPayload"
              ],
              "events": [{"kind": "STARTED"}]
            }
        """.trimIndent()

        val lines = GoCoreSnapshotParser.parseDaemonLogTail(json)
        assertEquals(3, lines.size)
        assertEquals("2026-11-25 [DEBUG] [org.gradle.cache.internal] truncated [unbalanced bracket", lines[0])
        assertEquals("2026-11-25 [INFO] quoted \"hello [world]\" and \\path\\file", lines[1])
        assertEquals("2026-11-25 [DEBUG] $longPayload", lines[2])

        val nullLinesJson = """{"pid": 50527, "lines": null, "events": ["should_not_bleed"]}"""
        assertEquals(emptyList(), GoCoreSnapshotParser.parseDaemonLogTail(nullLinesJson))
    }
}
