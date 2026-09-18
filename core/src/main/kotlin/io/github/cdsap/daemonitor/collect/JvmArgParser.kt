package io.github.cdsap.daemonitor.collect

import io.github.cdsap.daemonitor.domain.model.JvmArgs

/**
 * Parses JVM memory and GC flags from a command line (KTD-3). `max_heap_mb` is recoverable only
 * when `-Xmx` is explicit; live heap occupancy is not observable externally and is out of scope.
 */
object JvmArgParser {

    private val XMX = Regex("""-Xmx(\d+)([kKmMgGtT]?)""")
    private val XMS = Regex("""-Xms(\d+)([kKmMgGtT]?)""")
    private val GC = Regex("""-XX:\+Use(\w+?)GC""")
    private val DAEMON_FLAG = Regex("""-Dorg\.gradle\.\S+""")

    fun parse(commandLine: String): JvmArgs = JvmArgs(
        maxHeapMb = XMX.find(commandLine)?.let { toMb(it.groupValues[1], it.groupValues[2]) },
        minHeapMb = XMS.find(commandLine)?.let { toMb(it.groupValues[1], it.groupValues[2]) },
        gc = GC.find(commandLine)?.groupValues?.get(1),
        daemonFlags = DAEMON_FLAG.findAll(commandLine).map { it.value }.toList(),
    )

    private fun toMb(number: String, unit: String): Long {
        val n = number.toLong()
        return when (unit.lowercase()) {
            "k" -> n / 1024
            "m", "" -> n            // bare value is treated as MB-ish; JVM default unit is bytes,
            "g" -> n * 1024         // but -Xmx without a unit is rare; MB is the pragmatic read.
            "t" -> n * 1024 * 1024
            else -> n
        }
    }
}
