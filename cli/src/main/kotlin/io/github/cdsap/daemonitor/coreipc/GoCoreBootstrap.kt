package io.github.cdsap.daemonitor.coreipc

import io.github.cdsap.daemonitor.config.RetentionPolicy
import io.github.cdsap.daemonitor.platform.AppDirectories
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

/**
 * How the client chooses between Go core IPC and the in-process JVM collector.
 *
 * Default is [PreferGo]: attach to the app-dir socket (starting bundled `daemonitor-cored`
 * when needed), and fall back to the JVM collector if the core cannot be reached.
 */
sealed class GoCorePreference {
    data object PreferGo : GoCorePreference()
    data object JvmCollector : GoCorePreference()
    data class ExplicitSocket(val path: Path) : GoCorePreference()
}

data class GoCoreResolveResult(
    val wiring: GoCoreWiring,
    /** True when this process started `daemonitor-cored` (left running for later clients). */
    val startedCore: Boolean = false,
)

/**
 * Resolve Go-core wiring for launch.
 *
 * - [GoCorePreference.JvmCollector] → empty wiring (JVM collector).
 * - [GoCorePreference.ExplicitSocket] → require that socket (start cored if binary found; else fail soft
 *   only when health never comes up — callers that need hard-fail can check [GoCoreWiring.processSource]).
 * - [GoCorePreference.PreferGo] → default app socket; start cored if needed; JVM fallback on failure.
 *
 * [retentionDays] is clamped with [RetentionPolicy.DEFAULT] and passed as `-retention` when starting
 * (or restarting) cored. When a healthy cored reports a different `retention_ms` (or omits it),
 * PreferGo/ExplicitSocket restarts that process so the user's Settings window is preserved.
 */
fun resolveGoCore(
    preference: GoCorePreference,
    databasePath: Path,
    retentionDays: Long = RetentionPolicy.DEFAULT.defaultDays,
    defaultDatabase: Path = AppDirectories.system.databasePath,
    defaultSocket: Path = AppDirectories.system.coreSocketPath,
    error: PrintStream = System.err,
    fetch: (Path, String) -> String = ::unixHttpGet,
    findBinary: () -> Path? = ::findDaemonitorCoredBinary,
    startCore: (binary: Path, socket: Path, db: Path, retentionDays: Long) -> Boolean = ::startDaemonitorCored,
    stopCore: (socket: Path, pid: Long?) -> Boolean = ::stopDaemonitorCored,
    waitHealthyMs: Long = 5_000L,
): GoCoreResolveResult {
    when (preference) {
        GoCorePreference.JvmCollector -> return GoCoreResolveResult(GoCoreWiring())
        is GoCorePreference.ExplicitSocket,
        GoCorePreference.PreferGo,
        -> Unit
    }

    val socket = when (preference) {
        is GoCorePreference.ExplicitSocket -> preference.path
        else -> defaultSocket
    }
    val requireCore = preference is GoCorePreference.ExplicitSocket
    val days = RetentionPolicy.DEFAULT.clamp(retentionDays)
    val expectedRetentionMs = days * RetentionPolicy.MILLIS_PER_DAY

    if (isGoCoreHealthy(socket, fetch)) {
        val healthBody = runCatching { fetch(socket, "/v1/health") }.getOrNull()
        val health = healthBody?.let(GoCoreSnapshotParser::parseHealth)
        val actualRetentionMs = health?.retentionMs
        if (actualRetentionMs == expectedRetentionMs) {
            return GoCoreResolveResult(wireGoCore(socket, databasePath, defaultDatabase, fetch))
        }
        error.println(
            "daemonitor-cored retention mismatch" +
                " (running=${actualRetentionMs?.let { "${it}ms" } ?: "unknown"}," +
                " settings=${days}d); restarting…",
        )
        stopCore(socket, health?.pid)
        waitForGoCoreStopped(socket, fetch, waitHealthyMs)
    }

    val binary = findBinary()
    var started = false
    if (binary != null) {
        Files.createDirectories(socket.parent)
        error.println("Starting daemonitor-cored ($binary)…")
        started = startCore(binary, socket, databasePath, days)
        if (started && waitForGoCoreHealthy(socket, fetch, waitHealthyMs)) {
            return GoCoreResolveResult(wireGoCore(socket, databasePath, defaultDatabase, fetch), startedCore = true)
        }
    }

    if (requireCore) {
        error.println(
            "Go core unavailable at $socket" +
                if (binary == null) " (daemonitor-cored binary not found)" else " (started but health check failed)",
        )
        // Still wire so poll surfaces GoCoreUnavailableException with a clear message.
        return GoCoreResolveResult(wireGoCore(socket, databasePath, defaultDatabase, fetch), startedCore = started)
    }

    error.println(
        "daemonitor-cored unavailable" +
            (if (binary == null) " (binary not found)" else "") +
            "; using in-process JVM collector",
    )
    return GoCoreResolveResult(GoCoreWiring())
}

