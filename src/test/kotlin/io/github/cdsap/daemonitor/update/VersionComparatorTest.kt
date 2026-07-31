package io.github.cdsap.daemonitor.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionComparatorTest {

    @Test
    fun `detects newer semantic versions with optional v prefix`() {
        assertTrue(VersionComparator.isNewer("v1.0.3", "1.0.2"))
        assertTrue(VersionComparator.isNewer("1.1.0", "1.0.9"))
    }

    @Test
    fun `does not report same or older versions as newer`() {
        assertFalse(VersionComparator.isNewer("v1.0.2", "1.0.2"))
        assertFalse(VersionComparator.isNewer("1.0.1", "1.0.2"))
    }

    @Test
    fun `compares missing patch values as zero`() {
        assertFalse(VersionComparator.isNewer("1.0", "1.0.0"))
        assertTrue(VersionComparator.isNewer("1.0.1", "1.0"))
    }
}
