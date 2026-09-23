package io.github.cdsap.daemonitor.application

import io.github.cdsap.daemonitor.config.RetentionPolicy
import io.github.cdsap.daemonitor.domain.BuildAggregator
import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.domain.model.BuildStart
import io.github.cdsap.daemonitor.domain.model.BusyMark
import io.github.cdsap.daemonitor.domain.model.FinalStatus
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.IdleMark
import io.github.cdsap.daemonitor.domain.model.Outcome
import io.github.cdsap.daemonitor.domain.model.ProcessType
import io.github.cdsap.daemonitor.domain.model.Source
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        val builds = RecordingBuildWriter()
        val samples = RecordingSampleWriter()
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
    fun `log replay does not persist builds older than retention`() {
        val day = RetentionPolicy.MILLIS_PER_DAY
        val now = 100L * day
        val oldStart = now - 8 * day
        val log = DaemonLog(pid = 42, gradleVersion = "8.14.3", path = Path.of("/tmp/daemon-42.out.log"))
        val logSource = FakeDaemonLogSource(
            logs = listOf(log),
            linesByPid = mapOf(
                42L to listOf(
                    DaemonLogLine("busy", BusyMark(oldStart)),
                    DaemonLogLine("start", BuildStart(oldStart + 1, "aged-build", "/project")),
                    DaemonLogLine("ok", Outcome(success = true, durationSeconds = 1.0)),
                    DaemonLogLine("idle", IdleMark(oldStart + 1_000)),
                ),
            ),
        )
        val builds = RecordingBuildWriter()
        val monitoring = PollMonitoring(
            processSource = FakeProcessSource(emptyList()),
            logSource = logSource,
            builds = builds,
            samples = RecordingSampleWriter(),
            aggregator = BuildAggregator(),
            retentionDays = { 7 },
            clock = { now },
        )

        val result = monitoring.pollOnce()

        assertFalse(result.buildsChanged)
        assertTrue(builds.saved.isEmpty())
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
            builds = RecordingBuildWriter(),
            samples = RecordingSampleWriter(),
            aggregator = BuildAggregator(),
        )

        assertEquals(listOf("line-a", "line-b"), monitoring.tailFor(listOf(log), pid = 7))
        assertEquals(emptyList(), monitoring.tailFor(listOf(log), pid = 99))
    }

    @Test
    fun `pollOnce only reads tails for live or previously known gradle daemons`() {
        val live = DaemonLog(pid = 42, gradleVersion = "8.14.3", path = Path.of("/tmp/daemon-42.out.log"))
        val historical = DaemonLog(pid = 99, gradleVersion = "8.14.3", path = Path.of("/tmp/daemon-99.out.log"))
        val logSource = FakeDaemonLogSource(
            logs = listOf(live, historical),
            linesByPid = mapOf(
                42L to listOf(
                    DaemonLogLine("busy", BusyMark(1_000)),
                    DaemonLogLine("start", BuildStart(1_001, "build-1", "/project")),
                    DaemonLogLine("ok", Outcome(success = true, durationSeconds = 1.0)),
                    DaemonLogLine("idle", IdleMark(1_003)),
                ),
                99L to listOf(
                    DaemonLogLine("busy", BusyMark(1_000)),
                    DaemonLogLine("start", BuildStart(1_001, "stale", "/old")),
                    DaemonLogLine("idle", IdleMark(1_003)),
                ),
            ),
        )
        val builds = RecordingBuildWriter()
        val monitoring = PollMonitoring(
            processSource = FakeProcessSource(listOf(gradleDaemon(pid = 42))),
            logSource = logSource,
            builds = builds,
            samples = RecordingSampleWriter(),
            aggregator = BuildAggregator(),
            clock = { 5_000 },
        )

        val result = monitoring.pollOnce()

        assertEquals(listOf(live, historical), result.daemonLogs)
        assertEquals(listOf(live), logSource.readCalls)
        assertEquals(listOf("build-1"), builds.saved.map { it.buildId })
    }

    @Test
    fun `pollOnce still reads a daemon that just left the live set`() {
        val log = DaemonLog(pid = 42, gradleVersion = "8.14.3", path = Path.of("/tmp/daemon-42.out.log"))
        val logSource = FakeDaemonLogSource(
            logs = listOf(log),
            linesByPid = mapOf(
                42L to listOf(
                    DaemonLogLine("busy", BusyMark(1_000)),
                    DaemonLogLine("start", BuildStart(1_001, "build-1", "/project")),
                ),
            ),
        )
        val builds = RecordingBuildWriter()
        val processSource = FakeProcessSource(listOf(gradleDaemon(pid = 42)))
        val monitoring = PollMonitoring(
            processSource = processSource,
            logSource = logSource,
            builds = builds,
            samples = RecordingSampleWriter(),
            aggregator = BuildAggregator(),
            clock = { 5_000 },
        )

        monitoring.pollOnce()
        assertEquals(listOf(log), logSource.readCalls)

        processSource.processes = emptyList()
        logSource.linesByPid = mapOf(
            42L to listOf(
                DaemonLogLine("ok", Outcome(success = true, durationSeconds = 1.0)),
                DaemonLogLine("idle", IdleMark(2_000)),
            ),
        )
        logSource.readCalls.clear()
        val second = monitoring.pollOnce()

        assertEquals(listOf(log), logSource.readCalls)
        assertTrue(second.buildsChanged)
        assertEquals(listOf("build-1"), builds.saved.map { it.buildId })
    }

    @Test
    fun `pollOnce imports remote builds and skips log re-aggregation`() {
        val log = DaemonLog(pid = 42, gradleVersion = "8.14.3", path = Path.of("/tmp/daemon-42.out.log"))
        val logSource = FakeDaemonLogSource(
            logs = listOf(log),
            linesByPid = mapOf(
                42L to listOf(
                    DaemonLogLine("busy", BusyMark(1_000)),
                    DaemonLogLine("start", BuildStart(1_001, "ignored", "/project")),
                    DaemonLogLine("idle", IdleMark(1_003)),
                ),
            ),
        )
        val remote = Build(
            buildId = "go-build-1",
            daemonPid = 42,
            daemonIdentity = null,
            commandLine = null,
            workingDirectory = "/project",
            projectPath = "/project",
            startTimeMs = 1_000,
            endTimeMs = 2_000,
            durationSeconds = 1.0,
            peakMemoryMb = 256,
            avgMemoryMb = 200,
            peakCpuPercent = 10.0,
            inferredSource = Source.IDE,
            finalStatus = FinalStatus.SUCCESS,
            logSnippet = "BUILD SUCCESSFUL",
        )
        val builds = RecordingBuildWriter()
        val monitoring = PollMonitoring(
            processSource = FakeProcessSource(listOf(gradleDaemon(pid = 42))),
            logSource = logSource,
            builds = builds,
            samples = RecordingSampleWriter(),
            aggregator = BuildAggregator(),
            buildSource = object : BuildSource {
                override fun recentBuilds(limit: Int) = listOf(remote)
            },
            clock = { 5_000 },
        )

        val first = monitoring.pollOnce()
        assertTrue(first.buildsChanged)
        assertTrue(logSource.readCalls.isEmpty())
        assertEquals(listOf("go-build-1"), builds.saved.map { it.buildId })

        builds.saved.clear()
        val second = monitoring.pollOnce()
        assertFalse(second.buildsChanged)
        assertEquals(listOf("go-build-1"), builds.saved.map { it.buildId })
    }

    @Test
    fun `shared core db skips sample persistence and remote build import`() {
        val samples = RecordingSampleWriter()
        val builds = RecordingBuildWriter()
        val monitoring = PollMonitoring(
            processSource = FakeProcessSource(listOf(gradleDaemon(pid = 7))),
            logSource = FakeDaemonLogSource(emptyList()),
            builds = builds,
            samples = samples,
            aggregator = BuildAggregator(),
            buildSource = null,
            persistSamples = false,
            clock = { 5_000 },
        )

        val result = monitoring.pollOnce()
        assertEquals(1, result.processes.size)
        assertTrue(samples.saved.isEmpty())
        assertTrue(builds.saved.isEmpty())
        assertFalse(result.buildsChanged)
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
        var processes: List<GradleProcess>,
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
        var linesByPid: Map<Long, List<DaemonLogLine>> = emptyMap(),
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

    private class RecordingBuildWriter : BuildWriter {
        val saved = mutableListOf<Build>()
        override fun save(build: Build) {
            saved += build
        }
    }

    private class RecordingSampleWriter : ProcessSampleWriter {
        val saved = mutableListOf<Pair<GradleProcess, Long>>()
        override fun save(sample: GradleProcess, timestampMs: Long) {
            saved += sample to timestampMs
        }
    }
}
