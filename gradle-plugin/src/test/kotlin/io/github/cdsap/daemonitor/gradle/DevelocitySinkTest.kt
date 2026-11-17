package io.github.cdsap.daemonitor.gradle

import groovy.lang.Closure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.gradle.api.Action

class DevelocitySinkTest {
    @Test
    fun `selects Action buildFinished overload over Closure`() {
        val method = DevelocitySink.findBuildFinished(DualBuildFinished::class.java)
        assertNotNull(method)
        assertEquals(Action::class.java, method.parameterTypes[0])
    }

    @Test
    fun `selects String String value overload`() {
        val method = DevelocitySink.findValueMethod(DualValue::class.java)
        assertNotNull(method)
        assertTrue(method.parameterTypes.all { it == String::class.java })
    }

    /** Mirrors Develocity decorated buildScan: Closure often sorts before Action in Class.methods. */
    @Suppress("unused")
    private class DualBuildFinished {
        fun buildFinished(closure: Closure<*>) {}

        fun buildFinished(action: Action<*>) {}
    }

    @Suppress("unused")
    private class DualValue {
        fun value(key: String, value: Int) {}

        fun value(key: String, value: String) {}
    }
}
