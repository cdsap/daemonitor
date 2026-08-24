package io.github.cdsap.daemonitor.domain

import io.github.cdsap.daemonitor.config.MonitoringConfig
import io.github.cdsap.daemonitor.domain.model.BuildEnvNames
import io.github.cdsap.daemonitor.domain.model.BuildEvent
import io.github.cdsap.daemonitor.domain.model.BuildStart
import io.github.cdsap.daemonitor.domain.model.BusyMark
import io.github.cdsap.daemonitor.domain.model.DaemonContextEvent
import io.github.cdsap.daemonitor.domain.model.FinalStatus
import io.github.cdsap.daemonitor.domain.model.IdleMark
import io.github.cdsap.daemonitor.domain.model.Outcome
import io.github.cdsap.daemonitor.domain.model.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuildAggregatorTest {

    private val pid = 75597L

    private fun context(ts: Long) = DaemonContextEvent(ts, uid = "uid-abc", daemonOpts = "-Xmx512m")
    private fun start(ts: Long, dir: String = "/proj/a") =
        BuildStart(ts, buildId = "build-$ts", currentDir = dir)

    @Test
    fun `qualified build emits one record with window peaks`() {
        // 3 samples in the window: rss 100,300,200 ; cpu 10,50,30
        val samples = listOf(100L to 10.0, 300L to 50.0, 200L to 30.0) as List<Pair<Long, Double?>>
        val agg = BuildAggregator(sampleProvider = { _, _, _ -> samples })

        val emitted = agg.onEvents(
            pid,
            listOf(context(0), BusyMark(1_000), start(1_010),
                BuildEnvNames(1_020, listOf("TERM_PROGRAM")), Outcome(true, 3.0), IdleMark(4_000)),
        )

        assertEquals(1, emitted.size)
        val b = emitted.single()
        assertEquals(300L, b.peakMemoryMb)
        assertEquals(200L, b.avgMemoryMb)
        assertEquals(50.0, b.peakCpuPercent)
        assertEquals(FinalStatus.SUCCESS, b.finalStatus)
        assertEquals(Source.TERMINAL, b.inferredSource)
        assertEquals("uid-abc", b.daemonIdentity)
        assertEquals("/proj/a", b.projectPath)
    }

    @Test
    fun `two sequential builds on one daemon emit two records`() {
        val agg = BuildAggregator()
        val emitted = agg.onEvents(
            pid,
            listOf(
                context(0),
                BusyMark(1_000), start(1_010), Outcome(true, 1.0), IdleMark(2_000),
                BusyMark(3_000), start(3_010), Outcome(true, 1.0), IdleMark(4_000),
            ),
        )
        assertEquals(2, emitted.size)
        assertEquals(2, emitted.map { it.buildId }.distinct().size)
    }

    @Test
    fun `busy-idle bracket with no build-start marker emits nothing`() {
        val agg = BuildAggregator()
        val emitted = agg.onEvents(pid, listOf(context(0), BusyMark(1_000), IdleMark(1_500)))
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `bracket closing with no outcome yields completed-no-outcome`() {
        val agg = BuildAggregator()
        val emitted = agg.onEvents(pid, listOf(context(0), BusyMark(1_000), start(1_010), IdleMark(5_000)))
        assertEquals(FinalStatus.COMPLETED_NO_OUTCOME, emitted.single().finalStatus)
    }

    @Test
    fun `daemon disappearing mid-build emits interrupted`() {
        val agg = BuildAggregator()
        agg.onEvents(pid, listOf(context(0), BusyMark(1_000), start(1_010)))
        val b = agg.onDaemonGone(pid)
        assertEquals(FinalStatus.INTERRUPTED, b!!.finalStatus)
    }

    @Test
    fun `daemon disappearing after outcome emits completed build`() {
        val samples = listOf(100L to 20.0, 140L to 50.0)
        val agg = BuildAggregator(sampleProvider = { _, _, _ -> samples })
        agg.onEvents(pid, listOf(context(0), BusyMark(1_000), start(1_010), Outcome(true, 2.0)))

        val b = agg.onDaemonGone(pid)!!

        assertEquals(FinalStatus.SUCCESS, b.finalStatus)
        assertEquals(3_000, b.endTimeMs)
        assertEquals(2.0, b.durationSeconds)
        assertEquals(140L, b.peakMemoryMb)
        assertEquals(50.0, b.peakCpuPercent)
    }

    @Test
    fun `interrupted build retains its bounded in-window log excerpt`() {
        val agg = BuildAggregator()
        agg.onLogLine(pid, "busy", BusyMark(1_000))
        agg.onLogLine(pid, "start", start(1_010))
        val snippetLimit = MonitoringConfig.DEFAULT.logSnippetLimit
        repeat(snippetLimit.lines + 5) { index ->
            agg.onLogLine(pid, "line-$index-${"x".repeat(200)}", null)
        }

        val b = agg.onDaemonGone(pid)!!
        val snippet = b.logSnippet!!
        assertEquals(FinalStatus.INTERRUPTED, b.finalStatus)
        assertTrue(snippet.lines().size <= snippetLimit.lines)
        assertTrue(snippet.length <= snippetLimit.chars)
        assertTrue(snippet.contains("line-${snippetLimit.lines + 4}"))
    }

    @Test
    fun `zero in-window samples produce null peaks (sub-poll)`() {
        val agg = BuildAggregator(sampleProvider = { _, _, _ -> emptyList() })
        val b = agg.onEvents(pid, listOf(context(0), BusyMark(1_000), start(1_010), Outcome(true, 0.3), IdleMark(1_300))).single()
        assertNull(b.peakMemoryMb)
        assertNull(b.avgMemoryMb)
        assertNull(b.peakCpuPercent)
    }

    @Test
    fun `failed outcome maps to failed status`() {
        val agg = BuildAggregator()
        val b = agg.onEvents(pid, listOf(context(0), BusyMark(1_000), start(1_010), Outcome(false, 2.0), IdleMark(3_000))).single()
        assertEquals(FinalStatus.FAILED, b.finalStatus)
    }

    @Test
    fun `build env names carrying a Claude Code fingerprint set the agent`() {
        val agg = BuildAggregator()
        val b = agg.onEvents(
            pid,
            listOf(
                context(0), BusyMark(1_000), start(1_010),
                BuildEnvNames(1_020, listOf("PATH", "CLAUDECODE", "CLAUDE_CODE_SESSION_ID", "AI_AGENT")),
                Outcome(true, 1.0), IdleMark(2_000),
            ),
        ).single()
        assertEquals("Claude Code", b.agent)
        assertEquals("Anthropic", b.agentProvider)
    }

    @Test
    fun `ide builds do not get attributed to inherited Claude agent env names`() {
        val agg = BuildAggregator()
        val b = agg.onEvents(
            pid,
            listOf(
                context(0), BusyMark(1_000), start(1_010),
                BuildEnvNames(
                    1_020,
                    listOf("PATH", "VSCODE_GIT_IPC_HANDLE", "CLAUDECODE", "CLAUDE_CODE_SESSION_ID", "AI_AGENT"),
                ),
                Outcome(true, 1.0), IdleMark(2_000),
            ),
        ).single()

        assertEquals(Source.IDE, b.inferredSource)
        assertNull(b.agent)
        assertNull(b.agentProvider)
    }

    @Test
    fun `a second busy mark with no intervening idle flushes the first build`() {
        val agg = BuildAggregator()
        // First build qualifies but its IdleMark is missing; a new BusyMark arrives.
        val emitted = agg.onEvents(
            pid,
            listOf(
                context(0),
                BusyMark(1_000), start(1_010), Outcome(true, 1.0),
                BusyMark(3_000), start(3_010), Outcome(true, 1.0), IdleMark(4_000),
            ),
        )
        assertEquals(2, emitted.size, "first build must be flushed, not dropped")
    }

    @Test
    fun `one daemon serving two projects keeps a single identity`() {
        val agg = BuildAggregator()
        val emitted = agg.onEvents(
            pid,
            listOf(
                context(0),
                BusyMark(1_000), start(1_010, "/proj/a"), Outcome(true, 1.0), IdleMark(2_000),
                BusyMark(3_000), start(3_010, "/proj/b"), Outcome(true, 1.0), IdleMark(4_000),
            ),
        )
        assertEquals(listOf("uid-abc", "uid-abc"), emitted.map { it.daemonIdentity })
        assertEquals(listOf("/proj/a", "/proj/b"), emitted.map { it.projectPath })
    }
}
