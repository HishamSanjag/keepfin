package app.keepfin.cloud

import app.keepfin.copies.CopyRunner
import app.keepfin.copies.ImportOutcome
import app.keepfin.copies.MainHost
import app.keepfin.copies.RunConfig
import app.keepfin.copies.RunReport
import app.keepfin.core.copies.Action
import app.keepfin.core.copies.CopyMode
import app.keepfin.core.copies.DesiredEntry
import app.keepfin.folder.FakeFolderAccess
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

/**
 * The copies engine (the same runner as for a card or a drive) over a cloud account with no local mirror of the media: a backup, a
 * two-way edit coming back, and a mirror removal, on the desktop's fake Drive, OneDrive, Dropbox and WebDAV and on a real WebDAV server.
 * What the phone writes is read back by the DESKTOP transport (the layout the desktop expects), and a file the desktop writes is
 * imported by the phone.
 */
@RunWith(Parameterized::class)
internal class CloudCopiesTest(private val target: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun targets(): List<String> = TARGETS
    }

    private lateinit var peer: NodePeer
    private lateinit var rig: Rig
    private val main = FakeFolderAccess()
    private val desired = LinkedHashMap<String, DesiredEntry>()
    private val deleted = HashSet<String>()
    private val imported = ArrayList<String>()
    private lateinit var stateDir: File
    private var runNo = 0

    private val host = object : MainHost {
        override fun importNew(action: Action, stagedPath: String, destPath: String): ImportOutcome {
            main.rename(stagedPath, destPath.substringAfterLast('/'))
            imported.add(destPath)
            return ImportOutcome("new-${imported.size}", "op-new", destPath)
        }

        override fun reviseFromCopy(action: Action, stagedPath: String): String? {
            main.remove(action.path)
            main.rename(stagedPath, action.path.substringAfterLast('/'))
            return "op-rev"
        }

        override fun restoreEditedDeleted(action: Action, stagedPath: String): String? = reviseFromCopy(action, stagedPath)
        override fun deleteItem(action: Action) {}
    }

    @Before fun setUp() {
        peer = NodePeer.require()
        peer.reset()
        rig = Rig(peer, target, Rig.uniqueRoot())
        stateDir = java.nio.file.Files.createTempDirectory("kf-cloud-copies").toFile()
        rig.access.mkdirs(".keepfin")
        rig.access.publish(".keepfin/folder.json", """{"format":1,"libraryId":"lib","replicaId":"phone-copy-$target","createdAt":"2026-10-16T00:00:00Z","generation":0}""".toByteArray())
    }

    @After fun tearDown() {
        if (::peer.isInitialized) peer.reset()
    }

    private fun put(path: String, data: ByteArray) {
        main.mkdirs(path.substringBeforeLast('/', ""))
        main.files[path] = data
        desired[path] = DesiredEntry(path, "item-$path", "op-$runNo-$path", contentIdOf(data), data.size.toLong())
    }

    private fun drop(path: String) {
        desired.remove(path)?.let { deleted.add(it.itemId) }
        main.files.remove(path)
    }

    private fun run(mode: CopyMode, dry: Boolean = false, confirmed: Boolean = true): RunReport {
        runNo++
        val r = CopyRunner.run(
            RunConfig(
                main = main, copy = rig.access, stateDir = stateDir, placeId = "place-$target", mode = mode, device = "phone", desired = desired.values.toList().asSequence(), deleted = deleted.toSet(),
                previewConfirmed = confirmed, dryRun = dry, host = host, relistDelayMs = 50, parallelism = 2, runId = "run$runNo", mainReplica = "main-replica", copyReplica = "phone-copy-$target",
                lockHeartbeatMs = 60_000, lockStaleMs = 300_000,
            ),
        )
        assertTrue("run ${runNo} ended ${r.status} ${r.reason ?: ""} ${r.exec?.failed?.joinToString { it.path + ':' + it.reason }}", r.status == "done" || r.status == "preview")
        return r
    }

    private fun planIsEmpty(mode: CopyMode): Boolean {
        val r = run(mode, dry = true)
        return r.status == "preview" && r.summary!!.counts.values.sum() == 0
    }

    private fun text(path: String) = rig.access.read(path).toString(Charsets.UTF_8)

    private fun everywhere(): Map<String, String> {
        val out = HashMap<String, String>()
        fun walk(dir: String) {
            for (e in rig.access.list(dir).entries) {
                val p = if (dir.isEmpty()) e.name else "$dir/${e.name}"
                if (e.isDirectory) walk(p) else if (e.size < 1_000_000) out[p] = rig.access.read(p).toString(Charsets.UTF_8)
            }
        }
        walk("")
        walk(".keepfin")
        return out
    }

    @Test fun aBackupIsWrittenInTheDesktopsLayoutAndKeepsEarlierVersions() {
        put("Music/a.txt", "first".toByteArray())
        put("Music/Sub/b.bin", testBytes(2 * 1024 * 1024 + 11))
        put("c.txt", "see you".toByteArray())
        val first = run(CopyMode.Backup)
        assertEquals(3, first.exec!!.committed)
        // the DESKTOP reads exactly what the phone wrote, hashes included
        val seen = rig.desktopFiles()
        assertEquals(setOf("Music/a.txt", "Music/Sub/b.bin", "c.txt"), seen.keys)
        assertEquals(sha256Hex("first".toByteArray()), seen.getValue("Music/a.txt").getString("sha256").removePrefix("sha256:"))
        assertEquals(sha256Hex(main.files.getValue("Music/Sub/b.bin")), seen.getValue("Music/Sub/b.bin").getString("sha256").removePrefix("sha256:"))
        assertTrue("nothing left half-done", everywhere().keys.none { it.contains(".kftmp") })
        assertTrue("a second look finds nothing to do", planIsEmpty(CopyMode.Backup))

        // an edit: the copy gets the new bytes and the old ones are kept as an earlier version, moved on the provider's side
        put("Music/a.txt", "second, longer".toByteArray())
        run(CopyMode.Backup)
        assertEquals("second, longer", text("Music/a.txt"))
        val versions = everywhere().filterKeys { it.startsWith(".keepfin/versions/") && !it.endsWith("catalogue.jsonl") }
        assertEquals(listOf("first"), versions.values.toList())
        assertTrue(planIsEmpty(CopyMode.Backup))

        // a deletion in the library: a backup keeps what was deleted
        drop("c.txt")
        run(CopyMode.Backup)
        assertTrue("the bytes of the deleted file are still on the account", everywhere().values.any { it == "see you" })
        assertTrue(planIsEmpty(CopyMode.Backup))
    }

    @Test fun aTwoWayCopyBringsAFileTheDesktopWroteBackToThePhone() {
        put("Music/a.txt", "from the phone".toByteArray())
        run(CopyMode.TwoWay)
        rig.desktopPut("Music/from-desktop.txt", "hello from the computer")
        run(CopyMode.TwoWay)
        assertTrue("imported: $imported", imported.contains("Music/from-desktop.txt"))
        assertEquals("hello from the computer", main.files["Music/from-desktop.txt"]?.toString(Charsets.UTF_8))
        assertEquals("from the phone", text("Music/a.txt"))
        assertTrue(planIsEmpty(CopyMode.TwoWay))
    }

    @Test fun aMirrorRemovesWhatWasDeletedAndKeepsItInTheTrash() {
        for (i in 0 until 15) put("Music/song$i.txt", "song number $i".toByteArray())
        run(CopyMode.Mirror)
        assertEquals(15, rig.desktopFiles(false).size)
        drop("Music/song3.txt")
        run(CopyMode.Mirror)
        assertNull("gone from the copy", rig.access.stat("Music/song3.txt"))
        assertEquals(14, rig.desktopFiles(false).size)
        assertTrue("its bytes wait in the trash", everywhere().filterKeys { it.startsWith(".keepfin/trash/") && !it.endsWith("index.jsonl") }.values.contains("song number 3"))
        assertTrue(planIsEmpty(CopyMode.Mirror))
    }

    @Test fun aRunThatCannotListTheAccountChangesNothing() {
        if (target == "davreal") return
        for (i in 0 until 4) put("f$i.txt", "file $i".toByteArray())
        run(CopyMode.Mirror)
        drop("f1.txt")
        // the account answers every listing with a server error: the listing is not complete, so nothing is removed (principle 5)
        val listing = when (providerOf(target)) { "drive" -> "GET" to "/drive/v3/files"; "graph" -> "GET" to "/children"; "dropbox" -> "POST" to "list_folder"; else -> "PROPFIND" to null }
        peer.fault(target, 500, 200, listing.first, listing.second)
        runNo++
        val r = try {
            CopyRunner.run(
                RunConfig(
                    main = main, copy = rig.access, stateDir = stateDir, placeId = "place-$target", mode = CopyMode.Mirror, device = "phone", desired = desired.values.toList().asSequence(), deleted = deleted.toSet(),
                    previewConfirmed = true, host = host, relistDelayMs = 0, parallelism = 1, runId = "run$runNo", mainReplica = "main-replica", copyReplica = "phone-copy-$target", lockHeartbeatMs = 60_000,
                ),
            )
        } catch (e: Exception) { null }
        peer.reset()
        assertNotNull(rig.access.stat("f1.txt"))
        assertEquals("file 1", text("f1.txt"))
        if (r != null) assertTrue("not a normal finish: ${r.status}", r.status != "done" || r.exec?.byKind?.get("RemoveFromCopy") == null)
    }
}
