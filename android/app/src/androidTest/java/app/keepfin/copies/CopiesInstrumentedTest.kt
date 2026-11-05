package app.keepfin.copies

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.core.copies.Action
import app.keepfin.core.copies.CopyMode
import app.keepfin.core.copies.DesiredEntry
import app.keepfin.folder.FolderAccess
import app.keepfin.folder.FolderError
import app.keepfin.folder.SafFolderAccess
import app.keepfin.proto.ProbeSupport
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The copies engine against the real document provider: the Keepfin folder on primary storage and a copy on the virtual SD card
 * (when the device has none, a second folder on primary storage). Local generated files only; no network.
 */
@RunWith(AndroidJUnit4::class)
class CopiesInstrumentedTest {
    private val p = ProbeSupport("KeepfinCopiesTest")
    private val mainDir = "KeepfinCopiesMain"
    private val copyDir = "KeepfinCopiesCard"
    private lateinit var vol: String
    private lateinit var main: SafFolderAccess
    private lateinit var copy: SafFolderAccess
    private val work = File(p.ctx.cacheDir, "copies-test-" + UUID.randomUUID())
    private val desired = LinkedHashMap<String, DesiredEntry>()
    private val deleted = HashSet<String>()
    private var runNo = 0

    @Before fun setUp() {
        vol = p.sdVolume() ?: "primary"
        p.shell("rm -rf ${p.fsPath("primary", mainDir)} ${p.fsPath(vol, copyDir)}")
        p.shell("mkdir -p ${p.fsPath("primary", mainDir)} ${p.fsPath(vol, copyDir)}")
        main = SafFolderAccess.open(p.cr, p.pickTree("primary", mainDir))
        copy = SafFolderAccess.open(p.cr, p.pickTree(vol, copyDir))
        copy.mkdirs(".keepfin")
        copy.publish(".keepfin/folder.json", """{"format":1,"libraryId":"lib","replicaId":"copy-replica","createdAt":"2026-10-16T00:00:00Z","generation":0}""".toByteArray())
    }

    @After fun tearDown() {
        p.releaseAll(mainDir)
        p.releaseAll(copyDir)
        runCatching { p.shell("rm -rf ${p.fsPath("primary", mainDir)} ${p.fsPath(vol, copyDir)}") }
        work.deleteRecursively()
    }

    private fun put(path: String, text: String) {
        val bytes = text.toByteArray()
        if (main.stat(path) != null) main.remove(path)
        val r = main.publish(path, bytes)
        desired[path] = DesiredEntry(path, "item-$path", "op$runNo", "sha256:${r.sha256}", bytes.size.toLong())
    }

    private fun drop(path: String) {
        desired.remove(path)?.let { deleted.add(it.itemId) }
        main.remove(path)
    }

    private fun dump(r: RunReport?): String = "report=${r?.status} failed=${r?.exec?.failed?.map { it.path + ":" + it.reason }} skipped=${r?.exec?.skipped?.map { it.reason }} music=${runCatching { copy.list("Music").entries.map { it.name } }} root=${runCatching { copy.list("").entries.map { it.name } }} mainMusic=${runCatching { main.list("Music").entries.map { it.name } }}"

    private fun text(a: FolderAccess, path: String) = if (a.stat(path) == null) null else a.read(path).toString(Charsets.UTF_8)

    private class Host(val main: FolderAccess) : MainHost {
        val revised = ArrayList<String>()
        override fun importNew(action: Action, stagedPath: String, destPath: String): ImportOutcome {
            main.rename(stagedPath, destPath.substringAfterLast('/'))
            return ImportOutcome("new-item", null, destPath)
        }

        override fun reviseFromCopy(action: Action, stagedPath: String): String? {
            main.remove(action.path)
            main.rename(stagedPath, action.path.substringAfterLast('/'))
            revised += action.path
            return null
        }

        override fun restoreEditedDeleted(action: Action, stagedPath: String) = reviseFromCopy(action, stagedPath)
        override fun deleteItem(action: Action) {}
    }

    private fun run(mode: CopyMode, copyAccess: FolderAccess = copy, fault: FaultHook? = null, confirmHeld: Boolean = false, dry: Boolean = false): RunReport {
        runNo++
        val realHost = Host(main)
        return CopyRunner.run(
            RunConfig(
                main = main, copy = copyAccess, stateDir = File(work, "state-$mode"), placeId = "place", mode = mode, device = "instrumented", desired = desired.values.toList().asSequence(),
                deleted = deleted.toSet(), previewConfirmed = true, confirmHeld = confirmHeld, dryRun = dry, host = realHost, fault = fault, parallelism = 2, runId = "r$runNo", relistDelayMs = 200,
                mainReplica = "main-replica", copyReplica = "copy-replica",
            ),
        )
    }

