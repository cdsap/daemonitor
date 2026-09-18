package io.github.cdsap.daemonitor

import oshi.SystemInfo
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Slf4jBindingTest {
    @Test
    fun `oshi initialization does not print SLF4J provider warnings`() {
        val captured = ByteArrayOutputStream()
        val previous = System.err
        System.setErr(PrintStream(captured, true))
        try {
            // First SLF4J use in-process emits the provider/NOP warnings when unbound.
            SystemInfo()
        } finally {
            System.setErr(previous)
        }

        val err = captured.toString()
        assertFalse(err.contains("No SLF4J providers were found"), err)
        assertFalse(err.contains("Defaulting to no-operation (NOP) logger implementation"), err)
        assertFalse(err.contains("https://www.slf4j.org/codes.html#noProviders"), err)
    }

    @Test
    fun `slf4j nop binding is on the runtime classpath`() {
        val resource = Thread.currentThread().contextClassLoader
            .getResources("org/slf4j/nop/NOPServiceProvider.class")
            .toList()
        assertTrue(resource.isNotEmpty(), "Expected slf4j-nop provider on the runtime classpath")
    }
}
