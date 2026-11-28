package io.github.cdsap.daemonitor.domain

/**
 * Best-effort secret redaction applied before any command line or log line is persisted or
 * displayed (KTD-7). Catches `-Pkey=value`, `-Dkey=value`, and `--key=value` forms whose key
 * contains a denylisted token, including nested Bazel/JVM wrapper flags and bare environment
 * assignments. Also catches sensitive options followed by a value, credentialed URLs
 * (`scheme://user:pass@host`), and Bearer/Basic credentials in logs.
 *
 * Known residual: positional/bare-argument secrets (a value with no recognizable key) are not
 * caught — documented in the plan's Risks section.
 */
object Redactor {
    const val MASK = "***"

    // Multi-letter tokens match anywhere in a key; bare "key" must be a whole word so it does not
    // over-redact benign keys like "monkey.count" or "keystore.path".
    private val DENY_MULTI = listOf("password", "secret", "token", "credential", "apikey", "authenticationkey")
    private val KEY_WORD = Regex("""(^|[^a-z])key([^a-z]|$)""")

    // -Pkey=value | -Dkey=value | --key=value
    private val PROP_FLAG = Regex("""^(--|-[PD])([^=\s]+)=(.*)$""")
    private val ENV_ASSIGNMENT = Regex("""^([A-Za-z_][A-Za-z0-9_]*)=(.*)$""")
    private val WRAPPER_KEYS = setOf("action_env", "repo_env", "test_env", "client_env", "jvmopt", "host_jvmopt")
    private val AUTH_CREDENTIALS = Regex("""(?i)\b(Bearer|Basic)([\t ]+)\S+""")

    // scheme://user:pass@host  (credentials before the @)
    private val URL_CREDS = Regex("""([a-zA-Z][a-zA-Z0-9+.\-]*://)([^/@\s:]+):([^/@\s]+)@""")
    private val WHITESPACE = Regex("""\s+""")
    private val WHITESPACE_BOUNDARY = Regex("""(?<=\s)|(?=\s)""")

    /** Redact a single argument/token. */
    fun redactToken(token: String): String {
        // Peel wrapper flags iteratively so arbitrarily nested flags do not grow the stack.
        val wrappers = StringBuilder()
        var remaining = token
        while (true) {
            val prop = PROP_FLAG.matchEntire(remaining)
            val env = if (prop == null) ENV_ASSIGNMENT.matchEntire(remaining) else null
            val prefix = if (prop != null) prop.groupValues[1] else ""
            val key = prop?.groupValues?.get(2) ?: env?.groupValues?.get(1)
                ?: return wrappers.toString() + maskUrlCredentials(remaining)
            if (isSensitiveKey(key)) return "$wrappers$prefix$key=$MASK"
            if (prop == null || prefix != "--" || key !in WRAPPER_KEYS) {
                return wrappers.toString() + maskUrlCredentials(remaining)
            }
            wrappers.append(prefix).append(key).append('=')
            remaining = prop.groupValues[3]
        }
    }

    /** Redact a whole command line (whitespace-delimited tokens; tabs and runs of spaces too). */
    fun redactCommandLine(commandLine: String): String {
        val trimmed = commandLine.trim()
        if (trimmed.isEmpty()) return commandLine
        var maskNext = false
        return trimmed.split(WHITESPACE).joinToString(" ") { token ->
            if (maskNext) {
                maskNext = false
                MASK
            } else {
                maskNext = isSensitiveOption(token)
                redactToken(token)
            }
        }
    }

    /** Redact a daemon-log line: mask any credentialed URL and any inline `key=value` secret. */
    fun redactLogLine(line: String): String {
        val masked = AUTH_CREDENTIALS.replace(maskUrlCredentials(line)) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}$MASK"
        }
        return masked.split(WHITESPACE_BOUNDARY).joinToString("") { word ->
            redactToken(word)
        }
    }

    private fun isSensitiveOption(token: String): Boolean {
        if ('=' in token || !token.startsWith('-')) return false
        var key = token.trimStart('-')
        if (key.startsWith('P') || key.startsWith('D')) key = key.substring(1)
        return isSensitiveKey(key)
    }

    private fun isSensitiveKey(key: String): Boolean {
        val lower = key.lowercase()
        return DENY_MULTI.any { lower.contains(it) } || KEY_WORD.containsMatchIn(lower)
    }

    private fun maskUrlCredentials(text: String): String =
        URL_CREDS.replace(text) { m -> "${m.groupValues[1]}$MASK:$MASK@" }
}
