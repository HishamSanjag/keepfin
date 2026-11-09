package app.keepfin.library.repo

import app.keepfin.allItems
import app.keepfin.CollectionKind
import app.keepfin.EngineSettings
import app.keepfin.FollowMode
import app.keepfin.FollowUpdate
import app.keepfin.Job
import app.keepfin.JobStatus
import app.keepfin.LibraryHost
import app.keepfin.MediaKind
import app.keepfin.Recording
import app.keepfin.SourceTags
import app.keepfin.folder.FakeFolderAccess
import app.keepfin.folder.KeepfinStore
import app.keepfin.folder.StoreOptions
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Downloads in folder mode: the private file is published into the space folder through FolderAccess and added to the library. */
class FolderDownloadsTest {
    private class Host : LibraryHost {
        override val jobs = MutableStateFlow(emptyList<Job>())
        override val settings = MutableStateFlow(EngineSettings())
        override fun updateSettings(transform: (EngineSettings) -> EngineSettings) { settings.value = transform(settings.value) }
        override fun jobThumbnail(jobId: String) = null
        override fun sidecarUris() = emptySet<String>()
        override fun repointFile(uri: String, publicPath: String?) {}
        override fun requestCheck(id: String?, review: Boolean) {}
        override fun followSettingsChanged() {}
    }

    private val host = Host()
    private lateinit var root: File
    private val folder = FakeFolderAccess()
    private lateinit var store: KeepfinStore
    private lateinit var repo: LibraryRepository
    private var ids = 0
    private var clock = 1_700_000_000_000L

    @Before fun setUp() {
        root = Files.createTempDirectory("kf-dl").toFile()
        val dir = File(root, "dev")
        val state = testState(dir, "a1a1a1a1-a1a1-4a1a-8a1a-a1a1a1a1a1a1")
        store = KeepfinStore.open(
            StoreOptions(folder = folder, libraryId = "11111111-1111-4111-8111-111111111111", actor = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", deviceDir = dir, outboxDir = File(dir, "outbox"),
                newId = { "00000000-0000-4000-8000-%012d".format(++ids) }, now = { clock++ }, flushIdleMs = 3_600_000, checkpointDelayMs = 5, retryMs = 10, state = state),
        ).get(10, TimeUnit.SECONDS)
        repo = LibraryRepository(host, { RepositoryBackend(store, folder, "a1a1a1a1-a1a1-4a1a-8a1a-a1a1a1a1a1a1", state, { app.keepfin.folder.sql.JdbcHost().open(it) }) { "doc:$it" } }, dir, newId = { "00000000-0000-4000-8000-%012d".format(++ids) }, now = { clock++ })
        repo.start(); repo.awaitReady()
    }

    @After fun tearDown() { runCatching { repo.shutdown() }; store.abandon(); root.deleteRecursively() }

    private fun job(id: String, collection: String? = null, index: Int? = null) = Job(
        id = id, url = "https://music.youtube.com/watch?v=$id", title = "Song $id", thumbnail = null, uploader = "Band", duration = 120.0, provider = null, label = "",
        kind = MediaKind.AUDIO, status = JobStatus.COMPLETED, percent = 100f, speed = null, eta = null, downloadedBytes = null, totalBytes = null, expectedSize = null, fileSize = null,
        phase = null, error = null, file = null, publicUri = null, publicPath = null, saveError = null, createdAt = 0, completedAt = null, attempt = 1, group = null,
        collectionId = collection, collectionIndex = index, space = "music",
    )

    private fun private(name: String, bytes: ByteArray) = File(root, name).also { it.writeBytes(bytes) }

    @Test fun aFinishedDownloadIsPublishedIntoTheSpaceFolderAndListed() {
        val media = private("Song a.mp3", ByteArray(500) { it.toByte() })
        val sub = private("Song a.en.vtt", "WEBVTT".toByteArray())
        val saved = repo.saveDownload(job("a"), SourceTags("Song a", "Band", "Album"), "music", "", "Song a", media, listOf(sub))
        assertEquals("Music/Song a.mp3", saved.path)
        assertEquals("doc:Music/Song a.mp3", saved.uri)
        assertTrue(folder.files.containsKey("Music/Song a.en.vtt"))
        assertEquals(500, folder.files.getValue("Music/Song a.mp3").size)
        assertTrue(media.isFile) // the private file stays
        val item = repo.allItems().single()
        assertEquals("Band", item.artist)
        assertEquals("a", item.jobId)
    }

