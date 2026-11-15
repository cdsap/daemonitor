package io.github.cdsap.daemonitor.coreipc

internal data class GoCoreHealthSnapshot(
    val databasePath: String?,
    val retentionMs: Long?,
    val pid: Long?,
)
