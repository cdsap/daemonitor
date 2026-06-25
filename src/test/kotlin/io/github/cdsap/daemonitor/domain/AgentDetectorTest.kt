package io.github.cdsap.daemonitor.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentDetectorTest {

    @Test
    fun `claude code env names map to Claude Code Anthropic`() {
        // Exactly the names observed in real daemon logs on this machine.
        val env = listOf("PATH", "CLAUDECODE", "CLAUDE_CODE_SESSION_ID", "AI_AGENT", "TERM_PROGRAM")
        val a = AgentDetector.detect(env)!!
        assertEquals("Claude Code", a.agent)
        assertEquals("Anthropic", a.provider)
    }

    @Test
    fun `cursor is provider-configurable`() {
        val a = AgentDetector.detect(listOf("PATH", "CURSOR_TRACE_ID", "VSCODE_PID"))!!
        assertEquals("Cursor", a.agent)
        assertEquals("configurable", a.provider)
    }

    @Test
    fun `single-provider agents report their provider`() {
        assertEquals("OpenAI", AgentDetector.detect(listOf("CODEX_SANDBOX"))!!.provider)
        assertEquals("Google", AgentDetector.detect(listOf("GEMINI_API_KEY_PRESENT"))!!.provider)
    }

    @Test
    fun `generic AI marker with no known fingerprint is unrecognized`() {
        val a = AgentDetector.detect(listOf("PATH", "AI_AGENT"))!!
        assertEquals("AI agent (unrecognized)", a.agent)
        assertEquals("unknown", a.provider)
    }

    @Test
    fun `no agent signal returns null`() {
        assertNull(AgentDetector.detect(listOf("PATH", "HOME", "TERM_PROGRAM")))
        assertNull(AgentDetector.detect(emptyList()))
    }

    @Test
    fun `agent vars also present in the ambient watcher env are suppressed as inherited`() {
        // The watcher itself runs under Claude Code, so the build merely inherits its vars —
        // not evidence Claude drove this build.
        val env = listOf("PATH", "CLAUDECODE", "CLAUDE_CODE_SESSION_ID", "AI_AGENT")
        val ambient = setOf("PATH", "CLAUDECODE", "CLAUDE_CODE_SESSION_ID", "AI_AGENT")
        assertNull(AgentDetector.detect(env, ambient))
    }

    @Test
    fun `a distinct agent not in the ambient env is still attributed`() {
        // Watcher runs under Claude; the build itself was launched by Cursor — that's a real signal.
        val ambient = setOf("PATH", "CLAUDECODE", "CLAUDE_CODE_SESSION_ID")
        val env = listOf("PATH", "CLAUDECODE", "CURSOR_TRACE_ID")
        val a = AgentDetector.detect(env, ambient)!!
        assertEquals("Cursor", a.agent)
    }

    @Test
    fun `ambient subtraction is case-insensitive`() {
        val a = AgentDetector.detect(listOf("CLAUDECODE"), setOf("claudecode"))
        assertNull(a)
    }
}
