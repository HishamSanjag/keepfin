package app.keepfin.merge

import app.keepfin.core.library.DiffContext
import app.keepfin.core.library.LibItem
import app.keepfin.core.library.LibModel
import app.keepfin.core.library.LibRoutes
import app.keepfin.core.library.LibraryMapping
import app.keepfin.core.library.RecordingObserved
import app.keepfin.folder.FakeFolderAccess
import app.keepfin.folder.FolderLayout
import app.keepfin.folder.KeepfinStore
import app.keepfin.folder.OpDraft
import app.keepfin.folder.ProfileStore
import app.keepfin.folder.StoreOptions
import app.keepfin.folder.sql.JdbcHost
import app.keepfin.folder.sql.SqlMergeState
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The review before Combine: a dry run that counts and names, writes no batch, and shares its hash results with the real run. */
class DryRunTest {
    private lateinit var root: File
    private val stores = ArrayList<KeepfinStore>()
    private val sId = "11111111-1111-4111-8111-111111111111"
    private val tId = "22222222-2222-4222-8222-222222222222"

    @Before fun setUp() { root = Files.createTempDirectory("keepfin-dry").toFile() }
    @After fun tearDown() { stores.forEach { it.abandon() }; root.deleteRecursively() }

    private fun sha(bytes: ByteArray) = "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun drafts(actor: String, replica: String, id: String, title: String, place: String?, hash: String, size: Long): List<OpDraft> {
        val item = LibItem(id = id, space = "music", kind = "audio", title = title, addedAt = 1_000.0, place = place)
        val ctx = DiffContext(actor, replica, { UUID.randomUUID().toString() }, { 0L }, { emptyList() }, RecordingObserved())
        val model = LibModel(listOf(item), emptyList(), emptyList(), LibRoutes("music", "downloads"))
        val out = LibraryMapping.diff(null, model, ctx).filter { it.kind != "profile.set" }.map { OpDraft(it.kind, it.body) }.toMutableList()
        out.add(1, LibraryMapping.reviseDraft(id, hash, size, emptyList()).let { OpDraft(it.kind, it.body) })
        return out
    }

    private class Library(val store: KeepfinStore, val state: SqlMergeState, val replica: String, val layout: FolderLayout, val dir: File)

    private fun open(name: String, libraryId: String, actor: String, folder: FakeFolderAccess): Library {
        val dir = File(root, "dev-$name")
        val replica = if (name == "s") "a1a1a1a1-a1a1-4a1a-8a1a-a1a1a1a1a1a1" else "b2b2b2b2-b2b2-4b2b-8b2b-b2b2b2b2b2b2"
        val state = SqlMergeState(File(dir, "state/state.db").path, libraryId, replica, null, JdbcHost())
        val store = KeepfinStore.open(
            StoreOptions(
                folder = folder, libraryId = libraryId, actor = actor, deviceDir = dir, outboxDir = File(dir, "outbox"),
                newId = { UUID.randomUUID().toString() }, state = state, flushIdleMs = 3_600_000,
            ),
        ).get(10, TimeUnit.SECONDS).also { stores += it }
        return Library(store, state, replica, FolderLayout(""), dir)
    }

    private val env by lazy { newEnv() }

    private fun newEnv() = object : MergeEnv {
        override val filesDir: File get() = root
        override val profiles = ProfileStore(File(root, "profiles.json"))
        override val sqlHost = JdbcHost()
        override val signer = null
        override fun folder(treeUri: String) = error("unused")
        override fun offer(deviceId: String) = null
        override fun currentProfile() = null
        override fun openLibrary(): OpenLibrary = error("unused")
        override fun join(journal: MergeJournal, progress: (String, Int) -> Unit, cancelled: () -> Boolean): JoinOutcome = error("unused")
        override fun peer(journal: MergeJournal): PeerPort? = null
        override fun reopen() {}
        override fun remapOutside(maps: IdMaps) {}
        override fun refusal(): String? = null
        override fun copyRunning() = false
        override fun replayPlays(libraryId: String, plays: List<MergeFence.Play>, maps: IdMaps?) {}
    }

    @Test fun theDryRunCountsAndNamesAndWritesNoBatch() {
        val same = ByteArray(300) { (it % 251).toByte() }
        val fresh = ByteArray(400) { (it % 241).toByte() }
        val sFolder = FakeFolderAccess()
        val s = open("s", sId, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", sFolder)
        for ((path, title, bytes) in listOf(Triple("Music/same.bin", "Same", same), Triple("Music/new.bin", "New", fresh), Triple("Music/new copy.bin", "New again", fresh))) {
            sFolder.mkdirs("Music")
            sFolder.publish(path, bytes)
            s.store.apply(drafts("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", s.replica, UUID.randomUUID().toString(), title, path, sha(bytes), bytes.size.toLong())).get(10, TimeUnit.SECONDS)
        }
        val t = open("t", tId, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", FakeFolderAccess())
        t.store.apply(drafts("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", t.replica, UUID.randomUUID().toString(), "Shared song", null, sha(same), same.size.toLong())).get(10, TimeUnit.SECONDS)

        val sOpen = OpenLibrary(s.store, s.state, s.replica, sId, "Mine", s.layout, s.dir)
        val staged = Staged(t.store, t.state, LibraryReader(t.store, t.state, t.replica), t.layout, t.replica, t.store.actorId)
        val dir = File(root, "work")
        val hashes = HashCache(env.sqlHost, File(root, "hashes.db").path)
        val planner = ForwardPlanner(env, sFolder, sOpen, staged, "merge-1", dir, dryRun = true, hashes = hashes, cancelled = { false }) { _, _ -> }
        val plan = planner.run()
        hashes.close()

        val c = planner.counts
        assertEquals(3L, plan.items)
        assertEquals(listOf(1L, 1L, 1L, 0L), listOf(c.added, c.linked, c.keptDuplicates, c.missingFiles))
        assertEquals(3L, c.mapLines)
        // nothing is written for the real run: no batch, map or artwork file
        assertFalse(File(dir, "batches").exists())
        assertFalse(File(dir, "map.jsonl").exists())
        // the rows carry what they are about, never an id
        val rows = PlanFiles.lines(File(dir, "review-view.jsonl")).map { org.json.JSONObject(it) }.toList()
        assertEquals(c.reviewTotal, rows.size.toLong())
        assertTrue(rows.any { it.getString("kind") == "duplicateInS" && it.getString("title").startsWith("New") })
        assertTrue(rows.all { it.has("title") })
    }

    @Test fun aHashIsKeptOnlyForTheSizeAndTimeItWasReadAt() {
        val cache = HashCache(JdbcHost(), File(root, "h.db").path)
        cache.put("Music/a.bin", 10, 500, "sha256:aa")
        assertEquals("sha256:aa", cache.get("Music/a.bin", 10, 500))
        assertNull(cache.get("Music/a.bin", 11, 500))
        assertNull(cache.get("Music/a.bin", 10, 501))
        cache.close()
        // it survives a restart
        val again = HashCache(JdbcHost(), File(root, "h.db").path)
        assertEquals("sha256:aa", again.get("Music/a.bin", 10, 500))
        again.close()
    }

    @Test fun aLibraryWithAnUnreadablePartIsNotCombined() {
        assertTrue(MergeRefusals.unreadable(listOf("unreadable-segment:abc:ops/x")))
        assertFalse(MergeRefusals.unreadable(listOf("other-finding", "dup:1")))
        assertFalse(MergeRefusals.unreadable(emptyList()))
    }

    @Test fun aReviewNobodyConfirmedIsNotARunningMerge() {
        val store = JournalStore(File(root, "merges"))
        val j = MergeJournal(
            mergeId = "m", stage = MergeStage.Joined, peerDeviceId = "peer", keepMine = false, treeUri = "t", sProfileId = "p1", tProfileId = "p2",
            sLibraryId = sId, sReplicaId = "sr", sActor = "sa", sName = "Mine", sRecords = "", tLibraryId = tId, tName = "Main", startedAt = 1,
            started = false, preview = PreviewNumbers(3, 1, 1, 1, 0, 1, 2, 1, 4, 12),
        )
        store.write(j)
        assertEquals(j, store.read("m"))
        assertEquals("m", store.unfinished()!!.mergeId)
        assertNull(store.running())
        store.write(j.copy(started = true))
        assertEquals("m", store.running()!!.mergeId)
    }
}
