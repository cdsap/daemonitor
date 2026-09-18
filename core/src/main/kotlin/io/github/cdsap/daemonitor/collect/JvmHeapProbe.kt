package io.github.cdsap.daemonitor.collect

import io.github.cdsap.daemonitor.domain.model.LiveJvmHeap
import java.lang.management.ManagementFactory
import java.lang.management.MemoryMXBean
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.management.remote.JMXConnectorFactory
import javax.management.remote.JMXServiceURL

/**
 * Reads live JVM heap usage for a same-UID HotSpot process.
 *
 * Implementation uses the JDK Attach API to start (or reuse) the local JMX management agent, then
 * reads [MemoryMXBean.getHeapMemoryUsage]. Failures — attach denied, non-HotSpot target, exited
 * process, sandbox restrictions, timeouts — return [LiveJvmHeap.unavailable] rather than fabricating
 * zeros.
 *
 * Overhead: the first successful probe per PID loads the management agent into the target JVM
 * (one-time cost). Subsequent polls reuse the cached local connector address and perform a short
 * JMX round-trip. Connector addresses are dropped when the PID disappears or a read fails.
 * Each attach/read attempt is bounded by [PROBE_TIMEOUT_MS] so a stuck target cannot stall the
 * poll loop.
 *
 * See `docs/jvm-heap-collection.md`.
 */
fun interface JvmHeapProbe {
    fun probe(pid: Long, sampledAtMs: Long): LiveJvmHeap

    /** Drop cached connector state for PIDs that are no longer live. */
    fun retainOnly(livePids: Set<Long>) {}
}

class AttachJvmHeapProbe(
    private val timeoutMs: Long = PROBE_TIMEOUT_MS,
) : JvmHeapProbe {
    private val connectorAddresses = ConcurrentHashMap<Long, String>()
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "daemonitor-jvm-heap-probe").apply { isDaemon = true }
    }

    override fun probe(pid: Long, sampledAtMs: Long): LiveJvmHeap {
        if (pid <= 0L) return LiveJvmHeap.unavailable(sampledAtMs)
        // Self-attach can deadlock HotSpot; treat as unavailable.
        if (pid == ProcessHandle.current().pid()) return LiveJvmHeap.unavailable(sampledAtMs)
        return readWithCachedAddress(pid, sampledAtMs)
            ?: readAfterFreshAttach(pid, sampledAtMs)
            ?: LiveJvmHeap.unavailable(sampledAtMs)
    }

    override fun retainOnly(livePids: Set<Long>) {
        connectorAddresses.keys.retainAll(livePids)
    }

    private fun readWithCachedAddress(pid: Long, sampledAtMs: Long): LiveJvmHeap? {
        val address = connectorAddresses[pid] ?: return null
        return runTimed {
            readHeap(address, sampledAtMs)
        }.onFailure {
            connectorAddresses.remove(pid)
        }.getOrNull()
    }

    private fun readAfterFreshAttach(pid: Long, sampledAtMs: Long): LiveJvmHeap? =
        runTimed {
            val address = resolveConnectorAddress(pid)
            connectorAddresses[pid] = address
            readHeap(address, sampledAtMs)
        }.onFailure {
            connectorAddresses.remove(pid)
        }.getOrNull()

    private fun <T> runTimed(block: Callable<T>): Result<T> {
        val future = executor.submit(block)
        return try {
            Result.success(future.get(timeoutMs, TimeUnit.MILLISECONDS))
        } catch (_: TimeoutException) {
            future.cancel(true)
            Result.failure(TimeoutException("jvm heap probe timed out after ${timeoutMs}ms"))
        } catch (e: Exception) {
            future.cancel(true)
            Result.failure(e.cause ?: e)
        }
    }

    private fun resolveConnectorAddress(pid: Long): String {
        val vmClass = Class.forName("com.sun.tools.attach.VirtualMachine")
        val attach = vmClass.getMethod("attach", String::class.java)
        val detach = vmClass.getMethod("detach")
        val startLocalManagementAgent = vmClass.getMethod("startLocalManagementAgent")
        val vm = attach.invoke(null, pid.toString())
        try {
            val address = startLocalManagementAgent.invoke(vm) as? String
                ?: error("local management agent returned no connector address")
            require(address.isNotBlank()) { "blank JMX connector address" }
            return address
        } finally {
            runCatching { detach.invoke(vm) }
        }
    }

    private fun readHeap(connectorAddress: String, sampledAtMs: Long): LiveJvmHeap {
        val url = JMXServiceURL(connectorAddress)
        JMXConnectorFactory.connect(url).use { connector ->
            val server = connector.mBeanServerConnection
            val memory = ManagementFactory.newPlatformMXBeanProxy(
                server,
                ManagementFactory.MEMORY_MXBEAN_NAME,
                MemoryMXBean::class.java,
            )
            val usage = memory.heapMemoryUsage
            return LiveJvmHeap(
                usedMb = bytesToMb(usage.used),
                committedMb = bytesToMb(usage.committed),
                maxMb = usage.max.takeIf { it >= 0L }?.let(::bytesToMb),
                sampledAtMs = sampledAtMs,
                available = true,
            )
        }
    }

    private fun bytesToMb(bytes: Long): Long = (bytes / (1024L * 1024L)).coerceAtLeast(0L)

    companion object {
        const val PROBE_TIMEOUT_MS = 750L
    }
}
