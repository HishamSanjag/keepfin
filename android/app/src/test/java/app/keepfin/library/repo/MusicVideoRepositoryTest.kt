package app.keepfin.library.repo

import app.keepfin.allItems
import app.keepfin.EngineSettings
import app.keepfin.Job
import app.keepfin.JobStatus
import app.keepfin.LibraryHost
import app.keepfin.MediaKind
import app.keepfin.folder.FakeFolderAccess
import app.keepfin.folder.KeepfinStore
import app.keepfin.folder.StoreOptions
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A song's music video (dev-docs/plans/MUSIC-VIDEOS.md): `Song.video.mp4` beside the song, recorded on it, never an item of its own, and carried by
 * move, rename, delete and restore like its lyrics.
 */
class MusicVideoRepositoryTest {
    private class Host : LibraryHost {
        override val jobs = MutableStateFlow(emptyList<Job>())
        override val settings = MutableStateFlow(EngineSettings())
        override fun updateSettings(transform: (EngineSettings) -> EngineSettings) { settings.value = transform(settings.value) }
        override fun jobThumbnail(jobId: String) = null
        override fun sidecarUris() = emptySet<String>()
        override fun repointFile(uri: String, publicPath: String?) {}
        override fun requestCheck(id: String?, review: Boolean) {}
        override fun followSettingsChanged() {}
        var tags: Map<String, String>? = null
        override val tagTools = object : app.keepfin.TagTools {
            override fun available() = true
            override fun metadata(file: java.io.File) = tags
            override fun run(args: List<String>, timeoutSeconds: Long) = null
        }
    }

    private val replica = "a1a1a1a1-a1a1-4a1a-8a1a-a1a1a1a1a1a1"
    private val host = Host()
    private lateinit var root: File
    private lateinit var dir: File
    private val folder = FakeFolderAccess()
    private lateinit var store: KeepfinStore
    private lateinit var repo: LibraryRepository
    private var ids = 0
    private var clock = 1_700_000_000_000L

    @Before fun setUp() {
        root = Files.createTempDirectory("kf-mv").toFile()
        dir = File(root, "dev")
        val state = testState(dir, replica)
        store = KeepfinStore.open(
            StoreOptions(folder = folder, libraryId = "11111111-1111-4111-8111-111111111111", actor = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", deviceDir = dir, outboxDir = File(dir, "outbox"),
                newId = { "00000000-0000-4000-8000-%012d".format(++ids) }, now = { clock++ }, flushIdleMs = 3_600_000, checkpointDelayMs = 5, retryMs = 10, state = state),
        ).get(10, TimeUnit.SECONDS)
        repo = LibraryRepository(host, { RepositoryBackend(store, folder, replica, state, { app.keepfin.folder.sql.JdbcHost().open(it) }) { "doc:$it" } }, dir, newId = { "00000000-0000-4000-8000-%012d".format(++ids) }, now = { clock++ })
        repo.start(); repo.awaitReady()
    }

    @After fun tearDown() { runCatching { repo.shutdown() }; store.abandon(); root.deleteRecursively() }

    private fun job(id: String) = Job(
        id = id, url = "https://music.youtube.com/watch?v=$id", title = "Song $id", thumbnail = null, uploader = "Band", duration = 120.0, provider = null, label = "",
        kind = MediaKind.AUDIO, status = JobStatus.COMPLETED, percent = 100f, speed = null, eta = null, downloadedBytes = null, totalBytes = null, expectedSize = null, fileSize = null,
        phase = null, error = null, file = null, publicUri = null, publicPath = null, saveError = null, createdAt = 0, completedAt = null, attempt = 1, group = null, space = "music",
    )

    private fun private(name: String, bytes: ByteArray) = File(root, name).also { it.writeBytes(bytes) }

    private fun song(name: String): String = repo.saveDownload(job(name), null, "music", "", "Song $name", private("Song $name.mp3", ByteArray(50) { 5 }), emptyList()).itemId

