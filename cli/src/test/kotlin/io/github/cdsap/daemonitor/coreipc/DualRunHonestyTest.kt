package io.github.cdsap.daemonitor.coreipc

import io.github.cdsap.daemonitor.collect.JvmHeapProbe
import io.github.cdsap.daemonitor.collect.ProcessCollector
import io.github.cdsap.daemonitor.domain.model.LiveJvmHeap
import io.github.cdsap.daemonitor.domain.model.ProcessType
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Live dual-run honesty gate: compare [ProcessCollector] to Go `/v1/processes` on the same host.
 *
 * Opt-in only — requires a running `daemonitor-cored` and at least one live `GRADLE_DAEMON`:
 *
 * ```
 * DAEMONITOR_DUAL_RUN=1 DAEMONITOR_CORE_SOCKET=/path/to.sock ./gradlew :cli:test \
 *   --tests 'io.github.cdsap.daemonitor.coreipc.DualRunHonestyTest'
 * ```
 */
class DualRunHonestyTest {

    @Test
    fun `jvm and go process snapshots stay honest for common gradle daemons`() {
        assumeTrue(System.getenv("DAEMONITOR_DUAL_RUN") == "1") {
            "set DAEMONITOR_DUAL_RUN=1 to enable live dual-run honesty"
        }
        val socket = System.getenv("DAEMONITOR_CORE_SOCKET")?.let(Path::of)
            ?: fail("DAEMONITOR_CORE_SOCKET is required when DAEMONITOR_DUAL_RUN=1")
        assumeTrue(socket.exists()) { "Go core socket missing: $socket" }

        val collector = ProcessCollector(
            heapProbe = JvmHeapProbe { _, sampledAtMs -> LiveJvmHeap.unavailable(sampledAtMs) },
        )
        // Warm delta-CPU on both sides (Go cored polls continuously; JVM needs a prior sample).
        collector.poll()
        Thread.sleep(2_100)

        val jvm = collector.poll().associateBy { it.pid }
        val go = GoCoreProcessSource(socket).currentProcesses().associateBy { it.pid }

        val jvmDaemons = jvm.values.filter { it.type == ProcessType.GRADLE_DAEMON }.map { it.pid }.toSet()
        val goDaemons = go.values.filter { it.type == ProcessType.GRADLE_DAEMON }.map { it.pid }.toSet()
        assumeTrue(jvmDaemons.isNotEmpty()) { "no GRADLE_DAEMON on JVM side" }
        assumeTrue(goDaemons.isNotEmpty()) { "no GRADLE_DAEMON on Go side" }

        val common = jvm.keys.intersect(go.keys)
        assertTrue(common.isNotEmpty(), "no common PIDs between JVM (${jvm.size}) and Go (${go.size})")

        var typeMatches = 0
        var xmxMatches = 0
        var xmxCompared = 0
        var rssDeltas = mutableListOf<Long>()
        val mismatches = mutableListOf<String>()

        for (pid in common.sorted()) {
            val j = jvm.getValue(pid)
            val g = go.getValue(pid)
            if (j.type == g.type) {
                typeMatches++
            } else {
                mismatches += "pid=$pid type jvm=${j.type} go=${g.type}"
            }
            if (j.maxHeapMb != null || g.maxHeapMb != null) {
                xmxCompared++
                if (j.maxHeapMb == g.maxHeapMb) xmxMatches++
                else mismatches += "pid=$pid xmx jvm=${j.maxHeapMb} go=${g.maxHeapMb}"
            }
            rssDeltas += kotlin.math.abs(j.rssMemoryMb - g.rssMemoryMb)
        }

        val maxRssDelta = rssDeltas.maxOrNull() ?: 0L
        val medianRssDelta = rssDeltas.sorted().let { it[it.size / 2] }

        println(
            """
            |dual-run honesty:
            |  os=${System.getProperty("os.name")} arch=${System.getProperty("os.arch")}
            |  jvm_processes=${jvm.size} go_processes=${go.size} common=${common.size}
            |  jvm_daemons=${jvmDaemons.size} go_daemons=${goDaemons.size}
            |  type_match=$typeMatches/${common.size}
            |  xmx_match=$xmxMatches/$xmxCompared
            |  rss_delta_mb max=$maxRssDelta median=$medianRssDelta
            """.trimMargin(),
        )
        mismatches.take(8).forEach { println("  mismatch: $it") }

        assertTrue(mismatches.none { it.contains(" type ") }, "type mismatches: $mismatches")
        assertTrue(maxRssDelta <= 64, "RSS delta too large: max=${maxRssDelta}MB (common=${common.size})")
        assertTrue(
            typeMatches == common.size,
            "type labels must match for every common PID ($typeMatches/${common.size})",
        )
    }
}
