package io.github.cdsap.daemonitor.distribution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MacAppStoreSandboxAssessmentTest {
    @Test
    fun `assessment covers every issue validation feature`() {
        val ids = MacAppStoreSandboxAssessment.features.map { it.id }.toSet()
        assertEquals(
            setOf(
                "detect-gradle-jvm",
                "cpu-rss",
                "command-line",
                "working-directory",
                "gradle-daemon-home",
                "security-scoped-bookmarks",
                "mcp-localhost",
                "headless-mode",
                "github-updater",
            ),
            ids,
        )
    }

    @Test
    fun `command line and cwd are marked likely incompatible`() {
        val byId = MacAppStoreSandboxAssessment.features.associateBy { it.id }
        assertEquals(
            MacAppStoreSandboxAssessment.Status.LikelyIncompatible,
            byId.getValue("command-line").status,
        )
        assertEquals(
            MacAppStoreSandboxAssessment.Status.LikelyIncompatible,
            byId.getValue("working-directory").status,
        )
        assertEquals(
            MacAppStoreSandboxAssessment.Status.IncompatibleWithStoreRules,
            byId.getValue("github-updater").status,
        )
    }

    @Test
    fun `minimum entitlement set includes sandbox jvm network and bookmark keys`() {
        val keys = MacAppStoreSandboxAssessment.minimumEntitlementKeys
        assertTrue(keys.contains("com.apple.security.app-sandbox"))
        assertTrue(keys.contains("com.apple.security.network.server"))
        assertTrue(keys.contains("com.apple.security.files.bookmarks.app-scope"))
        assertEquals(7, keys.size)
    }
}
