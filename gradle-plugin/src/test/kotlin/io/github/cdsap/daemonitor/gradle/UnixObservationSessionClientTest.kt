package io.github.cdsap.daemonitor.gradle

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class UnixObservationSessionClientTest {
    @Test
    fun `open and close send JSON POST requests over the socket`() {
        assumeUnix()
        val socket = shortSocket("dobs")
        val requests = mutableListOf<String>()
        val ready = CountDownLatch(1)
        val bindError = AtomicReference<Throwable>()
        val server = Thread {
            try {
                ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { listener ->
                    listener.bind(UnixDomainSocketAddress.of(socket))
                    ready.countDown()
                    repeat(2) { index ->
                        listener.accept().use { channel ->
                            val request = readRequest(channel)
                            requests += request
                            val body = if (index == 0) "{\"session_id\":\"s1\"}" else "{\"session_id\":\"s1\",\"processes\":[]}"
                            val response = "HTTP/1.0 ${if (index == 0) 201 else 200} OK\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body"
                            channel.write(ByteBuffer.wrap(response.toByteArray()))
                        }
                    }
                }
            } catch (error: Throwable) {
                bindError.set(error)
                ready.countDown()
            }
        }.apply { isDaemon = true; start() }
        try {
            awaitReady(ready, bindError, socket)
            val client = UnixObservationSessionClient(socket, Duration.ofSeconds(2))
            assertEquals(201, client.open("{\"root_project_path\":\"/tmp/project\"}").status)
            assertEquals(200, client.close("s1", "{\"outcome\":\"success\"}").status)
            assertTrue(requests[0].startsWith("POST /v1/observation-sessions/open HTTP/1.0"))
            assertTrue(requests[0].contains("Content-Type: application/json"))
            assertTrue(requests[0].endsWith("{\"root_project_path\":\"/tmp/project\"}"))
            assertTrue(requests[1].startsWith("POST /v1/observation-sessions/close HTTP/1.0"))
            assertTrue(requests[1].contains("{\"outcome\":\"success\"}"))
        } finally {
            server.join(2_000)
            Files.deleteIfExists(socket)
        }
    }

    @Test
    fun `default close body names the session for cored`() {
        assumeUnix()
        val socket = shortSocket("dobs-close")
        val request = arrayOfNulls<String>(1)
        val ready = CountDownLatch(1)
        val bindError = AtomicReference<Throwable>()
        val server = Thread {
            try {
                ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { listener ->
                    listener.bind(UnixDomainSocketAddress.of(socket))
                    ready.countDown()
                    listener.accept().use { channel ->
                        readRequest(channel)
                        val response = "HTTP/1.0 201 OK\r\nContent-Length: 2\r\n\r\n{}"
                        channel.write(ByteBuffer.wrap(response.toByteArray()))
                    }
                    listener.accept().use { channel ->
                        request[0] = readRequest(channel)
                        val response = "HTTP/1.0 200 OK\r\nContent-Length: 2\r\n\r\n{}"
                        channel.write(ByteBuffer.wrap(response.toByteArray()))
                    }
                }
            } catch (error: Throwable) {
                bindError.set(error)
                ready.countDown()
            }
        }.apply { isDaemon = true; start() }
        try {
            awaitReady(ready, bindError, socket)
            val client = UnixObservationSessionClient(socket, Duration.ofSeconds(2))
            client.open("{}")
            client.close("session/with-unsafe-id")
            assertTrue(request[0]!!.contains("\"name\":\"session/with-unsafe-id\""))
        } finally {
            server.join(2_000)
            Files.deleteIfExists(socket)
        }
    }

    @Test
    fun `unreachable socket surfaces typed soft failure`() {
        val missing = Files.createTempDirectory("dobs-").resolve("missing.sock")
        val error = assertFailsWith<ObservationClientException> {
            UnixObservationSessionClient(missing).open("{}")
        }
        assertTrue(error.message!!.contains("observation IPC failed"))
    }

    @Test
    fun `slow server is bounded by the client timeout`() {
        assumeUnix()
        val socket = shortSocket("dobs-to")
        val accepted = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val bindError = AtomicReference<Throwable>()
        val server = Thread {
            try {
                ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { listener ->
                    listener.bind(UnixDomainSocketAddress.of(socket))
                    ready.countDown()
                    listener.accept().use {
                        accepted.countDown()
                        Thread.sleep(5_000)
                    }
                }
            } catch (error: Throwable) {
                bindError.set(error)
                ready.countDown()
            }
        }.apply { isDaemon = true; start() }
        try {
            awaitReady(ready, bindError, socket)
            val started = System.nanoTime()
            assertFailsWith<ObservationClientException> {
                UnixObservationSessionClient(socket, Duration.ofMillis(200)).open("{}")
            }
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue(accepted.await(1, TimeUnit.SECONDS))
            assertTrue(elapsed in 150..2_000, "client exceeded timeout: ${elapsed}ms")
        } finally {
            server.interrupt()
            server.join(2_000)
            Files.deleteIfExists(socket)
        }
    }

    private fun readRequest(channel: java.nio.channels.SocketChannel): String {
        val buffer = ByteBuffer.allocate(8 * 1024)
        val output = StringBuilder()
        while (channel.read(buffer) > 0) {
            buffer.flip()
            output.append(Charsets.UTF_8.decode(buffer))
            buffer.clear()
            if (output.contains("\r\n\r\n")) {
                val contentLength = Regex("(?im)^Content-Length:\\s*(\\d+)").find(output)?.groupValues?.get(1)?.toInt() ?: 0
                if (output.toString().substringAfter("\r\n\r\n").toByteArray().size >= contentLength) break
            }
        }
        return output.toString()
    }

    private fun shortSocket(prefix: String): java.nio.file.Path {
        // Keep under the macOS sockaddr_un sun_path limit (~104 bytes).
        val path = java.nio.file.Path.of("/tmp", "$prefix-${System.nanoTime()}.sock")
        Files.deleteIfExists(path)
        return path
    }

    private fun awaitReady(ready: CountDownLatch, bindError: AtomicReference<Throwable>, socket: java.nio.file.Path) {
        assertTrue(ready.await(2, TimeUnit.SECONDS), "test server did not bind")
        bindError.get()?.let { throw AssertionError("test server failed to bind", it) }
        assertTrue(Files.exists(socket), "test socket was not created")
    }
}

private fun assumeUnix() {
    assumeTrue(!System.getProperty("os.name").orEmpty().contains("windows", ignoreCase = true), "Unix socket test")
}
