package io.github.cdsap.daemonitor.update

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopPlatformTest {

    @Test
    fun `maps common os names to release asset suffixes`() {
        assertEquals(DesktopPlatform.MACOS, DesktopPlatform.current("Mac OS X"))
        assertEquals(DesktopPlatform.WINDOWS, DesktopPlatform.current("Windows 11"))
        assertEquals(DesktopPlatform.LINUX, DesktopPlatform.current("Linux"))
        assertEquals(DesktopPlatform.UNKNOWN, DesktopPlatform.current("Solaris"))
    }
}
