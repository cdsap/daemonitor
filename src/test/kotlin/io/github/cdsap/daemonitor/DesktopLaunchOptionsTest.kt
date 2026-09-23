package io.github.cdsap.daemonitor

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopLaunchOptionsTest {
    @Test
    fun `parses core socket and db paths`() {
        val options = DesktopLaunchOptions.parse(
            arrayOf("--core-socket", "/tmp/cored.sock", "--db", "/tmp/watcher.db"),
        )
        assertNotNull(options)
        assertEquals(Path("/tmp/cored.sock").toAbsolutePath().normalize(), options.coreSocket)
        assertEquals(Path("/tmp/watcher.db").toAbsolutePath().normalize(), options.databasePath)
    }

    @Test
    fun `parses jvm collector opt-out`() {
        val options = DesktopLaunchOptions.parse(arrayOf("--jvm-collector"))
        assertNotNull(options)
        assertTrue(options.jvmCollector)
        assertTrue(options.goCorePreference() is io.github.cdsap.daemonitor.coreipc.GoCorePreference.JvmCollector)
    }

    @Test
    fun `help mentions jvm collector and default go core`() {
        val options = DesktopLaunchOptions.parse(arrayOf("--help"))
        assertNotNull(options)
        assertTrue(DesktopLaunchOptions.USAGE.contains("--jvm-collector"))
        assertTrue(DesktopLaunchOptions.USAGE.contains("daemonitor-cored"))
    }

    @Test
    fun `help does not require other flags`() {
        val options = DesktopLaunchOptions.parse(arrayOf("--help"))
        assertNotNull(options)
        assertTrue(options.help)
    }

    @Test
    fun `missing core socket value fails`() {
        val error = ByteArrayOutputStream()
        assertNull(DesktopLaunchOptions.parse(arrayOf("--core-socket"), PrintStream(error)))
        assertTrue(error.toString().contains("Missing value for --core-socket"))
    }

    @Test
    fun `unknown option fails`() {
        val error = ByteArrayOutputStream()
        assertNull(DesktopLaunchOptions.parse(arrayOf("--nope"), PrintStream(error)))
        assertTrue(error.toString().contains("Unknown option: --nope"))
    }
}
