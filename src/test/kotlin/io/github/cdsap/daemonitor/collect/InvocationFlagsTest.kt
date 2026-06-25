package io.github.cdsap.daemonitor.collect

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InvocationFlagsTest {

    @Test
    fun `detects --non-interactive`() {
        assertTrue(InvocationFlags.isNonInteractive("/bin/sh gradlew build --non-interactive"))
    }

    @Test
    fun `detects --console plain in both forms`() {
        assertTrue(InvocationFlags.isNonInteractive("gradlew test --console=plain"))
        assertTrue(InvocationFlags.isNonInteractive("gradlew test --console plain"))
    }

    @Test
    fun `interactive invocation is not flagged`() {
        assertFalse(InvocationFlags.isNonInteractive("/bin/sh gradlew build"))
        assertFalse(InvocationFlags.isNonInteractive("gradlew assemble --info"))
    }

    @Test
    fun `does not match a substring inside another flag`() {
        assertFalse(InvocationFlags.isNonInteractive("gradlew run --non-interactive-disabled=true"))
    }
}
