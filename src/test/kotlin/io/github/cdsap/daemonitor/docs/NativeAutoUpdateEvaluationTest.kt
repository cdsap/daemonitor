package io.github.cdsap.daemonitor.docs

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class NativeAutoUpdateEvaluationTest {
    private val documentPath = Path.of("docs/release/native-auto-update-evaluation.md")
    private val document = Files.readString(documentPath)

    @Test
    fun `native updater evaluation covers the issue validation contract`() {
        assertTrue(Files.isRegularFile(documentPath), "$documentPath should be checked in")

        listOf(
            "## Recommendation",
            "## Sparkle 2 Assessment",
            "## WinSparkle Assessment",
            "## Shared Appcast And Key Management",
            "## Release Workflow Changes",
            "## Decision",
        ).forEach { heading ->
            assertTrue(document.contains(heading), "$documentPath should include $heading")
        }
    }

    @Test
    fun `native updater recommendation preserves the signed manual update sequence`() {
        listOf(
            "Do not adopt Sparkle 2 or WinSparkle until the Phase 1 manual-approved installer update path",
            "Developer ID signed and notarized",
            "Authenticode signed",
            "sparkle:edSignature",
            "Store private keys in CI secrets or a signing keychain",
            "Avoid command-line private-key flags",
            "staging updater smoke tests",
        ).forEach { requiredText ->
            assertTrue(document.contains(requiredText), "$documentPath should mention: $requiredText")
        }
    }
}
