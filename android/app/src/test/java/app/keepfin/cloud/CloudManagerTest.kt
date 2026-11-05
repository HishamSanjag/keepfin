package app.keepfin.cloud

import app.keepfin.copies.CopiesLibrary
import app.keepfin.copies.CopiesManager
import app.keepfin.copies.CopyDraft
import app.keepfin.copies.CopyHoldsUi
import app.keepfin.copies.CopyStatus
import app.keepfin.core.copies.CopyMode
import app.keepfin.core.sync.JArr
import app.keepfin.core.sync.JNum
import app.keepfin.core.sync.JObj
import app.keepfin.core.sync.JStr
import app.keepfin.folder.FakeFolderAccess
import app.keepfin.folder.FolderError
import app.keepfin.folder.KeepfinStore
import app.keepfin.folder.OpDraft
import app.keepfin.folder.StoreOptions
import app.keepfin.folder.sql.CopiesSql
import app.keepfin.folder.sql.IndexReader
import app.keepfin.folder.sql.JdbcHost
import app.keepfin.folder.sql.SqlMergeState
import app.keepfin.library.repo.CopiesBackend
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The manager end to end over a real store: Add a copy on a cloud account (preview, confirm), the first sync, the account card, the
 * network rule (Wi-Fi only unless the account allows mobile data), a sign-in that was withdrawn, and a second phone finding the
 * replica on the account. Cloud accounts are the desktop's fake providers.
 */
