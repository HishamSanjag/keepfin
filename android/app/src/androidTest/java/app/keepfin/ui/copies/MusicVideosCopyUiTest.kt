package app.keepfin.ui.copies

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.copies.CopiesUi
import app.keepfin.copies.CopyCardUi
import app.keepfin.copies.CopyDraft
import app.keepfin.copies.CopyFolderCheck
import app.keepfin.copies.CopyHoldsUi
import app.keepfin.copies.CopyPreview
import app.keepfin.copies.CopyStatus
import app.keepfin.copies.CopyVersionUi
import app.keepfin.copies.PeerChoiceUi
import app.keepfin.copies.ReviewAction
import app.keepfin.core.copies.CopyMode
import app.keepfin.ui.theme.KeepfinTheme
import app.keepfin.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Music videos switch of a copy (dev-docs/plans/MUSIC-VIDEOS.md): on a copy's settings and in Add a copy; it shows what the copy does and records only a choice. */
@RunWith(AndroidJUnit4::class)
class MusicVideosCopyUiTest {
    @get:Rule val rule = createComposeRule()

    private class Recorder : CopiesActions {
        val calls: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
        val drafts: MutableList<CopyDraft> = java.util.Collections.synchronizedList(ArrayList())
        override fun sync(placeId: String) {}
        override fun pause(placeId: String) {}
        override fun syncAll() {}
        override fun remove(placeId: String) {}
        override fun resolve(reviewId: String, action: ReviewAction) {}
        override fun edit(placeId: String, name: String?, holds: CopyHoldsUi?, everyHours: Int?, auto: Boolean?, exports: Triple<Boolean, Boolean, Boolean>?) {}
        override fun reconnect(placeId: String, treeUri: String): String? = null
        override fun checkFolder(treeUri: String) = CopyFolderCheck(true, null, "Anna's phone")
        override fun preview(draft: CopyDraft, previousId: String?): CopyPreview { drafts += draft; return CopyPreview("p1", "ready") }
        override fun cancelPreview(placeId: String) {}
        override fun add(placeId: String) {}
        override fun versions(placeId: String) = emptyList<CopyVersionUi>()
        override fun restore(placeId: String, entryId: String) {}
        override fun cleanup(placeId: String) {}
        override fun setCompanions(placeId: String, on: Boolean) { calls += "companions:$placeId:$on" }
        override fun setMusicVideos(placeId: String, on: Boolean) { calls += "musicVideos:$placeId:$on" }
    }

    private fun card(id: String, holdsAll: Boolean, musicVideos: Boolean) = CopyCardUi(
        id, "Card $id", CopyMode.Backup, CopyHoldsUi(all = holdsAll, spaces = if (holdsAll) emptySet() else setOf("music")), "Card / Keepfin", true, CopyStatus.UP_TO_DATE,
        lastVerifiedAt = 1_700_000_000_000, kept = 10, musicVideos = musicVideos,
    )

    private fun show(ui: CopiesUi, actions: CopiesActions) {
        rule.setContent { KeepfinTheme(mode = ThemeMode.LIGHT) { Column(androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) { CopiesContent(ui, actions) } } }
    }

    @Test fun aCopyOfEverythingShowsMusicVideosOnBesideLyricsAndTurnsThemOff() {
        val rec = Recorder()
        show(CopiesUi(true, listOf(card("a", holdsAll = true, musicVideos = true))), rec)
        rule.onNodeWithText("Lyrics, subtitles and info files").performScrollTo().assertExists()
        rule.onNodeWithText("Each song's music video goes with it.").performScrollTo().assertExists()
        rule.onNodeWithTag(CopiesTags.MUSIC_VIDEOS).performScrollTo().assertIsOn().performClick()
        rule.waitUntil(3000) { "musicVideos:a:false" in rec.calls }
        assertTrue("lyrics are a different choice", rec.calls.none { it.startsWith("companions") })
    }

    @Test fun aPartCopyShowsMusicVideosOffAndTheyCanBeTurnedOn() {
        val rec = Recorder()
        show(CopiesUi(true, listOf(card("a", holdsAll = false, musicVideos = false))), rec)
        rule.onNodeWithTag(CopiesTags.MUSIC_VIDEOS).performScrollTo().assertIsOff().performClick()
        rule.waitUntil(3000) { "musicVideos:a:true" in rec.calls }
    }

    private fun toTheHoldStep(rec: Recorder) {
        show(CopiesUi(true, spaces = listOf("music" to "Music"), devices = listOf(PeerChoiceUi("dev-anna", "Anna's phone", online = true, allowed = true))), rec)
        rule.onNodeWithTag(CopiesTags.ADD).performClick()
        rule.onNodeWithTag(CopiesTags.DEVICE).performClick()
        rule.onNodeWithTag(CopiesTags.DEVICE_ROW + "-dev-anna").performClick()
        rule.waitUntil(3000) { runCatching { rule.onNodeWithTag(CopiesTags.NEXT).assertIsEnabled() }.isSuccess }
        rule.onNodeWithTag(CopiesTags.NEXT).performClick()
    }

    private fun throughToThePreview(rec: Recorder) {
        rule.onNodeWithTag(CopiesTags.NEXT).performClick() // the way it stays up to date
        rule.waitUntil(3000) { runCatching { rule.onNodeWithTag(CopiesTags.NEXT).assertIsEnabled() }.isSuccess }
        rule.onNodeWithTag(CopiesTags.NEXT).performClick() // the preview
        rule.waitUntil(5000) { rec.drafts.isNotEmpty() }
    }

    @Test fun addACopyOffersMusicVideosOnForEverythingAndStoresNoChoiceUntilOneIsMade() {
        val rec = Recorder()
        toTheHoldStep(rec)
        rule.onNodeWithText("Music videos").assertExists()
        rule.onNodeWithText("Each song's music video goes with it.").assertExists()
        rule.onNodeWithTag(CopiesTags.WIZARD_MUSIC_VIDEOS).assertIsOn()
        throughToThePreview(rec)
        assertNull("no choice made: the copy follows what it holds", rec.drafts.last().musicVideos)
        assertTrue(rec.drafts.last().holds.all)
    }

    @Test fun addAPartCopyShowsMusicVideosOffUntilChosenOn() {
        val rec = Recorder()
        toTheHoldStep(rec)
        rule.onAllNodes(isSelectable())[1].performClick() // "Only some of it"
        rule.onNodeWithTag(CopiesTags.WIZARD_MUSIC_VIDEOS).assertIsOff()
        rule.onAllNodes(isToggleable())[0].performClick() // the Music space
        rule.onNodeWithTag(CopiesTags.WIZARD_MUSIC_VIDEOS).performClick()
        rule.onNodeWithTag(CopiesTags.WIZARD_MUSIC_VIDEOS).assertIsOn()
        throughToThePreview(rec)
        val draft = rec.drafts.last()
        assertEquals(true, draft.musicVideos)
        assertEquals(setOf("music"), draft.holds.spaces)
    }
}
