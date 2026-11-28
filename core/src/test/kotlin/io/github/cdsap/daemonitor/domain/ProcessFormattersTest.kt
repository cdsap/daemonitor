package io.github.cdsap.daemonitor.domain

import io.github.cdsap.daemonitor.domain.model.ProcessType
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProcessFormattersTest {
    @Test
    fun `uptime boundaries and future start`() {
        for ((seconds, expected) in listOf(-1L to "0s", 45L to "45s", 723L to "12m 03s", 11220L to "3h 07m", 187200L to "2d 4h")) {
            assertEquals(expected, formatUptime(0, seconds * 1000))
        }
    }

    @Test
    fun `bytes use binary thresholds and stable decimal separator`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            for ((bytes, expected) in listOf(0L to "0 B", 1023L to "1023 B", 1024L to "1.0 KB", 1572864L to "1.5 MB", 1073741824L to "1.0 GB")) {
                assertEquals(expected, formatBytes(bytes))
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `project basename supports both separators and roots`() {
        assertEquals("demo", projectBasename(" /work/demo/ "))
        assertEquals("demo", projectBasename("C:\\work\\demo\\"))
        assertEquals("/", projectBasename("/"))
        assertNull(projectBasename(" "))
        assertNull(projectBasename(null))
    }

    @Test
    fun `process labels remain shared across displays`() {
        assertEquals("Bazel server", ProcessType.BAZEL_SERVER.displayLabel())
        assertEquals("Unknown Gradle worker", ProcessType.UNKNOWN_GRADLE_WORKER.displayLabel())
    }
}
