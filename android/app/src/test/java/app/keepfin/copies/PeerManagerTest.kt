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
 * The manager's side of a copy on a paired device over a real store, the SQL state and a fake device: the devices the wizard offers and why
 * one is refused, Add a copy (preview, confirm), the first sync, the card naming the device, a device that is away (a wait, never an error)
 * and a sync that starts by itself when it is back, and the things a device copy does not do (exports, earlier versions on the phone).
 * The transport itself is covered over a real connection in `PeerCopyTest`.
 */
internal class PeerManagerTest {
    private val libraryId = "11111111-1111-4111-8111-111111111111"
    private val actor = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val replica = "a1a1a1a1-a1a1-4a1a-8a1a-a1a1a1a1a1a1"
    private val main = FakeFolderAccess()
    private lateinit var root: File
    private lateinit var store: KeepfinStore
    private lateinit var reader: IndexReader
    private var ids = 0
    private var clock = 1_700_000_000_000L
    private var manager: CopiesManager? = null

    /** The paired devices: one that can be chosen, with its disk standing in as a plain fake folder. */
    private class FakePeers : PeerHooks {
        val disk = FakeFolderAccess()
        @Volatile var waiting: String? = null
        @Volatile var allowed = true
        val address = PeerAddress.make("dev-anna")
        override fun isPeer(address: String?) = PeerAddress.isPeer(address)
        override fun devices() = listOf(PeerChoiceUi("dev-anna", "Anna's phone", waiting == null, allowed && waiting == null, if (!allowed) "On Anna's phone, turn on \"Keep a copy on this device\" for this device in Settings > Devices." else waiting))
        override fun card(address: String) = PeerCardUi("dev-anna", "Anna's phone", waiting == null, waiting)
        override fun label(address: String) = "Anna's phone"
        override fun meta(stateDir: File) = disk
        override fun open(address: String, stateDir: File, libraryId: String) = disk
        override fun forget(stateDir: File) {}
    }

    private val peers = FakePeers()

    private class StoreLibrary(val store: KeepfinStore, val reader: IndexReader, val main: FakeFolderAccess, val replica: String, val libraryId: String) : CopiesLibrary {
        override fun backend() = CopiesBackend(main, replica, libraryId) { block -> block(CopiesSql(reader::rows)) }
        override fun apply(drafts: List<OpDraft>) { store.apply(drafts).get(10, TimeUnit.SECONDS) }
        override fun observed(itemId: String): Pair<List<String>, List<String>> =
            store.observed(items = listOf(itemId)).get(10, TimeUnit.SECONDS).items[itemId]?.let { o -> o.tags.map { it.toString() } to o.heads.map { it.toString() } } ?: (emptyList<String>() to emptyList())
        override fun versionSum() = store.versionVector().get(10, TimeUnit.SECONDS).values.sum()
    }

