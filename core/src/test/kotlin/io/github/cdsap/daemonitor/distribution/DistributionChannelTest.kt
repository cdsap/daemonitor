package io.github.cdsap.daemonitor.distribution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DistributionChannelTest {
    @Test
    fun `parses direct and app store aliases`() {
        assertEquals(DistributionChannel.DIRECT, DistributionChannel.parse(null))
        assertEquals(DistributionChannel.DIRECT, DistributionChannel.parse(""))
        assertEquals(DistributionChannel.DIRECT, DistributionChannel.parse("direct"))
        assertEquals(DistributionChannel.APP_STORE, DistributionChannel.parse("APP_STORE"))
        assertEquals(DistributionChannel.APP_STORE, DistributionChannel.parse("appstore"))
        assertEquals(DistributionChannel.APP_STORE, DistributionChannel.parse("MAC_APP_STORE"))
        assertEquals(DistributionChannel.DIRECT, DistributionChannel.parse("unknown"))
    }

    @Test
    fun `only direct channel uses the GitHub updater`() {
        assertTrue(DistributionChannel.DIRECT.usesGitHubUpdater)
        assertFalse(DistributionChannel.APP_STORE.usesGitHubUpdater)
    }
}
