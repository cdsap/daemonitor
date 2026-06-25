package com.gradlewatcher.domain.model

/**
 * Named AI coding agent inferred for a build (U6 extension / the deferred "named-agent detection").
 * Derived from the daemon log's per-build environment-variable *names* (KTD-8, name-only → KTD-7
 * safe). [provider] is the LLM provider when the agent is single-provider; otherwise the literal
 * `"configurable"` (the tool talks to multiple providers) or `"unknown"`.
 */
data class AgentAttribution(
    val agent: String,
    val provider: String,
)
