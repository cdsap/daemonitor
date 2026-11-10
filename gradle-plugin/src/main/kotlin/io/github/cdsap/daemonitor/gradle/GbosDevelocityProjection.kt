package io.github.cdsap.daemonitor.gradle

import io.github.cdsap.gbos.core.GbosAttributeValue
import io.github.cdsap.gbos.core.GbosJson
import io.github.cdsap.gbos.core.GbosMeasurement
import io.github.cdsap.gbos.core.GbosObservation
import io.github.cdsap.gbos.core.GbosProducer

internal object GbosDevelocityProjection {
    const val SCHEMA_VERSION = "1.0.0"
    const val PRODUCER_NAME = "daemonitor"
    const val PRODUCER_VERSION = "0.1.0"
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
            "jvm.process.role" to GbosAttributeValue.Text("gradle-daemon"),
            "process.association" to GbosAttributeValue.Text(process.association),
        ),
        measurements = listOf(
            GbosMeasurement("jvm.process.memory.rss", process.peakRssMb * 1024.0 * 1024.0, "By", "max"),
            GbosMeasurement("jvm.process.memory.rss.first", process.firstRssMb * 1024.0 * 1024.0, "By", "first"),
            GbosMeasurement("jvm.process.memory.rss.last", process.lastRssMb * 1024.0 * 1024.0, "By", "last"),
            GbosMeasurement("jvm.process.memory.rss.delta", process.deltaRssMb * 1024.0 * 1024.0, "By", "last"),
        ),
    )
}
