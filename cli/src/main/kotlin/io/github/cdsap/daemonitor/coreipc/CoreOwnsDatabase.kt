package io.github.cdsap.daemonitor.coreipc

import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Decide whether [localDatabase] is the same file daemonitor-cored is writing.
 * Prefers `/v1/health` `db_path`; falls back to matching the app default DB path.
 */
internal fun coreOwnsDatabase(
    coreSocket: Path,
    localDatabase: Path,
    defaultDatabase: Path,
    fetch: (Path, String) -> String = ::unixHttpGet,
): Boolean {
    val local = localDatabase.toAbsolutePath().normalize()
    val remote = runCatching {
        GoCoreSnapshotParser.parseHealthDbPath(fetch(coreSocket, "/v1/health"))
            ?.let { Path(it).toAbsolutePath().normalize() }
    }.getOrNull()
    if (remote != null) return remote == local
    return local == defaultDatabase.toAbsolutePath().normalize()
}
