package app.keepfin.ui.downloads

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.FormatRequest
import app.keepfin.InspectResult
import app.keepfin.PhotoPosts
import app.keepfin.core.gallery.Gallery
import app.keepfin.core.gallery.GalleryRead
import app.keepfin.ui.Harness
import app.keepfin.ui.StudioEngine
import app.keepfin.ui.UiTags
import app.keepfin.ui.exists
import app.keepfin.ui.waitForTag
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The preview of a photo post (dev-docs/plans/PHOTOS-AND-SIGN-IN.md, option A): the grid, tapping to leave photos out, and the sign-in-needed variant. */
@RunWith(AndroidJUnit4::class)
class PhotoPostUiTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null }

    private val link = "https://www.pinterest.com/pin/99360735500167749/"

    /** A post of four photos, read by the shared core from a listing like the gallery engine prints. */
    private fun photoPost(): InspectResult.Media {
        val files = (1..4).joinToString(",") { """[3,"https://i.pinimg.com/$it.jpg",{"category":"pinterest","extension":"jpg","count":4}]""" }
        val read = Gallery.read("""[[2,{"category":"pinterest","title":"Four photos"}],$files]""", link) as GalleryRead.Post
        return InspectResult.Media(PhotoPosts.media(read, link))
    }

    private fun open(setup: (Harness) -> Unit): Harness {
        val h = Harness("empty")
        setup(h)
        h.show(rule)
        rule.onNodeWithTag(UiTags.LINK_FIELD).performTextInput(link)
        return h
    }

    @Test fun everythingIsChosenAndATapLeavesAPhotoOut() {
        val h = open { it.engine.inspectResults[link] = photoPost() }
        rule.waitForTag(UiTags.PHOTO_GRID)
        rule.onNodeWithTag(UiTags.PHOTO_NOTE).assertTextEquals("4 of 4 chosen. Tap a photo to leave it out.")
        rule.onNodeWithTag(UiTags.DOWNLOAD).assertTextContains("Download 4 photos")
        rule.onNodeWithContentDescription("Photo 3, chosen").assertIsDisplayed()
        val tile = rule.onNodeWithTag(UiTags.photoTile(3)).fetchSemanticsNode().size
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertTrue("a tile is at least 48dp wide and tall: ${tile.width / density} x ${tile.height / density} dp", tile.width / density >= 48f && tile.height / density >= 48f)

        rule.onNodeWithTag(UiTags.photoTile(3)).performClick()
        rule.onNodeWithContentDescription("Photo 3, left out").assertIsDisplayed()
        rule.onNodeWithTag(UiTags.PHOTO_NOTE).assertTextEquals("3 of 4 chosen. Tap a photo to leave it out.")
        rule.onNodeWithTag(UiTags.DOWNLOAD).assertTextContains("Download 3 photos")

        rule.onNodeWithTag(UiTags.photoTile(3)).performClick()
        rule.onNodeWithTag(UiTags.DOWNLOAD).assertTextContains("Download 4 photos")

        rule.onNodeWithTag(UiTags.photoTile(2)).performClick()
        rule.onNodeWithTag(UiTags.DOWNLOAD).performClick()
        rule.waitUntil(5_000) { h.engine.enqueued.isNotEmpty() }
        assertEquals(FormatRequest.Gallery(4, listOf(1, 3, 4)), h.engine.enqueued.single().second)
    }

    @Test fun aPhotoPostOffersDownloadOnAndSendsTheChosenPhotos() {
        val media = photoPost().media
        var choice = app.keepfin.ui.FormatChoice().toggled(2)
        val sent = mutableListOf<Pair<String, FormatRequest>>()
        rule.setContent {
            app.keepfin.ui.theme.KeepfinTheme {
                androidx.compose.foundation.layout.Column {
                    PhotoPreview(
                        media, choice, { choice = it }, null, false, {}, {},
                        targets = listOf(app.keepfin.ui.devices.DownloadTarget("d1", "Laptop")),
                        onDownloadOn = { sent += it.name to choice.request(media) },
                    )
                }
            }
        }
        rule.onNodeWithTag(app.keepfin.ui.devices.DownloadOnTags.BUTTON).assertIsDisplayed().performClick()
        rule.onNodeWithTag(app.keepfin.ui.devices.DownloadOnTags.device("d1")).performClick()
        assertEquals(listOf("Laptop" to FormatRequest.Gallery(4, listOf(1, 3, 4))), sent)
        // what goes over the network is the photos chosen, nothing else
        assertEquals("""{"type":"gallery","items":[1,3,4]}""", app.keepfin.ui.devices.formatJson(sent.single().second).toString())
    }

    @Test fun leavingEveryPhotoOutTurnsDownloadOff() {
        open { it.engine.inspectResults[link] = photoPost() }
        rule.waitForTag(UiTags.PHOTO_GRID)
        for (position in 1..4) rule.onNodeWithTag(UiTags.photoTile(position)).performClick()
        rule.onNodeWithTag(UiTags.DOWNLOAD).assertTextContains("Download 0 photos").assertIsNotEnabled()
        rule.onNodeWithTag(UiTags.PHOTO_NOTE).assertTextEquals("0 of 4 chosen. Tap a photo to choose it.")
        rule.onNodeWithTag(UiTags.photoTile(1)).performClick()
        rule.onNodeWithTag(UiTags.DOWNLOAD).assertTextContains("Download 1 photo").assertIsEnabled()
    }

    @Test fun anyLinkTheSiteShowsOnlyToSignedInPeopleOffersSigningInThere() {
        // not a photo post: the download engine's own failure for a link that needs a sign-in
        open { it.engine.inspectFailure = "ERROR: [Pinterest] 99360735500167749: This pin is only visible when you are logged in. Sign in required." }
        rule.waitForTag(UiTags.SIGN_IN_NEEDED)
        rule.onNodeWithText("Pinterest shows this post only to signed-in people.").assertIsDisplayed()
        rule.onNodeWithText("Sign in once and Keepfin uses it for Pinterest links from now on. Your password stays with Pinterest.").assertIsDisplayed()
        rule.onNodeWithTag(UiTags.SIGN_IN_SITE).assertTextContains("Sign in to Pinterest")
        assertFalse("nothing can be downloaded until signed in", rule.exists(UiTags.DOWNLOAD))
        // the button opens the in-app sign-in at that site, not the list of sites
        rule.onNodeWithTag(UiTags.SIGN_IN_SITE).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Sign in · Pinterest").fetchSemanticsNodes().isNotEmpty() }
        assertFalse("the general list of sites is skipped", rule.onAllNodesWithText("Choose a site").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun aSignInWallOffersSigningInInsteadOfDownloading() {
        open { it.engine.inspectSignInSite = "Instagram" }
        rule.waitForTag(UiTags.SIGN_IN_NEEDED)
        rule.onNodeWithText("Instagram shows this post only to signed-in people.").assertIsDisplayed()
        rule.onNodeWithTag(UiTags.SIGN_IN_SITE).assertTextContains("Sign in to Instagram")
        assertFalse("nothing can be downloaded until signed in", rule.exists(UiTags.DOWNLOAD))
        rule.onNodeWithTag(UiTags.SIGN_IN_NOT_NOW).performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.SIGN_IN_NEEDED) }
    }
}
