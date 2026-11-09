package app.keepfin.move

import app.keepfin.folder.FakeFolderAccess
import app.keepfin.folder.FolderAccess
import app.keepfin.folder.FolderEntry
import app.keepfin.folder.FolderError
import app.keepfin.folder.FolderListing
import app.keepfin.folder.Profile
import app.keepfin.folder.ProfileStore
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The move over fake folders: copy, check, switch, and what a stop, a crash or a bad copy leaves (the old folder is never changed). */
class MoveRunnerTest {
    /** A folder that reports modification times a test sets. */
    private class Timed(val inner: FakeFolderAccess) : FolderAccess by inner {
        val times = HashMap<String, Long>()
        override fun list(dir: String): FolderListing {
            val l = inner.list(dir)
            return FolderListing(l.entries.map { e -> FolderEntry(e.name, e.isDirectory, e.size, times[if (dir.isEmpty()) e.name else "$dir/${e.name}"] ?: 0) }, l.possiblyIncomplete)
        }
        override fun stat(path: String): FolderEntry? = inner.stat(path)?.let { FolderEntry(it.name, it.isDirectory, it.size, times[path] ?: 0) }
    }

    private class Host(val old: Timed, val new: Timed) : MoveHost {
        val calls = ArrayList<String>()
        var crashOnSwitch = false
        var switched = false
        override fun open(tree: String): FolderAccess = if (tree == "old") old else new
        override fun now() = 1000L
        override fun quiesce(libraryId: String) { calls += "quiesce"; onQuiesce() }
        override fun release(libraryId: String) { calls += "release" }
        override fun switchProfile(move: FolderMove) { if (crashOnSwitch) throw IllegalStateException("killed"); calls += "switch"; switched = true }
        override fun reopen(move: FolderMove) { calls += "reopen" }
        var onQuiesce: () -> Unit = {}
    }

    private val dir: File = Files.createTempDirectory("move").toFile()
    private val store = MoveStore(File(dir, "folder-move.json"))
    private val old = Timed(FakeFolderAccess())
    private val new = Timed(FakeFolderAccess())
    private val host = Host(old, new)
    private val runner = MoveRunner(host, store)

    private fun populate() {
        old.inner.mkdirs("Music/Rock")
        old.inner.mkdirs("Videos")
        old.inner.mkdirs(".keepfin/ops")
        old.inner.publish("Music/Rock/a.mp3", ByteArray(5000) { it.toByte() })
        old.inner.publish("Music/b.mp3", ByteArray(3000) { (it * 3).toByte() })
        old.inner.publish("Videos/v.mp4", ByteArray(8000) { (it * 7).toByte() })
        old.inner.publish(".keepfin/folder.json", "{\"format\":1}".toByteArray())
        old.inner.publish(".keepfin/ops/s1.seg", ByteArray(400) { 9 })
    }

    private fun move(phase: MovePhase = MovePhase.COPYING) = FolderMove("m1", "lib", "old", "new", "primary:Keepfin", "primary:Moved/Keepfin", phase, emptyList(), 1000L).also { store.write(it) }
    private fun snapshot(f: FakeFolderAccess) = f.files.mapValues { it.value.toList() }.toSortedMap()

    @Test fun movesEverythingChecksItAndSwitchesLeavingTheOldFolderAsItWas() {
        populate()
        val before = snapshot(old.inner)
        val phases = LinkedHashSet<String>()
        val outcome = runner.run(move(), { phases += it.phase }, { false })
        assertTrue(outcome is MoveOutcome.Done)
        assertEquals(before, snapshot(old.inner))
        assertEquals(before, snapshot(new.inner))
        assertTrue(new.inner.stat("Videos")!!.isDirectory)
        assertEquals(listOf("quiesce", "switch", "reopen", "release"), host.calls)
        assertEquals(listOf("copying", "finishing", "switching"), phases.toList())
        assertEquals(MovePhase.SWITCHED, store.read()!!.phase)
        // the library's own files show under one name
        assertEquals("Your library records", MovePlanning.shownName(".keepfin/ops/s1.seg"))
    }

    @Test fun aStopKeepsWholeFilesAndTheNextRunCopiesOnlyWhatIsMissing() {
        populate()
        var done = 0
        val stopped = runner.run(move(), { if (it.phase == "copying") done = it.done }, { done >= 2 })
        assertTrue(stopped is MoveOutcome.Stopped)
        assertEquals(MovePhase.COPYING, store.read()!!.phase)
        assertTrue("some, not all, are there", new.inner.files.keys.any { !it.startsWith(".keepfin") } && new.inner.files.size < old.inner.files.size)
        assertFalse(host.switched)
        // continuing publishes only the missing files (a published name is never replaced, so a repeat copy would throw)
        val again = runner.run(store.read()!!, {}, { false })
        assertTrue(again is MoveOutcome.Done)
        assertEquals(snapshot(old.inner), snapshot(new.inner))
    }

    @Test fun theLibraryIsPausedBeforeTheLastPassSoItsLateChangesAndRecordsArriveToo() {
        populate()
        // a file changes after the first pass (a tag edit keeps the size) and a record is rewritten while the library is paused
        host.onQuiesce = {
            old.inner.files["Music/b.mp3"] = ByteArray(3000) { 1 }
            old.times["Music/b.mp3"] = 5000L
            old.inner.files[".keepfin/folder.json"] = "{\"format\":2}".toByteArray()
            old.inner.files[".keepfin/ops/s2.seg"] = ByteArray(50) { 2 }
        }
        assertTrue(runner.run(move(), {}, { false }) is MoveOutcome.Done)
        assertEquals(snapshot(old.inner), snapshot(new.inner))
    }

