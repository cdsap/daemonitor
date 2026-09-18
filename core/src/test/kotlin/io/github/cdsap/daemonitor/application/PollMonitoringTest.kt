package io.github.cdsap.daemonitor.application

import io.github.cdsap.daemonitor.collect.DaemonLog
import io.github.cdsap.daemonitor.collect.DaemonLogLine
import io.github.cdsap.daemonitor.domain.BuildAggregator
import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.domain.model.BuildStart
import io.github.cdsap.daemonitor.domain.model.BusyMark
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.IdleMark
import io.github.cdsap.daemonitor.domain.model.Outcome
import io.github.cdsap.daemonitor.domain.model.ProcessType
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PollMonitoringTest {

    @Test
    fun `pollOnce persists samples and builds through repository ports only`() {
        val process = gradleDaemon(pid = 42)
        val log = DaemonLog(pid = 42, gradleVersion = "8.14.3", path = Path.of("/tmp/daemon-42.out.log"))
        val processSource = FakeProcessSource(listOf(process))
        val logSource = FakeDaemonLogSource(
            logs = listOf(log),
            linesByPid = mapOf(
                42L to listOf(
                    DaemonLogLine("busy", BusyMark(1_000)),
                    DaemonLogLine("start", BuildStart(1_001, "build-1", "/project")),
                    DaemonLogLine("ok", Outcome(success = true, durationSeconds = 1.0)),
                    DaemonLogLine("idle", IdleMark(1_003)),
                ),
            ),
        )
        val builds = RecordingBuildRepository()
        val samples = RecordingSampleRepository()
        val monitoring = PollMonitoring(
            processSource = processSource,
            logSource = logSource,
            builds = builds,
            samples = samples,
            aggregator = BuildAggregator(),
            clock = { 5_000 },
        )

        val result = monitoring.pollOnce()

        assertEquals(listOf(process), result.processes)
        assertEquals(listOf(log), result.daemonLogs)
        assertTrue(result.buildsChanged)
        assertEquals(listOf(process to 5_000L), samples.saved)
        assertEquals(listOf("build-1"), builds.saved.map { it.buildId })
        assertEquals(1, processSource.calls)
        assertEquals(1, logSource.discoverCalls)
        assertEquals(listOf(log), logSource.readCalls)
    }

    @Test
    fun `tailFor reads from the daemon log port`() {
        val log = DaemonLog(pid = 7, gradleVersion = "9.0", path = Path.of("/tmp/daemon-7.out.log"))
        val logSource = FakeDaemonLogSource(
            logs = listOf(log),
            tails = mapOf(log to listOf("line-a", "line-b")),
        )
        val monitoring = PollMonitoring(
            processSource = FakeProcessSource(emptyList()),
            logSource = logSource,
            builds = RecordingBuildRepository(),
            samples = RecordingSampleRepository(),
            aggregator = BuildAggregator(),
        )

        assertEquals(listOf("line-a", "line-b"), monitoring.tailFor(listOf(log), pid = 7))
        assertEquals(emptyList(), monitoring.tailFor(listOf(log), pid = 99))
    }

    private fun gradleDaemon(pid: Long) = GradleProcess(
        pid = pid,
        parentPid = 1,
        type = ProcessType.GRADLE_DAEMON,
        commandLine = "java GradleDaemon",
        workingDirectory = "/project",
        projectPath = "/project",
        cpuPercent = 1.0,
        rssMemoryMb = 128,
        maxHeapMb = 512,
        minHeapMb = null,
        gc = null,
        startTimeMs = 1,
        status = "RUNNING",
    )

    private class FakeProcessSource(
        private val processes: List<GradleProcess>,
    ) : ProcessSource {
        var calls = 0
            private set

        override fun currentProcesses(): List<GradleProcess> {
            calls += 1
            return processes
        }
    }

    private class FakeDaemonLogSource(
        private val logs: List<DaemonLog>,
        private val linesByPid: Map<Long, List<DaemonLogLine>> = emptyMap(),
        private val tails: Map<DaemonLog, List<String>> = emptyMap(),
    ) : DaemonLogSource {
        var discoverCalls = 0
            private set
        val readCalls = mutableListOf<DaemonLog>()

        override fun discover(): List<DaemonLog> {
            discoverCalls += 1
            return logs
        }

        override fun readNewLines(log: DaemonLog): List<DaemonLogLine> {
            readCalls += log
            return linesByPid[log.pid].orEmpty()
        }

        override fun tailFor(log: DaemonLog): List<String> = tails[log].orEmpty()
    }

    private class RecordingBuildRepository : BuildRepository {
        val saved = mutableListOf<Build>()
        override fun save(build: Build) {
            saved += build
        }
    }

    private class RecordingSampleRepository : ProcessSampleRepository {
        val saved = mutableListOf<Pair<GradleProcess, Long>>()
        override fun save(sample: GradleProcess, timestampMs: Long) {
            saved += sample to timestampMs
        }
    }
}