    private fun fieldsOf(id: String): org.json.JSONObject {
        val items = org.json.JSONObject(store.viewsJson().get(10, TimeUnit.SECONDS)).getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it) }.first { it.getString("id") == id }.getJSONObject("fields")
    }

    private fun hasVideoRecord(id: String) = fieldsOf(id).keys().asSequence().any { it.startsWith("companion:.video.") && fieldsOf(id).optJSONObject(it) != null }

    @Test fun aFinishedMusicVideoIsKeptBesideItsSongAndIsNotAnItemOfItsOwn() {
        val id = song("a")
        val video = ByteArray(2_000) { (it % 251).toByte() }
        val attached = repo.attachMusicVideo(id, private("Official.mp4", video))
        assertNotNull(attached)
        assertEquals("Music/Song a.video.mp4", attached!!.path)
        assertEquals("doc:Music/Song a.video.mp4", attached.uri)
        assertEquals(2_000, folder.files.getValue("Music/Song a.video.mp4").size)
        // recorded on the song, so copies carry it
        val record = fieldsOf(id).getJSONObject("companion:.video.mp4")
        assertEquals(2_000L, record.getLong("size"))
        assertTrue(record.getString("content").startsWith("sha256:"))
        // one item: the song, flagged; the video is nobody's own item
        val items = repo.allItems()
        assertEquals(1, items.size)
        assertTrue(items.single().musicVideo)
        assertEquals("doc:Music/Song a.video.mp4", repo.musicVideoUri(id))
    }

    @Test fun nothingIsReplacedOrLostWhenTheVideoCannotBeAttached() {
        val id = song("b")
        // a container the players do not use
        assertNull(repo.attachMusicVideo(id, private("clip.mkv", ByteArray(10))))
        // a file already has the name
        folder.publishFrom("Music/Song b.video.mp4", ByteArray(7) { 1 }.inputStream())
        val clip = private("clip.mp4", ByteArray(10) { 2 })
        assertNull(repo.attachMusicVideo(id, clip))
        assertEquals(7, folder.files.getValue("Music/Song b.video.mp4").size)
        assertTrue("the download stays where it was", clip.isFile)
        // the song is gone
        assertNull(repo.attachMusicVideo("00000000-0000-4000-8000-999999999999", clip))
        assertFalse(repo.allItems().single().musicVideo)
    }

    @Test fun aCopyThatFailsMidwayLeavesNoPartialFileBesideTheSong() {
        val id = song("t")
        val clip = private("clip.mp4", ByteArray(40) { 8 })
        folder.tearNextPublish = true
        assertTrue(runCatching { repo.attachMusicVideo(id, clip) }.isFailure)
        assertFalse(folder.files.keys.any { it.startsWith("Music/Song t.video") })
        assertTrue("the download stays where it was", clip.isFile)
    }

    @Test fun aSongThatHasAVideoKeepsIt() {
        val id = song("c")
        assertNotNull(repo.attachMusicVideo(id, private("one.mp4", ByteArray(30) { 3 })))
        val second = private("two.webm", ByteArray(20) { 4 })
        assertNull(repo.attachMusicVideo(id, second))
        assertEquals(30, folder.files.getValue("Music/Song c.video.mp4").size)
        assertFalse(folder.files.containsKey("Music/Song c.video.webm"))
        assertTrue(second.isFile)
    }

    @Test fun renameMoveAndDeleteCarryTheVideoAndRestoreBringsItBack() {
        val id = song("d")
        repo.attachMusicVideo(id, private("d.mp4", ByteArray(64) { 6 }))
        repo.rename(id, "Renamed")
        assertTrue(folder.files.containsKey("Music/Renamed.video.mp4"))
        assertFalse(folder.files.containsKey("Music/Song d.video.mp4"))
        assertTrue(repo.allItems().single().musicVideo)
        repo.move(listOf(id), "downloads")
        val moved = folder.files.keys.single { it.endsWith("Renamed.video.mp4") }
        assertTrue("moved with the song: $moved", moved.startsWith("Downloads/"))
        assertFalse(folder.files.containsKey("Music/Renamed.video.mp4"))
        // delete: the video goes to the trash with its song
        repo.deleteItems(listOf(id))
        assertFalse(folder.files.keys.any { !it.contains("trash") && it.endsWith(".video.mp4") })
        assertTrue(folder.files.keys.any { it.contains("trash") && it.endsWith("Renamed.video.mp4") })
        assertTrue(repo.allItems().isEmpty())
        // restore: the video returns, recorded again, and is still not an item
        val result = repo.restoreItems(listOf(id))
        assertEquals(listOf(id), result.restored)
        assertEquals(moved, folder.files.keys.single { it.endsWith(".video.mp4") })
        assertTrue(hasVideoRecord(id))
        val items = repo.allItems()
        assertEquals(1, items.size)
        assertTrue(items.single().musicVideo)
    }

    @Test fun aMusicVideoPlacedBesideASongBeforeIsRecordedOnceMoreEvenAfterTheEarlierLook() {
        val id = song("e")
        // an earlier version already looked at this folder (its marker), before music videos were companions
        File(dir, "companions-recorded-$replica").writeText("1")
        folder.publishFrom("Music/Song e.video.mp4", ByteArray(33) { 8 }.inputStream())
        folder.publishFrom("Music/Other.video.mp4", ByteArray(5).inputStream()) // no song of that name: nobody's video
        repo.recordCompanions()
        assertTrue(hasVideoRecord(id))
        assertEquals(33L, fieldsOf(id).getJSONObject("companion:.video.mp4").getLong("size"))
        val items = repo.allItems()
        assertEquals(1, items.size)
        assertTrue(items.single().musicVideo)
        // once more is a no-op
        repo.recordCompanions()
        assertEquals(33L, fieldsOf(id).getJSONObject("companion:.video.mp4").getLong("size"))
    }

    @Test fun aMusicVideoFileNewInACopyBecomesTheCompanionOfTheSongBesideItNeverAnItem() {
        val id = song("g")
        val host = app.keepfin.copies.LibraryHost(repo.copiesBackend(), app.keepfin.copies.RepoCopiesLibrary(repo), { "00000000-0000-4000-8000-%012d".format(++ids) }, { clock++ })
        // the file arrives from a copy: staged under a temporary name in the folder, then imported at its path
        folder.publishFrom("Music/stage.kftmp", ByteArray(21) { 1 }.inputStream())
        val content = "sha256:" + "e".repeat(64)
        val outcome = host.importNew(app.keepfin.core.copies.Action(app.keepfin.core.copies.ActionKind.ImportNew, "Music/Song g.video.mp4", contentId = content, bytes = 21), "Music/stage.kftmp", "Music/Song g.video.mp4")
        assertEquals(id, outcome.itemId)
        assertEquals("companion:.video.mp4", outcome.revisionOp)
        assertTrue(folder.files.containsKey("Music/Song g.video.mp4"))
        assertTrue(hasVideoRecord(id))
        assertEquals(1, repo.allItems().size)
        assertTrue(repo.allItems().single().musicVideo)
        // one with no song beside it is an ordinary new file, as before
        folder.publishFrom("Music/stage2.kftmp", ByteArray(5).inputStream())
        val lone = host.importNew(app.keepfin.core.copies.Action(app.keepfin.core.copies.ActionKind.ImportNew, "Music/Nobody.video.mp4", contentId = "sha256:" + "f".repeat(64), bytes = 5), "Music/stage2.kftmp", "Music/Nobody.video.mp4")
        assertTrue(lone.itemId != id)
        assertEquals(2, repo.allItems().size)
    }

    @Test fun onlyAnAudioItemOwnsAMusicVideo() {
        val clip = repo.saveDownload(job("v"), null, "music", "", "Clip v", private("Clip v.mp4", ByteArray(40) { 2 }), emptyList()).itemId
        folder.publishFrom("Music/Clip v.video.mp4", ByteArray(9) { 3 }.inputStream())
        repo.recordCompanions()
        assertFalse(hasVideoRecord(clip))
        assertFalse(repo.allItems().single().musicVideo)
    }

    @Test fun removingTheMusicVideoDeletesOnlyThatFile() {
        val id = song("f")
        repo.attachMusicVideo(id, private("f.mp4", ByteArray(40) { 9 }))
        folder.publishFrom("Music/Song f.lrc", "[00:01.00]la".toByteArray().inputStream())
        repo.removeMusicVideo(id)
        assertFalse(folder.files.containsKey("Music/Song f.video.mp4"))
        assertTrue(folder.files.containsKey("Music/Song f.lrc"))
        assertTrue(folder.files.containsKey("Music/Song f.mp3"))
        assertFalse(hasVideoRecord(id))
        assertFalse(repo.allItems().single().musicVideo)
        assertNull(repo.musicVideoUri(id))
    }

    // ---- a video of a song you have joins that song (dev-docs/plans/MUSIC-VIDEOS.md, "A song you already have") -------------------------------------

    private fun linkOf(id: String) = app.keepfin.MediaInfo(
        url = "https://www.youtube.com/watch?v=$id", webpageUrl = "https://www.youtube.com/watch?v=$id", title = "Song $id", uploader = "Band", duration = 120.0, thumbnail = null,
        extractor = "youtube", provider = "YouTube", isLive = false, music = null, video = emptyList(), audio = emptyList(), subtitles = emptyList(), chapters = 0, playlistUrl = null,
    )

    @Test fun theCauseAVideoJobIsMatchedByItsOwnKindSoItNeverMeetsItsSong() {
        val id = song("abcdefghijk")
        val recording = app.keepfin.Recording(setOf("youtube:abcdefghijk"), null, "Band", "Song abcdefghijk", null, 120.0)
        host.updateSettings { it.copy(library = it.library.copy(enabled = true)) }
        val asAudio = job("abcdefghijk")
        assertEquals("the audio job meets the song", id, repo.findExisting(asAudio, recording)?.itemId)
        assertNull("the video job of the same recording does not: it was downloaded as a second item (or lost in a music space)", repo.findExisting(asAudio.copy(kind = MediaKind.VIDEO), recording))
        // songOf looks for the song whatever the download is, in every space
        assertEquals(id, repo.songOf(linkOf("abcdefghijk"))?.itemId)
    }

    @Test fun songOfFindsTheSongInAnySpaceAndSaysWhetherItHasItsVideo() {
        val other = repo.saveDownload(job("bcdefghijkl"), null, "downloads", "", "Song bcdefghijkl", private("Song bcdefghijkl.mp3", ByteArray(50) { 7 }), emptyList()).itemId
        val found = repo.songOf(linkOf("bcdefghijkl"))!!
        assertEquals(other, found.itemId)
        assertEquals("downloads", found.space)
        assertFalse(found.hasVideo)
        repo.attachMusicVideo(other, private("v.mp4", ByteArray(30) { 3 }))
        assertTrue(repo.songOf(linkOf("bcdefghijkl"))!!.hasVideo)
        assertNull(repo.songOf(linkOf("cdefghijklm")))
    }

    @Test fun lyricsSavedInsideTheSongAreRead() {
        val id = song("e")
        host.tags = mapOf("lyrics" to "[00:01.00]inside the file")
        assertTrue(repo.lyrics(id)!!.synced)
    }

    @Test fun aSongWithoutLyricsHasNone() {
        host.tags = mapOf("title" to "Song")
        assertNull(repo.lyrics(song("n")))
    }

    @Test fun aDownloadWithLyricsInsideIsMarkedAsHavingThem() {
        host.tags = mapOf("lyrics" to "[00:01.00]inside the file")
        val id = song("m")
        assertTrue(repo.allItems().first { it.id == id }.hasLyrics)
    }

    @Test fun lyricsFoundForASongAreKeptBesideItAndRecordedLikeAFinishedDownloadsLyrics() {
        val id = song("h")
        assertNull(repo.lyrics(id))
        repo.saveLyrics(id, "[00:01.00]la la")
        assertEquals("[00:01.00]la la", folder.files.getValue("Music/Song h.lrc").toString(Charsets.UTF_8))
        assertTrue(fieldsOf(id).optJSONObject("companion:.lrc") != null)
        assertTrue(repo.lyrics(id)!!.synced)
        assertEquals(1, repo.allItems().size)
    }

    @Test fun theVideoRowsNeedOnlyTheSongsTheFlagAndTheFileFacts() {
        val id = song("i")
        assertNull(repo.musicVideoFacts(id))
        repo.attachMusicVideo(id, private("i.mp4", ByteArray(77) { 4 }))
        val facts = repo.musicVideoFacts(id)!!
        assertEquals(77L, facts.size)
        assertEquals("doc:Music/Song i.video.mp4", facts.uri)
        assertEquals(listOf(id), repo.browse.top(app.keepfin.ItemQuery(musicVideos = true), 10).map { it.id })
    }
}
