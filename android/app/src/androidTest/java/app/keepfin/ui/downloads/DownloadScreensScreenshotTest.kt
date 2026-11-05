package app.keepfin.ui.downloads

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.InspectResult
import app.keepfin.MusicVideoLookup
import app.keepfin.MusicVideoMatch
import app.keepfin.ui.Capture
import app.keepfin.ui.Harness
import app.keepfin.ui.StudioEngine
import app.keepfin.ui.UiTags
import app.keepfin.ui.exists
import app.keepfin.ui.openExtras
import app.keepfin.ui.preview.Fixtures
import app.keepfin.ui.theme.ThemeMode
import app.keepfin.ui.waitForTag
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in (-Pandroid.testInstrumentationRunnerArguments.screenshots=true): the download preview in every case, light and dark, to
 * /data/local/tmp/preview-phone-<case>-<theme>.png.
 */
@RunWith(AndroidJUnit4::class)
class DownloadScreensScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Before fun only() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("screenshots") == "true")
        StudioEngine.override = null
    }
    @After fun tearDown() { StudioEngine.override = null }

    private val video = "https://www.youtube.com/watch?v=aqz-KE-bpKQ"
    private val song = "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"
    private val match = MusicVideoMatch("dQw4w9WgXcQ", "Agent 327 (Official Video)", "Blender Studio", 3_200_000_000L, 215.0)

    private fun shot(name: String, dark: Boolean) {
        rule.waitForIdle()
        Thread.sleep(1500)
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/preview-phone-$name-${if (dark) "dark" else "light"}.png").close()
        Thread.sleep(800)
    }

    private fun harness(dark: Boolean, lookup: MusicVideoLookup = MusicVideoLookup.None, existing: Set<String> = emptySet()): Harness {
        val h = Harness("library")
        h.prefs.chooseTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
        h.engine.inspectResults[song] = InspectResult.Media(Fixtures.spotifyTrack(song).copy(title = existing.firstOrNull() ?: Fixtures.spotifyTrack(song).title))
        h.engine.musicVideoLookup = lookup
        h.engine.fakeLibrary.existingTitles = existing
        h.engine.discoverEntryDelayMs = 0
        h.show(rule)
        return h
    }

    private fun link(link: String) {
        rule.onNodeWithTag(UiTags.LINK_FIELD).performTextInput(link)
        rule.waitForTag(UiTags.REVIEW)
        rule.waitUntil(10_000) { rule.onAllNodesWithTagCount(UiTags.DOWNLOAD) > 0 }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun single(name: String, dark: Boolean) {
        harness(dark)
        link(video)
        shot(name, dark)
        rule.openExtras()
        shot("$name-extras", dark)
    }

    private fun songCase(dark: Boolean) {
        harness(dark, MusicVideoLookup.None)
        link(song)
        rule.waitUntil(8_000) { !rule.exists(UiTags.CAPTURE_VIDEO_LOOKING) }
        shot("song-no-video", dark)
    }

    private fun songBoth(dark: Boolean) {
        harness(dark, MusicVideoLookup.Found(match))
        link(song)
        rule.waitUntil(8_000) { !rule.exists(UiTags.CAPTURE_VIDEO_LOOKING) }
        rule.onNodeWithTag(UiTags.TAB_BOTH).performClick()
        shot("song-both", dark)
    }

    private fun songHave(dark: Boolean) {
        harness(dark, existing = setOf("Sintel Theme"))
        rule.onNodeWithTag(UiTags.LINK_FIELD).performTextInput(song)
        rule.waitForTag(UiTags.CAPTURE_EXISTING)
        shot("song-have", dark)
    }

    private fun collection(link: String, name: String, dark: Boolean) {
        val h = harness(dark)
        rule.onNodeWithTag(UiTags.LINK_FIELD).performTextInput(link)
        rule.waitForTag(UiTags.COLLECTION)
        rule.waitUntil(15_000) { h.vm.collection?.discovering == false && (h.vm.collection?.entries?.size ?: 0) > 0 }
        shot(name, dark)
        rule.openExtras()
        shot("$name-extras", dark)
    }

    private fun several(dark: Boolean) {
        val h = harness(dark)
        rule.runOnIdle { h.vm.loadLink("$video\n$song") }
        rule.waitUntil(10_000) { (h.vm.capture as? Capture.Batch)?.running == false }
        rule.waitForTag(UiTags.REVIEW)
        shot("links", dark)
    }

    private fun reading(dark: Boolean) {
        val h = harness(dark)
        h.engine.inspectDelayMs = 60_000
        rule.onNodeWithTag(UiTags.LINK_FIELD).performTextInput(video)
        rule.waitForTag(UiTags.INSPECTING)
        shot("reading", dark)
    }

    private fun couldNotRead(dark: Boolean) {
        val h = harness(dark)
        h.engine.inspectFailure = "This video is private. Sign in to see it."
        rule.onNodeWithTag(UiTags.LINK_FIELD).performTextInput(video)
        rule.waitForTag(UiTags.CAPTURE_ERROR)
        shot("could-not-read", dark)
    }

    @Test fun videoLight() = single("video", false)
    @Test fun videoDark() = single("video", true)
    @Test fun songLight() = songCase(false)
    @Test fun songDark() = songCase(true)
    @Test fun songBothLight() = songBoth(false)
    @Test fun songBothDark() = songBoth(true)
    @Test fun songHaveLight() = songHave(false)
    @Test fun songHaveDark() = songHave(true)
    @Test fun playlistLight() = collection("https://www.youtube.com/playlist?list=PLblender", "playlist", false)
    @Test fun playlistDark() = collection("https://www.youtube.com/playlist?list=PLblender", "playlist", true)
    @Test fun albumLight() = collection("https://music.youtube.com/playlist?list=OLAKblender", "album", false)
    @Test fun albumDark() = collection("https://music.youtube.com/playlist?list=OLAKblender", "album", true)
    @Test fun linksLight() = several(false)
    @Test fun linksDark() = several(true)
    @Test fun readingLight() = reading(false)
    @Test fun readingDark() = reading(true)
    @Test fun couldNotReadLight() = couldNotRead(false)
    @Test fun couldNotReadDark() = couldNotRead(true)
}
