package io.github.cdsap.daemonitor.coreipc

/**
 * Raised when `--core-socket` cannot reach `daemonitor-cored` (missing path, stale sock file,
 * or connect/IO failure). Callers should surface [message] directly to the user.
 */
class GoCoreUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