@RunWith(Parameterized::class)
internal class CloudManagerTest(private val target: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun targets(): List<String> = TARGETS
    }

    private val libraryId = "11111111-1111-4111-8111-111111111111"
    private val actor = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val replica = "a1a1a1a1-a1a1-4a1a-8a1a-a1a1a1a1a1a1"
    private val main = FakeFolderAccess()
    private lateinit var root: File
    private lateinit var store: KeepfinStore
    private lateinit var reader: IndexReader
    private lateinit var peer: NodePeer
    private lateinit var cloud: CloudRuntime
    private lateinit var accountId: String
    private var ids = 0
    private var clock = 1_700_000_000_000L
    private var metered = false
    private var manager: CopiesManager? = null

    private class StoreLibrary(val store: KeepfinStore, val reader: IndexReader, val main: FakeFolderAccess, val replica: String, val libraryId: String) : CopiesLibrary {
        override fun backend() = CopiesBackend(main, replica, libraryId) { block -> block(CopiesSql(reader::rows)) }
        override fun apply(drafts: List<OpDraft>) { store.apply(drafts).get(10, TimeUnit.SECONDS) }
        override fun observed(itemId: String): Pair<List<String>, List<String>> =
            store.observed(items = listOf(itemId)).get(10, TimeUnit.SECONDS).items[itemId]?.let { o -> o.tags.map { it.toString() } to o.heads.map { it.toString() } } ?: (emptyList<String>() to emptyList())
        override fun versionSum() = store.versionVector().get(10, TimeUnit.SECONDS).values.sum()
    }

    @Before fun setUp() {
        peer = NodePeer.require()
        peer.reset()
        root = Files.createTempDirectory("kf-cloud-manager").toFile()
        val dir = File(root, "dev")
        val state = SqlMergeState(File(dir, "state.db").path, libraryId, replica, null, JdbcHost())
        store = KeepfinStore.open(
            StoreOptions(folder = main, libraryId = libraryId, actor = actor, deviceDir = dir, outboxDir = File(dir, "outbox"), newId = { "00000000-0000-4000-8000-%012d".format(++ids) }, now = { clock++ }, flushIdleMs = 3_600_000, state = state),
        ).get(10, TimeUnit.SECONDS)
        reader = IndexReader(state.statePath, { state.epoch }) { JdbcHost().open(it) }
        cloud = CloudRuntime(
            null, File(root, "device"), CloudIds("g", "m", "d"), SoftwareBox(), peer::rewriteAll,
            gate = { _, mobile -> if (metered && !mobile) "Waiting for Wi-Fi. Allow mobile data for this account to sync now." else null },
            library = { libraryId to replica }, knownPlaces = { manager?.knownReplicas().orEmpty() }, addresses = { manager?.cloudAddresses().orEmpty() }, now = { System.currentTimeMillis() },
        )
        accountId = when (providerOf(target)) {
            "webdav" -> cloud.connectWebdav(peer.base(target), "keepfin", "app-password").account.id
            else -> cloud.accounts.addOAuth(providerOf(target), TokenSet("T1", "R1", System.currentTimeMillis() + 3_600_000), "tester@example.com").id
        }
    }

    @After fun tearDown() {
        manager?.close()
        if (::reader.isInitialized) reader.close()
        if (::store.isInitialized) store.abandon()
        if (::root.isInitialized) root.deleteRecursively()
        if (::peer.isInitialized) peer.reset()
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
            context = null, library = StoreLibrary(store, reader, main, replica, libraryId), deviceDir = File(root, "device"), mainTreeUri = "tree://main", device = "Pixel",
            openAccess = { if (CloudAddress.isCloud(it)) cloud.open(it) else throw FolderError.NotFound(it) },
            now = { System.currentTimeMillis() }, newId = { "bbbbbbbb-bbbb-4bbb-8bbb-%012d".format(++ids) }, cloud = cloud,
        )
        manager = m
        return m
    }

    private fun waitIdle(m: CopiesManager) {
        val end = System.currentTimeMillis() + 60_000
        while (m.isRunning() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertFalse("a run is still going", m.isRunning())
        Thread.sleep(50)
    }

    private val folderName = "Phone backup " + System.nanoTime().toString(36)
    private val rootOnAccount get() = if (providerOf(target) == "webdav") folderName else "Keepfin/$folderName"
    private val addressOf get() = cloud.addressFor(accountId, folderName)

    @Test fun addACopyOnTheAccountPreviewItConfirmItAndWatchItSync() {
        addSong(1); addSong(2)
        val m = manager()
        val check = m.checkFolder(addressOf)
        assertTrue(check.problem, check.ok)
        assertTrue(check.suggestedName.isNotEmpty())
        val preview = m.preview(CopyDraft(addressOf, "Cloud backup", CopyMode.Backup, CopyHoldsUi(all = true)))
        assertEquals(preview.reason, "ready", preview.status)
        assertEquals("lines=${preview.lines.map { it.label + ":" + it.files }} diffs=${preview.differences} reason=${preview.reason} destructive=${preview.destructive}", 2, preview.copyFiles)
        assertTrue("a preview writes nothing but the empty replica folder", preview.additiveOnly)
        m.add(preview.placeId)
        waitIdle(m)
        val card = m.state.value.places.single()
        assertTrue(card.status.toString(), card.status == CopyStatus.UP_TO_DATE || card.status == CopyStatus.WAITING)
        assertNotNull("the card knows its account", card.cloud)
        assertEquals("tester@example.com".takeIf { providerOf(target) != "webdav" } ?: card.cloud!!.accountLabel, card.cloud!!.accountLabel)
        assertTrue(card.folderLabel.contains(folderName))
        // the desktop reads the replica the phone made
        val rig = Rig(peer, target, rootOnAccount)
        assertEquals(setOf("Music/Song 1.mp3", "Music/Song 2.mp3"), rig.desktopFiles().keys)
        // nothing but the empty folder was left by a preview that was never confirmed
        assertTrue(m.checkFolder(addressOf).problem?.contains("already one of your copies") == true)
    }

    @Test fun aCancelledPreviewLeavesNothingOnTheAccount() {
        addSong(1)
        val m = manager()
        val preview = m.preview(CopyDraft(addressOf, "Cloud", CopyMode.Backup, CopyHoldsUi(all = true)))
        assertEquals("ready", preview.status)
        m.cancelPreview(preview.placeId)
        val rig = Rig(peer, target, rootOnAccount)
        assertEquals(0, rig.desktopFiles(false).size)
        assertNull("the fresh replica folder is gone again", rig.access.stat(".keepfin/folder.json"))
    }

    @Test fun onAMeteredNetworkTheCopyWaitsUnlessTheAccountAllowsMobileData() {
        addSong(1)
        val m = manager()
        val p = m.preview(CopyDraft(addressOf, "Cloud", CopyMode.Backup, CopyHoldsUi(all = true)))
        m.add(p.placeId)
        waitIdle(m)
        addSong(2)
        metered = true
        m.sync(p.placeId)
        waitIdle(m)
        val waiting = m.state.value.places.single()
        assertEquals(CopyStatus.DISCONNECTED, waiting.status)
        assertTrue(waiting.statusText, waiting.statusText!!.contains("Wi-Fi"))
        val rig = Rig(peer, target, rootOnAccount)
        assertEquals("nothing was sent over the metered network", setOf("Music/Song 1.mp3"), rig.desktopFiles(false).keys)
        // the person allows mobile data for this account
        cloud.setMobileData(accountId, true)
        m.sync(p.placeId)
        waitIdle(m)
        assertEquals(setOf("Music/Song 1.mp3", "Music/Song 2.mp3"), rig.desktopFiles(false).keys)
    }

    @Test fun aWithdrawnSignInShowsSignInAgainNothingIsLostAndASignInGetsTheCopyGoing() {
        if (providerOf(target) == "webdav") return // a password does not expire; the server refusing it is covered by the transport tests
        addSong(1)
        val m = manager()
        val p = m.preview(CopyDraft(addressOf, "Cloud", CopyMode.Backup, CopyHoldsUi(all = true)))
        m.add(p.placeId)
        waitIdle(m)
        addSong(2)
        // the provider stops accepting the token, and the authorization server refuses the refresh token (the person ended the grant)
        peer.fault(target, 401, 500, body = JSONObject().put("error_summary", "expired_access_token/..").put("error", JSONObject().put(".tag", "expired_access_token")))
        m.sync(p.placeId)
        waitIdle(m)
        peer.reset()
        val card = m.state.value.places.single()
        assertEquals(CopyStatus.DISCONNECTED, card.status)
        assertTrue(card.statusText, card.statusText!!.contains("Sign in again"))
        assertTrue(card.cloud!!.needsSignIn)
        assertTrue(cloud.state.value.accounts.also { cloud.refresh() }.isEmpty() || cloud.state.value.accounts.single().needsSignIn)
        val rig = Rig(peer, target, rootOnAccount)
        assertEquals("nothing was changed on the account", setOf("Music/Song 1.mp3"), rig.desktopFiles(false).keys)
        // Sign in again: the same account, new tokens
        cloud.accounts.replaceTokens(accountId, TokenSet("T-new", "R-new", System.currentTimeMillis() + 3_600_000))
        m.sync(p.placeId)
        waitIdle(m)
        assertEquals(setOf("Music/Song 1.mp3", "Music/Song 2.mp3"), rig.desktopFiles(false).keys)
        m.state.value.places.single().status.let { assertTrue(it.toString(), it == CopyStatus.UP_TO_DATE || it == CopyStatus.WAITING) }
    }

    @Test fun aSecondPhoneFindsTheReplicaOnTheSameAccount() {
        addSong(1)
        val m = manager()
        val p = m.preview(CopyDraft(addressOf, "Cloud", CopyMode.Backup, CopyHoldsUi(all = true)))
        m.add(p.placeId)
        waitIdle(m)
        // another phone of the same library signs in to the same account
        val other = CloudRuntime(null, File(root, "other"), CloudIds("g", "m", "d"), SoftwareBox(), peer::rewriteAll, library = { libraryId to "ffffffff-ffff-4fff-8fff-ffffffffffff" }, now = { System.currentTimeMillis() })
        val id2 = if (providerOf(target) == "webdav") other.connectWebdav(peer.base(target), "keepfin", "app-password").account.id else other.accounts.addOAuth(providerOf(target), TokenSet("T1", "R1", System.currentTimeMillis() + 3_600_000), "tester@example.com").id
        val found = other.find(id2, if (providerOf(target) == "webdav") folderName else null).filter { it.name == folderName }
        assertEquals(found.toString(), 1, found.size)
        assertTrue("it is a replica of this library", found[0].replicaId.isNotEmpty())
    }

    @Test fun disconnectingKeepsTheFilesAndTheCopyWaits() {
        addSong(1)
        val m = manager()
        val p = m.preview(CopyDraft(addressOf, "Cloud", CopyMode.Backup, CopyHoldsUi(all = true)))
        m.add(p.placeId)
        waitIdle(m)
        cloud.disconnect(accountId)
        m.tick()
        val card = m.state.value.places.single()
        assertEquals(CopyStatus.DISCONNECTED, card.status)
        val rig = Rig(peer, target, rootOnAccount)
        assertEquals("the files stay on the account", setOf("Music/Song 1.mp3"), rig.desktopFiles(false).keys)
        cloud.refresh()
        assertEquals(0, cloud.state.value.accounts.size)
    }
}




