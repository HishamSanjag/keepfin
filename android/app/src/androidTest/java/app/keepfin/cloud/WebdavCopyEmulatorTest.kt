package app.keepfin.cloud

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.bench.LocalFolderAccess
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
import app.keepfin.folder.KeepfinFolders
import app.keepfin.folder.KeepfinStore
import app.keepfin.folder.OpDraft
import app.keepfin.folder.StoreOptions
import app.keepfin.folder.sql.AndroidSqlHost
import app.keepfin.folder.sql.BundledSql
import app.keepfin.folder.sql.CopiesSql
import app.keepfin.folder.sql.IndexReader
import app.keepfin.folder.sql.SqlMergeState
import app.keepfin.library.repo.CopiesBackend
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A WebDAV copy end to end on the emulator, against the desktop's real WebDAV server (`node scripts/test/android-cloud-peer.mjs`, reached with
 * `adb reverse`): a WebDAV account is added with the real key store (plain HTTP is accepted only for the local test address), a backup copy
 * is previewed, confirmed and synced by the real manager, and then the DESKTOP transport reads the replica and checks every file's bytes
 * and the copy's `.keepfin` records. An edit made on the desktop side comes back in a two-way copy. Skipped unless the peer's ports are
 * passed as instrumentation arguments (`controlPort`, `davPort`).
 */
@RunWith(AndroidJUnit4::class)
class WebdavCopyEmulatorTest {
    private lateinit var context: Context
    private var controlPort = 0
    private var davPort = 0
    private val libraryId = UUID.randomUUID().toString()
    private val replica = UUID.randomUUID().toString()
    private lateinit var root: File
    private lateinit var main: LocalFolderAccess
    private lateinit var store: KeepfinStore
    private lateinit var reader: IndexReader
    private lateinit var cloud: CloudRuntime
    private var manager: CopiesManager? = null
    private lateinit var accountId: String
    private val folderName = "Phone copy " + System.nanoTime().toString(36)