internal fun isGoCoreHealthy(
    socket: Path,
    fetch: (Path, String) -> String = ::unixHttpGet,
): Boolean {
    if (!socket.exists()) return false
    return runCatching {
        val body = fetch(socket, "/v1/health")
        body.contains("\"status\"") && (body.contains("ok") || body.contains("OK"))
    }.getOrDefault(false)
}

internal fun waitForGoCoreHealthy(
    socket: Path,
    fetch: (Path, String) -> String,
    timeoutMs: Long,
): Boolean {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (System.nanoTime() < deadline) {
        if (isGoCoreHealthy(socket, fetch)) return true
        Thread.sleep(50)
    }
    return isGoCoreHealthy(socket, fetch)
}

internal fun waitForGoCoreStopped(
    socket: Path,
    fetch: (Path, String) -> String,
    timeoutMs: Long,
): Boolean {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (System.nanoTime() < deadline) {
        if (!isGoCoreHealthy(socket, fetch)) return true
        Thread.sleep(50)
    }
    return !isGoCoreHealthy(socket, fetch)
}

/** Host-arch binary name for the current OS. */
fun daemonitorCoredBinaryName(): String =
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        "daemonitor-cored.exe"
    } else {
        "daemonitor-cored"
    }

/**
 * Locate a packaged or PATH-installed `daemonitor-cored`.
 *
 * Search order: Compose app resources → install `bin/` beside the running JAR → `PATH`.
 */
fun findDaemonitorCoredBinary(): Path? {
    val name = daemonitorCoredBinaryName()
    val candidates = ArrayList<Path>()

    System.getProperty("compose.application.resources.dir")
        ?.takeIf { it.isNotBlank() }
        ?.let { candidates.add(Path(it).resolve(name)) }

    runCatching {
        val codeSource = GoCorePreference::class.java.protectionDomain?.codeSource?.location ?: return@runCatching
        val jarOrDir = java.nio.file.Path.of(codeSource.toURI())
        // …/lib/foo.jar → …/bin/daemonitor-cored
        val libDir = jarOrDir.parent
        if (libDir != null && libDir.fileName?.toString() == "lib") {
            candidates.add(libDir.parent.resolve("bin").resolve(name))
        }
        // also try sibling of jar
        if (libDir != null) {
            candidates.add(libDir.resolve(name))
        }
    }

    candidates.addAll(pathLookup(name))

    return candidates.firstOrNull { it.isRegularFile() && (it.isExecutable() || name.endsWith(".exe")) }
}

private fun pathLookup(name: String): List<Path> {
    val pathEnv = System.getenv("PATH") ?: return emptyList()
    val sep = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) ";" else ":"
    return pathEnv.split(sep)
        .filter { it.isNotBlank() }
        .map { Path(it).resolve(name) }
}

internal fun startDaemonitorCored(
    binary: Path,
    socket: Path,
    database: Path,
    retentionDays: Long,
): Boolean {
    val days = RetentionPolicy.DEFAULT.clamp(retentionDays)
    return runCatching {
        val builder = ProcessBuilder(
            binary.toAbsolutePath().toString(),
            "-socket", socket.toAbsolutePath().toString(),
            "-db", database.toAbsolutePath().toString(),
            "-retention", "${days * 24}h",
        )
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        builder.redirectError(ProcessBuilder.Redirect.DISCARD)
        builder.start()
        true
    }.getOrDefault(false)
}

/**
 * Stop a running `daemonitor-cored` for [socket].
 *
 * Prefers [pid] from `/v1/health` when present; otherwise matches process command lines that
 * include both `daemonitor-cored` and the socket path.
 */
internal fun stopDaemonitorCored(socket: Path, pid: Long?): Boolean {
    var stopped = false
    if (pid != null && pid > 0) {
        ProcessHandle.of(pid).ifPresent { handle ->
            handle.destroy()
            stopped = true
        }
    }
    val socketAbs = socket.toAbsolutePath().toString()
    ProcessHandle.allProcesses().use { processes ->
        processes.forEach { handle ->
            val cmd = handle.info().commandLine().orElseGet {
                handle.info().command().orElse("")
            }
            if (cmd.contains("daemonitor-cored") && cmd.contains(socketAbs)) {
                handle.destroy()
                stopped = true
            }
        }
    }
    return stopped
}
