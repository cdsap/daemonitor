package io.github.cdsap.daemonitor.gradle

import java.io.ByteArrayOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * POST client for cored observation sessions over Unix-domain HTTP/1.0.
 *
 * This client only connects to an existing socket. It never locates or starts cored.
 * All connection, request, and response IO shares one deadline so a wedged cored cannot
 * hold a Gradle build indefinitely.
 */
class UnixObservationSessionClient(
    private val socketPath: Path,
    private val timeout: Duration = Duration.ofSeconds(3),
) {
    init {
        require(!timeout.isNegative && !timeout.isZero) { "timeout must be positive" }
    }

    fun open(bodyJson: String): HttpResult =
        exchange("/v1/observation-sessions", bodyJson)

    fun close(sessionId: String, bodyJson: String = "{}"): HttpResult {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        return exchange("/v1/observation-sessions/${encodePathSegment(sessionId)}/close", bodyJson)
    }

    private fun exchange(path: String, body: String): HttpResult {
        val payload = body.toByteArray(StandardCharsets.UTF_8)
        val request = buildString {
            append("POST ").append(path).append(" HTTP/1.0\r\n")
            append("Host: localhost\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ").append(payload.size).append("\r\n")
            append("Accept: application/json\r\n\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        val deadline = System.nanoTime() + timeout.toNanos()

        try {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.configureBlocking(false)
                connect(channel, deadline)
                writeFully(channel, ByteBuffer.wrap(request), deadline)
                writeFully(channel, ByteBuffer.wrap(payload), deadline)
                channel.shutdownOutput()
                return parseHttp(readResponse(channel, deadline))
            }
        } catch (e: ObservationClientException) {
            throw e
        } catch (e: Exception) {
            throw ObservationClientException(
                "observation IPC failed for $socketPath: ${e.message ?: e::class.simpleName}",
                e,
            )
        }
    }

    private fun connect(channel: SocketChannel, deadline: Long) {
        if (channel.connect(UnixDomainSocketAddress.of(socketPath))) return
        await(channel, SelectionKey.OP_CONNECT, deadline, "connect")
        if (!channel.finishConnect()) {
            throw TimeoutException("connect timed out")
        }
    }

    private fun writeFully(channel: SocketChannel, buffer: ByteBuffer, deadline: Long) {
        while (buffer.hasRemaining()) {
            await(channel, SelectionKey.OP_WRITE, deadline, "write")
            if (channel.write(buffer) < 0) throw java.io.EOFException("socket closed during write")
        }
    }

    private fun readResponse(channel: SocketChannel, deadline: Long): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteBuffer.allocate(8 * 1024)
        while (true) {
            await(channel, SelectionKey.OP_READ, deadline, "read")
            val count = channel.read(buffer)
            if (count < 0) return output.toString(StandardCharsets.UTF_8)
            if (count == 0) continue
            buffer.flip()
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            output.write(bytes)
            buffer.clear()
            if (hasCompleteResponse(output.toByteArray())) return output.toString(StandardCharsets.UTF_8)
        }
    }

    private fun await(channel: SocketChannel, operations: Int, deadline: Long, operation: String) {
        Selector.open().use { selector ->
            val key = channel.register(selector, operations)
            try {
                while (true) {
                    val remainingNanos = deadline - System.nanoTime()
                    if (remainingNanos <= 0) throw TimeoutException("$operation timed out")
                    val selected = selector.select(TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1))
                    if (selected > 0 && key.isValid && key.readyOps() and operations != 0) return
                    selector.selectedKeys().clear()
                }
            } finally {
                key.cancel()
                selector.selectNow()
            }
        }
    }

    private fun parseHttp(raw: String): HttpResult {
        val split = raw.indexOf("\r\n\r\n")
        if (split < 0) return HttpResult(0, raw)
        val header = raw.substring(0, split)
        val body = raw.substring(split + 4)
        val status = header.lineSequence().firstOrNull()
            ?.split(' ')
            ?.getOrNull(1)
            ?.toIntOrNull() ?: 0
        return HttpResult(status, body)
    }

    private fun hasCompleteResponse(raw: ByteArray): Boolean {
        val text = raw.toString(StandardCharsets.ISO_8859_1)
        val headerEnd = text.indexOf("\r\n\r\n")
        if (headerEnd < 0) return false
        val contentLength = Regex("(?im)^Content-Length:\\s*(\\d+)\\s*$")
            .find(text.substring(0, headerEnd))
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
        return contentLength != null && raw.size - (headerEnd + 4) >= contentLength
    }

    private fun encodePathSegment(value: String): String =
        value.replace("%", "%25").replace("/", "%2F").replace("?", "%3F").replace("#", "%23")
}

data class HttpResult(val status: Int, val body: String) {
    val isSuccess: Boolean get() = status in 200..299
}

class ObservationClientException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
