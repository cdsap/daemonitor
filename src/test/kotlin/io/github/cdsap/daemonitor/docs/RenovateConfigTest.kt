package io.github.cdsap.daemonitor.docs

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RenovateConfigTest {
    private val configPath = Path.of(".github/renovate.json")
    private val config = Files.readString(configPath)

    @Test
    fun `renovate config is checked in under github`() {
        assertTrue(Files.isRegularFile(configPath), "$configPath should be checked in")
        assertTrue(config.isNotBlank(), "$configPath should not be empty")
    }

    @Test
    fun `renovate config mirrors ProjectGenerator maven automerge baseline`() {
        listOf(
            "\"\$schema\": \"https://docs.renovatebot.com/renovate-schema.json\"",
            "\"extends\": [\"config:base\"]",
            "\"matchDatasources\": [\"maven\"]",
            "https://dl.google.com/dl/android/maven2/",
            "https://plugins.gradle.org/m2/",
            "https://repo1.maven.org/maven2/",
            "\"automerge\": true",
            "\"automergeType\": \"pr\"",
            "\"automergeStrategy\": \"merge-commit\"",
        ).forEach { requiredText ->
            assertTrue(config.contains(requiredText), "$configPath should include: $requiredText")
        }

        // No Versions.kt (or equivalent) in this repo — keep regexManagers out of scope.
        assertFalse(
            config.contains("regexManagers"),
            "$configPath should omit regexManagers unless versions are pinned in Versions.kt",
        )
    }
}
