package io.github.cdsap.daemonitor.store

import io.github.cdsap.daemonitor.Defaults
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsStoreTest {

    @Test
    fun `missing file returns default retention`(@TempDir tmp: Path) {
        val store = SettingsStore(tmp.resolve("settings.properties"))
        assertEquals(Defaults.DEFAULT_RETENTION_DAYS, store.load().retentionDays)
    }

    @Test
    fun `save then load round-trips retention`(@TempDir tmp: Path) {
        val path = tmp.resolve("settings.properties")
        SettingsStore(path).save(Settings(retentionDays = 30))
        assertEquals(30L, SettingsStore(path).load().retentionDays)
    }

    @Test
    fun `out-of-range stored value is clamped on load`(@TempDir tmp: Path) {
        val path = tmp.resolve("settings.properties")
        SettingsStore(path).save(Settings(retentionDays = 9_999))
        assertEquals(Defaults.MAX_RETENTION_DAYS, SettingsStore(path).load().retentionDays)
    }
}

