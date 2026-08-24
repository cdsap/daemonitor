package io.github.cdsap.daemonitor.platform

import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class AppDirectoriesTest {

    @Test
    fun `discovers macOS application support paths`() {
        val dirs = AppDirectories.discover(
            home = "/Users/ada",
            osName = "Mac OS X",
            getenv = { null },
        )
        assertEquals(Path("/Users/ada/Library/Application Support/Daemonitor"), dirs.appSupportDir)
        assertEquals(Path("/Users/ada/Library/Application Support/Daemonitor/watcher.db"), dirs.databasePath)
        assertEquals(
            Path("/Users/ada/Library/Application Support/Daemonitor/settings.properties"),
            dirs.settingsPath,
        )
        assertEquals(Path("/Users/ada/.gradle"), dirs.gradleUserHome)
        assertEquals(Path("/Users/ada/Library/Application Support/Daemonitor/updates"), dirs.updatesDirectory)
    }

    @Test
    fun `discovers Windows LOCALAPPDATA when set`() {
        val dirs = AppDirectories.discover(
            home = "C:\\Users\\ada",
            osName = "Windows 11",
            getenv = { key -> if (key == "LOCALAPPDATA") "D:\\LocalAppData" else null },
        )
        assertEquals(Path("D:\\LocalAppData", "Daemonitor"), dirs.appSupportDir)
        assertEquals(Path("D:\\LocalAppData", "Daemonitor", "watcher.db"), dirs.databasePath)
        assertEquals(Path("C:\\Users\\ada", ".gradle"), dirs.gradleUserHome)
    }

    @Test
    fun `falls back to AppData Local on Windows without LOCALAPPDATA`() {
        val dirs = AppDirectories.discover(
            home = "C:\\Users\\ada",
            osName = "Windows 11",
            getenv = { null },
        )
        assertEquals(Path("C:\\Users\\ada", "AppData", "Local", "Daemonitor"), dirs.appSupportDir)
    }

    @Test
    fun `discovers Linux XDG_DATA_HOME when set`() {
        val dirs = AppDirectories.discover(
            home = "/home/ada",
            osName = "Linux",
            getenv = { key -> if (key == "XDG_DATA_HOME") "/var/data" else null },
        )
        assertEquals(Path("/var/data/Daemonitor"), dirs.appSupportDir)
        assertEquals(Path("/home/ada/.gradle"), dirs.gradleUserHome)
    }

    @Test
    fun `falls back to local share on Linux without XDG_DATA_HOME`() {
        val dirs = AppDirectories.discover(
            home = "/home/ada",
            osName = "Linux",
            getenv = { null },
        )
        assertEquals(Path("/home/ada/.local/share/Daemonitor"), dirs.appSupportDir)
    }
}
