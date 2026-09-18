package io.github.cdsap.daemonitor.application

import io.github.cdsap.daemonitor.domain.model.Build

/** Port for persisting confirmed builds produced by polling aggregation. */
interface BuildRepository {
    fun save(build: Build)
}