    private fun fieldsOf(id: String): org.json.JSONObject {
        val items = org.json.JSONObject(store.viewsJson().get(10, TimeUnit.SECONDS)).getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it) }.first { it.getString("id") == id }.getJSONObject("fields")
    }

    private fun sha(bytes: ByteArray) = "sha256:" + java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun aDownloadsLyricsSubtitlesAndCoverAreRecordedSoCopiesCarryThem() {
        val lyrics = "[00:01.00]la".toByteArray()
        val subs = "WEBVTT".toByteArray()
        val cover = ByteArray(40) { 9 }
        val saved = repo.saveDownload(
            job("l"), null, "music", "", "Song l", private("Song l.mp3", ByteArray(50) { 5 }),
            listOf(private("Song l.lrc", lyrics), private("Song l.en.vtt", subs), private("Song l.jpg", cover)),
        )
        val fields = fieldsOf(saved.itemId)
        assertEquals(sha(lyrics), fields.getJSONObject("companion:.lrc").getString("content"))
        assertEquals(lyrics.size.toLong(), fields.getJSONObject("companion:.lrc").getLong("size"))
        assertEquals(sha(subs), fields.getJSONObject("companion:.en.vtt").getString("content"))
        assertEquals(sha(cover), fields.getJSONObject("companion:.jpg").getString("content"))
    }

    @Test fun lyricsAlreadyBesideASongAreRecordedOnce() {
        val saved = repo.saveDownload(job("m"), null, "music", "", "Song m", private("Song m.mp3", ByteArray(60) { 6 }), emptyList())
        // lyrics that were there before this version recorded them (an earlier download, a file put there by hand)
        val lyrics = "[00:02.00]old".toByteArray()
        folder.publishFrom("Music/Song m.lrc", lyrics.inputStream())
        folder.publishFrom("Music/Other.lrc", "not this song's".toByteArray().inputStream())
        repo.recordCompanions()
        val fields = fieldsOf(saved.itemId)
        assertEquals(sha(lyrics), fields.getJSONObject("companion:.lrc").getString("content"))
        assertEquals(1, fields.keys().asSequence().count { it.startsWith("companion:") })
        // once per Keepfin folder: a later change is the download's or the copy's to record
        folder.publishFrom("Music/Song m.en.srt", "1".toByteArray().inputStream())
        repo.recordCompanions()
        assertTrue(!fieldsOf(saved.itemId).has("companion:.en.srt"))
    }

    @Test fun aFailedPublishKeepsThePrivateFileAndThrows() {
        val media = private("Song b.mp3", ByteArray(10))
        folder.failWith = app.keepfin.folder.FolderError.Unavailable("card out")
        var thrown = false
        try { repo.saveDownload(job("b"), null, "music", "", "Song b", media, emptyList()) } catch (e: app.keepfin.folder.FolderError) { thrown = true }
        folder.failWith = null
        assertTrue(thrown)
        assertTrue(media.isFile)
        assertTrue(repo.allItems().isEmpty())
    }

    @Test fun identicalContentIsNotListedTwice() {
        val bytes = ByteArray(64) { 7 }
        repo.saveDownload(job("c1"), null, "music", "", "One", private("One.mp3", bytes), emptyList())
        val second = repo.saveDownload(job("c2"), null, "music", "", "Two", private("Two.mp3", bytes), emptyList())
        assertTrue(second.duplicate)
        assertEquals(1, repo.allItems().size)
        assertTrue(folder.files.keys.none { it.endsWith("Two.mp3") })
    }

    @Test fun collectionsAreCreatedOnceAndTheDownloadJoinsAtItsPosition() {
        val url = "https://www.youtube.com/playlist?list=PLabc"
        val id = repo.collectionIdFor(url, null)
        val c = repo.ensureCollection(url, "Road trip", CollectionKind.PLAYLIST, "music", listOf("youtube:a", "youtube:b"), false, id)
        assertEquals(id, c.id)
        assertEquals(id, repo.collectionIdFor(url, "ignored"))
        val again = repo.ensureCollection(url, "Road trip", CollectionKind.PLAYLIST, "music", listOf("youtube:c"), false, id)
        assertEquals(id, again.id)
        assertEquals(1, repo.library.value.collections.size)
        assertEquals(3, repo.library.value.collections.single().seen.size)
        host.jobs.value = listOf(job("a", id, 1), job("b", id, 2))
        repo.saveDownload(job("b", id, 2), null, "music", "Road trip", "Song b", private("Song b.mp3", ByteArray(20) { 1 }), emptyList())
        repo.saveDownload(job("a", id, 1), null, "music", "Road trip", "Song a", private("Song a.mp3", ByteArray(21) { 2 }), emptyList())
        val ordered = repo.library.value.collections.single().itemIds.map { i -> repo.allItems().first { it.id == i }.title }
        assertEquals(listOf("Song a", "Song b"), ordered)
    }

    @Test fun aDownloadedCollectionStartsUnfollowedAndOwnListsAndMixesCannotBeFollowed() {
        val url = "https://www.youtube.com/playlist?list=PLabc"
        val c = repo.ensureCollection(url, "Road trip", CollectionKind.PLAYLIST, "music", listOf("youtube:a"), false, repo.collectionIdFor(url, null))
        assertEquals("following is opt-in", FollowMode.OFF, c.follow?.mode ?: FollowMode.OFF)
        val own = repo.collectionSave(null, "Mine", emptyList(), "music")
        assertTrue(runCatching { repo.collectionFollow(own, FollowUpdate(mode = FollowMode.AUTO)) }.exceptionOrNull() is IllegalArgumentException)
        val mixUrl = "https://www.youtube.com/watch?v=aaaaaaaaaaa&list=RDaaaaaaaaaaa"
        val mix = repo.ensureCollection(mixUrl, "Mix", CollectionKind.PLAYLIST, "music", listOf("youtube:a"), true, repo.collectionIdFor(mixUrl, null))
        assertTrue(runCatching { repo.collectionFollow(mix.id, FollowUpdate(mode = FollowMode.AUTO)) }.exceptionOrNull() is IllegalArgumentException)
    }

    private fun mp3WithCode(code: String?): ByteArray {
        fun syncsafe(n: Int) = byteArrayOf((n shr 21 and 0x7f).toByte(), (n shr 14 and 0x7f).toByte(), (n shr 7 and 0x7f).toByte(), (n and 0x7f).toByte())
        val content = byteArrayOf(3) + (code ?: "").toByteArray()
        val frame = if (code == null) ByteArray(0) else "TSRC".toByteArray() + syncsafe(content.size) + byteArrayOf(0, 0) + content
        return "ID3".toByteArray() + byteArrayOf(4, 0, 0) + syncsafe(frame.size) + frame + ByteArray(200) { 7 }
    }

    private fun imported(name: String, bytes: ByteArray, title: String, artist: String): String {
        val template = app.keepfin.core.library.LibItem(id = "", space = "music", kind = "audio", title = title, artist = artist, duration = 120.0, addedAt = 1.0)
        return repo.addFile("music", "", name, bytes.inputStream(), template).itemId
    }

    private fun isrcOf(id: String) = repo.mergeBackend().state.query("SELECT isrc FROM lib_isrc WHERE id = ?", listOf(id)).firstOrNull()?.get(0)

    @Test fun aSongsRecordingCodeIsReadOnImportKeptOnThisDeviceAndFindsTheSameSongUnderAnotherName() {
        val id = imported("Imported.mp3", mp3WithCode("GBDUW0000053"), "Imported", "Someone")
        assertEquals("GBDUW0000053", isrcOf(id))
        // nothing about the code is in the shared data
        assertTrue(!repo.mergeBackend().state.viewsJson().contains("GBDUW0000053"))
        // another title, artist and link, no source id in common: the code decides
        val match = repo.findExisting(job("n"), Recording(emptySet(), null, "Other Artist", "A Different Title", null, 5.0, isrc = "GB-DUW-00-00053"))
        assertNotNull(match)
        assertEquals(id, match!!.itemId)
        assertEquals("isrc", match.by)
        // a different code does not match
        assertNull(repo.findExisting(job("n2"), Recording(emptySet(), null, "Other Artist", "A Different Title", null, 5.0, isrc = "GBDUW0000099")))
    }

    @Test fun aSongWithoutACodeIsFoundTheWayItWasBefore() {
        val id = imported("Plain.mp3", mp3WithCode(null), "Plain Song", "Band")
        assertNull(isrcOf(id))
        assertNull(repo.findExisting(job("p"), Recording(emptySet(), null, "Other", "Unrelated", null, 5.0, isrc = "GBDUW0000053")))
        val match = repo.findExisting(job("p"), Recording(emptySet(), null, "Band", "Plain Song", null, 120.0))
        assertEquals(id, match?.itemId)
        assertEquals("metadata", match?.by)
    }

    @Test fun aDownloadsServiceCodeIsKeptWhenTheFileHoldsNoReadableOne() {
        val media = private("Song q.m4a", ByteArray(300) { 1 })
        repo.saveDownload(job("q"), SourceTags("Song q", "Band", null, isrc = "GBDUW0000053"), "music", "", "Song q", media, emptyList())
        val match = repo.findExisting(job("q2"), Recording(emptySet(), null, "Zed", "Elsewhere", null, 9.0, isrc = "GBDUW0000053"))
        assertEquals("isrc", match?.by)
    }

    @Test fun anExistingRecordingIsFoundWhenTheSpaceSkipsExisting() {
        repo.saveDownload(job("e"), SourceTags("Song e", "Band", null), "music", "", "Song e", private("Song e.mp3", ByteArray(30) { 3 }), emptyList())
        val match = repo.findExisting(job("e2"), Recording(app.keepfin.SourceIds.of(job("e")), null, "Band", "Song e", null, 120.0))
        assertNotNull(match)
        assertNull(repo.findExisting(job("z"), Recording(emptySet(), null, "Other", "Different", null, 10.0)))
    }
}
