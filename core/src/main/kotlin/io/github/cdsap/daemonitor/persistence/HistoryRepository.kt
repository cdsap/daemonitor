package io.github.cdsap.daemonitor.persistence

import io.github.cdsap.daemonitor.application.HistoryEntry

/** Port for the mixed retained build and observation-session history. */
interface HistoryRepository {
    fun searchHistory(query: String, limit: Long = 50L): List<HistoryEntry>
}
