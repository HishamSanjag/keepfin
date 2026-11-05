package app.keepfin.setup

import androidx.media3.common.MediaMetadata
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.Job
import app.keepfin.JobStatus
import app.keepfin.KeepfinApplication
import app.keepfin.LibrarySettings
import app.keepfin.MediaEngine
import app.keepfin.MediaKind
import app.keepfin.folder.KeepfinFolders
import app.keepfin.folder.KeepfinStore
import app.keepfin.folder.SafFolderAccess
import app.keepfin.folder.StoreOptions
import app.keepfin.library.repo.LibraryRepository
import app.keepfin.library.repo.RepositoryBackend
import app.keepfin.proto.ProbeSupport
import app.keepfin.ui.player.ControllerPlayerPort
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real playback service with a song that keeps a music video in a real Keepfin folder (dev-docs/plans/MUSIC-VIDEOS.md): the Song | Video switch
 * replaces the queue with the same song on the other side, the video item plays the companion file as a video of the same song, and each side
 * resumes where it was asked to. The emulator only: it plays through the real controller and service.
 */
@RunWith(AndroidJUnit4::class)
class MusicVideoPlaybackInstrumentedTest {
    private val p = ProbeSupport("KeepfinMusicVideoPlay")
    private val dirName = "KeepfinMusicVideoPlay"
    private val engine get() = (p.ctx.applicationContext as KeepfinApplication).engineApi as MediaEngine
    private lateinit var repo: LibraryRepository
    private lateinit var store: KeepfinStore
    private var before: LibraryRepository? = null
    private var port: ControllerPlayerPort? = null
    private val work = File(p.ctx.cacheDir, "music-video-play-" + UUID.randomUUID())

    @After fun cleanup() {
        runCatching { InstrumentationRegistry.getInstrumentation().runOnMainSync { port?.clearQueue(); port?.release() } }
        engine.swapFolderLibrary(before)
        runCatching { repo.shutdown() }
        runCatching { store.abandon() }
        p.releaseAll(dirName)
        runCatching { p.shell("rm -rf ${p.fsPath("primary", dirName)}") }
        work.deleteRecursively()
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun waitFor(what: String, timeoutMs: Long = 25_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) { if (condition()) return; Thread.sleep(100) }
        fail("Timed out waiting for $what")
    }

    @Test fun theSwitchPlaysTheSameSongAsItsVideoAndBackEachSideWhereItWasAsked() {
        p.shell("mkdir -p ${p.fsPath("primary", dirName)}")
        val tree = p.pickTree("primary", dirName)
        val access = SafFolderAccess.open(p.cr, tree)
        p.shell("rm -rf ${p.fsPath("primary", dirName)}/.keepfin")
        val folder = KeepfinFolders.create(access, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "2026-10-15T00:00:00Z")
        val dir = File(work, "device")
        val state = app.keepfin.folder.sql.SqlMergeState(File(dir, "state/state.db").path, folder.libraryId, folder.replicaId, null, app.keepfin.folder.sql.AndroidSqlHost())
        store = KeepfinStore.open(
            StoreOptions(folder = access, libraryId = folder.libraryId, actor = UUID.randomUUID().toString(), deviceDir = dir, outboxDir = File(dir, "outbox"), newId = { UUID.randomUUID().toString() }, state = state),
        ).get(30, TimeUnit.SECONDS)
        repo = LibraryRepository(QuietHost(LibrarySettings()), { RepositoryBackend(store, access, folder.replicaId, state) { path -> access.documentUriFor(path).toString() } }, dir)
        repo.start(); repo.awaitReady()
        work.mkdirs()
        // a song of about 17 seconds and its music video (the engine fixture, a short clip)
        val song = File(work, "Generated song.wav").also { it.writeBytes(p.wavHeader(3_000_000) + ByteArray(3_000_000) { (it % 50).toByte() }) }
        val clip = File(work, "official.mp4").also { f -> InstrumentationRegistry.getInstrumentation().context.assets.open("fixture.mp4").use { i -> f.outputStream().use { o -> i.copyTo(o) } } }
        val job = Job(
            id = "job1", url = "https://example.com/a", title = "Generated song", thumbnail = null, uploader = "Tester", duration = 25.0, provider = null, label = "",
            kind = MediaKind.AUDIO, status = JobStatus.COMPLETED, percent = 100f, speed = null, eta = null, downloadedBytes = null, totalBytes = null, expectedSize = null, fileSize = null,
            phase = null, error = null, file = null, publicUri = null, publicPath = null, saveError = null, createdAt = 0, completedAt = null, attempt = 1, group = null,
        )
        val saved = repo.saveDownload(job, null, "music", "", "Generated song", song, emptyList())
        assertNotNull(repo.attachMusicVideo(saved.itemId, clip))
        val id = saved.itemId

        // the real service reads its library through the engine: point the engine at this folder for the test
        before = engine.swapFolderLibrary(repo)
        engine.updateSettings { it.copy(library = LibrarySettings()) }

        val p1 = ControllerPlayerPort(p.ctx)
        port = p1
        onMain { p1.playNow(listOf(id)) }
        waitFor("the song to play") { val c = p1.ui.value.current; c != null && c.resolved && !c.musicVideo && p1.ui.value.playing }
        waitFor("the song's clock") { p1.position.value.positionMs > 500 }
        onMain { p1.pause(); p1.seekTo(4_000) }

        // Video: the same song id and queue, the companion file, described as a video, from its own beginning
        onMain { p1.switchSide(true, 0L) }
        waitFor("the video item") { val c = p1.ui.value.current; c != null && c.resolved && c.musicVideo }
        onMain {
            val item = p1.player!!.currentMediaItem!!
            assertEquals(id, item.mediaId)
            assertEquals(MediaMetadata.MEDIA_TYPE_VIDEO, item.mediaMetadata.mediaType)
            assertTrue("the companion file: ${item.localConfiguration?.uri}", item.localConfiguration!!.uri.toString().contains("video.mp4"))
            assertEquals(1, p1.player!!.mediaItemCount)
        }
        assertTrue(p1.ui.value.current!!.video)
        waitFor("the video to be ready") { onMainValue { p1.player!!.playbackState } == androidx.media3.common.Player.STATE_READY }
        assertTrue("the video side starts at its own beginning, not the song's 4 s", p1.position.value.positionMs < 2_000)

        // the video played nothing into the song: the song's own place is untouched
        assertTrue((repo.item(id)?.position ?: 0.0) < 1.0)

        // Song again, at the place the song was left
        onMain { p1.switchSide(false, 4_000L) }
        waitFor("the song item") { val c = p1.ui.value.current; c != null && c.resolved && !c.musicVideo }
        waitFor("the song to be ready") { onMainValue { p1.player!!.playbackState } == androidx.media3.common.Player.STATE_READY }
        assertTrue("the song resumes at 4 s: ${onMainValue { p1.player!!.currentPosition }}", onMainValue { p1.player!!.currentPosition } in 3_900L..6_000L)
        assertEquals(1, p1.ui.value.queue.size)
    }

    private fun <T> onMainValue(block: () -> T): T {
        var out: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { out = runCatching(block) }
        return out!!.getOrThrow()
    }
}
