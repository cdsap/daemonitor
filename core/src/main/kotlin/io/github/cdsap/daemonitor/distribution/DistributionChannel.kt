package io.github.cdsap.daemonitor.distribution

/**
 * Build-time distribution channel embedded in `daemonitor-build.properties`.
 *
 * - [DIRECT]: GitHub Releases DMG/zip with the in-app updater.
 * - [APP_STORE]: sandboxed Mac App Store / TestFlight build; Apple manages updates.
 */
enum class DistributionChannel {
    DIRECT,
    APP_STORE,
    ;

    val usesGitHubUpdater: Boolean get() = this == DIRECT

    companion object {
        fun parse(raw: String?): DistributionChannel = when (raw?.trim()?.uppercase()) {
            "APP_STORE", "APPSTORE", "MAC_APP_STORE" -> APP_STORE
            "DIRECT", null, "" -> DIRECT
            else -> DIRECT
        }
    }
}
