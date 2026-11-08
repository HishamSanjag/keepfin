package app.keepfin

import app.keepfin.core.gallery.Gallery
import app.keepfin.core.gallery.GalleryRead
import app.keepfin.ui.FormatChoice
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PhotoPostsTest {
    private val pin = "https://www.pinterest.com/pin/99360735500167749/"

    // A listing as the gallery engine prints it: a directory message, then one message per file.
    private fun listing(vararg urls: String): String = "[[2,{\"category\":\"pinterest\",\"title\":\"Pin\"}]," +
        urls.joinToString(",") { "[3,\"$it\",{\"category\":\"pinterest\",\"extension\":\"${it.substringAfterLast('.')}\",\"count\":${urls.size}}]" } + "]"

    private fun post(vararg urls: String): MediaInfo =
        PhotoPosts.media(Gallery.read(listing(*urls), pin) as GalleryRead.Post, pin)

    private val three = arrayOf("https://i.pinimg.com/a.jpg", "https://i.pinimg.com/b.jpg", "https://i.pinimg.com/c.mp4")

    @Test fun aListingBecomesPhotoMedia() {
        val media = post(*three)
        assertEquals("Pinterest", media.provider)
        assertEquals("https://i.pinimg.com/a.jpg", media.thumbnail)
        assertTrue(media.video.isEmpty() && media.audio.isEmpty())
        assertEquals(3, media.gallery!!.count)
        assertEquals(listOf(true, true, false), media.gallery!!.items.map { it.photo })
    }

    @Test fun aLoginWallBecomesSignInNeededAndOtherFailuresDoNot() {
        val wall = PhotoPosts.failure("HTTP redirect to login page (https://www.instagram.com/accounts/login/)", "https://www.instagram.com/p/x/")
        assertTrue(wall is SignInNeeded)
        assertEquals("Instagram", (wall as SignInNeeded).site)
        assertEquals("Instagram shows this post only to signed-in people.", wall.message)
        val other = PhotoPosts.failure("HTTP Error 404: Not Found", pin)
        assertFalse(other is SignInNeeded)
        assertFalse(other.message.orEmpty().contains("gallery-dl", ignoreCase = true))
    }

    @Test fun aSignInWallOnAKnownSiteOffersItsLoginPageOtherFailuresAndSitesDoNot() {
        val login = "ERROR: [instagram] DfW56Yoim48: Instagram sent an empty media response. Check if this post is accessible in your browser without being logged-in. If it is not, then use --cookies-from-browser"
        val site = PhotoPosts.signInWall("https://www.instagram.com/p/DfW56Yoim48/", IllegalStateException(login))!!
        assertEquals("instagram", site.key)
        assertEquals("https://www.instagram.com/accounts/login/", site.loginUrl)
        assertEquals("Instagram shows this post only to signed-in people.", PhotoPosts.signInMessage(site.name))
        val video = PhotoPosts.signInWall("https://www.youtube.com/watch?v=abc", IllegalStateException("ERROR: [youtube] abc: Sign in to confirm you're not a bot."))
        assertEquals("youtube", video?.key)
        assertEquals("instagram", PhotoPosts.signInWall("https://www.instagram.com/p/x/", SignInNeeded("Instagram", "wall"))?.key)
        assertNull("a missing post is no sign-in wall", PhotoPosts.signInWall("https://www.instagram.com/p/x/", IllegalStateException("ERROR: HTTP Error 404: Not Found")))
        assertNull("a site Keepfin has no sign-in page for", PhotoPosts.signInWall("https://example.com/v", IllegalStateException(login)))
    }

    @Test fun everythingChosenAsksForAllAndLeavingOutAsksForTheRest() {
        assertEquals(FormatRequest.Gallery(3), PhotoPosts.request(3, PhotoPosts.chosen(3, emptyList())))
        val some = PhotoPosts.request(3, PhotoPosts.chosen(3, listOf(2)))
        assertEquals(FormatRequest.Gallery(3, listOf(1, 3)), some)
        assertNull(PhotoPosts.range(FormatRequest.Gallery(3)))
        assertEquals("1,3", PhotoPosts.range(some))
        assertEquals(2, PhotoPosts.expected(some))
        assertEquals(3, PhotoPosts.expected(FormatRequest.Gallery(3)))
    }

    @Test fun anInvalidRequestIsRefused() {
        for (bad in listOf(FormatRequest.Gallery(0), FormatRequest.Gallery(3, emptyList()), FormatRequest.Gallery(3, listOf(4)), FormatRequest.Gallery(3, listOf(1, 1)))) {
            try { ArgsPolicy.validate(bad); fail("accepted $bad") } catch (_: IllegalArgumentException) { }
        }
        ArgsPolicy.validate(FormatRequest.Gallery(3, listOf(2, 3)))
    }

    @Test fun theChoiceLeavesPhotosOutAndBack() {
        val media = post(*three)
        var choice = FormatChoice().toggled(2)
        assertEquals(listOf(2), choice.leftOut)
        assertEquals(FormatRequest.Gallery(3, listOf(1, 3)), choice.request(media))
        choice = choice.toggled(2)
        assertEquals(FormatRequest.Gallery(3), choice.request(media))
    }

    @Test fun wordsFollowTheApprovedDesign() {
        val photos = post("https://i.pinimg.com/a.jpg", "https://i.pinimg.com/b.jpg")
        assertEquals("2 photos", PhotoPosts.countLabel(photos, listOf(1, 2)))
        assertEquals("1 photo", PhotoPosts.countLabel(photos, listOf(2)))
        assertEquals("0 photos", PhotoPosts.countLabel(photos, emptyList()))
        val mixed = post(*three)
        assertEquals("3 items", PhotoPosts.countLabel(mixed, listOf(1, 2, 3)))
        assertEquals("2 photos", PhotoPosts.countLabel(mixed, listOf(1, 2)))
        assertEquals("6 of 7 chosen. Tap a photo to leave it out.", PhotoPosts.note(6, 7, false))
        assertEquals("0 of 7 chosen. Tap a photo to choose it.", PhotoPosts.note(0, 7, false))
        assertTrue(PhotoPosts.note(7, 7, true).endsWith("so the post may contain more."))
        assertEquals("Photo 3, chosen", PhotoPosts.tileDescription(3, true, true))
        assertEquals("Video 3, left out", PhotoPosts.tileDescription(3, false, false))
        assertEquals("4 of 6 photos", PhotoPosts.savedLabel(4, 6, "6 photos"))
        assertEquals("6 photos", PhotoPosts.savedLabel(6, 6, "4 of 6 photos"))
    }

    @Test fun theJobIsAPhotoJobWithItsOwnLabel() {
        val request = FormatRequest.Gallery(5, listOf(1, 2))
        assertEquals(MediaKind.PHOTOS, ArgsPolicy.kind(request))
        assertEquals("2 photos", Labels.label(request, post(*three)))
        assertNull(Labels.expectedSize(request, post(*three)))
    }

    @Test fun thePhotoRequestSurvivesTheQueueFile() {
        for (request in listOf(FormatRequest.Gallery(4), FormatRequest.Gallery(4, listOf(2, 3)))) {
            val json = JobCodec.encodeFormat(request)
            assertEquals(request, JobCodec.decodeFormat(JSONObject(json.toString())))
        }
        assertEquals(MediaKind.PHOTOS, MediaKind.of("gallery"))
    }

    @Test fun downloadArgumentsNameTheFolderAndTheRange() {
        val args = GalleryLogic.downloadArgs(pin, "/data/photos-1", "1,3", "/data/c.txt")
        assertEquals(listOf("-D", "/data/photos-1", "--range", "1,3", "--", pin), args.takeLast(6))
        assertEquals(listOf("--cookies", "/data/c.txt"), args.subList(5, 7))
        val all = GalleryLogic.downloadArgs(pin, "/data/p", null, null)
        assertFalse("--range" in all || "--cookies" in all)
        assertEquals("--", all[all.size - 2])
    }

    @Test fun savedFilesAreThePathsTheEnginePrints() {
        assertEquals("/data/p/a.jpg", GalleryLogic.savedPath("/data/p/a.jpg"))
        assertEquals("/data/p/a.jpg", GalleryLogic.savedPath("# /data/p/a.jpg"))
        assertNull(GalleryLogic.savedPath("[pinterest][info] done"))
        assertNull(GalleryLogic.savedPath(""))
    }

    @Test fun engineFailuresReadAsErrorLines() {
        assertEquals("ERROR: HTTP redirect to login page", GalleryLogic.errorText("[instagram][error] HTTP redirect to login page", false))
        assertEquals("The download timed out.", GalleryLogic.errorText("", true))
        assertEquals("No images were downloaded from this link.", GalleryLogic.errorText(" ", false))
    }

    // ---- issue #14: Download on..., Save again, already saved, spaces ------------------------------------------------------------

    @Test fun aRequestFromAnotherDeviceMustNameThePhotosOfThePost() {
        assertEquals(FormatRequest.Gallery(6, null), PhotoPosts.fromDevice(6, null))
        assertEquals(FormatRequest.Gallery(6, listOf(2, 5)), PhotoPosts.fromDevice(6, listOf(5, 2)))
        assertEquals(FormatRequest.Gallery(3, null), PhotoPosts.fromDevice(3, listOf(3, 1, 2)))
        for (bad in listOf(listOf(7), listOf(0), listOf(1, 1), emptyList())) {
            try { PhotoPosts.fromDevice(6, bad); fail("accepted $bad") } catch (e: IllegalArgumentException) { assertEquals("The photos chosen on the other device are not in this post.", e.message) }
        }
    }

    private fun photoJob(
        id: String, url: String, request: FormatRequest.Gallery, saves: Map<String, String>, status: JobStatus = JobStatus.COMPLETED, saveError: String? = null, removed: Boolean = false,
    ) = JobRecord(
        Job(
            id = id, url = url, title = "Trip", thumbnail = null, uploader = null, duration = null, provider = "Instagram", label = "photos", kind = MediaKind.PHOTOS, status = status,
            percent = 100f, speed = null, eta = null, downloadedBytes = null, totalBytes = null, expectedSize = null, fileSize = null, phase = null, error = null, file = null,
            publicUri = saves.values.firstOrNull(), publicPath = null, saveError = saveError, createdAt = 0, completedAt = null, attempt = 1, filesRemoved = removed,
        ),
        request, DownloadOptions(), photoSaves = saves,
    )

    private fun saves(count: Int) = (1..count).associate { "$it.jpg" to "content://media/$it" }
    private val post1 = "https://www.instagram.com/p/AAA/"

    @Test fun aPostWhoseCopiesAreAllThereIsAlreadySaved() {
        val earlier = photoJob("a", post1, FormatRequest.Gallery(3), saves(3))
        val found = PhotoPosts.alreadySaved("https://instagram.com/p/AAA?igsh=x", FormatRequest.Gallery(3), PhotoPosts.earlier(listOf(earlier), "new") { true })
        assertEquals("a", found?.id)
        // fewer photos of the same post are covered too, more are not
        assertEquals("a", PhotoPosts.alreadySaved(post1, FormatRequest.Gallery(3, listOf(1, 3)), PhotoPosts.earlier(listOf(earlier), "new") { true })?.id)
        val some = photoJob("b", post1, FormatRequest.Gallery(3, listOf(1, 2)), saves(2))
        assertNull(PhotoPosts.alreadySaved(post1, FormatRequest.Gallery(3), PhotoPosts.earlier(listOf(some), "new") { true }))
    }

    @Test fun aPostIsNeverAlreadySavedWhenAFileWasRemovedOrNotSaved() {
        val ok = photoJob("a", post1, FormatRequest.Gallery(3), saves(3))
        // one copy no longer opens
        assertNull(PhotoPosts.alreadySaved(post1, FormatRequest.Gallery(3), PhotoPosts.earlier(listOf(ok), "new") { it != "content://media/2" }))
        // fewer copies than photos asked for (a save that stopped halfway), a save error, files removed, another post, itself, a job that did not finish
        val partial = photoJob("p", post1, FormatRequest.Gallery(3), saves(2), saveError = "Saved 2 of 3 files.")
        val removed = photoJob("r", post1, FormatRequest.Gallery(3), saves(3), removed = true)
        val failed = photoJob("f", post1, FormatRequest.Gallery(3), saves(3), status = JobStatus.FAILED)
        val other = photoJob("o", "https://www.instagram.com/p/BBB/", FormatRequest.Gallery(3), saves(3))
        assertNull(PhotoPosts.alreadySaved(post1, FormatRequest.Gallery(3), PhotoPosts.earlier(listOf(partial, removed, failed, other, ok), "a") { true }))
        assertEquals("a", PhotoPosts.alreadySaved(post1, FormatRequest.Gallery(3), PhotoPosts.earlier(listOf(partial, removed, failed, other, ok), "new") { true })?.id)
    }

    @Test fun theSavedPhotosSurviveTheQueueFile() {
        val record = photoJob("a", post1, FormatRequest.Gallery(2), saves(2))
        val back = JobCodec.decodeFile(JobCodec.encodeFile(listOf(record))).single()
        assertEquals(saves(2), back.photoSaves)
        assertEquals(emptyMap<String, String>(), JobCodec.decodeFile(JobCodec.encodeFile(listOf(record.copy(photoSaves = emptyMap())))).single().photoSaves)
    }

    @Test fun photosFollowTheSpaceFolderUnderPictures() {
        val downloads = defaultFolderSpaces().first { it.id == "downloads" }
        val music = defaultFolderSpaces().first { it.id == "music" }
        assertEquals("Pictures/Keepfin", SpaceLogic.picturesPath(downloads, ""))
        assertEquals("Pictures/Keepfin Music/Album", SpaceLogic.picturesPath(music, "Album"))
    }
}
