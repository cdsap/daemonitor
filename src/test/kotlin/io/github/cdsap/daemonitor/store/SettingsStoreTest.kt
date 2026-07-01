package io.github.cdsap.daemonitor.store

import io.github.cdsap.daemonitor.Defaults
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsStoreTest {

    @Test
    fun `missing file returns defaults`(@TempDir tmp: Path) {
        val store = SettingsStore(tmp.resolve("settings.properties"))
        assertEquals(Settings(), store.load())
    }

    @Test
    fun `save then load round-trips retention`(@TempDir tmp: Path) {
        val path = tmp.resolve("settings.properties")
        SettingsStore(path).save(Settings(retentionDays = 30, appearance = AppearancePreference.DARK))
        assertEquals(Settings(30, AppearancePreference.DARK), SettingsStore(path).load())
    }

    @Test
    fun `out-of-range stored value is clamped on load`(@TempDir tmp: Path) {
        val path = tmp.resolve("settings.properties")
        SettingsStore(path).save(Settings(retentionDays = 9_999))
        assertEquals(Defaults.MAX_RETENTION_DAYS, SettingsStore(path).load().retentionDays)
    }

    @Test
    fun `unknown stored appearance falls back to system`(@TempDir tmp: Path) {
        val path = tmp.resolve("settings.properties")
        java.nio.file.Files.writeString(path, "retentionDays=30\nappearance=sepia\n")
        assertEquals(AppearancePreference.SYSTEM, SettingsStore(path).load().appearance)
    }
}
