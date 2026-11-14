package io.github.cdsap.daemonitor.coreipc

import java.nio.file.Path

/**
 * Decide whether [localDatabase] is the same file daemonitor-cored is writing.
 * Prefers [reportedDatabase]; falls back to matching the app default DB path.
 */
internal fun coreOwnsDatabase(
    localDatabase: Path,
    defaultDatabase: Path,
    reportedDatabase: Path?,
): Boolean {
    val local = localDatabase.toAbsolutePath().normalize()
    val remote = reportedDatabase?.toAbsolutePath()?.normalize()
    if (remote != null) return remote == local
    return local == defaultDatabase.toAbsolutePath().normalize()
}
