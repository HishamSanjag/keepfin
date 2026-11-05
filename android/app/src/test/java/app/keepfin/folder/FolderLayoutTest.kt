package app.keepfin.folder

import app.keepfin.core.sync.JStr
import app.keepfin.net.JoinError
import app.keepfin.net.JoinFile
import app.keepfin.net.JoinRunner
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Contract section 1 and 2: where records live, folder.json format 2, the pointer switch and the join into a records root. */
class FolderLayoutTest {
    private val s = "11111111-1111-4111-8111-111111111111"
    private val t = "22222222-2222-4222-8222-222222222222"
    private val sReplica = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val tReplica = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private lateinit var root: File
    private val stores = ArrayList<KeepfinStore>()

    @Before fun setUp() { root = Files.createTempDirectory("keepfin-layout").toFile() }
    @After fun tearDown() { stores.forEach { it.abandon() }; root.deleteRecursively() }

    private fun combined(active: String = t) = FolderJson(
        2, active, if (active == t) tReplica else sReplica, "2026-01-01T00:00:00.000Z", if (active == t) "libraries/$t" else "",
        listOf(
            FolderLibrary(s, sReplica, "", if (active == t) "previous" else "active", keepUntil = "2026-11-20T00:00:00Z", mergeId = "m1"),
            FolderLibrary(t, tReplica, "libraries/$t", if (active == t) "active" else "left", since = "2026-10-21T00:00:00Z", mergeId = "m1"),
        ),
    )

    @Test fun layoutPathsAreBelowTheRecordsRoot() {
        assertEquals(".keepfin/ops", FolderLayout.DEFAULT.ops)
        val l = FolderLayout.forLibrary(t)
        assertEquals(".keepfin/libraries/$t/ops", l.ops)
        assertEquals(".keepfin/libraries/$t/checkpoints", l.checkpoints)
        assertEquals(".keepfin/libraries/$t/membership", l.membership)
        assertEquals(".keepfin/libraries/$t/trash", l.trash)
        assertEquals(".keepfin/libraries/$t/merge/m1", l.mergeDir("m1"))
        assertEquals(".keepfin/folder.json", KeepfinFolders.FOLDER_JSON)
        try { FolderLayout("../x"); fail() } catch (e: IllegalArgumentException) { }
    }

    @Test fun formatOneIsWrittenUnchangedAndFormatTwoRoundTrips() {
        val one = FolderJson(1, s, sReplica, "2026-01-01T00:00:00.000Z")
        assertEquals("{\n  \"format\": 1,\n  \"libraryId\": \"$s\",\n  \"replicaId\": \"$sReplica\",\n  \"createdAt\": \"2026-01-01T00:00:00.000Z\"\n}", KeepfinFolders.folderJsonText(one))
        val two = combined()
        val text = KeepfinFolders.folderJsonText(two)
        val back = (KeepfinFolders.parse(text.toByteArray()) as OpenFolderResult.Ok)
        assertFalse(back.readOnly)
        assertEquals(two, back.folder)
        assertEquals(FolderLayout("libraries/$t"), back.folder.layout)
        // an older reader sees the active identity in the first four fields
        assertTrue(text.startsWith("{\n  \"format\": 2,\n  \"libraryId\": \"$t\",\n  \"replicaId\": \"$tReplica\""))
        // the pointer can name the previous library again: records at .keepfin/ itself
        assertEquals(FolderLayout.DEFAULT, (KeepfinFolders.parse(KeepfinFolders.folderJsonText(combined(s)).toByteArray()) as OpenFolderResult.Ok).folder.layout)
    }

    @Test fun anUnsafeRecordsPathIsCorruptAndANewerFormatIsReadOnly() {
        val bad = KeepfinFolders.folderJsonText(combined().copy(records = "../outside"))
        assertTrue(KeepfinFolders.parse(bad.toByteArray()) is OpenFolderResult.Corrupt)
        val abs = KeepfinFolders.folderJsonText(combined().copy(records = "/etc"))
        assertTrue(KeepfinFolders.parse(abs.toByteArray()) is OpenFolderResult.Corrupt)
        val newer = KeepfinFolders.folderJsonText(combined()).replace("\"format\": 2", "\"format\": 3")
        assertTrue((KeepfinFolders.parse(newer.toByteArray()) as OpenFolderResult.Ok).readOnly)
    }

