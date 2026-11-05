package app.keepfin.net

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.bench.LocalFolderAccess
import app.keepfin.copies.CopyRunner
import app.keepfin.copies.ImportOutcome
import app.keepfin.copies.LocalDirAccess
import app.keepfin.copies.MainHost
import app.keepfin.copies.PeerFolderAccess
import app.keepfin.copies.RunConfig
import app.keepfin.copies.RunReport
import app.keepfin.core.copies.Action
import app.keepfin.core.copies.CopyMode
import app.keepfin.core.copies.DesiredEntry
import app.keepfin.folder.FolderAccess
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
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
 * A copy kept on the real desktop service (`node scripts/test/android-net-peer.mjs`, reached through `adb reverse`): the phone pairs with it by
 * the numbers, the desktop allows "Keep a copy on this device", and the copies engine runs straight over the transport. The desktop's own
 * executor and lock do every write, so the files land with the right hashes, the replaced bytes sit in its `.keepfin/trash/net`, its
 * generation moved and its lock is gone; an edit made in the desktop's folder comes back in a two-way copy. Skipped unless the peer's ports
 * are passed as instrumentation arguments (`interopPort`, `controlPort`).
 */
@RunWith(AndroidJUnit4::class)
class DesktopCopyInteropTest {
    private lateinit var context: Context
    private var stack: NetStack? = null
    private var interopPort = 0
    private var controlPort = 0
    private lateinit var work: File
    private lateinit var main: FolderAccess
    private lateinit var access: PeerFolderAccess
    private val desired = LinkedHashMap<String, DesiredEntry>()
    private val deleted = HashSet<String>()
    private var runNo = 0

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        interopPort = args.getString("interopPort")?.toIntOrNull() ?: 0
        controlPort = args.getString("controlPort")?.toIntOrNull() ?: 0
        assumeTrue("start scripts/test/android-net-peer.mjs and pass interopPort and controlPort", interopPort > 0 && controlPort > 0)
        diagnostics = { (stack?.dump() ?: "") + "\n" + runCatching { control("GET", "/state").toString().take(900) }.getOrDefault("") }
        work = File(context.cacheDir, "desktop-copy-${UUID.randomUUID().toString().take(6)}").also { it.mkdirs() }
    }

    @After fun tearDown() {
        stack?.close()
        if (::work.isInitialized) work.deleteRecursively()
    }

    private fun control(method: String, path: String, body: JSONObject? = null): JSONObject {
        Socket(InetAddress.getByName("127.0.0.1"), controlPort).use { s ->
            s.soTimeout = 60_000
            val payload = (body ?: JSONObject()).toString().toByteArray()
            s.getOutputStream().write("$method $path HTTP/1.1\r\nhost: x\r\nconnection: close\r\ncontent-type: application/json\r\ncontent-length: ${if (method == "POST") payload.size else 0}\r\n\r\n".toByteArray())
            if (method == "POST") s.getOutputStream().write(payload)
            s.getOutputStream().flush()
            return JSONObject(s.getInputStream().readBytes().toString(Charsets.UTF_8).substringAfter("\r\n\r\n"))
        }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun put(path: String, text: String) {
        val bytes = text.toByteArray()
        if (main.stat(path) != null) main.remove(path)
        main.publish(path, bytes)
        desired[path] = DesiredEntry(path, "item-$path", "op$runNo", "sha256:${sha(bytes)}", bytes.size.toLong())
    }

    private class Host(val main: FolderAccess) : MainHost {
        override fun importNew(action: Action, stagedPath: String, destPath: String): ImportOutcome {
            main.rename(stagedPath, destPath.substringAfterLast('/'))
            return ImportOutcome("new-item", null, destPath)
        }

        override fun reviseFromCopy(action: Action, stagedPath: String): String? {
            main.remove(action.path)
            main.rename(stagedPath, action.path.substringAfterLast('/'))
            return null
        }

        override fun restoreEditedDeleted(action: Action, stagedPath: String) = reviseFromCopy(action, stagedPath)
        override fun deleteItem(action: Action) {}
    }

    private fun run(mode: CopyMode, dry: Boolean = false): RunReport {
        runNo++
        return CopyRunner.run(
            RunConfig(
                main = main, copy = access, stateDir = File(work, "state"), placeId = "place", mode = mode, device = "phone", desired = desired.values.toList().asSequence(),
                deleted = deleted.toSet(), previewConfirmed = true, dryRun = dry, host = Host(main), parallelism = 2, runId = "r$runNo",
                lockHeartbeatMs = 5_000, mainReplica = "main-replica", copyReplica = "copy-replica",
            ),
        )
    }

    private fun why(r: RunReport) = "status=${r.status} reason=${r.reason} failed=${r.exec?.failed?.map { it.path + ":" + it.reason }} skipped=${r.exec?.skipped?.map { it.path + ":" + it.reason }} counts=${r.summary?.counts}"

    private fun folder(): Map<String, JSONObject> {
        val files = control("GET", "/folder").getJSONArray("files")
        return (0 until files.length()).associate { files.getJSONObject(it).getString("path") to files.getJSONObject(it) }
    }

    @Test fun aCopyOnTheDesktopLandsWithTheRightHashesAndTheDesktopsOwnLockAndGeneration() {
        val desktop = control("GET", "/state")
        val library = desktop.getString("libraryId")
        val desktopId = desktop.getString("deviceId")
        val s = NetStack("phone", context, File(work, "stack")).also { stack = it }
        main = LocalFolderAccess(File(work, "main-folder"))
        s.openLibrary(library, folder = main)
        s.start(jobsOn = false)

        // pair by the numbers, then the desktop allows this phone to keep a copy on it
        control("POST", "/open-pairing")
        var failure: Throwable? = null
        val asking = Thread { try { s.service.ask("127.0.0.1:$interopPort") } catch (e: Throwable) { failure = e } }.also { it.isDaemon = true; it.start() }
        until("the phone to show the numbers") { s.service.computeState().pairing?.sas != null }
        until("the desktop to show the numbers") { control("GET", "/state").optJSONObject("pairing")?.optString("sas", "")?.isNotEmpty() == true }
        val phoneView = s.service.computeState().pairing!!
        val desktopView = control("GET", "/state").getJSONObject("pairing")
        assertEquals(phoneView.sas, desktopView.getString("sas"))
        control("POST", "/decide", JSONObject().put("id", desktopView.getString("id")).put("match", true))
        s.service.decide(phoneView.id, true)
        asking.join(30_000)
        failure?.let { throw it }
        until("the phone to remember the desktop") { s.service.peers.get(desktopId) != null }
        until("the desktop to remember the phone") { control("GET", "/state").getJSONArray("paired").length() == 1 }
        control("POST", "/grant", JSONObject().put("actions", JSONArray(listOf("metadata.read", "metadata.write", "inventory.read", "media.read", "copy.mutate"))))
        until("the phone to learn it may keep a copy on the desktop") { s.service.links[desktopId]?.lastHello?.libraries?.any { l -> "copy.mutate" in l.actions } == true }

        val transport = PeerCopyTransport(clientOf = { s.service.links[desktopId]?.takeIf { it.online }?.withClient { it } }, libraryId = library, device = "phone")
        val meta = LocalDirAccess(File(work, "copy-bookkeeping"))
        meta.mkdirs(".keepfin")
        meta.publish(".keepfin/folder.json", """{"format":1,"libraryId":"$library","replicaId":"phone-copy","createdAt":"2026-10-17T00:00:00Z","generation":0}""".toByteArray())
        access = PeerFolderAccess(transport, meta, File(work, "staging"))
        val before = control("GET", "/folder")
        val generationBefore = if (before.isNull("generation")) 0L else before.getLong("generation")

        // a backup: the files arrive byte for byte
        val big = "x".repeat(200_000)
        put("Backup/a.wav", "alpha"); put("Backup/b.wav", "bravo"); put("Backup/big.bin", big)
        val first = run(CopyMode.Backup)
        assertEquals(why(first), "done", first.status)
        val landed = folder()
        assertEquals(sha("alpha".toByteArray()), landed.getValue("Backup/a.wav").getString("sha256"))
        assertEquals(sha("bravo".toByteArray()), landed.getValue("Backup/b.wav").getString("sha256"))
        assertEquals(sha(big.toByteArray()), landed.getValue("Backup/big.bin").getString("sha256"))
        assertFalse("no staged file is left on the desktop", landed.keys.any { it.contains("kftmp") })
        val afterFirst = control("GET", "/folder")
        assertFalse("the desktop's lock is released", afterFirst.getBoolean("lock"))
        assertTrue("the desktop's generation moved", afterFirst.getLong("generation") > generationBefore)

        // a replacement: the desktop keeps the old bytes in its own trash
        put("Backup/a.wav", "alpha two")
        val second = run(CopyMode.Backup)
        assertEquals(why(second), "done", second.status)
        val replaced = control("GET", "/folder")
        assertEquals(sha("alpha two".toByteArray()), folder().getValue("Backup/a.wav").getString("sha256"))
        val trash = replaced.getJSONArray("trash")
        assertTrue(trash.toString(), (0 until trash.length()).any { trash.getJSONObject(it).getString("sha256") == sha("alpha".toByteArray()) && trash.getJSONObject(it).getString("name").endsWith("-a.wav") })
        assertEquals("nothing more to do", 0, run(CopyMode.Backup, dry = true).summary!!.counts.values.sum())

        // two-way: a file edited in the desktop's folder comes back
        control("POST", "/edit-file", JSONObject().put("path", "Backup/b.wav").put("text", "bravo edited on the desktop"))
        val back = run(CopyMode.TwoWay)
        assertEquals(why(back), "done", back.status)
        assertEquals("bravo edited on the desktop", main.read("Backup/b.wav").toString(Charsets.UTF_8))
        assertNotNull(folder()["Backup/b.wav"])
        assertFalse(control("GET", "/folder").getBoolean("lock"))
    }
}
