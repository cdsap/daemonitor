package io.github.cdsap.daemonitor.coreipc

import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreOwnsDatabaseTest {
    @Test
    fun `compares reported paths after normalization`() {
        val local = Path("/tmp/Daemonitor/../app.db")
        val default = Path("/tmp/default.db")
        assertTrue(coreOwnsDatabase(local, default, Path("/tmp/app.db")))
        assertFalse(coreOwnsDatabase(local, default, Path("/tmp/other.db")))
        assertFalse(coreOwnsDatabase(default, default, Path("/tmp/other.db")))
    }

    @Test
    fun `falls back to normalized default when reported path is absent`() {
        val default = Path("/tmp/Daemonitor/../app.db")
        assertTrue(coreOwnsDatabase(Path("/tmp/app.db"), default, null))
        assertFalse(coreOwnsDatabase(Path("/tmp/other.db"), default, null))
    }
}