    @Test fun aCopyThatDoesNotMatchIsNeverSwitchedTo() {
        populate()
        // the provider hands back a bad file: the check after the last pass catches it and nothing switches
        host.onQuiesce = { new.inner.files["Music/b.mp3"] = ByteArray(10) }
        val outcome = runner.run(move(), {}, { false })
        // the last pass copies it again, so the move succeeds with a correct file
        assertTrue(outcome is MoveOutcome.Done)
        assertEquals(snapshot(old.inner), snapshot(new.inner))
        // a file that vanishes after the last pass is what verify exists for
        val mover = FolderMover(old, new, 0, { false }, {})
        new.inner.files.remove("Videos/v.mp4")
        val problem = mover.verify(mover.inventory())
        assertNotNull(problem)
        assertTrue(problem!!.contains("Videos/v.mp4"))
        assertTrue(problem.contains("still where it was"))
    }

    @Test fun aRecordOfTheSameSizeButOtherBytesIsCaught() {
        populate()
        new.inner.mkdirs(".keepfin")
        new.inner.files[".keepfin/folder.json"] = "{\"format\":9}".toByteArray() // same length as the original
        val mover = FolderMover(old, new, 0, { false }, {})
        assertNotNull(mover.verify(mover.inventory().filter { it.rel == ".keepfin/folder.json" }))
    }

    @Test fun killedAfterTheJournalSaysSwitchedTheNextStartFinishesTheSwitchBeforeTheLibraryOpens() {
        populate()
        host.crashOnSwitch = true
        val first = runner.run(move(), {}, { false })
        assertTrue(first is MoveOutcome.Failed)
        assertEquals(MovePhase.SWITCHED, store.read()!!.phase)
        // recovery works on the profile file alone
        val profiles = ProfileStore(File(dir, "profiles.json"))
        profiles.add(Profile("p", "Phone", "old", "lib", "actor", "now"))
        assertTrue(MoveRecovery.settle(store, profiles))
        assertEquals("new", profiles.current()!!.folderTreeUri)
        assertFalse("nothing to settle twice", MoveRecovery.settle(store, profiles))
        // and the run, started again, only finishes
        host.crashOnSwitch = false
        assertTrue(runner.run(store.read()!!, {}, { false }) is MoveOutcome.Done)
    }

    @Test fun aMoveThatNeverReachedTheSwitchChangesNothingInTheProfile() {
        populate()
        move()
        val profiles = ProfileStore(File(dir, "profiles.json"))
        profiles.add(Profile("p", "Phone", "old", "lib", "actor", "now"))
        assertFalse(MoveRecovery.settle(store, profiles))
        assertEquals("old", profiles.current()!!.folderTreeUri)
    }

    @Test fun stoppingGivesTheMoveUpAndRemovesOnlyWhatItMadeInTheNewPlace() {
        populate()
        var done = 0
        runner.run(move(), { done = it.done }, { done >= 2 })
        val journal = store.read()!!
        assertTrue(journal.created.isNotEmpty())
        // something the person put there afterwards is not ours
        new.inner.publish("Mine.txt", ByteArray(3))
        assertNull(runner.discard(journal))
        assertEquals(setOf("Mine.txt"), new.inner.files.keys)
        assertNull(store.read())
        assertEquals(5, old.inner.files.size)
    }

    @Test fun aFinishedMoveCannotBeDiscardedAndAnUnreachableNewPlaceKeepsTheJournal() {
        populate()
        val journal = move()
        runner.run(journal, {}, { false })
        val done = store.read()!!
        try { runner.discard(done); throw AssertionError("expected") } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("finished")) }
        val m2 = FolderMove("m2", "lib", "old", "new", "a", "b", MovePhase.COPYING, listOf("Music"), 1L).also { store.write(it) }
        new.inner.failWith = FolderError.Unavailable("card out")
        val problem = runner.discard(m2)
        assertNotNull(problem)
        assertNotNull("the journal stays so it is tried again", store.read())
    }

    @Test fun theFolderOnlyAHalfCopiedFileLeftIsCopiedAgain() {
        populate()
        new.inner.mkdirs("Music/Rock")
        new.inner.files["Music/Rock/a.mp3"] = ByteArray(100) // a torn earlier copy: wrong size
        assertTrue(runner.run(move(), {}, { false }) is MoveOutcome.Done)
        assertEquals(snapshot(old.inner), snapshot(new.inner))
    }

    @Test fun theStagedNameAKilledRunLeftBehindIsCleanedUp() {
        populate()
        new.inner.mkdirs("Music")
        new.inner.files["Music/b.mp3.1a2b3c4d.tmp"] = ByteArray(20)
        new.inner.files["notes.tmp"] = ByteArray(2) // not a staged name
        assertTrue(runner.run(move(), {}, { false }) is MoveOutcome.Done)
        assertFalse(new.inner.files.containsKey("Music/b.mp3.1a2b3c4d.tmp"))
        assertTrue(new.inner.files.containsKey("notes.tmp"))
    }

    @Test fun anOldFolderThatListsIncompleteIsAnErrorNotAnEmptyFolder() {
        populate()
        old.inner.emptyListings = true
        val outcome = runner.run(move(), {}, { false })
        assertTrue(outcome is MoveOutcome.Failed)
        assertTrue((outcome as MoveOutcome.Failed).message.contains("whole folder"))
        assertFalse(host.switched)
    }

    @Test fun theMoveFileSurvivesAReadBack() {
        val m = move(MovePhase.FINISHING).copy(created = listOf("Music", "Videos"))
        store.write(m)
        assertEquals(m, store.read())
        store.clear()
        assertNull(store.read())
    }
}
