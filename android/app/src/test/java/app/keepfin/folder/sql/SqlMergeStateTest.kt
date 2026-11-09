package app.keepfin.folder.sql

import app.keepfin.ItemOrder
import app.keepfin.ItemQuery
import app.keepfin.LibraryItemKind
import app.keepfin.PageDirection
import app.keepfin.core.library.DiffContext
import app.keepfin.core.library.LibItem
import app.keepfin.core.library.LibModel
import app.keepfin.core.library.LibRoutes
import app.keepfin.core.library.LibSpace
import app.keepfin.core.library.LibraryMapping
import app.keepfin.core.library.ObservedAnswer
import app.keepfin.folder.FakeFolderAccess
import app.keepfin.folder.KeepfinStore
import app.keepfin.folder.OpDraft
import app.keepfin.folder.OpLogState
import app.keepfin.folder.StoreOptions
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SqlMergeStateTest {
    private val library = "11111111-1111-4111-8111-111111111111"
    private val actor = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val replica = "a1a1a1a1-a1a1-4a1a-8a1a-a1a1a1a1a1a1"
    private lateinit var root: File
    private val stores = ArrayList<KeepfinStore>()
    private var ids = 0
    private var clock = 1_700_000_000_000L

    @Before fun setUp() {
        root = Files.createTempDirectory("keepfin-sqlstate").toFile()
    }

    @After fun tearDown() {
        stores.forEach { it.abandon() }
        root.deleteRecursively()
    }

    private fun nextId() = "00000000-0000-4000-8000-%012d".format(++ids)

    private fun open(name: String, folder: FakeFolderAccess, state: app.keepfin.folder.MergeState): KeepfinStore {
        val dir = File(root, name)
        return KeepfinStore.open(
            StoreOptions(
                folder = folder, libraryId = library, actor = actor, deviceDir = dir, outboxDir = File(dir, "outbox"), newId = ::nextId,
                now = { clock++ }, flushIdleMs = 3_600_000, checkpointDelayMs = 5, retryMs = 10, state = state,
            ),
        ).get(10, TimeUnit.SECONDS).also { stores += it }
    }

    private fun sql(name: String) = SqlMergeState(File(root, "$name/state.db").path, library, replica, null, JdbcHost())

    private fun model(n: Int): LibModel {
        val spaces = listOf(LibSpace("music", "Music", "music", "Music"), LibSpace("downloads", "Downloads", "mixed", "Downloads"))
        val items = (1..n).map { i ->
            LibItem(
                id = "%08x-0000-4000-8000-000000000000".format(i), space = if (i % 3 == 0) "downloads" else "music", kind = if (i % 3 == 0) "video" else "audio",
                title = "Song $i", artist = "Artist ${i % 5}", album = "Album ${i % 4}", track = (i % 12 + 1).toDouble(), duration = 100.0 + i,
                addedAt = 1000.0 + i, favorite = i % 7 == 0, userTags = if (i % 10 == 0) listOf("road") else emptyList(), place = "Music/Song $i.mp3",
            )
        }
        return LibModel(items, emptyList(), spaces, LibRoutes("music", "downloads"))
    }

    private fun drafts(m: LibModel): List<OpDraft> {
        val ctx = DiffContext(actor, replica, ::nextId, { 0L }, { emptyList() }, ObservedAnswer(emptyMap(), emptyMap(), emptyMap(), emptyMap()))
        return LibraryMapping.diff(null, m, ctx).map { OpDraft(it.kind, it.body) }
    }

    @Test fun sqlStateGivesTheSameViewsAsTheInMemoryLog() {
        val m = model(60)
        val a = open("mem", FakeFolderAccess(), OpLogState())
        val sqlState = sql("sql")
        val b = open("sql", FakeFolderAccess(), sqlState)
        val d = drafts(m)
        a.apply(d).get(10, TimeUnit.SECONDS)
        b.apply(d).get(10, TimeUnit.SECONDS)
        assertEquals(a.viewsJson().get(), b.viewsJson().get())
        assertEquals(a.versionVector().get(), b.versionVector().get())
    }

    @Test fun theIndexFollowsTheOpsAndPagesByKeyset() {
        val m = model(60)
        val state = sql("idx")
        val store = open("idx", FakeFolderAccess(), state)
        store.apply(drafts(m)).get(10, TimeUnit.SECONDS)
        val env = object : BrowseEnv {
            override fun modelSpace(wire: String) = wire
            override fun spaceFolder(modelId: String) = "Music"
            override fun uriFor(place: String) = "keepfin-folder:$place"
            override fun linked(id: String, place: String): LinkedView? = null
            override fun jobIdFor(id: String): String? = null
            override fun itemForJob(jobId: String): String? = null
        }
        val reader = IndexReader(state.statePath, { state.epoch }) { JdbcHost().open(it) }
        val browse = IndexBrowse(reader, env) { LibraryMapping.spaceWireId(it) }
        assertEquals(60, browse.count(ItemQuery()))
        assertEquals(20, browse.count(ItemQuery(kinds = setOf(LibraryItemKind.VIDEO))))
        assertEquals(8, browse.count(ItemQuery(favorite = true)))
        // newest first, in pages, without gaps or repeats
        val seen = ArrayList<String>()
        var key: List<Any>? = null
        var dir = PageDirection.FIRST
        while (true) {
            val p = browse.page(ItemQuery(order = ItemOrder.ADDED), dir, key, 25)
            seen += p.items.map { it.id }
            key = p.next ?: break
            dir = PageDirection.AFTER
        }
        assertEquals(60, seen.size)
        assertEquals(60, seen.toSet().size)
        assertEquals("0000003c-0000-4000-8000-000000000000", seen.first())
        // backwards from the end
        val last = browse.page(ItemQuery(order = ItemOrder.ADDED), PageDirection.BEFORE, browse.keyOf(browse.item(seen[10])!!, ItemOrder.ADDED), 5)
        assertEquals(seen.subList(5, 10), last.items.map { it.id })
        // search and tags
        assertEquals(1, browse.count(ItemQuery(text = "song 17")))
        assertEquals(6, browse.count(ItemQuery(text = "road")))
        assertEquals(6, browse.tags(ItemQuery(), 1).first { it.tag == "road" }.count)
        assertEquals(20, browse.albums(ItemQuery(), null, 50).size)
        assertEquals(5, browse.artists(ItemQuery(), null, 50).size)
        // a change: favourite one more item, rename another
        val one = browse.item("00000001-0000-4000-8000-000000000000")!!
        assertEquals(false, one.favorite)
        val ctx = DiffContext(actor, replica, ::nextId, { 0L }, { emptyList() }, ObservedAnswer(emptyMap(), emptyMap(), emptyMap(), emptyMap()))
        val items = m.items.map { if (it.id.startsWith("00000001")) it.copy(favorite = true, title = "Renamed") else it }
        val after = LibModel(items, emptyList(), m.spaces, m.routes)
        store.apply(LibraryMapping.diff(m, after, ctx).map { OpDraft(it.kind, it.body) }).get(10, TimeUnit.SECONDS)
        val changed = browse.item("00000001-0000-4000-8000-000000000000")!!
        assertEquals(true, changed.favorite)
        assertEquals("Renamed", changed.title)
        assertEquals(9, browse.count(ItemQuery(favorite = true)))
        assertNotNull(state.takeChanges())
    }

    @Test fun aColdBuildFromCheckpointPartsGivesTheSameLibraryAndRemoteChangesStillJoin() {
        val m = model(80)
        val folder = FakeFolderAccess()
        val first = sql("cold1")
        val a = KeepfinStore.open(
            StoreOptions(folder = folder, libraryId = library, actor = actor, deviceDir = File(root, "cold1"), outboxDir = File(root, "cold1/outbox"), newId = ::nextId,
                now = { clock++ }, flushIdleMs = 3_600_000, checkpointEvery = 1, checkpointDelayMs = 5, retryMs = 10, state = first),
        ).get(10, TimeUnit.SECONDS).also { stores += it }
        a.apply(drafts(m)).get(10, TimeUnit.SECONDS)
        val expectedViews = a.viewsJson().get()
        a.close().get(10, TimeUnit.SECONDS) // writes the parts
        assertTrue(folder.files.keys.any { it.contains("/checkpoints/") && it.endsWith(".ckpt") })
        // a new device: no database, only the folder
        val second = sql("cold2")
        val b = KeepfinStore.open(
            StoreOptions(folder = folder, libraryId = library, actor = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", deviceDir = File(root, "cold2"), outboxDir = File(root, "cold2/outbox"), newId = ::nextId,
                now = { clock++ }, flushIdleMs = 3_600_000, checkpointDelayMs = 5, retryMs = 10, state = second),
        ).get(30, TimeUnit.SECONDS).also { stores += it }
        assertTrue("the parts were used", b.stats!!.checkpoint)
        assertEquals(expectedViews, b.viewsJson().get())
        assertEquals(80L, second.query("SELECT COUNT(*) FROM lib_index")[0][0])
        assertNotNull(second.lastBulkLoad)
        // and it keeps working: an edit made on the cold device lands in the index
        b.apply(listOf(OpDraft("set.add", mapOf("set" to app.keepfin.core.sync.JStr("favourites"), "element" to app.keepfin.core.sync.JStr(m.items[1].id))))).get(10, TimeUnit.SECONDS)
        assertEquals(1L, second.query("SELECT fav FROM lib_index WHERE id = ?", listOf(m.items[1].id))[0][0])
    }

    @Test fun aFirstImportInOnePassBuildsTheSameStateAsOpByOp() {
        val m = model(60)
        val lines = ArrayList<String>()
        val producer = open("imp-src", FakeFolderAccess(), OpLogState())
        producer.apply(drafts(m)).get(10, TimeUnit.SECONDS)
        val opsDir = File(root, "imp-src/outbox/ops.jsonl")
        lines += opsDir.readLines().filter { it.isNotEmpty() }
        val one = sql("imp-one")
        val codes = one.importFresh(lines.iterator())
        assertNotNull(codes)
        assertTrue(codes!!.all { it == app.keepfin.folder.AdmitKind.Admitted })
        val many = sql("imp-many")
        many.admit(lines)
        assertEquals(many.viewsJson(), one.viewsJson())
        assertEquals(60L, one.query("SELECT COUNT(*) FROM lib_index")[0][0])
        assertEquals(many.query("SELECT id, title, fav FROM lib_index ORDER BY id"), one.query("SELECT id, title, fav FROM lib_index ORDER BY id"))
    }

    @Test fun anIndexFromBeforeTheRecordingCodeTableOpensWithItsRowsAndKeepsTheCodesItIsGiven() {
        val m = model(40)
        val folder = FakeFolderAccess()
        val state = sql("isrc")
        val store = open("isrc", folder, state)
        store.apply(drafts(m)).get(10, TimeUnit.SECONDS)
        val id = state.query("SELECT id FROM lib_index ORDER BY id LIMIT 1")[0][0] as String
        store.close().get(10, TimeUnit.SECONDS)
        // the previous schema had no lib_isrc
        java.sql.DriverManager.getConnection("jdbc:sqlite:" + File(root, "isrc/state.db").path).use { c -> c.createStatement().use { it.execute("DROP TABLE lib_isrc") } }
        val again = SqlMergeState(File(root, "isrc/state.db").path, library, replica, null, JdbcHost())
        val reopened = open("isrc", folder, again)
        val reader = IndexReader(again.statePath, { again.epoch }) { JdbcHost().open(it) }
        assertEquals(40L, reader.rows("SELECT COUNT(*) FROM lib_index")[0][0])
        assertEquals(0L, reader.rows("SELECT COUNT(*) FROM lib_isrc")[0][0])
        reopened.setIsrc(id, "GBDUW0000053").get(10, TimeUnit.SECONDS)
        assertEquals("GBDUW0000053", reader.rows("SELECT isrc FROM lib_isrc WHERE id = ?", listOf(id))[0][0])
        // a rebuild of the index keeps the code of an item that is still there, and drops the code of one that is not
        reopened.setIsrc("00000000-0000-4000-8000-0000000fffff", "GBDUW0000054").get(10, TimeUnit.SECONDS)
        again.rebuildIndex()
        assertEquals(listOf(listOf<Any?>("GBDUW0000053")), reader.rows("SELECT isrc FROM lib_isrc"))
        assertEquals(40L, reader.rows("SELECT COUNT(*) FROM lib_index")[0][0])
    }

    @Test fun aKeptDatabaseIsOpenedWithoutReplayAndStaysCurrent() {
        val m = model(40)
        val folder = FakeFolderAccess()
        val state = sql("kept")
        val store = open("kept", folder, state)
        store.apply(drafts(m)).get(10, TimeUnit.SECONDS)
        store.close().get(10, TimeUnit.SECONDS)
        val again = SqlMergeState(File(root, "kept/state.db").path, library, replica, null, JdbcHost())
        val reopened = open("kept", folder, again)
        assertTrue(reopened.stats!!.checkpoint)
        assertEquals(0, reopened.stats!!.opsAdmitted)
        val reader = IndexReader(again.statePath, { again.epoch }) { JdbcHost().open(it) }
        val rows = reader.rows("SELECT COUNT(*) FROM lib_index")
        assertEquals(40L, rows[0][0])
        assertNull(reopened.lastError)
    }
}
