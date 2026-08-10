package io.github.cdsap.daemonitor.update

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallationLocatorTest {

    @Test
    fun `detects macOS app bundle installs`() {
        val info = InstallationLocator.current(
            platform = DesktopPlatform.MACOS,
            architecture = CpuArchitecture.ARM64,
            executable = "/Applications/Daemonitor.app/Contents/MacOS/Daemonitor",
            javaHome = "/unused",
            classpath = "unused",
        )

        assertEquals(InstallationKind.MACOS_APP_BUNDLE, info.kind)
        assertEquals(Path.of("/Applications/Daemonitor.app"), info.installRoot)
        assertTrue(info.supportsAutomaticUpdate)
        assertEquals(listOf("/usr/bin/open", "-n", "/Applications/Daemonitor.app"), info.relaunchCommand)
    }

    @Test
    fun `detects windows app directory installs`() {
        val info = InstallationLocator.current(
            platform = DesktopPlatform.WINDOWS,
            architecture = CpuArchitecture.X64,
            executable = "C:/Users/demo/AppData/Local/Daemonitor/Daemonitor.exe",
            javaHome = "C:/unused",
            classpath = "unused",
        )

        assertEquals(InstallationKind.WINDOWS_APP_DIR, info.kind)
        assertEquals(Path.of("C:/Users/demo/AppData/Local/Daemonitor"), info.installRoot)
        assertTrue(info.supportsAutomaticUpdate)
    }

    @Test
    fun `marks linux package managed installs as manual only`() {
        val info = InstallationLocator.current(
            platform = DesktopPlatform.LINUX,
            architecture = CpuArchitecture.X64,
            executable = "/usr/lib/daemonitor/bin/Daemonitor",
            javaHome = "/unused",
            classpath = "unused",
            packageManagedProbe = { true },
        )

        assertEquals(InstallationKind.LINUX_PACKAGE_MANAGED, info.kind)
        assertFalse(info.supportsAutomaticUpdate)
        assertTrue(info.manualUpdateReason!!.contains("package manager"))
    }

    @Test
    fun `marks java classpath launches as development`() {
        val info = InstallationLocator.current(
            platform = DesktopPlatform.MACOS,
            architecture = CpuArchitecture.ARM64,
            executable = "/opt/homebrew/opt/openjdk/bin/java",
            javaHome = "/opt/homebrew/opt/openjdk",
            classpath = "build/classes",
        )

        assertEquals(InstallationKind.DEVELOPMENT, info.kind)
        assertFalse(info.supportsAutomaticUpdate)
    }
}
