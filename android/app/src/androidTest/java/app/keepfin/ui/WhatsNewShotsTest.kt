package app.keepfin.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.ArtistRelease
import app.keepfin.FollowedArtist
import app.keepfin.FollowingState
import app.keepfin.InspectResult
import app.keepfin.JobStatus
import app.keepfin.PhotoPosts
import app.keepfin.core.gallery.Gallery
import app.keepfin.core.gallery.GalleryRead
import app.keepfin.ui.preview.Fixtures
import app.keepfin.ui.settings.WhereFilesGroup
import app.keepfin.where.PlaceKind
import app.keepfin.where.WhereFilesUi
import app.keepfin.where.WherePlace
import app.keepfin.where.WhereTap
import app.keepfin.ui.theme.KeepfinTheme
import app.keepfin.ui.theme.ThemeMode
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Takes the pictures of the What's new pages from the real screens over fake data. Skipped unless run with
 * `-Pandroid.testInstrumentationRunnerArguments.whatsnew=true`; they land in shared Pictures (Pictures/kf-whatsnew, pulled with adb).
 */
@RunWith(AndroidJUnit4::class)
class WhatsNewShotsTest {
    @get:Rule val rule = createComposeRule()

    @Before fun only() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("whatsnew") == "true")
        StudioEngine.override = null
    }
    @After fun tearDown() { StudioEngine.override = null }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Saves [node] as a PNG; [crop] picks the part to keep as fractions (left, top, width, height) of the node. */
    private fun shot(name: String, node: SemanticsNodeInteraction? = rule.onRoot(), crop: List<Float> = listOf(0f, 0f, 1f, 1f)) {
        rule.waitForIdle()
        Thread.sleep(900)
        // a sheet is a second window, so the whole screen is taken when there is no node
        val full = node?.captureToImage()?.asAndroidBitmap() ?: InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val x = (full.width * crop[0]).toInt(); val y = (full.height * crop[1]).toInt()
        val w = (full.width * crop[2]).toInt().coerceAtMost(full.width - x); val h = (full.height * crop[3]).toInt().coerceAtMost(full.height - y)
        val part = Bitmap.createBitmap(full, x, y, w, h)
        // the app is uninstalled after a run, so the picture goes to shared Pictures
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name.png"); put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/kf-whatsnew")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { part.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** A plain gradient picture in a file: the fixtures' own links point at sites the emulator cannot reach. */
    private fun picture(index: Int): String {
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val hues = floatArrayOf(205f, 20f, 150f, 280f, 45f, 340f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 400f, 400f, android.graphics.Color.HSVToColor(floatArrayOf(hues[index % 6], .55f, .95f)), android.graphics.Color.HSVToColor(floatArrayOf((hues[index % 6] + 40) % 360, .7f, .45f)), Shader.TileMode.CLAMP)
        Canvas(bitmap).drawRect(0f, 0f, 400f, 400f, paint)
        paint.shader = null; paint.color = android.graphics.Color.argb(70, 255, 255, 255)
        Canvas(bitmap).drawCircle(120f + index * 40f, 150f, 90f, paint)
        val file = File(context.cacheDir, "shot-photo-$index.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return "file://${file.absolutePath}"
    }

    @Test fun photos() {
        val link = "https://www.pinterest.com/pin/99360735500167749/"
        val files = (1..6).joinToString(",") { """[3,"${picture(it)}",{"category":"pinterest","extension":"png","count":6}]""" }
        val read = Gallery.read("""[[2,{"category":"pinterest","title":"Harbour at dawn"}],$files]""", link) as GalleryRead.Post
        val h = Harness("empty")
        h.prefs.chooseTheme(ThemeMode.DARK)
        h.engine.inspectResults[link] = InspectResult.Media(PhotoPosts.media(read, link))
        h.show(rule)
        rule.onNodeWithTag(UiTags.LINK_FIELD).performTextInput(link)
        rule.waitForTag(UiTags.PHOTO_GRID)
        rule.onNodeWithTag(UiTags.photoTile(5)).performClick()
        shot("photos-full", null)
    }

    @Test fun signin() {
        val h = Harness("empty")
        h.prefs.chooseTheme(ThemeMode.DARK)
        h.engine.setJobs(listOf(
            Fixtures.job("signed", "Sunset carousel", null, JobStatus.DOWNLOADING, label = "1080p · MP4", url = "https://www.instagram.com/p/Cxample123/", provider = "Instagram", percent = 42f).copy(signedIn = "Signed in", uploader = "harbour.days"),
            Fixtures.job("plain", "Open clip", null, JobStatus.DOWNLOADING, percent = 70f).copy(uploader = "Blender Studio"),
        ))
        h.show(rule)
        rule.waitForTag(UiTags.job("signed"))
        shot("signin-full")
    }

    @Test fun mixes() {
        val h = Harness("library")
        h.prefs.chooseTheme(ThemeMode.DARK)
        val now = System.currentTimeMillis(); val day = 86_400_000L
        val artists = listOf("Blender Studio", "Pale Cassette", "Oren Vale").mapIndexed { n, name -> FollowedArtist("fa-$n", name, name.lowercase(), checkedOnce = true, checkedAt = now - 5 * 60_000L) }
        val l1 = "https://music.youtube.com/playlist?list=OLAKfake1"; val l2 = "https://music.youtube.com/playlist?list=OLAKfake2"
        h.engine.fakeFollowing.value = FollowingState(artists, listOf(ArtistRelease(l1, "fa-0", "Salt Season", "Album", l1, null, now - 3 * day), ArtistRelease(l2, "fa-2", "Night Ferry", "Single", l2, null, now - 10 * day)))
        h.vm.navigate(Destination.HOME)
        h.show(rule)
        rule.waitForTag(UiTags.HOME_SCREEN)
        rule.waitForTag(UiTags.HOME_GRID)
        rule.onNodeWithTag(UiTags.HOME_LIST).performScrollToNode(hasTestTag(UiTags.homeMix("on-repeat")))
        rule.waitForTag(UiTags.homeMix("on-repeat"))
        shot("mixes-a-full")
        rule.onNodeWithTag(UiTags.HOME_LIST).performScrollToNode(hasTestTag(UiTags.RELEASE_CARD))
        rule.waitForTag(UiTags.RELEASE_CARD)
        // drag the list down so the section above the card shows too
        rule.onNodeWithTag(UiTags.HOME_LIST).performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, 330f)); up() }
        shot("mixes-b-full")
    }

    @Test fun arrange() {
        val h = Harness("library")
        h.prefs.chooseTheme(ThemeMode.DARK)
        h.vm.navigate(Destination.HOME)
        h.show(rule)
        rule.waitForTag(UiTags.HOME_SCREEN)
        rule.waitForTag(UiTags.HOME_GRID)
        rule.onNodeWithTag("edit-page").performClick()
        rule.waitForTag("edit-bar")
        shot("arrange-full")
    }

    @Test fun copies() {
        // Settings > Storage > Where your files are: the main storage and the two cloud copies, each with its mark
        val ui = WhereFilesUi(
            ready = true,
            places = listOf(
                WherePlace("this", PlaceKind.THIS_DEVICE, "android", "This phone", "Everything", 13_314_398_618, "up to date", null),
                WherePlace("copy:c1", PlaceKind.CLOUD, "drive", "Google Drive", "Everything", 12_900_000_000, "up to date 4 min ago", WhereTap.Copy("c1"), "Backup"),
                WherePlace("copy:c2", PlaceKind.CLOUD, "dropbox", "Dropbox", "Everything", 12_900_000_000, "copying · 40 of 210", WhereTap.Copy("c2"), "Backup"),
            ),
            singles = emptyList(),
        )
        rule.setContent { KeepfinTheme(mode = ThemeMode.DARK) { Column(Modifier.background(androidx.compose.material3.MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())) { WhereFilesGroup(ui, {}, {}) } } }
        shot("copies-full", rule.onNodeWithTag("where-files-group"))
    }
}
