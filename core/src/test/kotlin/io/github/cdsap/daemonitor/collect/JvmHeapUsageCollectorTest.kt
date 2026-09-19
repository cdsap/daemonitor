package io.github.cdsap.daemonitor.collect

import io.github.cdsap.daemonitor.domain.model.LiveJvmHeap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals

class JvmHeapUsageCollectorTest {

    @Test
    fun `refresh throttling reuses sample within interval`() {
        val now = AtomicLong(1_000L)
        val probes = AtomicInteger(0)
        val collector = JvmHeapUsageCollector(
            probe = recordingProbe(probes) { sampledAtMs ->
                LiveJvmHeap(usedMb = 100, committedMb = 200, maxMb = 512, sampledAtMs = sampledAtMs, available = true)
            },
            clock = { now.get() },
            refreshIntervalMs = 10_000L,
        )

        val first = collector.read(pid = 42, startTimeMs = 500L, sampledAtMs = 1_000L)
        now.set(5_000L)
        val second = collector.read(pid = 42, startTimeMs = 500L, sampledAtMs = 5_000L)

        assertEquals(1, probes.get())
        assertEquals(first, second)
        assertEquals(100L, second.usedMb)
        assertEquals(1_000L, second.sampledAtMs)
    }

    @Test
    fun `refresh throttling probes again after interval`() {
        val now = AtomicLong(1_000L)
        val probes = AtomicInteger(0)
        val usedMb = AtomicLong(100L)
        val collector = JvmHeapUsageCollector(
            probe = recordingProbe(probes) { sampledAtMs ->
                LiveJvmHeap(
                    usedMb = usedMb.get(),
                    committedMb = 200,
                    maxMb = 512,
                    sampledAtMs = sampledAtMs,
                    available = true,
                )
            },
            clock = { now.get() },
            refreshIntervalMs = 10_000L,
        )

        collector.read(pid = 42, startTimeMs = 500L, sampledAtMs = 1_000L)
        usedMb.set(150L)
        now.set(11_000L)
        val refreshed = collector.read(pid = 42, startTimeMs = 500L, sampledAtMs = 11_000L)

        assertEquals(2, probes.get())
        assertEquals(150L, refreshed.usedMb)
        assertEquals(11_000L, refreshed.sampledAtMs)
    }

    @Test
    fun `pid reuse with new start time does not return previous sample`() {
        val now = AtomicLong(1_000L)
        val probes = AtomicInteger(0)
        val usedByStart = AtomicReference(mapOf(500L to 100L, 9_000L to 250L))
        val collector = JvmHeapUsageCollector(
            probe = object : JvmHeapProbe {
                override fun probe(pid: Long, sampledAtMs: Long): LiveJvmHeap =
                    error("start time required")

                override fun probe(pid: Long, startTimeMs: Long, sampledAtMs: Long): LiveJvmHeap {
                    probes.incrementAndGet()
                    return LiveJvmHeap(
                        usedMb = usedByStart.get().getValue(startTimeMs),
                        committedMb = 300,
                        maxMb = 512,
                        sampledAtMs = sampledAtMs,
                        available = true,
                    )
                }
            },
            clock = { now.get() },
            refreshIntervalMs = 10_000L,
        )

        val first = collector.read(pid = 7, startTimeMs = 500L, sampledAtMs = 1_000L)
        now.set(2_000L)
        val reusedPid = collector.read(pid = 7, startTimeMs = 9_000L, sampledAtMs = 2_000L)

        assertEquals(2, probes.get())
        assertEquals(100L, first.usedMb)
        assertEquals(250L, reusedPid.usedMb)
        assertEquals(2, collector.cachedSize())
    }

    @Test
    fun `retainOnly evicts disappeared process samples`() {
        val now = AtomicLong(1_000L)
        val probes = AtomicInteger(0)
        val collector = JvmHeapUsageCollector(
            probe = recordingProbe(probes) { sampledAtMs ->
                LiveJvmHeap(usedMb = 80, committedMb = 160, maxMb = 512, sampledAtMs = sampledAtMs, available = true)
            },
            clock = { now.get() },
            refreshIntervalMs = 10_000L,
        )
        val alive = ProcessIdentity(pid = 11, startTimeMs = 100L)
        val gone = ProcessIdentity(pid = 12, startTimeMs = 200L)

        collector.read(alive.pid, alive.startTimeMs, sampledAtMs = 1_000L)
        collector.read(gone.pid, gone.startTimeMs, sampledAtMs = 1_000L)
        assertEquals(2, collector.cachedSize())

        collector.retainOnly(listOf(alive))
        assertEquals(1, collector.cachedSize())

        now.set(2_000L)
        collector.read(gone.pid, gone.startTimeMs, sampledAtMs = 2_000L)
        assertEquals(3, probes.get(), "evicted identity must probe again even inside refresh window")
        assertEquals(2, collector.cachedSize())
    }

    @Test
    fun `forget removes a single process identity`() {
        val collector = JvmHeapUsageCollector(
            probe = JvmHeapProbe { _, sampledAtMs -> LiveJvmHeap.unavailable(sampledAtMs) },
            clock = { 1_000L },
            refreshIntervalMs = 10_000L,
        )
        collector.read(pid = 3, startTimeMs = 10L)
        collector.read(pid = 4, startTimeMs = 20L)
        collector.forget(pid = 3, startTimeMs = 10L)
        assertEquals(1, collector.cachedSize())
    }

    @Test
    fun `retainOnly forwards process identities to underlying probe`() {
        val retained = AtomicReference<Collection<ProcessIdentity>>(emptyList())
        val probe = object : JvmHeapProbe {
            override fun probe(pid: Long, sampledAtMs: Long) = LiveJvmHeap.unavailable(sampledAtMs)
            override fun retainOnly(liveProcesses: Collection<ProcessIdentity>) {
                retained.set(liveProcesses.toList())
            }
        }
        val collector = JvmHeapUsageCollector(probe = probe, clock = { 1_000L })
        val live = listOf(ProcessIdentity(1, 10), ProcessIdentity(2, 20))
        collector.retainOnly(live)
        assertEquals(live, retained.get().toList())
    }

    private fun recordingProbe(
        probes: AtomicInteger,
        block: (sampledAtMs: Long) -> LiveJvmHeap,
    ): JvmHeapProbe = JvmHeapProbe { _, sampledAtMs ->
        probes.incrementAndGet()
        block(sampledAtMs)
    }
}
