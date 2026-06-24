package com.gradlewatcher.domain.model

/**
 * Inferred origin of a build (U6 / KTD-8). Binary outcome — resolved (`TERMINAL`/`IDE`) or
 * `UNKNOWN`; there is no intermediate confidence tier in v1. Named-agent attribution
 * (Claude Code, Codex) is deferred but cheap to add from the same env-name signal.
 */
enum class Source {
    TERMINAL,
    IDE,
    UNKNOWN,
}