    @Test fun aBackupKeepsEarlierVersionsOnTheCard() {
        put("Music/a.wav", "alpha"); put("Music/b.wav", "bravo"); put("Videos/c.mp4", "charlie")
        val r1 = run(CopyMode.Backup)
        assertEquals("done", r1.status)
        assertEquals(dump(r1), "alpha", text(copy, "Music/a.wav"))
        put("Music/a.wav", "alpha two")
        assertEquals("done", run(CopyMode.Backup).status)
        assertEquals("alpha two", text(copy, "Music/a.wav"))
        val live = Versions.liveEntries(copy, Store.VERSIONS)
        assertEquals(1, live.size)
        assertEquals("alpha", text(copy, ".keepfin/${live[0].file}"))
        // nothing is left half-written, and a second look finds nothing to do
        assertTrue(CopyInventory.scan(copy, File(work, "inv.jsonl").also { work.mkdirs() }, RulesId.ASCII, File(work, "t")).complete)
        val plan = run(CopyMode.Backup, dry = true)
        assertEquals(0, plan.summary!!.counts.values.sum())
        assertNull(copy.list("Music").entries.firstOrNull { it.name.contains(".kftmp") })
    }

    @Test fun anEditOnTheCardComesBackInTwoWayMode() {
        put("Music/a.wav", "alpha"); put("Music/b.wav", "bravo"); put("Videos/c.mp4", "charlie")
        assertEquals("done", run(CopyMode.TwoWay).status)
        copy.remove("Music/a.wav")
        copy.publish("Music/a.wav", "edited on the card".toByteArray())
        val r = run(CopyMode.TwoWay)
        val why = "status=${r.status} counts=${r.summary?.counts} review=${r.review.map { it.kind }} skipped=${r.exec?.skipped?.map { it.reason }} failed=${r.exec?.failed?.map { it.reason }}"
        assertEquals(why, "done", r.status)
        assertEquals(why, "edited on the card", text(main, "Music/a.wav"))
        assertEquals("edited on the card", text(copy, "Music/a.wav"))
    }

    @Test fun aMirrorSendsRemovedFilesToTheTrash() {
        put("Music/a.wav", "alpha"); put("Music/b.wav", "bravo"); put("Videos/c.mp4", "charlie")
        assertEquals("done", run(CopyMode.Mirror).status)
        drop("Music/b.wav")
        assertEquals("done", run(CopyMode.Mirror).status)
        assertNull(copy.stat("Music/b.wav"))
        val trash = Versions.liveEntries(copy, Store.TRASH)
        assertEquals(1, trash.size)
        assertEquals("bravo", text(copy, ".keepfin/${trash[0].file}"))
    }

    @Test fun ejectionMidRunResumesWhenTheCardIsBack() {
        for (i in 1..8) put("Music/s$i.wav", "song $i ".repeat(20))
        var staged = 0
        var gone = false
        val flaky = object : FolderAccess by copy {
            override fun list(dir: String) = if (gone) throw FolderError.Unavailable("card removed") else copy.list(dir)
            override fun stat(path: String) = if (gone) throw FolderError.Unavailable("card removed") else copy.stat(path)
            override fun publishFrom(path: String, source: java.io.InputStream, onProgress: ((Long) -> Unit)?) = if (gone) throw FolderError.Unavailable("card removed") else copy.publishFrom(path, source, onProgress)
            override fun openRead(path: String) = if (gone) throw FolderError.Unavailable("card removed") else copy.openRead(path)
            override fun read(path: String) = if (gone) throw FolderError.Unavailable("card removed") else copy.read(path)
            override fun replace(path: String, bytes: ByteArray) = if (gone) throw FolderError.Unavailable("card removed") else copy.replace(path, bytes)
            override fun remove(path: String) = if (gone) throw FolderError.Unavailable("card removed") else copy.remove(path)
            override fun mkdirs(dir: String) = if (gone) throw FolderError.Unavailable("card removed") else copy.mkdirs(dir)
            override fun rename(path: String, newName: String) = if (gone) throw FolderError.Unavailable("card removed") else copy.rename(path, newName)
        }
        val r = run(CopyMode.Backup, flaky, fault = FaultHook { s, _, _ -> if (s == "staged" && ++staged == 3) gone = true })
        assertEquals("disconnected", r.status)
        // back again
        val back = run(CopyMode.Backup)
        assertEquals("done", back.status)
        for (i in 1..8) assertEquals("song $i ".repeat(20), text(copy, "Music/s$i.wav"))
        assertEquals(0, copy.list("Music").entries.count { it.name.contains("kftmp") || it.name.endsWith(".tmp") })
        assertEquals(0, run(CopyMode.Backup, dry = true).summary!!.counts.values.sum())
    }

    @Test fun anIncompleteListingNeverRemovesAnything() {
        for (i in 1..4) put("Music/s$i.wav", "song $i")
        assertEquals("done", run(CopyMode.Mirror).status)
        drop("Music/s1.wav")
        val lying = object : FolderAccess by copy {
            override fun list(dir: String) = app.keepfin.folder.FolderListing(emptyList(), true)
        }
        run(CopyMode.Mirror, lying)
        assertNotNull(dump(null), copy.stat("Music/s1.wav"))
        assertEquals("done", run(CopyMode.Mirror).status)
        assertNull(copy.stat("Music/s1.wav"))
    }
}
