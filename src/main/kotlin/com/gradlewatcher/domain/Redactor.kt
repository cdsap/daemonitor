package com.gradlewatcher.domain

/**
 * Best-effort secret redaction applied before any command line or log line is persisted or
 * displayed (KTD-7). Catches `-Pkey=value`, `-Dkey=value`, and `--key=value` forms whose key
 * contains a denylisted token, plus credentialed URLs (`scheme://user:pass@host`).
 *
 * Known residual: positional/bare-argument secrets (a value with no recognizable key) are not
 * caught — documented in the plan's Risks section.
 */
object Redactor {
    const val MASK = "***"

    private val DENY_TOKENS = listOf("password", "secret", "token", "credential", "apikey", "key")

    // -Pkey=value | -Dkey=value | --key=value
    private val PROP_FLAG = Regex("""^(--|-[PD])([^=\s]+)=(.*)$""")

    // scheme://user:pass@host  (credentials before the @)
    private val URL_CREDS = Regex("""([a-zA-Z][a-zA-Z0-9+.\-]*://)([^/@\s:]+):([^/@\s]+)@""")

    /** Redact a single argument/token. */
    fun redactToken(token: String): String {
        val prop = PROP_FLAG.matchEntire(token)
        if (prop != null) {
            val (prefix, key, _) = prop.destructured
            if (isSensitiveKey(key)) return "$prefix$key=$MASK"
        }
        return maskUrlCredentials(token)
    }

    /** Redact a whole command line (space-delimited tokens). */
    fun redactCommandLine(commandLine: String): String =
        commandLine.split(" ").joinToString(" ") { if (it.isEmpty()) it else redactToken(it) }

    /** Redact a daemon-log line: mask any credentialed URL and any inline `key=value` secret. */
    fun redactLogLine(line: String): String {
        val urlMasked = maskUrlCredentials(line)
        return urlMasked.split(" ").joinToString(" ") { word ->
            val prop = PROP_FLAG.matchEntire(word)
            if (prop != null) {
                val (prefix, key, _) = prop.destructured
                if (isSensitiveKey(key)) return@joinToString "$prefix$key=$MASK"
            }
            word
        }
    }

    private fun isSensitiveKey(key: String): Boolean {
        val lower = key.lowercase()
        return DENY_TOKENS.any { lower.contains(it) }
    }

    private fun maskUrlCredentials(text: String): String =
        URL_CREDS.replace(text) { m -> "${m.groupValues[1]}$MASK:$MASK@" }
}
