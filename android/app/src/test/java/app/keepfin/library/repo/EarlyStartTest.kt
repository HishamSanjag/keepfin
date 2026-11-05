package app.keepfin.library.repo

import app.keepfin.EngineSettings
import app.keepfin.Job
import app.keepfin.JobStatus
import app.keepfin.LibraryHost
import app.keepfin.MediaKind
import app.keepfin.allItems
import app.keepfin.folder.FakeFolderAccess
import app.keepfin.folder.KeepfinStore
import app.keepfin.folder.StoreOptions
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The library shows on this phone's index before the Keepfin folder is open (the folder opened through the storage provider, which
 * took seconds while Home and the library looked empty), and goes on with the same state once the folder is open.
 */
class EarlyStartTest {
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

    private val root: File = Files.createTempDirectory("kf-early").toFile()
    private val dir = File(root, "dev")
    private val folder = FakeFolderAccess()
    private val replica = "a1a1a1a1-a1a1-4a1a-8a1a-a1a1a1a1a1a1"
    private var ids = 0
    private var clock = 1_700_000_000_000L
    private val stores = ArrayList<KeepfinStore>()
    private val repos = ArrayList<LibraryRepository>()

    @After fun tearDown() {
        repos.forEach { runCatching { it.shutdown() } }
        stores.forEach { runCatching { it.abandon() } }
        root.deleteRecursively()
    }

    private fun openStore(state: app.keepfin.folder.sql.SqlMergeState) = KeepfinStore.open(
        StoreOptions(folder = folder, libraryId = "11111111-1111-4111-8111-111111111111", actor = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", deviceDir = dir, outboxDir = File(dir, "outbox"),
            newId = { "00000000-0000-4000-8000-%012d".format(++ids) }, now = { clock++ }, flushIdleMs = 3_600_000, checkpointDelayMs = 5, retryMs = 10, state = state),
    ).get(10, TimeUnit.SECONDS).also { stores += it }

    private fun job(id: String) = Job(
        id = id, url = "https://music.youtube.com/watch?v=$id", title = "Song $id", thumbnail = null, uploader = "Band", duration = 120.0, provider = null, label = "",
        kind = MediaKind.AUDIO, status = JobStatus.COMPLETED, percent = 100f, speed = null, eta = null, downloadedBytes = null, totalBytes = null, expectedSize = null, fileSize = null,
        phase = null, error = null, file = null, publicUri = null, publicPath = null, saveError = null, createdAt = 0, completedAt = null, attempt = 1, group = null,
        collectionId = null, collectionIndex = null, space = "music",
    )

    @Test fun theLibraryShowsBeforeTheFolderOpensAndGoesOnWithTheSameState() {
        // last time: a song was downloaded into the library, and the app closed
        val firstState = testState(dir, replica)
        val firstStore = openStore(firstState)
        val first = LibraryRepository(Host(), { RepositoryBackend(firstStore, folder, replica, firstState, { app.keepfin.folder.sql.JdbcHost().open(it) }) { "doc:$it" } }, dir, newId = { "00000000-0000-4000-8000-%012d".format(++ids) }, now = { clock++ })
        first.start(); first.awaitReady()
        val media = File(root, "Song a.mp3").also { it.writeBytes(ByteArray(500) { b -> b.toByte() }) }
        first.saveDownload(job("a"), null, "music", "", "Song a", media, emptyList())
        first.shutdown()
        firstState.close()

        // this start: the folder takes its time to open; the screens read the index meanwhile
        val opening = CountDownLatch(1)
        val state = testState(dir, replica)
        var early: LocalStart? = null
        val repo = LibraryRepository(
            Host(),
            open = {
                assertTrue("the folder's open waits", opening.await(10, TimeUnit.SECONDS))
                RepositoryBackend(openStore(early!!.state), folder, replica, early!!.state, { app.keepfin.folder.sql.JdbcHost().open(it) }) { "doc:$it" }
            },
            deviceDir = dir, newId = { "00000000-0000-4000-8000-%012d".format(++ids) }, now = { clock++ },
            openLocal = { LocalStart(state, replica, { "doc:$it" }, { app.keepfin.folder.sql.JdbcHost().open(it) }).also { early = it } },
        ).also { repos += it }
        repo.start()
        val deadline = System.currentTimeMillis() + 5_000
        while (repo.allItems().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val before = repo.allItems()
        assertEquals("the song is listed while the folder is still opening", listOf("Song a"), before.map { it.title })
        assertEquals("its file address is the folder's", "doc:Music/Song a.mp3", before.single().uri)

        opening.countDown()
        repo.awaitReady()
        assertEquals(listOf("Song a"), repo.allItems().map { it.title })
        // the open library writes through the same state the early view read
        repo.saveDownload(job("b"), null, "music", "", "Song b", File(root, "Song b.mp3").also { it.writeBytes(ByteArray(300) { 3 }) }, emptyList())
        assertEquals(listOf("Song a", "Song b"), repo.allItems().map { it.title }.sorted())
    }

    @Test fun anotherFolderThanLastTimeTakesTheEarlyViewDownBeforeItsOwnStateOpens() {
        val early = LocalStart(testState(File(root, "remembered"), replica), replica, { "old:$it" }, { app.keepfin.folder.sql.JdbcHost().open(it) })
        var opens = 0
        var droppedBeforeOpen = false
        val repo = LibraryRepository(
            Host(),
            open = {
                opens++
                // what LibrarySelector does: the remembered view does not fit this folder
                if (!early.dropped) throw EarlyViewMismatch()
                droppedBeforeOpen = true
                val state = testState(dir, replica)
                RepositoryBackend(openStore(state), folder, replica, state, { app.keepfin.folder.sql.JdbcHost().open(it) }) { "doc:$it" }
            },
            deviceDir = dir, openLocal = { early },
        ).also { repos += it }
        repo.start(); repo.awaitReady()
        assertEquals(2, opens)
        assertTrue("the early view was down before the folder's own state opened", droppedBeforeOpen)
        assertTrue(repo.allItems().isEmpty())
        // and the open library is the folder's: a download lands there with the folder's addresses
        repo.saveDownload(job("c"), null, "music", "", "Song c", File(root, "Song c.mp3").also { it.writeBytes(ByteArray(200) { 5 }) }, emptyList())
        assertEquals("doc:Music/Song c.mp3", repo.allItems().single().uri)
    }

    @Test fun anEarlyViewThatFailsIsNeverHandedToTheFolder() {
        val early = LocalStart(testState(File(root, "broken"), replica), replica, { "old:$it" }) { throw IllegalStateException("the index cannot be read") }
        var handedDropped: Boolean? = null
        val repo = LibraryRepository(
            Host(),
            open = {
                handedDropped = early.dropped
                val state = testState(dir, replica)
                RepositoryBackend(openStore(state), folder, replica, state, { app.keepfin.folder.sql.JdbcHost().open(it) }) { "doc:$it" }
            },
            deviceDir = dir, openLocal = { early },
        ).also { repos += it }
        repo.start(); repo.awaitReady()
        assertEquals("the failed view was dropped before the folder opened", true, handedDropped)
    }

    @Test fun withoutAnEarlyViewTheLibraryOpensAsBefore() {
        val state = testState(dir, replica)
        val store = openStore(state)
        val repo = LibraryRepository(Host(), { RepositoryBackend(store, folder, replica, state, { app.keepfin.folder.sql.JdbcHost().open(it) }) { "doc:$it" } }, dir, openLocal = { null }).also { repos += it }
        repo.start(); repo.awaitReady()
        assertTrue(repo.allItems().isEmpty())
    }
}
