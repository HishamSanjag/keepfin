package app.keepfin

import app.keepfin.core.gallery.GalleryRead
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

class PublicPhotosTest {
    private val cases: List<JSONObject> by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "fixtures/engine/gallery/public.json").isFile) dir = dir.parentFile
        val array = JSONObject(File(dir ?: error("fixtures/engine/gallery/public.json not found"), "fixtures/engine/gallery/public.json").readText()).getJSONArray("cases")
        (0 until array.length()).map { array.getJSONObject(it) }
    }

    private fun answer(name: String): Pair<String, String> {
        val input = cases.first { it.getString("name") == name }.getJSONObject("input")
        return input.getString("answer") to input.getString("url")
    }

    // What the engine prints: a notice line, then the answer as one base64 line.
    private fun printed(answer: String) =
        "[Instagram] Dumping request to https://www.instagram.com/api/graphql\n" + Base64.getEncoder().encodeToString(answer.toByteArray(Charsets.UTF_8)) + "\n[download] done"

    @Test fun aPrintedAnswerBecomesThePost() {
        val (json, url) = answer("carousel-of-photos")
        val post = PublicPhotos.read(printed(json), url)
        assertNotNull(post)
        assertEquals(4, post!!.count)
        assertEquals(listOf(1, 2, 3, 4), post.items.map { it.position })
    }

    @Test fun anythingElseGoesTheUsualWay() {
        val (video, url) = answer("one-video-is-the-engines")
        assertNull(PublicPhotos.read(printed(video), url))
        assertNull(PublicPhotos.read("nothing here", url))
        assertNull(PublicPhotos.read("[Instagram] Dumping request to https://www.instagram.com/api/graphql\n%%%not base64%%%", url))
    }

    @Test fun theChosenPositionsBecomeTheirLinks() {
        val (json, url) = answer("carousel-of-photos")
        val post = PublicPhotos.read(printed(json), url)!!
        assertEquals(listOf(post.items[1].url, post.items[3].url), PublicPhotos.links(post, 4, listOf(2, 4)))
        assertEquals(post.items.map { it.url }, PublicPhotos.links(post, 4, null))
    }

    @Test fun aChoiceThatNoLongerFitsThePostIsNotUsed() {
        val (json, url) = answer("carousel-of-photos")
        val post = PublicPhotos.read(printed(json), url)!!
        assertNull(PublicPhotos.links(post, 5, null))
        assertNull(PublicPhotos.links(post, 4, listOf(9)))
        assertNull(PublicPhotos.links(post, 4, emptyList()))
    }

    @Test fun theOptionsAskForThePagesWithoutFormats() {
        assertTrue(PublicPhotos.OPTIONS.containsAll(listOf("--dump-pages", "--skip-download", "--ignore-no-formats-error")))
    }

    @Test fun directLinksAreSavedUnderTheirOwnNames() {
        val args = GalleryLogic.directArgs("/data/photos-1", listOf("https://a/1.jpg", "https://a/2.jpg"))
        assertEquals(listOf("-D", "/data/photos-1", "-f", "{filename}.{extension}", "--", "https://a/1.jpg", "https://a/2.jpg"), args.takeLast(7))
        assertTrue("--cookies" !in args)
    }
}
