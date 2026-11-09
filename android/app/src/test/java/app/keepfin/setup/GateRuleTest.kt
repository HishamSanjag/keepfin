package app.keepfin.setup

import org.junit.Assert.assertEquals
import org.junit.Test

/** GitHub #38: the start pages are a first run only. A chosen folder means the app, whatever else is true. */
class GateRuleTest {
    @Test fun freshInstallShowsTheStartPages() = assertEquals(Gate.FIRST_START, Gate.of(unfinishedRun = false, folderChosen = false, earlierLibrary = false))

    @Test fun anUpgradedInstallWithAFolderNeverShowsThemAgain() {
        assertEquals(Gate.NONE, Gate.of(unfinishedRun = false, folderChosen = true, earlierLibrary = false))
        // even with an earlier library file still lying around
        assertEquals(Gate.NONE, Gate.of(unfinishedRun = false, folderChosen = true, earlierLibrary = true))
    }

    @Test fun anEarlierLibraryWithoutAFolderIsASetupNotAFirstRun() = assertEquals(Gate.SETUP, Gate.of(false, false, true))

    @Test fun anUnfinishedSetupResumes() = assertEquals(Gate.SETUP, Gate.of(unfinishedRun = true, folderChosen = true, earlierLibrary = false))
}