    @Before fun setUp() {
        root = Files.createTempDirectory("kf-peer-manager").toFile()
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

    private fun manager(): CopiesManager {
        val m = CopiesManager(
            context = null, library = StoreLibrary(store, reader, main, replica, libraryId), deviceDir = File(root, "device"), mainTreeUri = "tree://main", device = "test",
            openAccess = { throw app.keepfin.folder.FolderError.NotFound(it) }, now = { clock }, newId = { "bbbbbbbb-bbbb-4bbb-8bbb-%012d".format(++ids) }, peers = peers,
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

    private fun addCopy(m: CopiesManager, mode: CopyMode = CopyMode.Backup): String {
        val preview = m.preview(CopyDraft(peers.address, "On Anna's phone", mode, CopyHoldsUi(all = true)))
        assertEquals(preview.reason, "ready", preview.status)
        m.add(preview.placeId)
        waitIdle(m)
        return preview.placeId
    }

    @Test fun theWizardOffersTheDevicesAndSaysWhyOneCannotBeChosen() {
        val m = manager()
        m.start()
        assertEquals(listOf("Anna's phone"), m.state.value.devices.map { it.name })
        val ok = m.checkFolder(peers.address)
        assertTrue(ok.problem, ok.ok)
        assertEquals("Anna's phone", ok.suggestedName)
        peers.allowed = false
        val refused = m.checkFolder(peers.address)
        assertFalse(refused.ok)
        assertTrue(refused.problem, refused.problem!!.contains("Keep a copy on this device"))
        // not allowed: no preview either, and nothing was made on the device
        assertEquals("refused", m.preview(CopyDraft(peers.address, "x", CopyMode.Backup, CopyHoldsUi(all = true))).status)
        assertNull(peers.disk.files[".keepfin/folder.json"])
    }

    @Test fun aCopyOnADeviceIsPreviewedConfirmedAndSyncedAndTheCardNamesTheDevice() {
        addSong(1); addSong(2)
        val m = manager()
        val placeId = addCopy(m)
        assertEquals("song number 1", peers.disk.files["Music/Song 1.mp3"]?.toString(Charsets.UTF_8))
        assertEquals("song number 2", peers.disk.files["Music/Song 2.mp3"]?.toString(Charsets.UTF_8))
        val card = m.state.value.places.single()
        assertEquals(placeId, card.id)
        assertTrue(card.status.toString() + " " + card.statusText, card.status == CopyStatus.UP_TO_DATE || card.status == CopyStatus.WAITING)
        assertEquals("Anna's phone", card.peer!!.deviceName)
        assertEquals("Anna's phone", card.folderLabel)
        assertNull("it is not a cloud account", card.cloud)
        assertTrue(card.connected)
        // a second copy on the same device is refused
        assertTrue(m.checkFolder(peers.address).problem, m.checkFolder(peers.address).problem!!.contains("already a copy on this device"))
    }

    @Test fun aDeviceThatIsAwayIsAWaitAndTheCopyStartsByItselfWhenItIsBack() {
        addSong(1)
        val m = manager()
        addCopy(m)
        addSong(2)
        peers.waiting = "Anna's phone is not connected right now. The copy continues when it is."
        clock += 60_000
        m.tick()
        val away = m.state.value.places.single()
        assertEquals(CopyStatus.DISCONNECTED, away.status)
        assertFalse(away.connected)
        assertTrue(away.statusText, away.statusText!!.contains("not connected right now"))
        assertEquals(false, away.peer!!.online)
        assertNull("nothing was sent while it was away", peers.disk.files["Music/Song 2.mp3"])
        // and it is back: the next look starts the sync
        peers.waiting = null
        m.tick()
        waitIdle(m)
        assertNotNull(peers.disk.files["Music/Song 2.mp3"])
        assertTrue(m.state.value.places.single().connected)
    }

    @Test fun mobileDataIsAWaitWithItsOwnWords() {
        addSong(1)
        val m = manager()
        addCopy(m)
        addSong(2)
        peers.waiting = "Waiting for Wi-Fi: this phone is on mobile data, and Anna's phone is not set to take files over mobile data."
        clock += 60_000
        m.tick()
        val card = m.state.value.places.single()
        assertEquals(CopyStatus.DISCONNECTED, card.status)
        assertTrue(card.statusText, card.statusText!!.contains("Wi-Fi"))
        assertNull(peers.disk.files["Music/Song 2.mp3"])
    }

    @Test fun aDeviceCopyHasNoExportsAndNoEarlierVersionsOnThisPhone() {
        addSong(1)
        val m = manager()
        val placeId = addCopy(m)
        try {
            m.edit(placeId, exports = Triple(true, false, false))
            throw AssertionError("exports are for folders and drives")
        } catch (e: IllegalStateException) {
            assertTrue(e.message, e.message!!.contains("exports"))
        }
        assertTrue(m.versions(placeId).isEmpty())
        try {
            m.cleanup(placeId)
            throw AssertionError("a device keeps its own earlier versions")
        } catch (e: IllegalStateException) {
            assertTrue(e.message, e.message!!.contains("another device"))
        }
        assertNull("no exports record was written", peers.disk.files[Exports.RECORD])
    }
}
