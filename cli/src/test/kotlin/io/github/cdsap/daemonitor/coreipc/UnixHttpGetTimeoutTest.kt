package io.github.cdsap.daemonitor.coreipc

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class UnixHttpGetTimeoutTest {
    @Test
    fun `hung go core read fails within the configured timeout`() {
        assumeTrue(!isWindows(), "AF_UNIX hang test is Unix-specific")

        val socket = Files.createTempDirectory("daemonitor-http-timeout-").resolve("cored.sock")
        Files.deleteIfExists(socket)
        val bound = CountDownLatch(1)
        val accepted = CountDownLatch(1)
        val serverError = AtomicReference<Throwable>()
        val server = Thread({
            try {
                ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { serverChannel ->
                    serverChannel.bind(UnixDomainSocketAddress.of(socket))
                    bound.countDown()
                    // Accept once and hold the connection open without writing a response.
                    serverChannel.accept().use {
                        accepted.countDown()
                        Thread.sleep(30_000L)
                    }
                }
            } catch (t: Throwable) {
                serverError.set(t)
                bound.countDown()
                accepted.countDown()
            }
        }, "daemonitor-unix-http-hang-server").apply {
            isDaemon = true
            start()
        }

        try {
            assertTrue(bound.await(2, TimeUnit.SECONDS), "hang server did not bind socket")
            assumeTrue(Files.exists(socket), "hang server did not create socket")

            val started = System.nanoTime()
            val error = assertFailsWith<GoCoreUnavailableException> {
                unixHttpGet(socket, "/v1/health", timeoutMs = 400)
            }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

            assertTrue(
                error.message!!.contains("timed out") || error.message!!.contains("unreachable"),
                "expected timeout/unreachable message: ${error.message}",
            )
            assertTrue(
                elapsedMs in 300..3_000,
                "timeout should trip near 400ms, not hang forever (elapsed=${elapsedMs}ms)",
            )
            assertTrue(accepted.await(2, TimeUnit.SECONDS), "server should have accepted the client")
            serverError.get()?.let { throw AssertionError("hang server failed", it) }
        } finally {
            server.interrupt()
            server.join(2_000L)
            Files.deleteIfExists(socket)
            runCatching { Files.deleteIfExists(socket.parent) }
        }
    }

    @Test
    fun `unixHttpGet preserves multi-byte UTF-8 characters split across read chunks`() {
        assumeTrue(!isWindows(), "AF_UNIX socket test is Unix-specific")

        val socket = Files.createTempDirectory("daemonitor-http-utf8-").resolve("cored.sock")
        Files.deleteIfExists(socket)
        val header = "HTTP/1.0 200 OK\r\nContent-Type: application/json\r\n\r\n"
        // Place a 3-byte UTF-8 character ('·' = E2 80 A2 / '€' = E2 82 AC) across the 64 KiB boundary.
        val prefixLen = (64 * 1024) - header.length - 1
        val body = "a".repeat(prefixLen) + "€" + "tail"
        val fullResponse = (header + body).toByteArray(StandardCharsets.UTF_8)
        val bound = CountDownLatch(1)
        val serverError = AtomicReference<Throwable>()
        val server = Thread({
            try {
                ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { serverChannel ->
                    serverChannel.bind(UnixDomainSocketAddress.of(socket))
                    bound.countDown()
                    serverChannel.accept().use { client ->
                        // Drain request bytes briefly, then write response split mid-UTF-8 character.
                        val reqBuf = ByteBuffer.allocate(1024)
                        client.read(reqBuf)
                        val splitAt = 64 * 1024
                        val firstChunk = ByteBuffer.wrap(fullResponse, 0, splitAt)
                        while (firstChunk.hasRemaining()) client.write(firstChunk)
                        Thread.sleep(20L)
                        val secondChunk = ByteBuffer.wrap(fullResponse, splitAt, fullResponse.size - splitAt)
                        while (secondChunk.hasRemaining()) client.write(secondChunk)
                    }
                }
            } catch (t: Throwable) {
                serverError.set(t)
                bound.countDown()
            }
        }, "daemonitor-unix-http-utf8-server").apply {
            isDaemon = true
            start()
        }

        try {
            assertTrue(bound.await(2, TimeUnit.SECONDS), "utf8 server did not bind socket")
            assumeTrue(Files.exists(socket), "utf8 server did not create socket")

            val actual = unixHttpGet(socket, "/v1/processes", timeoutMs = 2_000)
            assertEquals(body, actual)
            serverError.get()?.let { throw AssertionError("utf8 server failed", it) }
        } finally {
            server.interrupt()
            server.join(2_000L)
            Files.deleteIfExists(socket)
            runCatching { Files.deleteIfExists(socket.parent) }
        }
    }
}

private fun isWindows(): Boolean =
    System.getProperty("os.name").orEmpty().lowercase().contains("windows")