    private class StoreLibrary(val store: KeepfinStore, val reader: IndexReader, val main: LocalFolderAccess, val replica: String, val libraryId: String) : CopiesLibrary {
        override fun backend() = CopiesBackend(main, replica, libraryId) { block -> block(CopiesSql(reader::rows)) }
        override fun apply(drafts: List<OpDraft>) { store.apply(drafts).get(15, TimeUnit.SECONDS) }
        override fun observed(itemId: String): Pair<List<String>, List<String>> =
            store.observed(items = listOf(itemId)).get(15, TimeUnit.SECONDS).items[itemId]?.let { o -> o.tags.map { it.toString() } to o.heads.map { it.toString() } } ?: (emptyList<String>() to emptyList())
        override fun versionSum() = store.versionVector().get(15, TimeUnit.SECONDS).values.sum()
    }

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        controlPort = args.getString("cloudControlPort")?.toIntOrNull() ?: args.getString("controlPort")?.toIntOrNull() ?: 0
        davPort = args.getString("davPort")?.toIntOrNull() ?: 0
        assumeTrue("start scripts/test/android-cloud-peer.mjs, adb reverse its ports, and pass cloudControlPort and davPort", controlPort > 0 && davPort > 0)
        root = File(context.cacheDir, "webdav-copy-${UUID.randomUUID().toString().take(6)}").also { it.mkdirs() }
        main = LocalFolderAccess(File(root, "folder"))
        KeepfinFolders.create(main, libraryId, replica, java.time.Instant.now().toString())
        val dir = File(root, "lib").also { it.mkdirs() }
        val state = SqlMergeState(File(dir, "state/state.db").also { it.parentFile!!.mkdirs() }.path, libraryId, replica, null, AndroidSqlHost())
        store = KeepfinStore.open(
            StoreOptions(
                folder = main, libraryId = libraryId, actor = UUID.randomUUID().toString(), deviceDir = dir, outboxDir = File(dir, "outbox"), newId = { UUID.randomUUID().toString() },
                state = state, flushIdleMs = 150, retryMs = 50,
            ),
        ).get(30, TimeUnit.SECONDS)
        reader = IndexReader(state.statePath, { state.epoch }) { BundledSql.openReader(it) }
        cloud = CloudRuntime(
            null, File(root, "device"), CloudIds(), KeystoreBox("kftest-webdav-${UUID.randomUUID().toString().take(6)}"),
            library = { libraryId to replica }, knownPlaces = { manager?.knownReplicas().orEmpty() }, addresses = { manager?.cloudAddresses().orEmpty() },
        )
        accountId = cloud.connectWebdav("http://127.0.0.1:$davPort", "keepfin", "app-password").account.id
    }

    @After fun tearDown() {
        manager?.close()
        if (::reader.isInitialized) reader.close()
        if (::store.isInitialized) store.abandon()
        if (::root.isInitialized) root.deleteRecursively()
    }

    // ---- the desktop peer's control port (a raw socket: the app forbids cleartext through the normal HTTP stack in release builds) ----

    private fun control(path: String, body: JSONObject = JSONObject()): JSONObject {
        Socket(InetAddress.getByName("127.0.0.1"), controlPort).use { s ->
            s.soTimeout = 120_000
            val payload = body.toString().toByteArray()
            s.getOutputStream().write("POST $path HTTP/1.1\r\nhost: x\r\nconnection: close\r\ncontent-type: application/json\r\ncontent-length: ${payload.size}\r\n\r\n".toByteArray())
            s.getOutputStream().write(payload)
            s.getOutputStream().flush()
            val text = s.getInputStream().readBytes().toString(Charsets.UTF_8)
            val status = text.substringBefore("\r\n").split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            val json = text.substringAfter("\r\n\r\n")
            check(status == 200) { "the peer answered $status for $path: ${json.take(500)}" }
            return JSONObject(json)
        }
    }

    private fun desktop(path: String, vararg fields: Pair<String, Any>): JSONObject =
        control("/desktop/$path", JSONObject().put("target", "davreal").put("root", folderName).also { o -> fields.forEach { o.put(it.first, it.second) } })

    private fun scan(hash: Boolean = true): Map<String, JSONObject> {
        val files = desktop("scan", "hash" to hash).getJSONArray("files")
        return (0 until files.length()).associate { files.getJSONObject(it).getString("path") to files.getJSONObject(it) }
    }

    private fun sha(bytes: ByteArray) = "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun addSong(n: Int) {
        val path = "Music/Song $n.mp3"
        val bytes = "song number $n".toByteArray()
        main.mkdirs("Music")
        main.publish(path, bytes)
        val item = "%08x-0000-4000-8000-000000000000".format(n)
        store.apply(
            listOf(
                OpDraft("item.add", linkedMapOf("item" to JStr(item), "fields" to JObj(mapOf("space" to JStr("Music"), "media" to JStr("audio"), "title" to JStr("Song $n"))))),
                OpDraft("item.place", linkedMapOf("item" to JStr(item), "place" to JStr(replica), "path" to JStr(path))),
                OpDraft("item.revise", linkedMapOf("item" to JStr(item), "content" to JStr(sha(bytes)), "size" to JNum(bytes.size.toLong()), "parents" to JArr(emptyList()))),
            ),
        ).get(15, TimeUnit.SECONDS)
    }

    /** The song is edited in the library: new bytes at the same path and a new revision of the same item. */
    private fun editSong(n: Int, text: String) {
        val path = "Music/Song $n.mp3"
        val bytes = text.toByteArray()
        main.remove(path)
        main.publish(path, bytes)
        val item = "%08x-0000-4000-8000-000000000000".format(n)
        val parents = StoreLibrary(store, reader, main, replica, libraryId).observed(item).second
        store.apply(
            listOf(OpDraft("item.revise", linkedMapOf("item" to JStr(item), "content" to JStr(sha(bytes)), "size" to JNum(bytes.size.toLong()), "parents" to JArr(parents.map { JStr(it) })))),
        ).get(15, TimeUnit.SECONDS)
    }

    private fun manager(): CopiesManager {
        val m = CopiesManager(
            context = null, library = StoreLibrary(store, reader, main, replica, libraryId), deviceDir = File(root, "device"), mainTreeUri = "tree://main", device = "Pixel",
            openAccess = { if (CloudAddress.isCloud(it)) cloud.open(it) else throw app.keepfin.folder.FolderError.NotFound(it) }, cloud = cloud,
        )
        manager = m
        return m
    }

    private fun waitIdle(m: CopiesManager) {
        val end = System.currentTimeMillis() + 120_000
        while (m.isRunning() && System.currentTimeMillis() < end) Thread.sleep(25)
        assertFalse("a run is still going", m.isRunning())
        Thread.sleep(100)
    }

    private val address get() = cloud.addressFor(accountId, folderName)

    @Test fun aBackupCopyOnAWebdavAccountIsPreviewedConfirmedAndSyncedAndTheDesktopReadsItBack() {
        addSong(1); addSong(2); addSong(3)
        val m = manager()
        assertTrue(m.checkFolder(address).problem, m.checkFolder(address).ok)
        val preview = m.preview(CopyDraft(address, "Webdav backup", CopyMode.Backup, CopyHoldsUi(all = true)))
        assertEquals(preview.reason, "ready", preview.status)
        assertEquals(3, preview.copyFiles)
        m.add(preview.placeId)
        waitIdle(m)
        val card = m.state.value.places.single()
        assertTrue(card.status.toString() + " " + card.statusText, card.status == CopyStatus.UP_TO_DATE || card.status == CopyStatus.WAITING)
        assertNotNull(card.cloud)

        // the desktop's own transport reads what the phone wrote: every file with the right bytes, the copy's records in the layout it expects
        val files = scan()
        assertEquals(setOf("Music/Song 1.mp3", "Music/Song 2.mp3", "Music/Song 3.mp3"), files.keys)
        for (n in 1..3) {
            val f = files.getValue("Music/Song $n.mp3")
            assertEquals(sha("song number $n".toByteArray()), f.getString("sha256"))
            assertEquals("song number $n".length.toLong(), f.getLong("size"))
        }
        val result = desktop("scan", "hash" to false)
        assertTrue("the listing is complete", result.getBoolean("complete"))
        val folderJson = JSONObject(result.getJSONObject("keepfin").getString("folder.json"))
        assertEquals(libraryId, folderJson.getString("libraryId"))
        assertEquals("the copy's replica is the one the library lists", m.knownReplicas().keys.single(), folderJson.getString("replicaId"))
        assertTrue("a generation record was appended", result.getLong("generation") >= 1L)
        assertTrue("nothing is left half-written", files.keys.none { it.contains("kftmp") })

        // a later change goes up as a replacement; the old bytes stay in the account's trash, not gone
        editSong(1, "song number 1, remastered")
        m.sync(card.id)
        waitIdle(m)
        assertEquals("song number 1, remastered", desktop("get", "path" to "Music/Song 1.mp3").getString("text"))
    }

    @Test fun anEditMadeOnTheDesktopComesBackInATwoWayCopy() {
        addSong(1); addSong(2)
        val m = manager()
        val preview = m.preview(CopyDraft(address, "Webdav two-way", CopyMode.TwoWay, CopyHoldsUi(all = true)))
        assertEquals(preview.reason, "ready", preview.status)
        m.add(preview.placeId)
        waitIdle(m)
        assertEquals(setOf("Music/Song 1.mp3", "Music/Song 2.mp3"), scan(false).keys)

        desktop("edit", "path" to "Music/Song 2.mp3", "text" to "edited on the desktop")
        desktop("put", "path" to "Music/Brand new.mp3", "text" to "new from the desktop")
        m.sync(m.state.value.places.single().id)
        waitIdle(m)
        assertEquals("edited on the desktop", main.read("Music/Song 2.mp3").toString(Charsets.UTF_8))
        assertEquals("new from the desktop", main.read("Music/Brand new.mp3").toString(Charsets.UTF_8))
        assertEquals("song number 1", main.read("Music/Song 1.mp3").toString(Charsets.UTF_8))
        // and nothing more to do on the next look
        val card = m.state.value.places.single()
        assertTrue(card.status.toString() + " " + card.statusText, card.status == CopyStatus.UP_TO_DATE || card.status == CopyStatus.WAITING)
    }
}
