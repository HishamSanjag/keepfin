package app.keepfin.move

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.folder.FolderAccess
import app.keepfin.folder.FolderError
import app.keepfin.folder.KeepfinFolders
import app.keepfin.folder.Profile
import app.keepfin.folder.ProfileStore
import app.keepfin.folder.SafFolderAccess
import app.keepfin.proto.ProbeSupport
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The move of the Keepfin folder over real SAF trees on primary storage: copy, check, switch, an interrupted move that continues, a stop that
 * removes only what the move made, and the old folder untouched throughout. The two folders are picked with the real system picker (UiAutomator).
 */
@RunWith(AndroidJUnit4::class)
class FolderMoveInstrumentedTest {
    private val p = ProbeSupport("KeepfinMoveTest")
    private val oldDir = "KeepfinMoveOld"
    private val newDir = "KeepfinMoveNew"
    private val work = File(p.ctx.cacheDir, "move-test-" + UUID.randomUUID())

    @After fun cleanup() {
        p.releaseAll(oldDir)
        p.releaseAll(newDir)
        runCatching { p.shell("rm -rf ${p.fsPath("primary", oldDir)} ${p.fsPath("primary", newDir)}") }
        work.deleteRecursively()
    }

    private class Host(val cr: android.content.ContentResolver, val profiles: ProfileStore) : MoveHost {
        var held = false
        var reopened = 0
        override fun open(tree: String): FolderAccess = SafFolderAccess.open(cr, Uri.parse(tree))
        override fun now() = System.currentTimeMillis()
        override fun quiesce(libraryId: String) { held = true }
        override fun release(libraryId: String) { held = false }
        override fun switchProfile(move: FolderMove) {
            val profile = profiles.load().profiles.first { it.libraryId == move.libraryId }
            profiles.add(profile.copy(folderTreeUri = move.toTree), makeCurrent = false)
        }
        override fun reopen(move: FolderMove) { reopened++ }
    }

    private fun picked(rel: String): Uri {
        p.shell("rm -rf ${p.fsPath("primary", rel)}")
        p.shell("mkdir -p ${p.fsPath("primary", rel)}")
        return p.pickTree("primary", rel)
    }

    /** A Keepfin folder with media of several sizes (one a few MB, so a stop can land inside the run), an empty space folder and records. */
    private fun seed(old: SafFolderAccess): Map<String, ByteArray> {
        KeepfinFolders.create(old, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "2026-10-15T00:00:00Z")
        val files = LinkedHashMap<String, ByteArray>()
        files["Music/Rock/a.mp3"] = ByteArray(300_000) { (it * 7).toByte() }
        files["Music/b.mp3"] = ByteArray(1_200) { (it * 3).toByte() }
        files["Videos/big.mp4"] = ByteArray(3_000_000) { (it * 11).toByte() }
        files["Downloads/Show/ep 1 é.mp4"] = ByteArray(90_000) { (it xor 5).toByte() }
        files[".keepfin/ops/s1.seg"] = ByteArray(5_000) { 9 }
        files[".keepfin/art/cover.jpg"] = ByteArray(20_000) { 4 }
        for ((path, bytes) in files) old.publish(path, bytes)
        old.mkdirs("Podcasts/Empty")
        return files
    }

    private fun move(oldTree: Uri, newTree: Uri, libraryId: String) = FolderMove(
        UUID.randomUUID().toString(), libraryId, oldTree.toString(), newTree.toString(),
        DocumentsContract.getTreeDocumentId(oldTree), DocumentsContract.getTreeDocumentId(newTree), MovePhase.COPYING, emptyList(), System.currentTimeMillis(),
    )

    private fun assertSameAsOld(old: SafFolderAccess, new: SafFolderAccess, expected: Map<String, ByteArray>) {
        for ((path, bytes) in expected) {
            assertArrayEquals("$path differs", bytes, new.read(path))
            assertArrayEquals("$path of the old folder changed", bytes, old.read(path))
        }
        assertTrue(new.stat("Podcasts/Empty")!!.isDirectory)
        assertNotNull(new.stat(".keepfin/folder.json"))
        assertEquals(old.read(".keepfin/folder.json").toList(), new.read(".keepfin/folder.json").toList())
        assertNotNull(new.stat(".keepfin/.nomedia"))
        // no staged leftovers anywhere
        fun walk(dir: String): List<String> = new.list(dir).entries.flatMap { e -> val rel = if (dir.isEmpty()) e.name else "$dir/${e.name}"; if (e.isDirectory) walk(rel) else listOf(rel) }
        assertTrue(walk("").none { it.endsWith(".tmp") })
    }

