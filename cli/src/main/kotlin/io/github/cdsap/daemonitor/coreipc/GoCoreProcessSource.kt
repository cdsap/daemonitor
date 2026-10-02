package io.github.cdsap.daemonitor.coreipc

import io.github.cdsap.daemonitor.application.ProcessSource
import io.github.cdsap.daemonitor.domain.model.GradleProcess
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

/**
 * [ProcessSource] that reads `/v1/processes` from `daemonitor-cored`
 * over a Unix-domain socket (Go core IPC).
 *
 * Live JVM heap used/committed comes from cored's jcmd/jstat probe for Gradle/Kotlin
 * daemons (see `cored/internal/heap`). Wrappers and workers stay unavailable.
 */
class GoCoreProcessSource(
    private val socketPath: Path,
    private val fetch: (Path, String) -> String = ::unixHttpGet,
) : ProcessSource {
    override fun currentProcesses(): List<GradleProcess> {
        requireGoCoreSocket(socketPath)
        val body = fetch(socketPath, "/v1/processes")
        return GoCoreSnapshotParser.parseProcesses(body)
    }
}

internal fun requireGoCoreSocket(socketPath: Path) {
    if (!socketPath.exists()) {
        throw GoCoreUnavailableException(
            "Go core socket not found: $socketPath (is daemonitor-cored running?)",
        )
    }
}

/** Bound connect/read so a wedged cored cannot freeze the CLI poll loop indefinitely. */
internal const val UNIX_HTTP_TIMEOUT_MS = 5_000

internal fun unixHttpGet(
    socketPath: Path,
    path: String,
    timeoutMs: Int = UNIX_HTTP_TIMEOUT_MS,
): String {
    requireGoCoreSocket(socketPath)
    return try {
        val address = UnixDomainSocketAddress.of(socketPath)
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.configureBlocking(false)
            if (!channel.connect(address)) {
                awaitChannel(channel, SelectionKey.OP_CONNECT, timeoutMs, "connect")
                if (!channel.finishConnect()) {
                    throw SocketTimeoutException("connect timed out after ${timeoutMs}ms")
                }
            }
            val request = "GET $path HTTP/1.0\r\nHost: localhost\r\nAccept: application/json\r\n\r\n"
            writeFully(channel, ByteBuffer.wrap(request.toByteArray(StandardCharsets.US_ASCII)), timeoutMs)
            val text = readUntilEof(channel, timeoutMs)
            val split = text.indexOf("\r\n\r\n")
            require(split >= 0) { "Go core returned a malformed HTTP response" }
            val statusLine = text.lineSequence().firstOrNull().orEmpty()
            require(statusLine.contains(" 200 ") || statusLine.endsWith(" 200")) {
                "Go core request failed: $statusLine"
            }
            text.substring(split + 4)
        }
    } catch (e: GoCoreUnavailableException) {
        throw e
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: SocketTimeoutException) {
        throw GoCoreUnavailableException(
            "Go core timed out at $socketPath after ${timeoutMs}ms (is daemonitor-cored stuck?): ${e.message}",
            e,
        )
    } catch (e: InterruptedIOException) {
        throw GoCoreUnavailableException(
            "Go core unreachable at $socketPath (is daemonitor-cored running, or is the socket stale?): ${e.message}",
            e,
        )
    } catch (e: Exception) {
        throw GoCoreUnavailableException(
            "Go core unreachable at $socketPath (is daemonitor-cored running, or is the socket stale?): ${e.message}",
            e,
        )
    }
}

private fun writeFully(channel: SocketChannel, buffer: ByteBuffer, timeoutMs: Int) {
    while (buffer.hasRemaining()) {
        awaitChannel(channel, SelectionKey.OP_WRITE, timeoutMs, "write")
        if (channel.write(buffer) < 0) {
            throw SocketTimeoutException("write failed after ${timeoutMs}ms")
        }
    }
}

private fun readUntilEof(channel: SocketChannel, timeoutMs: Int): String {
    val buffer = ByteBuffer.allocate(64 * 1024)
    val raw = StringBuilder()
    while (true) {
        awaitChannel(channel, SelectionKey.OP_READ, timeoutMs, "read")
        val read = channel.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        buffer.flip()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        raw.append(String(bytes, StandardCharsets.UTF_8))
        buffer.clear()
    }
    return raw.toString()
}

private fun awaitChannel(channel: SocketChannel, ops: Int, timeoutMs: Int, opName: String) {
    Selector.open().use { selector ->
        val key = channel.register(selector, ops)
        try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
            while (true) {
                val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (remaining <= 0L) {
                    throw SocketTimeoutException("$opName timed out after ${timeoutMs}ms")
                }
                val selected = selector.select(remaining)
                if (selected > 0 && key.isValid && key.readyOps() and ops != 0) {
                    return
                }
                selector.selectedKeys().clear()
            }
        } finally {
            key.cancel()
            selector.selectNow()
        }
    }
}
