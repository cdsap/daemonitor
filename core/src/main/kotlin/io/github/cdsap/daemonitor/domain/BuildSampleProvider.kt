package io.github.cdsap.daemonitor.domain

/**
 * One RSS + CPU observation inside a build window.
 *
 * Named fields replace the persistence-shaped `(rssMemoryMb, cpuPercent)` pair so domain
 * aggregation can reason about metrics without depending on storage representation.
 */
data class BuildSample(
    val rssMemoryMb: Long,
    val cpuPercent: Double?,
)

/** Domain port for process samples used when correlating a build window. */
fun interface BuildSampleProvider {
    fun samplesInWindow(pid: Long, startMs: Long, endMs: Long): List<BuildSample>
}
