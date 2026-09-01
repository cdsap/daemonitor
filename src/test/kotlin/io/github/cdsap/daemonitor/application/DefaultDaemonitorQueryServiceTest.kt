package io.github.cdsap.daemonitor.application

import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.domain.model.FinalStatus
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import io.github.cdsap.daemonitor.domain.model.ProcessType
import io.github.cdsap.daemonitor.domain.model.Source
import io.github.cdsap.daemonitor.persistence.BuildRepository
import io.github.cdsap.daemonitor.persistence.ProcessSample
import io.github.cdsap.daemonitor.persistence.ProcessSampleRepository
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultDaemonitorQueryServiceTest {

    @Test
    fun `searchHistory delegates to retained builds`() {
        val builds = FakeBuildRepository(
            listOf(
                build("old", 1_000, project = "/repo/a"),
                build("match", 2_000, project = "/repo/target", status = FinalStatus.FAILED),
            ),
        )
        val queries = DefaultDaemonitorQueryService(builds, FakeProcessSampleRepository(), ProcessSource { emptyList() })

        val result = queries.searchHistory("target", limit = 50)

        assertEquals(listOf("match"), result.map { it.buildId })
        assertEquals(FinalStatus.FAILED, result.single().finalStatus)
    }

    @Test
    fun `buildsForProcess matches pid builds and samples`() {
        val builds = FakeBuildRepository(listOf(build("b1", 3_000, pid = 42, project = "/repo/target")))
        val samples = FakeProcessSampleRepository(
            listOf(
                processSample(
                    pid = 42,
                    parentPid = 7,
                    project = "/repo/target",
                    timestampMs = 3_100,
                    commandLine = "java GradleDaemon",
                    cpuPercent = 12.0,
                    rssMemoryMb = 512,
                    maxHeapMb = 2048,
                ),
            ),
        )
        val queries = DefaultDaemonitorQueryService(builds, samples, ProcessSource { emptyList() })

        val result = queries.buildsForProcess("42", limit = 50)

        assertEquals("42", result.process)
        assertEquals(listOf("b1"), result.matchedBuilds.map { it.buildId })
        assertEquals(listOf(42L), result.matchedProcessSamples.map { it.pid })
    }

    @Test
    fun `buildsForProcess text match filters recent samples`() {
        val builds = FakeBuildRepository(listOf(build("keep", 4_000, project = "/repo/target")))
        val samples = FakeProcessSampleRepository(
            listOf(
                processSample(pid = 10, project = "/repo/target", timestampMs = 4_100),
                processSample(pid = 11, project = "/repo/other", timestampMs = 4_200),
            ),
        )
        val queries = DefaultDaemonitorQueryService(builds, samples, ProcessSource { emptyList() })

        val result = queries.buildsForProcess("target", limit = 50)

        assertEquals(listOf("keep"), result.matchedBuilds.map { it.buildId })
        assertEquals(listOf(10L), result.matchedProcessSamples.map { it.pid })
    }

    @Test
    fun `currentProcesses uses process source`() {
        val process = gradleProcess(pid = 99, project = "/repo", type = ProcessType.KOTLIN_DAEMON)
        val queries = DefaultDaemonitorQueryService(
            FakeBuildRepository(),
            FakeProcessSampleRepository(),
            ProcessSource { listOf(process) },
        )

        assertEquals(listOf(99L), queries.currentProcesses().map { it.pid })
    }

    private fun build(
        id: String,
        startMs: Long,
        pid: Long = 1,
        project: String = "/repo",
        status: FinalStatus = FinalStatus.SUCCESS,
    ) = Build(
        buildId = id,
        daemonPid = pid,
        daemonIdentity = "uid-$pid",
        commandLine = "gradlew build",
        workingDirectory = project,
        projectPath = project,
        startTimeMs = startMs,
        endTimeMs = startMs + 1000,
        durationSeconds = 1.0,
        peakMemoryMb = 700,
        avgMemoryMb = 600,
        peakCpuPercent = 50.0,
        inferredSource = Source.TERMINAL,
        finalStatus = status,
        logSnippet = "BUILD ${if (status == FinalStatus.SUCCESS) "SUCCESSFUL" else "FAILED"}",
        agent = null,
        agentProvider = null,
    )

    private fun gradleProcess(
        pid: Long,
        project: String,
        type: ProcessType = ProcessType.GRADLE_DAEMON,
    ) = GradleProcess(
        pid = pid,
        parentPid = 1,
        type = type,
        commandLine = "java $type",
        workingDirectory = project,
        projectPath = project,
        cpuPercent = 1.0,
        rssMemoryMb = 256,
        maxHeapMb = 1024,
        minHeapMb = null,
        gc = null,
        startTimeMs = 1,
        status = "RUNNING",
    )

    private fun processSample(
        pid: Long,
        project: String,
        timestampMs: Long,
        type: ProcessType = ProcessType.GRADLE_DAEMON,
        commandLine: String = "java $type",
        parentPid: Long = 1,
        cpuPercent: Double? = 1.0,
        rssMemoryMb: Long = 256,
        maxHeapMb: Long? = 1024,
    ) = ProcessSample(
        timestampMs = timestampMs,
        pid = pid,
        parentPid = parentPid,
        processType = type,
        commandLine = commandLine,
        workingDirectory = project,
        projectPath = project,
        cpuPercent = cpuPercent,
        rssMemoryMb = rssMemoryMb,
        maxHeapMb = maxHeapMb,
        status = "RUNNING",
    )

    private class FakeBuildRepository(
        private val stored: List<Build> = emptyList(),
    ) : BuildRepository {
        override fun save(build: Build) = error("not used")

        override fun recent(): List<Build> = stored

        override fun search(query: String, limit: Long): List<Build> {
            val needle = query.trim()
            if (needle.isEmpty()) return stored.take(limit.toInt())
            return stored.filter { build ->
                build.projectPath?.contains(needle, ignoreCase = true) == true ||
                    build.workingDirectory?.contains(needle, ignoreCase = true) == true ||
                    build.commandLine?.contains(needle, ignoreCase = true) == true ||
                    build.buildId.contains(needle, ignoreCase = true)
            }.take(limit.toInt())
        }

        override fun findByDaemon(pid: Long, limit: Long): List<Build> =
            stored.filter { it.daemonPid == pid }.take(limit.toInt())

        override fun distinctProjects(): List<String> = error("not used")
    }

    private class FakeProcessSampleRepository(
        private val stored: List<ProcessSample> = emptyList(),
    ) : ProcessSampleRepository {
        override fun save(sample: GradleProcess, timestampMs: Long) = error("not used")

        override fun samples(pid: Long, fromMs: Long, toMs: Long): List<Pair<Long, Double?>> =
            error("not used")

        override fun recentSamples(limit: Long): List<ProcessSample> =
            stored.sortedByDescending { it.timestampMs }.take(limit.toInt())

        override fun findByPid(pid: Long, limit: Long): List<ProcessSample> =
            stored.filter { it.pid == pid }.take(limit.toInt())
    }
}
