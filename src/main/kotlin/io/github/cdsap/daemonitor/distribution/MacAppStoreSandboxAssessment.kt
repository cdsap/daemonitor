package io.github.cdsap.daemonitor.distribution

/**
 * Code-backed copy of the Mac App Store sandbox investigation matrix
 * (`docs/mac-app-store-distribution.md`). Kept in sync by
 * [io.github.cdsap.daemonitor.docs.MacAppStoreDistributionDocTest].
 */
object MacAppStoreSandboxAssessment {
    enum class Status {
        Compatible,
        RequiresEntitlement,
        RequiresUserGrant,
        LikelyIncompatible,
        RuntimeProofRequired,
        IncompatibleWithStoreRules,
    }

    data class Feature(
        val id: String,
        val title: String,
        val status: Status,
        val notes: String,
    )

    val features: List<Feature> = listOf(
        Feature(
            id = "detect-gradle-jvm",
            title = "Detect running Gradle/JVM processes",
            status = Status.RuntimeProofRequired,
            notes = "OSHI same-UID enumeration; argv denial under App Sandbox is the primary risk.",
        ),
        Feature(
            id = "cpu-rss",
            title = "Read CPU and RSS information",
            status = Status.RuntimeProofRequired,
            notes = "Often available for same-UID processes; confirm under App Store sandbox.",
        ),
        Feature(
            id = "command-line",
            title = "Read process command line/JVM arguments",
            status = Status.LikelyIncompatible,
            notes = "No public entitlement restores KERN_PROCARGS2-style access in App Sandbox.",
        ),
        Feature(
            id = "working-directory",
            title = "Resolve process working directories",
            status = Status.LikelyIncompatible,
            notes = "Same restriction family as command-line introspection.",
        ),
        Feature(
            id = "gradle-daemon-home",
            title = "Read and monitor ~/.gradle/daemon/",
            status = Status.RequiresUserGrant,
            notes = "Outside the container; needs Open panel + security-scoped bookmark or a risky temporary exception.",
        ),
        Feature(
            id = "security-scoped-bookmarks",
            title = "User-selected ~/.gradle access via security-scoped bookmarks",
            status = Status.RequiresUserGrant,
            notes = "Requires user-selected read-only + app-scope bookmark entitlements.",
        ),
        Feature(
            id = "mcp-localhost",
            title = "Local MCP server bind to 127.0.0.1",
            status = Status.RequiresEntitlement,
            notes = "Needs com.apple.security.network.server.",
        ),
        Feature(
            id = "headless-mode",
            title = "Desktop → headless/background mode",
            status = Status.RuntimeProofRequired,
            notes = "Same-bundle relaunch should remain sandboxed; needs smoke test.",
        ),
        Feature(
            id = "github-updater",
            title = "GitHub Releases in-app updater",
            status = Status.IncompatibleWithStoreRules,
            notes = "Disabled for APP_STORE; Apple manages updates.",
        ),
    )

    val minimumEntitlementKeys: Set<String> = setOf(
        "com.apple.security.app-sandbox",
        "com.apple.security.cs.allow-jit",
        "com.apple.security.cs.allow-unsigned-executable-memory",
        "com.apple.security.cs.disable-library-validation",
        "com.apple.security.network.server",
        "com.apple.security.files.user-selected.read-only",
        "com.apple.security.files.bookmarks.app-scope",
    )
}
