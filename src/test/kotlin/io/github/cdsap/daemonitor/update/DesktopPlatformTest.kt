package io.github.cdsap.daemonitor.update

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopPlatformTest {

    @Test
    fun `maps common os names to platforms`() {
        assertEquals(DesktopPlatform.MACOS, DesktopPlatform.current("Mac OS X"))
        assertEquals(DesktopPlatform.WINDOWS, DesktopPlatform.current("Windows 11"))
        assertEquals(DesktopPlatform.LINUX, DesktopPlatform.current("Linux"))
        assertEquals(DesktopPlatform.UNKNOWN, DesktopPlatform.current("Solaris"))
    }

    @Test
    fun `exposes installer and update package extensions`() {
        assertEquals("dmg", DesktopPlatform.MACOS.installerExtension)
        assertEquals("zip", DesktopPlatform.MACOS.updatePackageExtension)
        assertEquals("msi", DesktopPlatform.WINDOWS.installerExtension)
        assertEquals("zip", DesktopPlatform.WINDOWS.updatePackageExtension)
        assertEquals("deb", DesktopPlatform.LINUX.installerExtension)
        assertEquals("tar.gz", DesktopPlatform.LINUX.updatePackageExtension)
    }
}
