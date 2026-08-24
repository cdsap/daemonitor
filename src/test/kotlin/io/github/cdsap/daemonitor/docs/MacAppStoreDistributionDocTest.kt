package io.github.cdsap.daemonitor.docs

import io.github.cdsap.daemonitor.distribution.MacAppStoreSandboxAssessment
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class MacAppStoreDistributionDocTest {
    private val documentPath = Path.of("docs/mac-app-store-distribution.md")
    private val document = Files.readString(documentPath)
    private val entitlements = Files.readString(Path.of("packaging/macos/app-store.entitlements"))
    private val runtimeEntitlements = Files.readString(Path.of("packaging/macos/app-store-runtime.entitlements"))

    @Test
    fun `mac app store investigation covers the issue validation contract`() {
        assertTrue(Files.isRegularFile(documentPath), "$documentPath should be checked in")

        listOf(
            "## Recommendation",
            "## Distribution channels",
            "## Sandbox validation matrix",
            "## Minimum App Sandbox entitlements",
            "## Incompatible or high-risk behavior",
            "## Decision",
            "`DIRECT`",
            "`APP_STORE`",
            "security-scoped bookmark",
            "127.0.0.1",
            "Likely incompatible",
        ).forEach { requiredText ->
            assertTrue(document.contains(requiredText), "$documentPath should include: $requiredText")
        }
    }

    @Test
    fun `entitlement files include the documented minimum keys`() {
        MacAppStoreSandboxAssessment.minimumEntitlementKeys.forEach { key ->
            assertTrue(entitlements.contains(key), "app-store.entitlements should include $key")
        }
        listOf(
            "com.apple.security.app-sandbox",
            "com.apple.security.cs.allow-jit",
            "com.apple.security.cs.allow-unsigned-executable-memory",
            "com.apple.security.cs.disable-library-validation",
        ).forEach { key ->
            assertTrue(runtimeEntitlements.contains(key), "runtime entitlements should include $key")
        }
    }

    @Test
    fun `decision keeps direct releases primary until sandbox proof`() {
        listOf(
            "Do **not** ship Daemonitor on the Mac App Store until",
            "process introspection is proven under sandbox",
            "GitHub Releases",
        ).forEach { requiredText ->
            assertTrue(document.contains(requiredText), "$documentPath should mention: $requiredText")
        }
    }
}
