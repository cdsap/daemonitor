package io.github.cdsap.daemonitor.collect

import com.sun.tools.attach.VirtualMachine
import java.lang.management.ManagementFactory
import java.lang.management.MemoryMXBean
import javax.management.remote.JMXConnector
import javax.management.remote.JMXConnectorFactory
import javax.management.remote.JMXServiceURL
import java.util.concurrent.ConcurrentHashMap

/** A point-in-time JVM heap measurement in MiB. */
data class JvmHeapUsage(
    val usedMb: Long,
    val committedMb: Long,
    val maxMb: Long?,
)

/**
 * Reads heap usage from same-user JVMs through their local management agent.
 *
 * Attach is deliberately throttled and failures are treated as unavailable: a daemon may exit,
 * deny attachment, or run on a JVM that does not expose a local management agent.
 */
class JvmHeapUsageCollector(
    private val clock: () -> Long = System::currentTimeMillis,
    private val refreshIntervalMs: Long = DEFAULT_REFRESH_INTERVAL_MS,
) {
    private val samples = ConcurrentHashMap<Long, CachedSample>()

    fun read(pid: Long): JvmHeapUsage? {
        val now = clock()
        val cached = samples[pid]
        if (cached != null && now - cached.atMs < refreshIntervalMs) return cached.value

        val value = runCatching { attachAndRead(pid) }.getOrNull()
        samples[pid] = CachedSample(now, value)
        return value
    }

    fun forget(pid: Long) {
        samples.remove(pid)
    }

    private fun attachAndRead(pid: Long): JvmHeapUsage {
        var vm: VirtualMachine? = null
        var connector: JMXConnector? = null
        try {
            vm = VirtualMachine.attach(pid.toString())
            val address = vm.startLocalManagementAgent()
            connector = JMXConnectorFactory.connect(JMXServiceURL(address))
            val memory = ManagementFactory.newPlatformMXBeanProxy(
                connector.mBeanServerConnection,
                ManagementFactory.MEMORY_MXBEAN_NAME,
                MemoryMXBean::class.java,
            )
            val usage = memory.heapMemoryUsage
            return JvmHeapUsage(
                usedMb = usage.used.toMb(),
                committedMb = usage.committed.toMb(),
                maxMb = usage.max.takeIf { it >= 0 }?.toMb(),
            )
        } finally {
            runCatching { connector?.close() }
            runCatching { vm?.detach() }
        }
    }

    private fun Long.toMb(): Long = this / BYTES_PER_MB

    private data class CachedSample(val atMs: Long, val value: JvmHeapUsage?)

    private companion object {
        const val BYTES_PER_MB = 1024L * 1024L
        const val DEFAULT_REFRESH_INTERVAL_MS = 10_000L
    }
}
