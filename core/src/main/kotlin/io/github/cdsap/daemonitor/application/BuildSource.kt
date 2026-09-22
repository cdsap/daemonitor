package io.github.cdsap.daemonitor.application

import io.github.cdsap.daemonitor.domain.model.Build

/**
 * Port for reading confirmed builds from an external core (e.g. Go `GET /v1/builds`)
 * instead of correlating daemon-log tails in-process.
 */
interface BuildSource {
    fun recentBuilds(limit: Int = 100): List<Build>
}
