package app.keepfin.ui.updates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone asks the shared core: only the stored version decides, never how the APK was installed. */
class WhatsNewRuleTest {
    @Test fun anUpdateShowsIt() {
        assertTrue(whatsNewDue("1.0.2", "1.1.0"))
        assertTrue(whatsNewDue("1.1.0-beta.4", "1.1.0"))
    }

    @Test fun aFreshInstallTheSameVersionAndADowngradeShowNothing() {
        assertFalse(whatsNewDue(null, "1.1.0"))
        assertFalse(whatsNewDue("1.1.0", "1.1.0"))
        assertFalse(whatsNewDue("1.1.0", "1.0.2"))
    }
}
