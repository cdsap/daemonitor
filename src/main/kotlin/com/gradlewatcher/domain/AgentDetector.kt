package com.gradlewatcher.domain

import com.gradlewatcher.domain.model.AgentAttribution

/**
 * Fingerprints the AI coding agent that ran a build from its environment-variable *names*
 * (KTD-8). Names only — never values — so it stays within the redaction posture (KTD-7).
 *
 * Confidence note: the Claude Code signature is empirically confirmed from real daemon logs on
 * this machine (CLAUDECODE / CLAUDE_CODE_* / CLAUDE_EFFORT / AI_AGENT). The other entries are
 * best-effort patterns for known agents and should be corroborated against real env-name lists as
 * they are observed; an AI marker with no specific match resolves to an explicit "unrecognized".
 */
object AgentDetector {

    private data class Fingerprint(
        val agent: String,
        val provider: String,
        val matches: (Set<String>) -> Boolean,
    )

    // Ordered specific → generic; first match wins.
    private val FINGERPRINTS = listOf(
        Fingerprint("Claude Code", "Anthropic") { n ->
            n.any { it == "CLAUDECODE" || it.startsWith("CLAUDE_CODE") || it == "CLAUDE_EFFORT" }
        },
        Fingerprint("Cursor", "configurable") { n -> n.any { it.startsWith("CURSOR") } },
        Fingerprint("Codex", "OpenAI") { n -> n.any { it.startsWith("CODEX") } },
        Fingerprint("Gemini CLI", "Google") { n -> n.any { it.startsWith("GEMINI") } },
        Fingerprint("Aider", "configurable") { n -> n.any { it.startsWith("AIDER") } },
    )

    /** @return the detected agent, or null when no AI-agent signal is present in [envNames]. */
    fun detect(envNames: List<String>): AgentAttribution? {
        if (envNames.isEmpty()) return null
        val names = envNames.map { it.uppercase() }.toSet()

        FINGERPRINTS.firstOrNull { it.matches(names) }?.let {
            return AgentAttribution(it.agent, it.provider)
        }

        // A generic automation/agent marker with no specific fingerprint — surface it as such
        // rather than guessing, so unknown agents are visible and can be catalogued.
        if (names.any { it == "AI_AGENT" || it.startsWith("AI_") }) {
            return AgentAttribution("AI agent (unrecognized)", "unknown")
        }
        return null
    }
}
