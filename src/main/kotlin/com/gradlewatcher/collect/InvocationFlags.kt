package com.gradlewatcher.collect

/**
 * Detects automation markers on a Gradle invocation's command line (U2 enhancement).
 *
 * `--non-interactive` (Gradle 9.6.0+) is the canonical "no human at the keyboard" flag — the
 * release notes name CI pipelines, scripts, and AI agents as its use cases, so its presence is a
 * strong automation signal for the product's "did an agent trigger this build?" question.
 * `--console=plain` is a weaker, older correlate of the same intent.
 *
 * Note: this reads the launcher/wrapper command line (captured live by the collector), not the
 * daemon log — the daemon `.out.log` does not record build args. Per-build attribution of this
 * signal is deferred (it needs launcher↔build correlation, same as named-agent detection).
 */
object InvocationFlags {

    private val NON_INTERACTIVE = Regex("""(^|\s)--non-interactive(\s|$)""")
    private val CONSOLE_PLAIN = Regex("""(^|\s)--console[=\s]plain(\s|$)""")

    fun isNonInteractive(commandLine: String): Boolean =
        NON_INTERACTIVE.containsMatchIn(commandLine) || CONSOLE_PLAIN.containsMatchIn(commandLine)
}
