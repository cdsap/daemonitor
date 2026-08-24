package io.github.cdsap.daemonitor.infrastructure.update

import io.github.cdsap.daemonitor.application.update.UpdateService
import io.github.cdsap.daemonitor.distribution.DistributionChannel
import io.github.cdsap.daemonitor.update.UpdateCheckResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class UpdateServiceForDistributionTest {
    @Test
    fun `direct channel keeps the provided update service`() {
        val direct = UpdateService.inactive()
        assertSame(direct, updateServiceForDistribution(DistributionChannel.DIRECT) { direct })
    }

    @Test
    fun `app store channel reports managed-by-store without contacting GitHub`() = runTest {
        val service = updateServiceForDistribution(DistributionChannel.APP_STORE) {
            error("DIRECT updater must not be constructed for APP_STORE")
        }
        assertEquals(UpdateCheckResult.ManagedByAppStore, service.check())
        assertEquals(null, service.prepare(candidate = unusedCandidate(), onProgress = {}))
    }

    private fun unusedCandidate() = io.github.cdsap.daemonitor.update.UpdateCandidate(
        version = "9.9.9",
        releaseUrl = "https://example.com",
        assetName = "unused.zip",
        downloadUrl = "https://example.com/unused.zip",
    )
}
