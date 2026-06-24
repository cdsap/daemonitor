package com.gradlewatcher.domain

import com.gradlewatcher.domain.model.Source

/**
 * Resolves a build's [Source] (U6 / KTD-8). Primary signal is the per-build environment-variable
 * *name* set captured from the daemon log (name-only, KTD-7-safe); live process ancestry is a
 * secondary fallback. Binary outcome: a positive marker yields TERMINAL or IDE, otherwise UNKNOWN —
 * never a guess. IDE markers win over terminal markers, because an IDE's embedded terminal also
 * sets TERM_PROGRAM.
 */
object SourceDetector {

    // Substrings that appear in env-var NAMES injected by IDEs (case-insensitive).
    private val IDE_ENV_MARKERS = listOf(
        "VSCODE", "INTELLIJ", "JETBRAINS", "TERMINAL_EMULATOR", "CURSOR", "PYCHARM", "ANDROID_STUDIO",
    )
    // Names that indicate a terminal session.
    private val TERMINAL_ENV_MARKERS = listOf("TERM_PROGRAM", "TERM_SESSION_ID", "ITERM_SESSION_ID")

    private val IDE_PROC = listOf("idea", "studio", "pycharm", "webstorm", "goland", "cursor", "code")
    private val TERMINAL_PROC = listOf("zsh", "bash", "fish", "tmux", "login", "terminal", "iterm")

    /**
     * @param envNames env-var names captured for the build (from the daemon log), may be empty.
     * @param ancestry process names of the connecting launcher's ancestry (fallback), may be empty.
     */
    fun detect(envNames: List<String>, ancestry: List<String> = emptyList()): Source {
        val names = envNames.map { it.uppercase() }
        if (names.any { n -> IDE_ENV_MARKERS.any { n.contains(it) } }) return Source.IDE
        if (names.any { n -> TERMINAL_ENV_MARKERS.any { n.contains(it) } }) return Source.TERMINAL

        val procs = ancestry.map { it.lowercase() }
        if (procs.any { p -> IDE_PROC.any { p.contains(it) } }) return Source.IDE
        if (procs.any { p -> TERMINAL_PROC.any { p.contains(it) } }) return Source.TERMINAL

        return Source.UNKNOWN
    }
}
