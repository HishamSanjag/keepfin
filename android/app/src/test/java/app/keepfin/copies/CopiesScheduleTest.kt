package app.keepfin.copies

import app.keepfin.core.copies.CopyMode
import app.keepfin.core.sync.JArr
import app.keepfin.core.sync.JNum
import app.keepfin.core.sync.JObj
import app.keepfin.core.sync.JStr
import app.keepfin.folder.FakeFolderAccess
import app.keepfin.folder.KeepfinStore
import app.keepfin.folder.OpDraft
import app.keepfin.folder.StoreOptions
import app.keepfin.folder.sql.CopiesSql
import app.keepfin.folder.sql.IndexReader
import app.keepfin.folder.sql.JdbcHost
import app.keepfin.folder.sql.SqlMergeState
import app.keepfin.library.repo.CopiesBackend
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The manager's background decision end to end over a real store, the SQL state and fake folders: a scheduled (due) run starts for a small
 * change whatever the power, a large one waits for charging or an unmetered network, and Sync now is never held.
 */
internal class CopiesScheduleTest {
    private val libraryId = "11111111-1111-4111-8111-111111111111"
    private val actor = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val replica = "a1a1a1a1-a1a1-4a1a-8a1a-a1a1a1a1a1a1"
    private val main = FakeFolderAccess()
    private val card = FakeFolderAccess()
    private lateinit var root: File
    private lateinit var store: KeepfinStore
    private lateinit var reader: IndexReader
    private var ids = 0
    private var clock = 1_700_000_000_000L
    private var charging = false
    private var unmetered = false
    private var manager: CopiesManager? = null

    private class StoreLibrary(val store: KeepfinStore, val reader: IndexReader, val main: FakeFolderAccess, val replica: String, val libraryId: String) : CopiesLibrary {
        override fun backend() = CopiesBackend(main, replica, libraryId) { block -> block(CopiesSql(reader::rows)) }
        override fun apply(drafts: List<OpDraft>) { store.apply(drafts).get(10, TimeUnit.SECONDS) }
        override fun observed(itemId: String): Pair<List<String>, List<String>> =
            store.observed(items = listOf(itemId)).get(10, TimeUnit.SECONDS).items[itemId]?.let { o -> o.tags.map { it.toString() } to o.heads.map { it.toString() } } ?: (emptyList<String>() to emptyList())
        override fun versionSum() = store.versionVector().get(10, TimeUnit.SECONDS).values.sum()
    }

    @Before fun setUp() {
        root = Files.createTempDirectory("kf-schedule").toFile()
        val dir = File(root, "dev")
        val state = SqlMergeState(File(dir, "state.db").path, libraryId, replica, null, JdbcHost())
        store = KeepfinStore.open(
            StoreOptions(folder = main, libraryId = libraryId, actor = actor, deviceDir = dir, outboxDir = File(dir, "outbox"), newId = { "00000000-0000-4000-8000-%012d".format(++ids) }, now = { clock++ }, flushIdleMs = 3_600_000, state = state),
        ).get(10, TimeUnit.SECONDS)
        reader = IndexReader(state.statePath, { state.epoch }) { JdbcHost().open(it) }
    }

    @After fun tearDown() {
        manager?.close()
        reader.close()
        store.abandon()
        root.deleteRecursively()
    }

