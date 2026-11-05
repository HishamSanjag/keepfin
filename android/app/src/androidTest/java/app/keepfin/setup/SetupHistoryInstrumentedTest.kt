package app.keepfin.setup

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.JobCodec
import app.keepfin.JobRecord
import app.keepfin.JobStatus
import app.keepfin.DownloadOptions
import app.keepfin.FormatRequest
import app.keepfin.LibraryCodec
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.LibrarySettings
import app.keepfin.MediaEngine
import app.keepfin.folder.KeepfinFolders
import app.keepfin.folder.KeepfinStore
import app.keepfin.folder.Profile
import app.keepfin.folder.ProfileStore
import app.keepfin.folder.SafFolderAccess
import app.keepfin.folder.StoreOptions
import app.keepfin.library.repo.LibraryRepository
import app.keepfin.library.repo.RepositoryBackend
import app.keepfin.proto.ProbeSupport
import app.keepfin.testJob
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * After the setup moves files into the Keepfin folder the Downloads tab must still show them as saved: the history rows follow the
 * files while they move, and a row an earlier run left marked "file removed" is repaired from the run's plan.
 */
@RunWith(AndroidJUnit4::class)
class SetupHistoryInstrumentedTest {
    private val p = ProbeSupport("KeepfinSetupHistory")
    private val dirName = "KeepfinSetupHistory"
    private val inserted = ArrayList<Uri>()
    private val root = File(p.ctx.filesDir, "test-setup-history-" + UUID.randomUUID()).apply { mkdirs() }
    private val isolated: Context = object : ContextWrapper(p.ctx) {
        override fun getFilesDir() = root
        override fun getExternalFilesDir(type: String?) = File(root, "external/${type ?: "root"}").apply { mkdirs() }
    }
    private var repo: LibraryRepository? = null
    private var store: KeepfinStore? = null
    private var engine: MediaEngine? = null

    @After fun cleanup() {
        runCatching { repo?.shutdown() }
        runCatching { store?.abandon() }
        runCatching { engine?.shutdown() }
        inserted.forEach { runCatching { p.cr.delete(it, null, null) } }
        p.releaseAll(dirName)
        runCatching { p.shell("rm -rf ${p.fsPath("primary", dirName)}") }
        root.deleteRecursively()
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
        val folder = KeepfinFolders.create(access, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "2026-10-15T00:00:00Z")
        val dir = File(root, "device")
        val state = app.keepfin.folder.sql.SqlMergeState(File(dir, "state/state.db").path, folder.libraryId, folder.replicaId, null, app.keepfin.folder.sql.AndroidSqlHost())
        store = KeepfinStore.open(
            StoreOptions(folder = access, libraryId = folder.libraryId, actor = UUID.randomUUID().toString(), deviceDir = dir, outboxDir = File(dir, "outbox"), newId = { UUID.randomUUID().toString() }, state = state),
        ).get(30, TimeUnit.SECONDS)
        return LibraryRepository(QuietHost(LibrarySettings()), { RepositoryBackend(store!!, access, folder.replicaId, state) { path -> access.documentUriFor(path).toString() } }, dir)
            .also { it.start(); it.awaitReady(); repo = it }
    }

    private data class Song(val uri: Uri, val jobId: String, val rec: LibraryCodec.Rec)

    private fun songs(): List<Song> = (1..2).map { n ->
        val body = p.wavHeader(4000L * n) + ByteArray(4000 * n) { (it + n).toByte() }
        val uri = insertSong("kf-history-$n.wav", body)
        val jobId = "history-job-$n-${UUID.randomUUID()}"
        val rec = LibraryCodec.Rec(
            LibraryItem(uri.toString(), "music", uri.toString(), "", LibraryItemKind.AUDIO, "History song $n", size = body.size.toLong(), modifiedAt = 1, addedAt = 1000L + n, jobId = jobId),
            owned = true, name = "kf-history-$n.wav",
        )
        Song(uri, jobId, rec)
    }

    private fun writeQueue(songs: List<Song>, removed: Boolean) {
        val records = songs.map { s ->
            val job = testJob(JobStatus.COMPLETED, s.jobId).copy(title = s.rec.item.title, publicUri = if (removed) null else s.uri.toString(), publicPath = if (removed) null else "Music/Keepfin Music/${s.rec.name}", filesRemoved = removed)
            JobRecord(job, FormatRequest.Video(), DownloadOptions(), publicFiles = if (removed) emptyList() else listOf(s.uri.toString()))
        }
        File(root, "queue.json").writeText(JobCodec.encodeFile(records))
    }

