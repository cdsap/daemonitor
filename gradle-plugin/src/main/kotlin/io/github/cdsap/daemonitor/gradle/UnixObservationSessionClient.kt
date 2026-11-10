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

class UnixObservationSessionClient(private val socketPath: Path, private val timeout: Duration = Duration.ofSeconds(3)) {
    init { require(!timeout.isNegative && !timeout.isZero) { "timeout must be positive" } }
    fun open(bodyJson: String): HttpResult = exchange("/v1/observation-sessions/open", bodyJson)
    fun close(name: String, bodyJson: String = "{}"): HttpResult = exchange("/v1/observation-sessions/close", bodyJson)
    private fun exchange(path: String, body: String): HttpResult {
        val payload = body.toByteArray(StandardCharsets.UTF_8)
        val request = "POST $path HTTP/1.0\r\nHost: localhost\r\nContent-Type: application/json\r\n" +
            "Content-Length: ${payload.size}\r\nAccept: application/json\r\n\r\n"
        val deadline = System.nanoTime() + timeout.toNanos()
        try {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.configureBlocking(false)
                connect(channel, deadline)
                writeFully(channel, ByteBuffer.wrap(request.toByteArray(StandardCharsets.US_ASCII)), deadline)
                writeFully(channel, ByteBuffer.wrap(payload), deadline)
                channel.shutdownOutput()
                return parseHttp(readResponse(channel, deadline))
            }
        } catch (e: ObservationClientException) { throw e }
        catch (e: Exception) { throw ObservationClientException("observation IPC failed for $socketPath: ${e.message ?: e::class.simpleName}", e) }
    }
    private fun connect(channel: SocketChannel, deadline: Long) {
        if (channel.connect(UnixDomainSocketAddress.of(socketPath))) return
        await(channel, SelectionKey.OP_CONNECT, deadline, "connect")
        if (!channel.finishConnect()) throw TimeoutException("connect timed out")
    }
    private fun writeFully(channel: SocketChannel, buffer: ByteBuffer, deadline: Long) {
        while (buffer.hasRemaining()) { await(channel, SelectionKey.OP_WRITE, deadline, "write"); if (channel.write(buffer) < 0) error("socket closed") }
    }
    private fun readResponse(channel: SocketChannel, deadline: Long): String {
        val out = ByteArrayOutputStream(); val buffer = ByteBuffer.allocate(8192)
        while (true) { await(channel, SelectionKey.OP_READ, deadline, "read"); val count = channel.read(buffer); if (count < 0) return out.toString(StandardCharsets.UTF_8.name()); if (count == 0) continue; buffer.flip(); out.write(buffer.array(), buffer.position(), buffer.remaining()); buffer.clear(); if (hasCompleteResponse(out.toByteArray())) return out.toString(StandardCharsets.UTF_8.name()) }
    }
    private fun await(channel: SocketChannel, operations: Int, deadline: Long, operation: String) {
        Selector.open().use { selector -> val key = channel.register(selector, operations); try { while (true) { val remaining = deadline - System.nanoTime(); if (remaining <= 0) throw TimeoutException("$operation timed out"); if (selector.select(TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1)) > 0 && key.isValid && key.readyOps() and operations != 0) return; selector.selectedKeys().clear() } } finally { key.cancel() } }
    }
    private fun parseHttp(raw: String): HttpResult { val split = raw.indexOf("\r\n\r\n"); if (split < 0) return HttpResult(0, raw); val status = raw.substring(0, split).lineSequence().firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: 0; return HttpResult(status, raw.substring(split + 4)) }
    private fun hasCompleteResponse(raw: ByteArray): Boolean { val text = raw.toString(StandardCharsets.ISO_8859_1); val end = text.indexOf("\r\n\r\n"); if (end < 0) return false; val length = Regex("(?im)^Content-Length:\\s*(\\d+)\\s*$").find(text.substring(0, end))?.groupValues?.get(1)?.toIntOrNull(); return length != null && raw.size - end - 4 >= length }
}

data class HttpResult(val status: Int, val body: String) { val isSuccess get() = status in 200..299 }
class ObservationClientException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
