package app.keepfin.setup

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.Job
import app.keepfin.JobStatus
import app.keepfin.LibrarySettings
import app.keepfin.MediaKind
import app.keepfin.allItems
import app.keepfin.folder.KeepfinFolders
import app.keepfin.folder.KeepfinStore
import app.keepfin.folder.SafFolderAccess
import app.keepfin.folder.StoreOptions
import app.keepfin.library.repo.LibraryRepository
import app.keepfin.library.repo.RepositoryBackend
import app.keepfin.proto.ProbeSupport
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A song's music video through the real storage access framework (dev-docs/plans/MUSIC-VIDEOS.md): attached beside the song as `Song.video.mp4`, recorded,
 * never an item of its own, moved, renamed and deleted with the song, restored with it, and readable through its content address.
 */
@RunWith(AndroidJUnit4::class)
class MusicVideoSafInstrumentedTest {
    private val p = ProbeSupport("KeepfinMusicVideoTest")
    private val dirName = "KeepfinMusicVideoTest"
    private lateinit var repo: LibraryRepository
    private lateinit var store: KeepfinStore
    private val work = File(p.ctx.cacheDir, "music-video-test-" + UUID.randomUUID())

    @After fun cleanup() {
        runCatching { repo.shutdown() }
        runCatching { store.abandon() }
        p.releaseAll(dirName)
        runCatching { p.shell("rm -rf ${p.fsPath("primary", dirName)}") }
        work.deleteRecursively()
    }

    private fun open(access: SafFolderAccess): LibraryRepository {
        p.shell("rm -rf ${p.fsPath("primary", dirName)}/.keepfin")
        val folder = KeepfinFolders.create(access, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "2026-10-15T00:00:00Z")
        val dir = File(work, "device")
        val state = app.keepfin.folder.sql.SqlMergeState(File(dir, "state/state.db").path, folder.libraryId, folder.replicaId, null, app.keepfin.folder.sql.AndroidSqlHost())
        store = KeepfinStore.open(
            StoreOptions(folder = access, libraryId = folder.libraryId, actor = UUID.randomUUID().toString(), deviceDir = dir, outboxDir = File(dir, "outbox"), newId = { UUID.randomUUID().toString() }, state = state),
        ).get(30, TimeUnit.SECONDS)
        repo = LibraryRepository(QuietHost(LibrarySettings()), { RepositoryBackend(store, access, folder.replicaId, state) { path -> access.documentUriFor(path).toString() } }, dir)
        repo.start(); repo.awaitReady()
        return repo
    }

    @Test fun theVideoTravelsWithItsSongAndIsNeverAnItemOfItsOwn() {
        p.shell("mkdir -p ${p.fsPath("primary", dirName)}")
        val tree = p.pickTree("primary", dirName)
        val access = SafFolderAccess.open(p.cr, tree)
        val repository = open(access)
        work.mkdirs()
        val song = File(work, "Generated song.wav").also { it.writeBytes(p.wavHeader(8000) + ByteArray(8000) { 3 }) }
        val clip = File(work, "official.mp4").also { f -> InstrumentationRegistry.getInstrumentation().context.assets.open("fixture.mp4").use { i -> f.outputStream().use { o -> i.copyTo(o) } } }
        val job = Job(
            id = "job1", url = "https://example.com/a", title = "Generated song", thumbnail = null, uploader = "Tester", duration = 1.0, provider = null, label = "",
            kind = MediaKind.AUDIO, status = JobStatus.COMPLETED, percent = 100f, speed = null, eta = null, downloadedBytes = null, totalBytes = null, expectedSize = null, fileSize = null,
            phase = null, error = null, file = null, publicUri = null, publicPath = null, saveError = null, createdAt = 0, completedAt = null, attempt = 1, group = null,
        )
        val saved = repository.saveDownload(job, null, "music", "", "Generated song", song, emptyList())

        // attached beside the song, recorded, readable through its content address
        val attached = repository.attachMusicVideo(saved.itemId, clip)
        assertNotNull(attached)
        assertEquals("Music/Generated song.video.mp4", attached!!.path)
        assertEquals(clip.length(), access.stat(attached.path)!!.size)
        val uri = repository.musicVideoUri(saved.itemId)
        assertNotNull(uri)
        assertEquals(clip.length(), p.cr.openInputStream(Uri.parse(uri))!!.use { it.readBytes().size }.toLong())
        var items = repository.allItems()
        assertEquals("the video is not an item", 1, items.size)
        assertTrue(items.single().musicVideo)

        // a second attach keeps the first, and nothing is lost
        assertNull(repository.attachMusicVideo(saved.itemId, clip))
        assertEquals(clip.length(), access.stat(attached.path)!!.size)

        // rename and move carry it
        repository.rename(saved.itemId, "Renamed")
        assertNotNull(access.stat("Music/Renamed.video.mp4"))
        assertNull(access.stat("Music/Generated song.video.mp4"))
        repository.move(listOf(saved.itemId), "downloads")
        val moved = repository.allItems().single()
        assertTrue(moved.musicVideo)
        val movedPlace = repository.musicVideoUri(saved.itemId)
        assertNotNull(movedPlace)
        assertNull(access.stat("Music/Renamed.video.mp4"))

        // delete takes it to the trash with the song, restore brings both back and the item is flagged again
        repository.deleteItems(listOf(saved.itemId))
        assertTrue(repository.allItems().isEmpty())
        val restored = repository.restoreItems(listOf(saved.itemId))
        assertEquals(listOf(saved.itemId), restored.restored)
        items = repository.allItems()
        assertEquals(1, items.size)
        assertTrue("restored with its video", items.single().musicVideo)
        assertNotNull(repository.musicVideoUri(saved.itemId))

        // removing it deletes only the video
        repository.removeMusicVideo(saved.itemId)
        assertNull(repository.musicVideoUri(saved.itemId))
        assertTrue(repository.allItems().single().let { !it.musicVideo })
    }
}
