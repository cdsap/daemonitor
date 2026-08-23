package io.github.cdsap.daemonitor.store

import io.github.cdsap.daemonitor.Defaults
import io.github.cdsap.daemonitor.config.RetentionPolicy
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SettingsStoreTest {

    @Test
    fun `missing file returns defaults`(@TempDir tmp: Path) {
        val store = SettingsStore(tmp.resolve("settings.properties"))
        val settings = store.load()
        assertEquals(RetentionPolicy.DEFAULT.defaultDays, settings.retentionDays)
        assertEquals(AppearancePreference.SYSTEM, settings.appearance)
        assertFalse(settings.mcpEnabled)
        assertEquals(Defaults.DEFAULT_MCP_PORT, settings.mcpPort)
        assertFalse(settings.mcpToken.isBlank())
    }

    @Test
    fun `save then load round-trips settings`(@TempDir tmp: Path) {
        val path = tmp.resolve("settings.properties")
        SettingsStore(path).save(
            Settings(
                retentionDays = 30,
                appearance = AppearancePreference.DARK,
                mcpEnabled = true,
                mcpPort = 18_123,
                mcpToken = "test-token",
            ),
        )
        assertEquals(
            Settings(30, AppearancePreference.DARK, mcpEnabled = true, mcpPort = 18_123, mcpToken = "test-token"),
            SettingsStore(path).load(),
        )
    }

    @Test
    fun `out-of-range stored value is clamped on load`(@TempDir tmp: Path) {
        val path = tmp.resolve("settings.properties")
        SettingsStore(path).save(Settings(retentionDays = 9_999))
        assertEquals(RetentionPolicy.DEFAULT.maxDays, SettingsStore(path).load().retentionDays)
    }

    @Test
    fun `unknown stored appearance falls back to system`(@TempDir tmp: Path) {
        val path = tmp.resolve("settings.properties")
        java.nio.file.Files.writeString(path, "retentionDays=30\nappearance=sepia\n")
        assertEquals(AppearancePreference.SYSTEM, SettingsStore(path).load().appearance)
    }
}
