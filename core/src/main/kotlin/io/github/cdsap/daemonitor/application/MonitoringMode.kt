package io.github.cdsap.daemonitor.application

/**
 * Defines which component owns monitoring persistence for a polling runtime.
 *
 * The remote-build variant owns the source used to import builds, keeping build-source selection
 * and persistence ownership as one valid configuration.
 */
sealed interface MonitoringMode {
    /** Persist samples and aggregate builds from daemon logs locally. */
    data object Local : MonitoringMode

    /** Persist samples locally and import completed builds from the external core. */
    data class RemoteBuilds(val source: BuildSource) : MonitoringMode

    /** The shared core owns both sample and build persistence. */
    data object SharedCoreOwnedPersistence : MonitoringMode
}