    @Test fun aClonedCombinedFolderKeepsItsLibrariesAndMovesTheActiveReplica() {
        val fake = FakeFolderAccess()
        fake.mkdirs(".keepfin")
        fake.replace(KeepfinFolders.FOLDER_JSON, KeepfinFolders.folderJsonText(combined()).toByteArray())
        val updated = KeepfinFolders.reassignReplica(fake, combined(), "cccccccc-cccc-4ccc-8ccc-cccccccccccc", "2026-02-02T00:00:00Z")
        assertEquals("cccccccc-cccc-4ccc-8ccc-cccccccccccc", updated.replicaId)
        assertEquals(sReplica, updated.libraries.first { it.libraryId == s }.replicaId)
        assertEquals("cccccccc-cccc-4ccc-8ccc-cccccccccccc", updated.libraries.first { it.libraryId == t }.replicaId)
        assertNotNull(fake.stat(".keepfin/libraries/$t/actors/clone-cccccccc-cccc-4ccc-8ccc-cccccccccccc.json"))
    }

    // ---- a store on a records root -----------------------------------------------------------------------------------

    @Test fun aStoreOnARecordsRootWritesOnlyBelowIt() {
        val fake = FakeFolderAccess()
        KeepfinFolders.create(fake, s, sReplica, "2026-01-01T00:00:00.000Z")
        val layout = FolderLayout.forLibrary(t)
        val store = KeepfinStore.open(
            StoreOptions(
                folder = fake, libraryId = t, actor = "dddddddd-dddd-4ddd-8ddd-dddddddddddd", deviceDir = File(root, "dev"), outboxDir = File(root, "out"),
                newId = { java.util.UUID.randomUUID().toString() }, state = OpLogState(), flushIdleMs = 3_600_000, layout = layout,
            ),
        ).get(10, TimeUnit.SECONDS).also { stores += it }
        store.apply(listOf(OpDraft("profile.set", mapOf("field" to JStr("name"), "value" to JStr("x"))))).get(10, TimeUnit.SECONDS)
        store.flush().get(10, TimeUnit.SECONDS)
        assertEquals(1, fake.files.keys.count { it.startsWith("${layout.ops}/") && it.endsWith(".seg") })
        assertTrue("S's records are untouched", fake.files.keys.none { it.startsWith(".keepfin/ops/") })
        assertNotNull(fake.stat("${layout.actors}"))
        assertNotNull("folder.json stays in .keepfin/", fake.stat(KeepfinFolders.FOLDER_JSON))
    }

    // ---- the pointer switch --------------------------------------------------------------------------------------------

    private class Crash : RuntimeException()

    /** The provider's replace in steps: stage, set the old file aside, rename the staged file, delete the aside. A crash can follow any step. */
    private class StagedReplace(val base: FakeFolderAccess, var crashAfter: Int) : FolderAccess by base {
        override fun replace(path: String, bytes: ByteArray) {
            if (base.stat(path) == null) { base.publish(path, bytes); return }
            val dir = parentOf(path)
            val name = nameOf(path)
            val unique = java.util.UUID.randomUUID().toString().take(8)
            val staged = join(dir, "$name.$unique.stage")
            val aside = "$name.$unique.old"
            var step = 0
            fun after() { if (++step > crashAfter) throw Crash() }
            base.publish(staged, bytes); after()
            base.rename(path, aside); after()
            base.rename(staged, name); after()
            base.remove(join(dir, aside)); after()
        }
    }

    @Test fun aCrashAtAnyPointOfTheSwitchLandsOnTheOldOrTheNewLibraryNeverNeither() {
        val seen = HashSet<String>()
        for (crashAfter in 0..4) {
            val base = FakeFolderAccess()
            KeepfinFolders.create(base, s, sReplica, "2026-01-01T00:00:00.000Z")
            val staged = StagedReplace(base, crashAfter)
            try {
                staged.replace(KeepfinFolders.FOLDER_JSON, KeepfinFolders.folderJsonText(combined()).toByteArray())
            } catch (e: Crash) { }
            val opened = KeepfinFolders.open(base)
            assertTrue("crash after step $crashAfter gave $opened", opened is OpenFolderResult.Ok)
            val lib = (opened as OpenFolderResult.Ok).folder.libraryId
            assertTrue(lib == s || lib == t)
            seen += lib
            // a second attempt (what recovery does) always ends on the new pointer
            val retry = StagedReplace(base, 99)
            retry.replace(KeepfinFolders.FOLDER_JSON, KeepfinFolders.folderJsonText(combined()).toByteArray())
            assertEquals(t, (KeepfinFolders.open(base) as OpenFolderResult.Ok).folder.libraryId)
        }
        assertEquals("both outcomes happen across the crash points", setOf(s, t), seen)
    }

