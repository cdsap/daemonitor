package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.domain.model.Build
import io.github.cdsap.daemonitor.domain.model.FinalStatus
import io.github.cdsap.daemonitor.domain.model.Source
import io.github.cdsap.daemonitor.store.AppearancePreference
import io.github.cdsap.daemonitor.store.SettingsStore
import io.github.cdsap.daemonitor.store.WatcherDatabase
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsServiceTest {
    @Test
    fun `load returns persisted settings`(@TempDir tmp: Path) {
        val store = SettingsStore(tmp.resolve("settings.properties"))
        store.save(
            io.github.cdsap.daemonitor.store.Settings(
                retentionDays = 30,
                appearance = AppearancePreference.DARK,
                mcpEnabled = true,
                mcpPort = 18_123,
                mcpToken = "token",
            ),
        )
        val database = WatcherDatabase.open(tmp.resolve("watcher.db"))
        try {
            val service = SettingsService(store, database)
            val loaded = service.load()
            assertEquals(30, loaded.retentionDays)
            assertEquals(AppearancePreference.DARK, loaded.appearance)
            assertTrue(loaded.mcpEnabled)
            assertEquals(18_123, loaded.mcpPort)
            assertEquals("token", loaded.mcpToken)
        } finally {
            database.close()
        }
    }

    @Test
    fun `updateRetention persists and purges older rows`(@TempDir tmp: Path) {
        val database = WatcherDatabase.open(tmp.resolve("watcher.db"))
        val store = SettingsStore(tmp.resolve("settings.properties"))
        val now = 100L * 24 * 60 * 60 * 1000
        try {
            database.insertBuild(build("old", now - 20L * 24 * 60 * 60 * 1000))
            database.insertBuild(build("recent", now - 1L * 24 * 60 * 60 * 1000))

            val service = SettingsService(store, database, clock = { now })
            val updated = service.updateRetention(7)

            assertEquals(7, updated.retentionDays)
            assertEquals(7, store.load().retentionDays)
            assertEquals(listOf("recent"), HistoryService(database).history().map { it.buildId })
        } finally {
            database.close()
        }
    }

    @Test
    fun `updateAppearance persists preference`(@TempDir tmp: Path) {
        val database = WatcherDatabase.open(tmp.resolve("watcher.db"))
        val store = SettingsStore(tmp.resolve("settings.properties"))
        try {
            val service = SettingsService(store, database)
            val updated = service.updateAppearance(AppearancePreference.LIGHT)
            assertEquals(AppearancePreference.LIGHT, updated.appearance)
            assertEquals(AppearancePreference.LIGHT, store.load().appearance)
        } finally {
            database.close()
        }
    }

    private fun build(id: String, startMs: Long) = Build(
        buildId = id,
        daemonPid = 1,
        daemonIdentity = "uid-1",
        commandLine = "gradlew build",
        workingDirectory = "/p",
        projectPath = "/p",
        startTimeMs = startMs,
        endTimeMs = startMs + 3_000,
        durationSeconds = 3.0,
        peakMemoryMb = 700,
        avgMemoryMb = 600,
        peakCpuPercent = 50.0,
        inferredSource = Source.TERMINAL,
        finalStatus = FinalStatus.SUCCESS,
        logSnippet = "ok",
        agent = null,
        agentProvider = null,
    )
}
