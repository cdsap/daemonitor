package io.github.cdsap.daemonitor.application

/** Optional host-wide data supplied by the active collector. */
interface HostContextSource {
    fun host(): HostContext
    fun capabilities(): List<MetricCapability>
}

data class HostContext(
    val sampledAtMs: Long?,
    val logicalProcessors: Long?,
    val cpuPercent: Double?,
    val memoryUsedBytes: Long?,
    val memoryTotalBytes: Long?,
)

data class MetricCapability(val name: String, val availability: String)
