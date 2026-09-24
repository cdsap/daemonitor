package io.github.cdsap.daemonitor.coreipc

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class UnixHttpGetTimeoutTest {
    @Test
    fun `hung go core read fails within the configured timeout`() {
        assumeTrue(!isWindows(), "AF_UNIX hang test is Unix-specific")

        val socket = Files.createTempDirectory("daemonitor-http-timeout-").resolve("cored.sock")
        Files.deleteIfExists(socket)
        val accepted = CountDownLatch(1)
        val serverError = AtomicReference<Throwable>()
        val server = Thread({
            try {
                ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { serverChannel ->
                    serverChannel.bind(UnixDomainSocketAddress.of(socket))
                    // Accept once and hold the connection open without writing a response.
                    serverChannel.accept().use {
                        accepted.countDown()
                        Thread.sleep(30_000L)
                    }
                }
            } catch (t: Throwable) {
                serverError.set(t)
                accepted.countDown()
            }
        }, "daemonitor-unix-http-hang-server").apply {
            isDaemon = true
            start()
        }

        try {
            // Wait briefly for the listener; the client connect races the bind otherwise.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (System.nanoTime() < deadline && !Files.exists(socket)) {
                Thread.sleep(20L)
            }
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
}

private fun isWindows(): Boolean =
    System.getProperty("os.name").orEmpty().lowercase().contains("windows")
