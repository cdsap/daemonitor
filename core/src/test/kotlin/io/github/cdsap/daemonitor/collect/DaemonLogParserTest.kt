package io.github.cdsap.daemonitor.collect

import io.github.cdsap.daemonitor.domain.model.BuildEnvNames
import io.github.cdsap.daemonitor.domain.model.BuildStart
import io.github.cdsap.daemonitor.domain.model.BusyMark
import io.github.cdsap.daemonitor.domain.model.DaemonContextEvent
import io.github.cdsap.daemonitor.domain.model.IdleMark
import io.github.cdsap.daemonitor.domain.model.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DaemonLogParserTest {

    // Authored from a real daemon-*.out.log (Gradle 8.10.2).
    private val busy = "2026-06-24T14:42:12.464-0700 [INFO] [org.gradle.launcher.daemon.server.DaemonRegistryUpdater] Marking the daemon as busy, address: [c6fa port:62608]"
    private val buildStart = "2026-06-24T14:42:12.465-0700 [INFO] [org.gradle.launcher.daemon.server.exec.StartBuildOrRespondWithBusy] Daemon is about to start building Build{id=cb67ca7c-3264-4db5-9e32-cfe689c685c0, currentDir=/Users/ivillar/personal/gradle_watcher}. Dispatching build started information..."
    private val envLine = "2026-06-24T14:42:12.466-0700 [DEBUG] [org.gradle.launcher.daemon.server.exec.EstablishBuildEnvironment] Configuring env variables: [PATH, CLAUDECODE, AI_AGENT, TERM_PROGRAM, HOME]"
    private val outcome = "BUILD SUCCESSFUL in 38s"
    private val idle = "2026-06-24T14:42:50.327-0700 [INFO] [org.gradle.launcher.daemon.server.DaemonRegistryUpdater] Marking the daemon as idle, address: [c6fa port:62608]"
    private val context = "2026-06-24T14:42:12.402-0700 [INFO] [org.gradle.launcher.daemon.server.Daemon] start() called on daemon - DefaultDaemonContext[uid=78415476-2316-475f-82b3-69a16a06e3d0,javaHome=/x,javaVersion=21,daemonOpts=-Xmx512m,-Dfile.encoding=UTF-8]"

    @Test
    fun `parses one full build in order`() {
        val events = DaemonLogParser.parse(sequenceOf(context, busy, buildStart, envLine, outcome, idle))
        assertTrue(events[0] is DaemonContextEvent)
        assertTrue(events.any { it is BusyMark })
        val start = events.filterIsInstance<BuildStart>().single()
        assertEquals("/Users/ivillar/personal/gradle_watcher", start.currentDir)
        assertEquals("cb67ca7c-3264-4db5-9e32-cfe689c685c0", start.buildId)
        assertTrue(events.any { it is Outcome && it.success })
        assertTrue(events.last() is IdleMark)
    }

    @Test
    fun `bare outcome line parses without prefix`() {
        val ev = DaemonLogParser.parseLine("BUILD FAILED in 1m 2s")
        assertTrue(ev is Outcome)
        ev as Outcome
        assertTrue(!ev.success)
        assertEquals(62.0, ev.durationSeconds, 0.001)
    }

    @Test
    fun `extracts env var names for source detection`() {
        val ev = DaemonLogParser.parseLine(envLine) as BuildEnvNames
        assertTrue(ev.envNames.contains("CLAUDECODE"))
        assertTrue(ev.envNames.contains("TERM_PROGRAM"))
    }

    @Test
    fun `daemon context yields uid`() {
        val ev = DaemonLogParser.parseLine(context) as DaemonContextEvent
        assertEquals("78415476-2316-475f-82b3-69a16a06e3d0", ev.uid)
    }

    @Test
    fun `parses duration shapes`() {
        assertEquals(0.28, DaemonLogParser.parseDuration("280ms"), 0.001)
        assertEquals(7.0, DaemonLogParser.parseDuration("7s"), 0.001)
        assertEquals(62.0, DaemonLogParser.parseDuration("1m 2s"), 0.001)
        assertEquals(120.0, DaemonLogParser.parseDuration("2m"), 0.001)
    }

    @Test
    fun `non-matching and unrelated lines are ignored`() {
        assertEquals(null, DaemonLogParser.parseLine("2026-06-24T14:42:12.470-0700 [DEBUG] [Foo] something unrelated"))
        assertEquals(null, DaemonLogParser.parseLine("garbage"))
    }
}