    @Test fun aRealMoveCopiesChecksSwitchesAndLeavesTheOldFolderAsItWas() {
        val oldTree = picked(oldDir)
        val newTree = picked(newDir)
        val old = SafFolderAccess.open(p.cr, oldTree)
        val new = SafFolderAccess.open(p.cr, newTree)
        val expected = seed(old)
        val libraryId = KeepfinFolders.open(old).let { (it as app.keepfin.folder.OpenFolderResult.Ok).folder.libraryId }
        val profiles = ProfileStore(File(work, "profiles.json"))
        profiles.add(Profile("p", "Phone", oldTree.toString(), libraryId, "actor", "now"))
        val host = Host(p.cr, profiles)
        val store = MoveStore(File(work, "folder-move.json"))
        val phases = LinkedHashSet<String>()
        val outcome = MoveRunner(host, store).run(move(oldTree, newTree, libraryId).also { store.write(it) }, { phases += it.phase }, { false })
        assertTrue("outcome $outcome ${(outcome as? MoveOutcome.Failed)?.message}", outcome is MoveOutcome.Done)
        assertSameAsOld(old, new, expected)
        assertEquals(newTree.toString(), profiles.current()!!.folderTreeUri)
        assertEquals(1, host.reopened)
        assertFalse("the library is released at the end", host.held)
        assertEquals(listOf("copying", "finishing", "switching"), phases.toList())
        assertEquals(MovePhase.SWITCHED, store.read()!!.phase)
        // the address of a file in the old folder maps to the same file in the new one
        val m = store.read()!!
        val oldUri = old.documentUriFor("Music/b.mp3").toString()
        val mapped = AndroidMoveHost.movedUri(newTree, m, oldUri)
        assertEquals(new.documentUriFor("Music/b.mp3").toString(), mapped)
        assertNull(AndroidMoveHost.movedUri(newTree, m, "content://media/external/audio/media/5"))
        assertArrayEquals(expected.getValue("Music/b.mp3"), p.cr.openInputStream(Uri.parse(mapped))!!.use { it.readBytes() })
    }

    @Test fun aMoveThatWasInterruptedContinuesWithoutCopyingAgainAndCleansWhatAKillLeftBehind() {
        val oldTree = picked(oldDir)
        val newTree = picked(newDir)
        val old = SafFolderAccess.open(p.cr, oldTree)
        val new = SafFolderAccess.open(p.cr, newTree)
        val expected = seed(old)
        val libraryId = (KeepfinFolders.open(old) as app.keepfin.folder.OpenFolderResult.Ok).folder.libraryId
        val profiles = ProfileStore(File(work, "profiles.json"))
        profiles.add(Profile("p", "Phone", oldTree.toString(), libraryId, "actor", "now"))
        val host = Host(p.cr, profiles)
        val file = File(work, "folder-move.json")
        MoveStore(file).write(move(oldTree, newTree, libraryId))
        // the run is stopped part-way: inside the big file, so a whole file of the new place is complete and the rest is not there
        var bytesInBig = 0L
        val first = MoveRunner(host, MoveStore(file)).run(MoveStore(file).read()!!, { if (it.current == "Videos/big.mp4") bytesInBig = it.bytesDone }, { bytesInBig > 100_000 })
        assertTrue(first is MoveOutcome.Stopped)
        assertEquals(oldTree.toString(), profiles.current()!!.folderTreeUri)
        assertNull("a stopped file is not published half-written", new.stat("Videos/big.mp4"))
        assertEquals(MovePhase.COPYING, MoveStore(file).read()!!.phase)
        // what a killed process leaves in the new place: the staged name of a file
        new.publish("Music/b.mp3.1a2b3c4d.tmp", ByteArray(40))
        // "the next start": a new store and a new runner read the journal and finish the move
        val second = MoveRunner(host, MoveStore(file)).run(MoveStore(file).read()!!, {}, { false })
        assertTrue(second is MoveOutcome.Done)
        assertSameAsOld(old, new, expected)
        assertEquals(newTree.toString(), profiles.current()!!.folderTreeUri)
    }

    @Test fun stoppingTheMoveRemovesOnlyWhatItMadeAndTheOldFolderNeverChanged() {
        val oldTree = picked(oldDir)
        val newTree = picked(newDir)
        val old = SafFolderAccess.open(p.cr, oldTree)
        val new = SafFolderAccess.open(p.cr, newTree)
        val expected = seed(old)
        val libraryId = (KeepfinFolders.open(old) as app.keepfin.folder.OpenFolderResult.Ok).folder.libraryId
        val profiles = ProfileStore(File(work, "profiles.json"))
        profiles.add(Profile("p", "Phone", oldTree.toString(), libraryId, "actor", "now"))
        val host = Host(p.cr, profiles)
        val store = MoveStore(File(work, "folder-move.json"))
        store.write(move(oldTree, newTree, libraryId))
        val runner = MoveRunner(host, store)
        var done = 0
        assertTrue(runner.run(store.read()!!, { done = it.done }, { done >= 2 }) is MoveOutcome.Stopped)
        assertTrue(new.list("").entries.isNotEmpty())
        assertNull(runner.discard(store.read()!!))
        assertEquals(emptyList<String>(), new.list("").entries.map { it.name })
        assertNull(store.read())
        for ((path, bytes) in expected) assertArrayEquals(bytes, old.read(path))
        assertEquals(oldTree.toString(), profiles.current()!!.folderTreeUri)
    }

    @Test fun placesAndroidCannotUseAsAFolderAreRefused() {
        val drive = Uri.parse("content://com.google.android.apps.docs.storage/tree/acc%3Aroot")
        assertFalse(app.keepfin.setup.FolderPlaces.supportedAuthority(drive.authority))
        try {
            SafFolderAccess.open(p.cr, drive)
            fail("expected UnsupportedProvider")
        } catch (e: FolderError.UnsupportedProvider) {
            assertTrue(e.message!!.contains("not supported"))
        }
        assertEquals(app.keepfin.setup.SetupTexts.FORBIDDEN, MovePlanning.problem("primary:Keepfin", "primary:Download", false))
    }
}
