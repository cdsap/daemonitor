package io.github.cdsap.daemonitor.collect

import io.github.cdsap.daemonitor.domain.model.LiveJvmHeap
import io.github.cdsap.daemonitor.domain.model.ProcessInfo
import io.github.cdsap.daemonitor.domain.model.ProcessType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class CollectorFakeProcess(
    override val pid: Long,
    override val parentPid: Long = 1,
    override val name: String = "java",
    override val commandLine: String,
    override val workingDirectory: String = "/Users/dev/proj",
    override val rssBytes: Long = 512L * 1024 * 1024,
    override val startTimeMs: Long = 1_000,
    override val state: String = "RUNNING",
    override val userId: String = "501",
    override val cpuTimeMs: Long = 0,
) : ProcessInfo

private class RecordingHeapProbe : JvmHeapProbe {
    val probedPids = mutableListOf<Long>()

    override fun probe(pid: Long, sampledAtMs: Long): LiveJvmHeap {
        probedPids += pid
        return LiveJvmHeap.unavailable(sampledAtMs)
    }
}

class ProcessCollectorTest {

    @Test
    fun `ordinary same-user process does not invoke heap collection`() {
        val probe = RecordingHeapProbe()
        val collector = ProcessCollector(
            clock = { 2_000L },
            heapProbe = probe,
            logicalProcessors = 8,
            enumerateProcesses = {
                listOf(
                    CollectorFakeProcess(pid = 42, name = "node", commandLine = "node server.js"),
                )
            },
        )

        val processes = collector.currentProcesses()

        assertTrue(processes.isEmpty())
        assertTrue(probe.probedPids.isEmpty(), "non-Gradle process must not trigger Attach/JMX")
    }

    @Test
    fun `gradle daemon invokes heap collection after classification`() {
        val probe = RecordingHeapProbe()
        val collector = ProcessCollector(
            clock = { 2_000L },
            heapProbe = probe,
            logicalProcessors = 8,
            enumerateProcesses = {
                listOf(
                    CollectorFakeProcess(
                        pid = 77,
                        commandLine =
                            "java -Xmx4g org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.14.3",
                    ),
                )
            },
        )

        val processes = collector.currentProcesses()

        assertEquals(1, processes.size)
        assertEquals(ProcessType.GRADLE_DAEMON, processes.single().type)
        assertEquals(listOf(77L), probe.probedPids)
    }

    @Test
    fun `gradle wrapper is retained without heap attachment`() {
        val probe = RecordingHeapProbe()
        val collector = ProcessCollector(
            clock = { 2_000L },
            heapProbe = probe,
            logicalProcessors = 8,
            enumerateProcesses = {
                listOf(
                    CollectorFakeProcess(
                        pid = 88,
                        commandLine = "java org.gradle.wrapper.GradleWrapperMain test",
                    ),
                )
            },
        )

        val processes = collector.currentProcesses()

        assertEquals(1, processes.size)
        assertEquals(ProcessType.GRADLE_WRAPPER, processes.single().type)
        assertTrue(probe.probedPids.isEmpty(), "wrappers must not trigger Attach/JMX")
    }
}
