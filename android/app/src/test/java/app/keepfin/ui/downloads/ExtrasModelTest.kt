package app.keepfin.ui.downloads

import app.keepfin.DownloadOptions
import app.keepfin.SubtitleOptions
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Extras tab's count: one for each group that differs from the person's defaults (the rule is the core's, shared with Windows). */
class ExtrasModelTest {
    private val base = DownloadOptions()

    @Test fun nothingChangedCountsNone() = assertEquals(0, ExtrasModel.changed(base, base))

    @Test fun eachChangedGroupCountsOnce() {
        val options = base.copy(
            subtitles = SubtitleOptions(enabled = true), sectionStart = 12.0, sponsorBlock = "remove",
            embedThumbnail = false, embedMetadata = false, writeThumbnail = true,
        )
        // subtitles, cut, sponsor, what goes in the file (two switches, one group), also save beside the file
        assertEquals(5, ExtrasModel.changed(options, base))
    }

    @Test fun theQueueChoiceIsOneMore() = assertEquals(2, ExtrasModel.changed(base.copy(sponsorBlock = "mark"), base, queueChanged = true))

    @Test fun aChangeBackToTheDefaultIsNotAChange() = assertEquals(0, ExtrasModel.changed(base.copy(sponsorBlock = "mark").copy(sponsorBlock = "off"), base))

    @Test fun theFileLineNamesWhatIsOn() {
        assertEquals("Cover, tags and chapters are on.", ExtrasModel.inTheFile(base))
        assertEquals("Cover and tags are on.", ExtrasModel.inTheFile(base.copy(embedChapters = false)))
        assertEquals("Tags is on.", ExtrasModel.inTheFile(base.copy(embedThumbnail = false, embedChapters = false)))
        assertEquals("Nothing extra goes in the file.", ExtrasModel.inTheFile(base.copy(embedThumbnail = false, embedMetadata = false, embedChapters = false)))
    }
}