    // ---- joining into a records root -----------------------------------------------------------------------------------

    @Test fun theEmptinessCheckAppliesToTheRecordsRootNotToTheMediaFolder() {
        val fake = FakeFolderAccess()
        KeepfinFolders.create(fake, s, sReplica, "2026-01-01T00:00:00.000Z")
        fake.publish("Music/song.mp3", ByteArray(3))
        val layout = FolderLayout.forLibrary(t)
        val record = JoinRunner.prepare(t, "peer-1", "Main", fake, layout)
        assertNotNull("the record sits beside the records root", fake.stat(JoinFile.pathOf(layout)))
        assertEquals(".keepfin/libraries/$t.join.json", JoinFile.pathOf(layout))
        assertTrue(fake.stat(layout.ops)?.isDirectory == true)
        assertEquals(s, (KeepfinFolders.open(fake) as OpenFolderResult.Ok).folder.libraryId)
        // an interrupted join continues with the same replica and actor
        val again = JoinRunner.prepare(t, "peer-1", "Main", fake, layout)
        assertEquals(record.replicaId, again.replicaId)
        assertEquals(record.actor, again.actor)
    }

    @Test fun aRecordsRootThatAlreadyHasContentIsRefusedAndAnotherPeerCannotTakeOverTheRecord() {
        val fake = FakeFolderAccess()
        KeepfinFolders.create(fake, s, sReplica, "2026-01-01T00:00:00.000Z")
        val layout = FolderLayout.forLibrary(t)
        fake.publish("${layout.root}/something.txt", ByteArray(1))
        try { JoinRunner.prepare(t, "peer-1", "Main", fake, layout); fail() } catch (e: JoinError) { assertEquals("folder", e.code) }
        fake.remove(layout.root)
        JoinRunner.prepare(t, "peer-1", "Main", fake, layout)
        try { JoinRunner.prepare(t, "peer-2", "Main", fake, layout); fail() } catch (e: JoinError) { assertEquals("folder", e.code) }
    }

    @Test fun theJoinRecordIsWrittenBeforeTheRecordsRootIsCreatedSoACrashResumes() {
        val layout = FolderLayout.forLibrary(t)
        val base = FakeFolderAccess()
        KeepfinFolders.create(base, s, sReplica, "2026-01-01T00:00:00.000Z")
        // the creation of the records root fails once (a crash right after the record)
        val failing = object : FolderAccess by base {
            override fun mkdirs(dir: String) {
                if (dir.startsWith(layout.root)) throw Crash()
                base.mkdirs(dir)
            }
        }
        try { JoinRunner.prepare(t, "peer-1", "Main", failing, layout); fail() } catch (e: Crash) { }
        val record = JoinFile.read(base, layout)
        assertNotNull("the record outlived the crash", record)
        assertNull("no records root yet", base.stat(layout.ops))
        val resumed = JoinRunner.prepare(t, "peer-1", "Main", base, layout)
        assertEquals(record!!.replicaId, resumed.replicaId)
        assertNotNull(base.stat(layout.ops))
    }

    @Test fun theFirstJoinOfAFreshFolderAlsoWritesTheRecordBeforeTheFolder() {
        val base = FakeFolderAccess()
        val failing = object : FolderAccess by base {
            override fun publish(path: String, bytes: ByteArray): PublishResult {
                if (path == KeepfinFolders.FOLDER_JSON) throw Crash()
                return base.publish(path, bytes)
            }
        }
        try { JoinRunner.prepare(t, "peer-1", "Main", failing); fail() } catch (e: Crash) { }
        val record = JoinFile.read(base)
        assertNotNull(record)
        val resumed = JoinRunner.prepare(t, "peer-1", "Main", base)
        assertEquals(record!!.replicaId, resumed.replicaId)
        val opened = KeepfinFolders.open(base) as OpenFolderResult.Ok
        assertEquals(t, opened.folder.libraryId)
        assertEquals(record.replicaId, opened.folder.replicaId)
    }
}
