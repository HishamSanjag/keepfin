package app.keepfin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {
    @Test fun stableVersionsCompareNumerically() {
        assertTrue(UpdateRelease.newer("0.4.10", "0.4.2"))
        assertFalse(UpdateRelease.newer("0.4.2", "0.4.2"))
        assertFalse(UpdateRelease.newer("0.4.1", "0.4.2"))
    }

    @Test fun aBetaIsBetweenThePreviousAndItsFinalRelease() {
        // Beta testers are offered the final release, never the older stable one.
        assertTrue(UpdateRelease.newer("0.5.0", "0.5.0-beta.1"))
        assertFalse(UpdateRelease.newer("0.4.2", "0.5.0-beta.1"))
        assertTrue(UpdateRelease.newer("0.5.0-beta.2", "0.5.0-beta.1"))
        assertFalse(UpdateRelease.newer("0.5.0-beta.1", "0.5.0"))
    }
}
