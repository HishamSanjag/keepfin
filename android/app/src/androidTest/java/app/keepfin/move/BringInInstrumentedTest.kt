package app.keepfin.move

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.LibrarySettings
import app.keepfin.core.library.LibItem
import app.keepfin.core.library.LibRoutes
import app.keepfin.core.library.LibSpace
import app.keepfin.folder.KeepfinFolders
import app.keepfin.folder.KeepfinStore
import app.keepfin.folder.SafFolderAccess
import app.keepfin.folder.StoreOptions
import app.keepfin.library.repo.LibraryRepository
import app.keepfin.library.repo.LinkedLocator
import app.keepfin.library.repo.MediaStoreLinked
import app.keepfin.library.repo.RepositoryBackend
import app.keepfin.library.repo.ResolverImports
import app.keepfin.proto.ProbeSupport
import app.keepfin.setup.QuietHost
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Bring them in" over a real library on a real SAF folder with real MediaStore files this test made: Move in copies each file into the folder,
 * checks it, repoints the item (same id) and removes the original; Leave only repoints the space; a stop keeps what is done.
 */
@RunWith(AndroidJUnit4::class)
class BringInInstrumentedTest {
    private companion object {
        const val ID_A = "00000000-0000-4000-8000-00000000000a"
        const val ID_B = "00000000-0000-4000-8000-00000000000b"
        const val ID_C = "00000000-0000-4000-8000-00000000000c"
    }

    private val p = ProbeSupport("KeepfinBringInTest")
    private val dirName = "KeepfinBringInTest"
    private val tag = "KeepfinBringIn" + UUID.randomUUID().toString().take(6)
    private val inserted = ArrayList<Uri>()
    private val work = File(p.ctx.cacheDir, "bring-test-" + UUID.randomUUID())
    private lateinit var repo: LibraryRepository
    private lateinit var store: KeepfinStore
    private val media = MediaStoreLinked(p.ctx)

    @Before fun allowReading() {
        val permissions = if (android.os.Build.VERSION.SDK_INT >= 33) listOf("READ_MEDIA_AUDIO", "READ_MEDIA_VIDEO") else listOf("READ_EXTERNAL_STORAGE")
        permissions.forEach { p.inst.uiAutomation.grantRuntimePermission(p.ctx.packageName, "android.permission.$it") }
    }

    @After fun cleanup() {
        runCatching { repo.shutdown() }
        runCatching { store.abandon() }
        inserted.forEach { runCatching { p.cr.delete(it, null, null) } }
        p.releaseAll(dirName)
        runCatching { p.shell("rm -rf ${p.fsPath("primary", dirName)}") }
        work.deleteRecursively()
    }

