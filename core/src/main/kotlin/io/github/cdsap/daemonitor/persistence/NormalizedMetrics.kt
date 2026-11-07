package io.github.cdsap.daemonitor.persistence

import io.github.cdsap.daemonitor.domain.model.ProcessType

/** Stable process identity. The id must include process start identity, not only the PID. */
data class ProcessInstance(
    val instanceId: String,
    val pid: Long,
    val processType: ProcessType,
    val configJson: String? = null,
    val firstObservedAtMs: Long,
    val lastObservedAtMs: Long,
)

data class MetricDefinition(
    val metricId: String,
    val name: String,
    val unit: String?,
    val kind: String,
    val temporality: String,
    val source: String,
    val platform: String? = null,
)

data class MetricSample(
    val timestampMs: Long,
    val instanceId: String,
    val scopeId: String?,
    val metricId: String,
    val intValue: Long?,
    val realValue: Double?,
    val source: String,
    val sampledAtMs: Long,
) {
    init {
        require((intValue == null) != (realValue == null)) {
            "exactly one of intValue or realValue must be set"
        }
    }
}

data class ProcessEvent(
    val instanceId: String,
    val timestampMs: Long,
    val eventType: String,
    val metadataJson: String?,
    val source: String,
)

data class ResourceScope(
    val scopeId: String,
    val scopeType: String,
    val identity: String,
    val metadataJson: String?,
    val firstObservedAtMs: Long,
    val lastObservedAtMs: Long,
)

data class CollectionCapability(
    val capabilityId: String,
    val name: String,
    val source: String,
    val supported: Boolean,
    val available: Boolean,
    val detailsJson: String?,
    val updatedAtMs: Long,
)

/** A metric reading independent of whether it came from the legacy or normalized store. */
data class NormalizedMetricSample(
    val timestampMs: Long,
    val instanceId: String,
    val metricId: String,
    val intValue: Long?,
    val realValue: Double?,
    val source: String,
    val sampledAtMs: Long,
)
