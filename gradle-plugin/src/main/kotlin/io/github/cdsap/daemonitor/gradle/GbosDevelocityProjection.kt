package io.github.cdsap.daemonitor.gradle

import io.github.cdsap.gbos.core.GbosAttributeValue
import io.github.cdsap.gbos.core.GbosJson
import io.github.cdsap.gbos.core.GbosMeasurement
import io.github.cdsap.gbos.core.GbosObservation
import io.github.cdsap.gbos.core.GbosProducer

internal object GbosDevelocityProjection {
    const val SCHEMA_VERSION = "1.0.0"
    const val PRODUCER_NAME = "daemonitor"
    const val PRODUCER_VERSION = "0.1.1"
    const val NAMESPACE = "gbos.v1.producer.daemonitor"
    const val OBSERVATION_KEY = "$NAMESPACE.observation"

    fun values(summary: ObservationSummary): List<Pair<String, String>> = buildList {
        add("gbos.schema" to SCHEMA_VERSION)
        add("$NAMESPACE.name" to PRODUCER_NAME)
        add("$NAMESPACE.version" to PRODUCER_VERSION)
        summary.processes.forEach { add(OBSERVATION_KEY to GbosJson.encodeFragment(observation(it))) }
    }

    fun observations(summary: ObservationSummary): List<GbosObservation> = summary.processes.map(::observation)

    private fun observation(process: ProcessRssSummary): GbosObservation = GbosObservation(
        schemaVersion = SCHEMA_VERSION,
        producer = GbosProducer(PRODUCER_NAME, PRODUCER_VERSION),
        scope = "jvm.process",
        aggregationScope = "entity",
        attributes = mapOf(
            "process.pid" to GbosAttributeValue.Integer(process.pid),
            "jvm.process.role" to GbosAttributeValue.Text(processRole(process.processType)),
            "process.type" to GbosAttributeValue.Text(process.processType.ifBlank { "UNKNOWN" }),
            "process.association" to GbosAttributeValue.Text(process.association),
        ),
        measurements = buildList {
            add(GbosMeasurement("jvm.process.memory.rss", process.peakRssMb * 1024.0 * 1024.0, "By", "max"))
            add(GbosMeasurement("jvm.process.memory.rss.first", process.firstRssMb * 1024.0 * 1024.0, "By", "first"))
            add(GbosMeasurement("jvm.process.memory.rss.last", process.lastRssMb * 1024.0 * 1024.0, "By", "last"))
            add(GbosMeasurement("jvm.process.memory.rss.delta", process.deltaRssMb * 1024.0 * 1024.0, "By", "last"))
            process.peakHeapUsedMb?.let { add(GbosMeasurement("jvm.memory.heap.used", it * 1024.0 * 1024.0, "By", "max")) }
            process.firstHeapUsedMb?.let { add(GbosMeasurement("jvm.memory.heap.used.first", it * 1024.0 * 1024.0, "By", "first")) }
            process.lastHeapUsedMb?.let { add(GbosMeasurement("jvm.memory.heap.used.last", it * 1024.0 * 1024.0, "By", "last")) }
            process.deltaHeapUsedMb?.let { add(GbosMeasurement("jvm.memory.heap.used.delta", it * 1024.0 * 1024.0, "By", "last")) }
            process.peakMetaspaceUsedMb?.let { add(GbosMeasurement("jvm.memory.metaspace.used", it * 1024.0 * 1024.0, "By", "max")) }
            process.deltaMetaspaceUsedMb?.let { add(GbosMeasurement("jvm.memory.metaspace.used.delta", it * 1024.0 * 1024.0, "By", "last")) }
            process.deltaYoungGcCount?.let { add(GbosMeasurement("jvm.gc.young.count.delta", it.toDouble(), "1", "last")) }
            process.deltaTotalGcCount?.let { add(GbosMeasurement("jvm.gc.total.count.delta", it.toDouble(), "1", "last")) }
            process.deltaYoungGcTimeMs?.let { add(GbosMeasurement("jvm.gc.young.time.delta", it / 1000.0, "s", "last")) }
            process.deltaOldGcTimeMs?.let { add(GbosMeasurement("jvm.gc.old.time.delta", it / 1000.0, "s", "last")) }
        },
    )

    internal fun processRole(processType: String): String = when (processType) {
        "GRADLE_DAEMON" -> "gradle-daemon"
        "KOTLIN_DAEMON" -> "kotlin-daemon"
        "KOTLIN_TOOLCHAIN" -> "kotlin-toolchain"
        "GRADLE_WORKER" -> "gradle-worker"
        "GRADLE_CLIENT" -> "gradle-client"
        else -> processType.ifBlank { "unknown" }.lowercase().replace('_', '-')
    }
}
