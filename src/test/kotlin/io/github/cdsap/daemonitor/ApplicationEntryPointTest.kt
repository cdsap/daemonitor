package io.github.cdsap.daemonitor

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertTrue

class ApplicationEntryPointTest {
    @Test
    fun `application exposes a named static entry point`() {
        val entryPoint = Class.forName("io.github.cdsap.daemonitor.Daemonitor")
            .getMethod("main", Array<String>::class.java)

        assertTrue(Modifier.isPublic(entryPoint.modifiers))
        assertTrue(Modifier.isStatic(entryPoint.modifiers))
    }

    @Test
    fun `headless mode exposes a named static entry point`() {
        val entryPoint = Class.forName("io.github.cdsap.daemonitor.DaemonitorHeadless")
            .getMethod("main", Array<String>::class.java)

        assertTrue(Modifier.isPublic(entryPoint.modifiers))
        assertTrue(Modifier.isStatic(entryPoint.modifiers))
    }
}
