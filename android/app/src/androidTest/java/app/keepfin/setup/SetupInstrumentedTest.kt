package app.keepfin.setup

import app.keepfin.allItems
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.Job
import app.keepfin.JobStatus
import app.keepfin.LibraryCodec
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.LibrarySettings
import app.keepfin.MediaKind
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
 * An earlier library with owned MediaStore files, moved into a real Keepfin folder (SAF tree on primary storage) by the real runner,
 * and a finished download published into the folder. Local generated files only; no network.
 */
@RunWith(AndroidJUnit4::class)
class SetupInstrumentedTest {
    private val p = ProbeSupport("KeepfinSetupTest")
    private val dirName = "KeepfinSetupTest"
    private val inserted = ArrayList<Uri>()
    private lateinit var repo: LibraryRepository
    private lateinit var store: KeepfinStore
    private val work = File(p.ctx.cacheDir, "setup-test-" + UUID.randomUUID())

    @After fun cleanup() {
        runCatching { repo.shutdown() }
        runCatching { store.abandon() }
        inserted.forEach { runCatching { p.cr.delete(it, null, null) } }
        p.releaseAll(dirName)
        runCatching { p.shell("rm -rf ${p.fsPath("primary", dirName)}") }
        work.deleteRecursively()
    }

    private fun insertSong(name: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name); put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Keepfin Music"); put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = p.cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)!!
        p.cr.openOutputStream(uri)!!.use { it.write(bytes) }
        p.cr.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        inserted += uri
        return uri
    }

    private fun openLibrary(access: SafFolderAccess): LibraryRepository {
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

    @Test fun legacyOwnedFilesMoveIntoTheFolderAndTheLibraryShowsThem() {
        p.shell("mkdir -p ${p.fsPath("primary", dirName)}")
        val tree = p.pickTree("primary", dirName)
        val access = SafFolderAccess.open(p.cr, tree)
        val bodies = (1..3).map { n -> p.wavHeader(4000L * n) + ByteArray(4000 * n) { (it + n).toByte() } }
        val uris = bodies.mapIndexed { i, b -> insertSong("kf-setup-${i + 1}.wav", b) }
        val recs = uris.mapIndexed { i, u ->
            LibraryCodec.Rec(
                LibraryItem(u.toString(), "music", u.toString(), "", LibraryItemKind.AUDIO, "Setup song ${i + 1}", artist = "Tester", size = bodies[i].size.toLong(), modifiedAt = 1, addedAt = 1000L + i, favorite = i == 0, plays = i),
                owned = true, name = "kf-setup-${i + 1}.wav",
            )
        }
        val inv = LegacyInventories.read(LibraryCodec.encodeFile(recs, emptyList()), LibrarySettings())
        val plan = Planner.plan(inv, inv.spaces.associate { it.space.id to Choice.MOVE }, "run-e2e", 1L) { UUID.randomUUID().toString() }
        val repository = openLibrary(access)
        val runDir = File(work, "run")
        val outcome = MigrationRunner(plan, inv, MediaStoreFiles(p.ctx), RepositoryTarget(repository, access), MigrationJournal.open(runDir)).run()
        assertTrue("outcome $outcome", outcome is RunOutcome.Completed)
        // the folder library lists the three items, with their favourites, in the folder
        val items = repository.allItems().sortedBy { it.title }
        assertEquals(listOf("Setup song 1", "Setup song 2", "Setup song 3"), items.map { it.title })
        assertTrue(items[0].favorite)
        for ((i, item) in items.withIndex()) {
            val entry = access.stat("Music/kf-setup-${i + 1}.wav")
            assertNotNull("copy $i", entry)
            assertEquals(bodies[i].size.toLong(), entry!!.size)
            assertTrue(item.uri.startsWith("content://"))
        }
        // owned originals are gone from MediaStore
        for (u in uris) assertNull(MediaStoreFiles(p.ctx).size(u.toString()))
        assertEquals(3, (outcome as RunOutcome.Completed).report.moved)
        // a new start of the same journal changes nothing
        val again = MigrationRunner(plan, inv, MediaStoreFiles(p.ctx), RepositoryTarget(repository, access), MigrationJournal.open(runDir)).run()
        assertTrue(again is RunOutcome.Completed)
        assertEquals(3, repository.allItems().size)
    }

    @Test fun aFinishedDownloadIsPublishedIntoTheFolderFromAPrivateFile() {
        p.shell("mkdir -p ${p.fsPath("primary", dirName)}")
        val tree = p.pickTree("primary", dirName)
        val access = SafFolderAccess.open(p.cr, tree)
        val repository = openLibrary(access)
        work.mkdirs()
        val media = File(work, "Generated song.wav").also { it.writeBytes(p.wavHeader(8000) + ByteArray(8000) { 3 }) }
        val sub = File(work, "Generated song.en.vtt").also { it.writeText("WEBVTT") }
        val job = Job(
            id = "job1", url = "https://example.com/a", title = "Generated song", thumbnail = null, uploader = "Tester", duration = 1.0, provider = null, label = "",
            kind = MediaKind.AUDIO, status = JobStatus.COMPLETED, percent = 100f, speed = null, eta = null, downloadedBytes = null, totalBytes = null, expectedSize = null, fileSize = null,
            phase = null, error = null, file = null, publicUri = null, publicPath = null, saveError = null, createdAt = 0, completedAt = null, attempt = 1, group = null,
        )
        val saved = repository.saveDownload(job, null, "music", "", "Generated song", media, listOf(sub))
        assertEquals("Music/Generated song.wav", saved.path)
        assertEquals(media.length(), access.stat(saved.path)!!.size)
        assertNotNull(access.stat("Music/Generated song.en.vtt"))
        assertTrue(media.isFile)
        val item = repository.allItems().single()
        assertEquals("job1", item.jobId)
        assertEquals(saved.uri, item.uri)
    }
}
