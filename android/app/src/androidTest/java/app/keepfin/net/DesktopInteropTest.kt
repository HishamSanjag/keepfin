package app.keepfin.net

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.core.sync.JObj
import app.keepfin.core.sync.JStr
import app.keepfin.folder.OpDraft
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The phone against the real desktop network service (`node scripts/test/android-net-peer.mjs`, reached through `adb reverse`): pairing by the
 * numbers (both screens show the same six digits), the membership root, segments both ways (KFSEG 2 written by each side and verified
 * by the other), Range reads, "Download on..." with progress, and removal. Skipped unless the peer's ports are passed as instrumentation
 * arguments (`interopPort`, `controlPort`).
 */
@RunWith(AndroidJUnit4::class)
class DesktopInteropTest {
    private lateinit var context: Context
    private var stack: NetStack? = null
    private var interopPort = 0
    private var controlPort = 0

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        interopPort = args.getString("interopPort")?.toIntOrNull() ?: 0
        controlPort = args.getString("controlPort")?.toIntOrNull() ?: 0
        assumeTrue("start scripts/test/android-net-peer.mjs and pass interopPort and controlPort", interopPort > 0 && controlPort > 0)
        diagnostics = { (stack?.dump() ?: "") + "\n" + runCatching { control("GET", "/state").toString().take(900) }.getOrDefault("") }
    }

    @After fun tearDown() {
        stack?.close()
    }

    /** A plain HTTP call to the peer's control port (a raw socket: the app forbids cleartext through the normal HTTP stack). */
    private fun control(method: String, path: String, body: JSONObject? = null): JSONObject {
        Socket(InetAddress.getByName("127.0.0.1"), controlPort).use { s ->
            s.soTimeout = 20_000
            val payload = (body ?: JSONObject()).toString().toByteArray()
            val head = "$method $path HTTP/1.1\r\nhost: x\r\nconnection: close\r\ncontent-type: application/json\r\ncontent-length: ${if (method == "POST") payload.size else 0}\r\n\r\n"
            s.getOutputStream().write(head.toByteArray())
            if (method == "POST") s.getOutputStream().write(payload)
            s.getOutputStream().flush()
            val text = s.getInputStream().readBytes().toString(Charsets.UTF_8)
            return JSONObject(text.substringAfter("\r\n\r\n"))
        }
    }

    @Test fun thePhoneAndTheDesktopPairShareALibraryPlayFilesAndRunJobs() {
        val desktop = control("GET", "/state")
        val library = desktop.getString("libraryId")
        val desktopId = desktop.getString("deviceId")
        val s = NetStack("phone", context, File(context.cacheDir, "interop-${UUID.randomUUID().toString().take(6)}")).also { stack = it }
        s.openLibrary(library)
        s.start(jobsOn = false)

        // pairing by the numbers: the desktop answers, the phone asks
        control("POST", "/open-pairing")
        var failure: Throwable? = null
        val asking = Thread { try { s.service.ask("127.0.0.1:$interopPort") } catch (e: Throwable) { failure = e } }.also { it.isDaemon = true; it.start() }
        until("the phone to show the numbers") { s.service.computeState().pairing?.sas != null }
        until("the desktop to show the numbers") { control("GET", "/state").optJSONObject("pairing")?.optString("sas", "")?.isNotEmpty() == true }
        val phoneView = s.service.computeState().pairing!!
        val desktopView = control("GET", "/state").getJSONObject("pairing")
        assertEquals("both screens show the same six digits", phoneView.sas, desktopView.getString("sas"))
        control("POST", "/decide", JSONObject().put("id", desktopView.getString("id")).put("match", true))
        s.service.decide(phoneView.id, true)
        asking.join(30_000)
        failure?.let { throw it }
        until("the phone to remember the desktop") { s.service.peers.get(desktopId) != null }
        until("the desktop to remember the phone") { control("GET", "/state").getJSONArray("paired").length() == 1 }

        // membership: one of the two writes the root and the first grant; the other receives them (sync of signed segments both ways)
        until("both libraries to enforce membership", 30_000) {
            s.service.computeState().libraries.single().signed && control("GET", "/state").getJSONArray("signed").getJSONObject(0).getBoolean("signed")
        }

        // the desktop's items arrive
        until("the desktop's items to reach the phone", 30_000) { (1..3).all { s.views(library).contains("Desktop item $it") } }
        control("POST", "/add", JSONObject().put("n", 4).put("title", "Desktop item 4"))
        until("a later desktop item to reach the phone") { s.views(library).contains("Desktop item 4") }

        // the phone's item reaches the desktop (an item the desktop lists by title)
        val itemId = "00000000-0000-4000-8000-0000000000aa"
        val store = s.storeOf.getValue(library)
        store.apply(listOf(OpDraft("item.add", mapOf("item" to JStr(itemId), "fields" to JObj(mapOf("media" to JStr("audio"), "title" to JStr("Phone item"))))))).get(15, TimeUnit.SECONDS)
        store.flush().get(15, TimeUnit.SECONDS)
        until("the phone's item to reach the desktop", 30_000) { control("GET", "/state").getJSONArray("titles").toString().contains("Phone item") }

        // play from the desktop: real Range reads of the file it holds
        control("POST", "/grant", JSONObject().put("actions", JSONArray(listOf("metadata.read", "metadata.write", "media.read", "jobs"))))
        until("the phone to learn it may play files") { s.service.links[desktopId]?.lastHello?.libraries?.any { "media.read" in it.actions } == true }
        val contentId = desktop.getString("contentId")
        val played = s.service.playFrom(library, contentId, "interop.bin")
        assertEquals(desktop.getLong("fileSize"), played.size)
        val source = s.service.peerSource(played.uri.removePrefix("keepfin-peer://"))!!
        val range = source.open(5000L..5999L)
        assertEquals(206, range.status)
        val expected = ByteArray(1000) { ((5000 + it) * 7 % 251).toByte() }
        assertEquals(expected.toList(), range.input.readBytes().toList())
        range.close()
        val whole = source.open(null)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        whole.input.use { input -> val buf = ByteArray(65536); while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) } }
        assertEquals(desktop.getString("fileSha256"), digest.digest().joinToString("") { "%02x".format(it) })

        // Download on... the desktop
        until("the phone to learn it may send downloads") { s.service.computeState().paired.single().jobsAllowed }
        s.service.sendJob(desktopId, "https://example.com/watch?v=interop", JSONObject("""{"type":"video","maxHeight":720,"codec":"auto","container":"mp4"}"""))
        until("the desktop to have the job") { control("GET", "/state").getJSONArray("jobs").length() == 1 }
        until("the phone to hear it is queued") { s.service.outgoing.list().single().state == "queued" }
        control("POST", "/job-view", JSONObject().put("state", "downloading").put("percent", 55))
        until("the phone to show the progress") { s.service.outgoing.list().single().percent == 55.0 }
        control("POST", "/job-view", JSONObject().put("state", "completed").put("percent", 100))
        until("the phone to show it done") { s.service.outgoing.list().single().state == "completed" }

        // the desktop removes the phone: the phone is no longer allowed
        control("POST", "/remove-peer")
        until("the phone to be turned away", 30_000) { s.service.peers.get(desktopId)?.unreachableSince != null }
        assertTrue(s.service.links[desktopId]?.online != true)
    }
}