    private fun sha(bytes: ByteArray) = "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun addSong(n: Int) {
        val path = "Music/Song $n.mp3"
        val bytes = "song number $n".toByteArray()
        main.mkdirs("Music")
        main.files[path] = bytes
        val item = "%08x-0000-4000-8000-000000000000".format(n)
        store.apply(
            listOf(
                OpDraft("item.add", linkedMapOf("item" to JStr(item), "fields" to JObj(mapOf("space" to JStr("Music"), "media" to JStr("audio"), "title" to JStr("Song $n"))))),
                OpDraft("item.place", linkedMapOf("item" to JStr(item), "place" to JStr(replica), "path" to JStr(path))),
                OpDraft("item.revise", linkedMapOf("item" to JStr(item), "content" to JStr(sha(bytes)), "size" to JNum(bytes.size.toLong()), "parents" to JArr(emptyList()))),
            ),
        ).get(10, TimeUnit.SECONDS)
    }

    private fun manager(largeBytes: Long = Scheduling.LARGE_BYTES): CopiesManager {
        val m = CopiesManager(
            context = null, library = StoreLibrary(store, reader, main, replica, libraryId), deviceDir = File(root, "device"), mainTreeUri = "tree://main", device = "test",
            openAccess = { if (it == "tree://card") card else throw app.keepfin.folder.FolderError.NotFound(it) },
            now = { clock }, newId = { "bbbbbbbb-bbbb-4bbb-8bbb-%012d".format(++ids) },
            power = { charging to unmetered }, largeBytes = largeBytes,
        )
        manager = m
        return m
    }

    private fun waitIdle(m: CopiesManager) {
        val end = System.currentTimeMillis() + 20_000
        while (m.isRunning() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertFalse("a run is still going", m.isRunning())
        Thread.sleep(50)
    }

    /** A copy with its first sync done and a schedule of every hour; the clock then moves two hours on and a new song arrives. */
    private fun dueCopy(m: CopiesManager): String {
        addSong(1)
        val preview = m.preview(CopyDraft("tree://card", "Card", CopyMode.Backup, CopyHoldsUi(all = true), everyHours = 1))
        assertEquals("ready", preview.status)
        m.add(preview.placeId)
        m.sync(preview.placeId, previewConfirmed = true)
        waitIdle(m)
        assertNotNull("the first sync copied song 1", card.files["Music/Song 1.mp3"])
        clock += 2 * 3_600_000L
        addSong(2)
        return preview.placeId
    }

    @Test fun aSmallScheduledRunStartsWithoutPowerOrAnUnmeteredNetwork() {
        val m = manager()
        dueCopy(m)
        m.tick(background = true)
        waitIdle(m)
        assertNotNull(card.files["Music/Song 2.mp3"])
    }

    @Test fun aLargeScheduledRunWaitsForPowerOrAnUnmeteredNetwork() {
        val m = manager(largeBytes = 1)
        dueCopy(m)
        m.tick(background = true)
        assertFalse(m.isRunning())
        assertNull("held: nothing was copied", card.files["Music/Song 2.mp3"])
        // still held on the next pass, and released as soon as the phone charges
        m.tick(background = true)
        assertNull(card.files["Music/Song 2.mp3"])
        charging = true
        m.tick(background = true)
        waitIdle(m)
        assertNotNull(card.files["Music/Song 2.mp3"])
    }

    @Test fun anUnmeteredNetworkAlsoReleasesALargeRun() {
        val m = manager(largeBytes = 1)
        dueCopy(m)
        unmetered = true
        m.tick(background = true)
        waitIdle(m)
        assertNotNull(card.files["Music/Song 2.mp3"])
    }

    @Test fun syncNowIsNeverHeld() {
        val m = manager(largeBytes = 1)
        val id = dueCopy(m)
        assertTrue(m.sync(id))
        waitIdle(m)
        assertNotNull(card.files["Music/Song 2.mp3"])
    }

    @Test fun theAppInTheForegroundStartsADueRunWhateverItsSize() {
        val m = manager(largeBytes = 1)
        dueCopy(m)
        m.tick(background = false)
        waitIdle(m)
        assertNotNull(card.files["Music/Song 2.mp3"])
    }

    @Test fun aCardBackUnderAnotherAddressIsRecognisedAndSyncsAgain() {
        var address = "tree://card"
        val trees = mutableMapOf<String, FakeFolderAccess>("tree://card" to card)
        val m = CopiesManager(
            context = null, library = StoreLibrary(store, reader, main, replica, libraryId), deviceDir = File(root, "device"), mainTreeUri = "tree://main", device = "test",
            openAccess = { trees[it] ?: throw app.keepfin.folder.FolderError.NotFound(it) },
            now = { clock }, newId = { "bbbbbbbb-bbbb-4bbb-8bbb-%012d".format(++ids) },
            candidateTrees = { listOf("tree://main", "tree://stranger", address) },
        )
        manager = m
        trees["tree://stranger"] = FakeFolderAccess()
        dueCopy(m)
        // the card is taken out and comes back as a different address
        trees.remove("tree://card")
        trees["tree://card-2"] = card
        address = "tree://card-2"
        m.tick(background = true)
        waitIdle(m)
        assertNotNull("recognised by its replica id and synced", card.files["Music/Song 2.mp3"])
        assertEquals(true, m.state.value.places.single().connected)
    }
}
