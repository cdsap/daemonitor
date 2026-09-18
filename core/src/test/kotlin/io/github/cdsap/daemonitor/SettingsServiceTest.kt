package io.github.cdsap.daemonitor

import io.github.cdsap.daemonitor.persistence.AppearancePreference
import io.github.cdsap.daemonitor.persistence.RetentionRepository
import io.github.cdsap.daemonitor.persistence.Settings
import io.github.cdsap.daemonitor.persistence.SettingsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsServiceTest {
    @Test
    fun `load returns persisted settings`() {
        val store = FakeSettingsRepository(
            Settings(
                retentionDays = 30,
                appearance = AppearancePreference.DARK,
                mcpEnabled = true,
                mcpPort = 18_123,
                mcpToken = "token",
            ),
        )
        val service = SettingsService(store, FakeRetentionRepository())

        val loaded = service.load()
        assertEquals(30, loaded.retentionDays)
        assertEquals(AppearancePreference.DARK, loaded.appearance)
        assertTrue(loaded.mcpEnabled)
        assertEquals(18_123, loaded.mcpPort)
        assertEquals("token", loaded.mcpToken)
    }

    @Test
    fun `updateRetention persists and purges older rows`() {
        val store = FakeSettingsRepository()
        val retention = FakeRetentionRepository()
        val now = 100L * 24 * 60 * 60 * 1000
        val service = SettingsService(store, retention, clock = { now })

        val updated = service.updateRetention(7)

        assertEquals(7, updated.retentionDays)
        assertEquals(7, store.load().retentionDays)
        assertEquals(listOf(now to 7L), retention.purges)
    }

    @Test
    fun `updateAppearance persists preference`() {
        val store = FakeSettingsRepository()
        val service = SettingsService(store, FakeRetentionRepository())

        val updated = service.updateAppearance(AppearancePreference.LIGHT)

        assertEquals(AppearancePreference.LIGHT, updated.appearance)
        assertEquals(AppearancePreference.LIGHT, store.load().appearance)
    }

    private class FakeSettingsRepository(
        initial: Settings = Settings(),
    ) : SettingsRepository {
        private var current = initial

        override fun load(): Settings = current

        override fun save(settings: Settings) {
            current = settings
        }
    }

    private class FakeRetentionRepository : RetentionRepository {
        val purges = mutableListOf<Pair<Long, Long>>()

        override fun purgeOlderThan(nowMs: Long, retentionDays: Long) {
            purges += nowMs to retentionDays
        }
    }
}
