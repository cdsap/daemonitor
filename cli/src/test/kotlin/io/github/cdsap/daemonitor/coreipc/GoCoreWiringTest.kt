package io.github.cdsap.daemonitor.coreipc

import io.github.cdsap.daemonitor.application.MonitoringMode
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoCoreWiringTest {
    @Test
    fun `null socket keeps jvm collector defaults`() {
        val wiring = wireGoCore(null, Path("/tmp/watcher.db"))
        assertNull(wiring.processSource)
        assertNull(wiring.logSource)
        assertTrue(wiring.mode is MonitoringMode.Local)
        assertNull(wiring.banner)
    }

    @Test
    fun `matching default db skips build import and sample writes when health unreachable`() {
        val db = Path("/tmp/Daemonitor/watcher.db").toAbsolutePath().normalize()
        val wiring = wireGoCore(
            coreSocket = Path("/tmp/missing-cored.sock"),
            databasePath = db,
            defaultDatabase = db,
        )
        assertNotNull(wiring.processSource)
        assertNotNull(wiring.logSource)
        assertTrue(wiring.mode is MonitoringMode.SharedCoreOwnedPersistence)
        val sharedBanner = assertNotNull(wiring.banner)
        assertTrue(sharedBanner.contains("shared DB"))
        assertFalse(sharedBanner.contains("unavailable on Go path"))
    }

    @Test
    fun `separate db keeps http build import when health unreachable`() {
        val local = Path("/tmp/app.db").toAbsolutePath().normalize()
        val wiring = wireGoCore(
            coreSocket = Path("/tmp/missing-cored.sock"),
            databasePath = local,
            defaultDatabase = Path("/tmp/Daemonitor/watcher.db"),
        )
        assertNotNull(wiring.processSource)
        assertTrue(wiring.mode is MonitoringMode.RemoteBuilds)
        val banner = assertNotNull(wiring.banner)
        assertFalse(banner.contains("shared DB"))
        assertFalse(banner.contains("unavailable on Go path"))
    }
}