    private fun song(name: String, sub: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name); put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/$tag/$sub"); put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = p.cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)!!
        p.cr.openOutputStream(uri)!!.use { it.write(bytes) }
        p.cr.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        inserted += uri
        return uri
    }

    private fun open(access: SafFolderAccess): LibraryRepository {
        val folder = KeepfinFolders.create(access, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "2026-10-15T00:00:00Z")
        val dir = File(work, "device")
        val state = app.keepfin.folder.sql.SqlMergeState(File(dir, "state/state.db").path, folder.libraryId, folder.replicaId, null, app.keepfin.folder.sql.AndroidSqlHost())
        store = KeepfinStore.open(
            StoreOptions(folder = access, libraryId = folder.libraryId, actor = UUID.randomUUID().toString(), deviceDir = dir, outboxDir = File(dir, "outbox"), newId = { UUID.randomUUID().toString() }, state = state),
        ).get(30, TimeUnit.SECONDS)
        repo = LibraryRepository(
            QuietHost(LibrarySettings()), { RepositoryBackend(store, access, folder.replicaId, state) { path -> access.documentUriFor(path).toString() } }, dir,
            imports = ResolverImports(p.cr), linked = media,
        )
        repo.start(); repo.awaitReady()
        return repo
    }

    private fun linkedItem(id: String, space: String, title: String, sub: String, name: String) =
        LibItem(id = id, space = space, kind = "audio", title = title, favorite = true, place = listOf("linked:$space", sub, name).filter { it.isNotEmpty() }.joinToString("/"))

    private fun migrated(uri: Uri, item: LibItem): LibraryRepository.MigratedItem {
        val where = media.stat(uri.toString())!!
        return LibraryRepository.MigratedItem(item, null, where.size, LinkedLocator(uri.toString(), where.path, where.size, where.mtime, true))
    }

    private fun setup(): Triple<SafFolderAccess, LibraryRepository, Map<String, Pair<Uri, ByteArray>>> {
        p.shell("rm -rf ${p.fsPath("primary", dirName)}")
        p.shell("mkdir -p ${p.fsPath("primary", dirName)}")
        val access = SafFolderAccess.open(p.cr, p.pickTree("primary", dirName))
        val repo = open(access)
        repo.migrateLayout(
            listOf(LibSpace("music", "Music", "music", tag, linked = true, layout = "collection"), LibSpace("podcasts", "Podcasts", "music", "$tag Pods", linked = true, layout = "collection"), LibSpace("downloads", "Downloads", "mixed", "Downloads")),
            LibRoutes("music", "downloads"),
        )
        val bytesA = ByteArray(4_000) { (it * 5).toByte() }
        val bytesB = ByteArray(300_000) { (it * 9).toByte() }
        val bytesC = ByteArray(2_000) { (it * 3).toByte() }
        val a = song("a.wav", "Rock", bytesA)
        val b = song("b.wav", "", bytesB)
        val c = song("c.wav", "", bytesC)
        val added = repo.migrateItems(
            listOf(
                migrated(a, linkedItem(ID_A, "music", "A", "Rock", "a.wav")), migrated(b, linkedItem(ID_B, "music", "B", "", "b.wav")),
                migrated(c, linkedItem(ID_C, "podcasts", "C", "", "c.wav")),
            ),
        )
        assertEquals(3, added)
        val until = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < until && !repo.hasItem(ID_C)) Thread.sleep(100)
        return Triple(access, repo, mapOf(ID_A to (a to bytesA), ID_B to (b to bytesB), ID_C to (c to bytesC)))
    }

    private fun placeOf(repo: LibraryRepository, id: String) = repo.modelNow().items.first { it.id == id }.place

    @Test fun moveInCopiesChecksRepointsKeepingIdsAndRemovesTheOriginals() {
        val (access, repo, files) = setup()
        val before = repo.outsideSpaces().associateBy { it.id }
        assertEquals(setOf("music", "podcasts"), before.keys)
        assertEquals(repo.modelNow().items.map { it.id + "/" + it.space + "/" + it.place }.toString() + repo.modelNow().spaces.map { it.id }, 2, before.getValue("music").files)
        assertEquals("Music", before.getValue("music").folder)
        val progress = ArrayList<Int>()
        val result = repo.bringIn("music", true, { done, _, _ -> progress += done }, { false })
        assertEquals(result.failed.toString(), 2, result.moved)
        assertEquals(emptyList<String>(), result.failed)
        assertNull("the originals are the app's own, so no question", result.confirm)
        // each file is in the folder with the same bytes, under the space's default folder, and the item keeps its id
        assertEquals("Music/Rock/a.wav", placeOf(repo, ID_A))
        assertEquals("Music/b.wav", placeOf(repo, ID_B))
        assertArrayEquals(files.getValue(ID_A).second, access.read("Music/Rock/a.wav"))
        assertArrayEquals(files.getValue(ID_B).second, access.read("Music/b.wav"))
        assertTrue(repo.hasItem(ID_A) && repo.hasItem(ID_B))
        // the originals went after the copies were checked
        assertNull(media.stat(files.getValue(ID_A).first.toString()))
        assertNull(media.stat(files.getValue(ID_B).first.toString()))
        // the other space is untouched, and Music is no longer outside
        assertEquals(listOf("podcasts"), repo.outsideSpaces().map { it.id })
        assertTrue(media.stat(files.getValue(ID_C).first.toString()) != null)
        assertTrue(repo.modelNow().items.first { it.id == ID_A }.favorite)
    }

    @Test fun leaveRepointsTheSpaceAndKeepsEveryFileWhereItWas() {
        val (access, repo, files) = setup()
        val result = repo.bringIn("podcasts", false, { _, _, _ -> }, { false })
        assertEquals(0, result.moved)
        assertEquals(1, result.kept)
        assertEquals(listOf("music"), repo.outsideSpaces().map { it.id })
        assertTrue(media.stat(files.getValue(ID_C).first.toString()) != null)
        assertTrue(placeOf(repo, ID_C)!!.startsWith("linked:"))
        // new files of the space now go inside the Keepfin folder
        val space = repo.modelNow().spaces.first { it.id == "podcasts" }
        assertEquals(false, space.linked)
        assertEquals("Podcasts", space.folder)
        assertNull(access.stat("Podcasts/c.wav"))
    }

    @Test fun aStopKeepsWhatIsDoneAndTheNextRunFinishesTheRest() {
        val (access, repo, files) = setup()
        var asked = 0
        val first = repo.bringIn("music", true, { _, _, _ -> }, { asked++ >= 1 })
        assertEquals(first.failed.toString(), 1, first.moved)
        assertEquals("one file is still outside", 1, listOf(ID_A, ID_B).count { placeOf(repo, it)!!.startsWith("linked:") })
        val second = repo.bringIn("music", true, { _, _, _ -> }, { false })
        assertEquals(second.failed.toString(), 1, second.moved)
        assertTrue(repo.outsideSpaces().none { it.id == "music" })
        for (id in listOf(ID_A, ID_B)) assertArrayEquals(files.getValue(id).second, access.read(placeOf(repo, id)!!))
    }
}
