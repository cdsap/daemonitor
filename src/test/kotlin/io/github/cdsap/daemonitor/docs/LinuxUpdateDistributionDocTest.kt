package io.github.cdsap.daemonitor.docs

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class LinuxUpdateDistributionDocTest {
    @Test
    fun `linux update distribution decision covers release deb apt prompts metadata hosting and signing`() {
        val doc = Files.readString(Path.of("docs/linux-update-distribution.md"))

        listOf(
            "direct `.deb` asset on GitHub Releases",
            "signed apt repository",
            "must not try to self-install updates on Linux",
            "If the current install came from the apt repository",
            "If the current install came from a downloaded `.deb`",
            "Package name: `daemonitor`",
            "A signed `Release` or `InRelease` file",
            "private signing key managed only in release infrastructure",
            "not required before shipping the Phase 1 updater prompt",
        ).forEach { requiredText ->
            assertTrue(
                doc.contains(requiredText),
                "Linux update distribution doc should include: $requiredText",
            )
        }
    }
}