    @Test fun movedFilesKeepTheirRowsSavedOnTheDownloadsTab() {
        p.shell("mkdir -p ${p.fsPath("primary", dirName)}")
        val access = SafFolderAccess.open(p.cr, p.pickTree("primary", dirName))
        val songs = songs()
        writeQueue(songs, removed = false)
        val e = MediaEngine(isolated).also { engine = it }
        assertTrue("before the move every row is saved", e.jobs.value.all { !it.filesRemoved && it.publicUri != null })
        val inv = LegacyInventories.read(LibraryCodec.encodeFile(songs.map { it.rec }, emptyList()), LibrarySettings())
        val plan = Planner.plan(inv, inv.spaces.associate { it.space.id to Choice.MOVE }, "run-history", 1L) { UUID.randomUUID().toString() }
        val repository = openLibrary(access)
        val outcome = MigrationRunner(
            plan, inv, MediaStoreFiles(p.ctx), RepositoryTarget(repository, access), MigrationJournal.open(File(root, "run")),
            relocated = { old, uri, path -> e.relocateFile(old, uri, path) },
        ).run()
        assertTrue("outcome $outcome", outcome is RunOutcome.Completed)
        for (s in songs) {
            assertNull("the original is gone", MediaStoreFiles(p.ctx).size(s.uri.toString()))
            // the scan of the old library reports the vanished original: the row, now pointing at the copy, must not be touched
            e.repointFile(s.uri.toString(), null)
        }
        for (job in e.jobs.value) {
            assertFalse("${job.title} is shown as saved, not as removed", job.filesRemoved)
            val dest = plan.items.first { it.oldId.endsWith("/" + songs.first { s -> s.jobId == job.id }.uri.lastPathSegment) }.dest
            assertEquals(dest, job.publicPath)
            assertTrue(job.publicUri!!.startsWith("content://com.android.externalstorage.documents/tree"))
            assertNotNull(access.stat(dest))
        }
    }

    @Test fun rowsAnEarlierRunLeftAsRemovedAreRepairedFromItsPlan() {
        p.shell("mkdir -p ${p.fsPath("primary", dirName)}")
        val tree = p.pickTree("primary", dirName)
        val access = SafFolderAccess.open(p.cr, tree)
        val songs = songs()
        val inv = LegacyInventories.read(LibraryCodec.encodeFile(songs.map { it.rec }, emptyList()), LibrarySettings())
        val plan = Planner.plan(inv, inv.spaces.associate { it.space.id to Choice.MOVE }, "run-repair", 1L) { UUID.randomUUID().toString() }
        val repository = openLibrary(access)
        // the earlier version moved the files and never told the history; the scan then marked the rows removed
        assertTrue(MigrationRunner(plan, inv, MediaStoreFiles(p.ctx), RepositoryTarget(repository, access), MigrationJournal.open(File(root, "run"))).run() is RunOutcome.Completed)
        repo?.shutdown(); repo = null
        writeQueue(songs, removed = true)
        val runDir = File(root, "migration/run-repair").apply { File(this, "backup").mkdirs() }
        File(runDir, "plan.json").writeText(plan.toJson())
        File(runDir, "backup/library.json").writeText(LibraryCodec.encodeFile(songs.map { it.rec }, emptyList()))
        ProfileStore(File(root, "profiles.json")).add(Profile(UUID.randomUUID().toString(), "Test", tree.toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), "2026-10-15T00:00:00Z"))
        val e = MediaEngine(isolated).also { engine = it }
        assertTrue("the rows start out marked removed", e.jobs.value.all { it.filesRemoved })
        StorageSetup(isolated, e).repairHistory()
        for (job in e.jobs.value) {
            assertFalse("${job.title} is saved again", job.filesRemoved)
            assertTrue(job.publicUri!!.startsWith("content://com.android.externalstorage.documents/tree"))
        }
        // and a second start of the app keeps it (the repair is written to the history file)
        e.shutdown()
        val again = MediaEngine(isolated).also { engine = it }
        assertTrue(again.jobs.value.none { it.filesRemoved })
    }
}
