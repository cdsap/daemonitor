package io.github.cdsap.daemonitor.domain

import io.github.cdsap.daemonitor.domain.model.Source
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceDetectorTest {

    @Test
    fun `term program env marks a terminal`() {
        assertEquals(
            Source.TERMINAL,
            SourceDetector.detect(envNames = listOf("PATH", "TERM_PROGRAM", "HOME")),
        )
    }

    @Test
    fun `vscode env marks an IDE`() {
        assertEquals(
            Source.IDE,
            SourceDetector.detect(envNames = listOf("PATH", "VSCODE_GIT_IPC_HANDLE", "TERM_PROGRAM")),
        )
    }

    @Test
    fun `intellij terminal emulator marks an IDE`() {
        assertEquals(
            Source.IDE,
            SourceDetector.detect(envNames = listOf("PATH", "TERMINAL_EMULATOR")),
        )
    }

    @Test
    fun `falls back to ancestry when env is inconclusive`() {
        assertEquals(
            Source.TERMINAL,
            SourceDetector.detect(envNames = listOf("PATH", "HOME"), ancestry = listOf("gradlew", "zsh", "login")),
        )
    }

    @Test
    fun `unknown when no signal resolves`() {
        assertEquals(
            Source.UNKNOWN,
            SourceDetector.detect(envNames = listOf("PATH", "HOME"), ancestry = listOf("launchd")),
        )
    }
}
