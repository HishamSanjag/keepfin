package app.keepfin.net

import java.io.File
import okhttp3.HttpUrl
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

private const val REQ_1 = "11111111-aaaa-4aaa-8aaa-111111111111"
private const val REQ_2 = "22222222-aaaa-4aaa-8aaa-222222222222"
private const val PEER = "peer-one"

private class FakePort : JobPort {
    val created = ArrayList<Triple<String, String, JSONObject>>()
    val views = HashMap<String, RemoteJobView>()
    var spaces = listOf("music" to "Music")
    var free: Long? = 10L * 1024 * 1024 * 1024
    var failCreate: String? = null
    private val listeners = ArrayList<() -> Unit>()
    override fun spaces() = spaces
    override fun create(jobId: String, url: String, format: JSONObject, options: JSONObject?, space: String?) {
        failCreate?.let { throw IllegalStateException(it) }
        created += Triple(jobId, url, format)
        views[jobId] = RemoteJobView("queued", 0.0, "Title", "720p")
    }
    override fun view(jobId: String) = views[jobId]
    override fun subscribe(listener: () -> Unit): () -> Unit { listeners += listener; return { listeners -= listener } }
    override fun freeBytes() = free
    fun tick() = listeners.toList().forEach { it() }
}

class JobsHostTest {
    private lateinit var dir: File
    private lateinit var port: FakePort
    private lateinit var host: JobsHost
    private var clock = 1_700_000_000_000L
    private val notices = ArrayList<Pair<String, JSONObject>>()
    private var allowUrl: (String) -> String = { it }

    @Before fun setUp() {
        dir = tempDir()
        port = FakePort()
        host = open()
    }

    @After fun tearDown() {
        host.close()
        dir.deleteRecursively()
    }

    private fun open(perPeer: Int = 500) = JobsHost.open(
        JobsHostOptions(File(dir, "jobs.json"), port, { clock }, { allowUrl(it) }, { peer, msg -> notices += peer to msg }, perPeerActive = perPeer),
    )

    private fun body(request: String = REQ_1, url: String = "https://example.com/v", format: String = """{"type":"video","maxHeight":720,"codec":"auto","container":"mp4"}""", extra: String = "") =
        JSONObject("""{"requestId":"$request","url":"$url","format":$format$extra}""")

    @Test fun aRequestMakesOneJobAndAnAnswerIsStable() {
        val first = host.handle(PEER, "Laptop", body())
        assertEquals(201, first.status)
        assertEquals(1, port.created.size)
        assertEquals("queued", first.body.getJSONObject("view").getString("state"))
        assertEquals("Laptop", host.originOf(port.created[0].first))
    }

    @Test fun theSameRequestAgainNeverMakesASecondJob() {
        host.handle(PEER, "Laptop", body())
        repeat(3) { assertEquals(200, host.handle(PEER, "Laptop", body()).status) }
        assertEquals(1, port.created.size)
        // a different device may use the same request id: it is a different request
        assertEquals(201, host.handle("peer-two", "Tablet", body()).status)
        assertEquals(2, port.created.size)
    }

    @Test fun theSameIdWithAnotherPayloadIsRefused() {
        host.handle(PEER, "Laptop", body())
        val changed = host.handle(PEER, "Laptop", body(url = "https://example.com/other"))
        assertEquals(409, changed.status)
        assertEquals("payload-changed", changed.body.getString("error"))
        assertEquals(1, port.created.size)
    }

    @Test fun exactFormatIdsBelongToTheDeviceThatInspectedTheLink() {
        host.handle(PEER, "Laptop", body(format = """{"type":"video","maxHeight":1080,"formatId":"137","audioFormatId":"140","codec":"auto","container":"mp4"}"""))
        val format = port.created.single().third
        assertFalse(format.has("formatId"))
        assertFalse(format.has("audioFormatId"))
        assertEquals(1080, format.getInt("maxHeight"))
    }

    @Test fun badRequestsAreAnsweredWithWords() {
        assertEquals(400, host.handle(PEER, "L", JSONObject("""{"url":"https://x.com","format":{}}""")).status)
        assertEquals(400, host.handle(PEER, "L", body(request = "not-a-uuid")).status)
        assertEquals(400, host.handle(PEER, "L", body(url = "")).status)
        assertEquals(400, host.handle(PEER, "L", body(url = "https://x.com/" + "a".repeat(5000))).status)
        assertEquals(400, host.handle(PEER, "L", JSONObject("""{"requestId":"$REQ_1","url":"https://x.com"}""")).status)
        assertEquals(400, host.handle(PEER, "L", body(extra = ""","options":[1]""")).status)
        assertEquals(400, host.handle(PEER, "L", body(extra = ""","space":5""")).status)
        assertTrue(port.created.isEmpty())
    }

    @Test fun aRefusedLinkIsRememberedAndNeverBecomesAJob() {
        allowUrl = { throw UrlRefused("address:private", wordsFor("address:private")) }
        val refused = host.handle(PEER, "L", body(url = "http://192.168.1.1/x"))
        assertEquals(422, refused.status)
        assertEquals("url", refused.body.getString("error"))
        assertTrue(port.created.isEmpty())
        // asking again with the link now "allowed" gets the same answer: a policy refusal does not change by asking
        allowUrl = { it }
        assertEquals(422, host.handle(PEER, "L", body(url = "http://192.168.1.1/x")).status)
        assertTrue(port.created.isEmpty())
    }

    @Test fun aSpaceThatDoesNotExistIsRefused() {
        val r = host.handle(PEER, "L", body(extra = ""","space":"podcasts""""))
        assertEquals(422, r.status)
        assertEquals("space", r.body.getString("error"))
        assertEquals(201, host.handle(PEER, "L", body(request = REQ_2, extra = ""","space":"music"""")).status)
    }

    @Test fun lowStorageIsAnsweredAndTheRequestStaysUnrecorded() {
        port.free = 100L * 1024 * 1024
        assertEquals(507, host.handle(PEER, "L", body()).status)
        port.free = 10L * 1024 * 1024 * 1024
        assertEquals(201, host.handle(PEER, "L", body()).status) // it can be asked again once there is room
    }

    @Test fun theQueueRefusesToGrowWithoutEnd() {
        host.close()
        host = open(perPeer = 2)
        assertEquals(201, host.handle(PEER, "L", body(request = REQ_1)).status)
        assertEquals(201, host.handle(PEER, "L", body(request = REQ_2)).status)
        assertEquals(429, host.handle(PEER, "L", body(request = "33333333-aaaa-4aaa-8aaa-333333333333")).status)
    }

    @Test fun theRecordIsWrittenBeforeTheJobAndSurvivesARestart() {
        host.handle(PEER, "L", body())
        host.close()
        val jobId = port.created.single().first
        // the app restarted and lost the queue entry (a crash between the record and the job): asking again makes the same job
        port.views.clear()
        port.created.clear()
        host = open()
        val again = host.handle(PEER, "L", body())
        assertEquals(200, again.status)
        assertEquals(jobId, port.created.single().first)
        // and a job that still exists is not made twice
        port.created.clear()
        assertEquals(200, host.handle(PEER, "L", body()).status)
        assertTrue(port.created.isEmpty())
    }

    @Test fun aJobTheQueueRefusesIsRememberedAsRefused() {
        port.failCreate = "That format is not available."
        val r = host.handle(PEER, "L", body())
        assertEquals(422, r.status)
        assertEquals("That format is not available.", r.body.getString("message"))
        port.failCreate = null
        assertEquals(422, host.handle(PEER, "L", body()).status)
        assertNull(host.originOf("anything"))
    }

    @Test fun recordsAreKeptThirtyDays() {
        host.handle(PEER, "L", body())
        assertEquals(1, host.size)
        clock += JOB_RETENTION_MS + 1
        port.views.values.forEach { }
        port.views.clear() // the queue forgot it too
        host.handle(PEER, "L", body(request = REQ_2)) // a save sweeps the old record
        assertEquals(1, host.size)
    }

    @Test fun progressGoesBackOnlyWhenSomethingChangedAndAgainAfterAReconnect() {
        host.handle(PEER, "L", body())
        val jobId = port.created.single().first
        port.tick()
        assertEquals(1, notices.size)
        assertEquals(REQ_1, notices[0].second.getString("requestId"))
        port.tick() // nothing changed
        assertEquals(1, notices.size)
        port.views[jobId] = RemoteJobView("downloading", 40.0, "Title", "720p", 1000, 30)
        port.tick()
        assertEquals(2, notices.size)
        port.views[jobId] = RemoteJobView("downloading", 40.2, "Title", "720p", 2000, 20) // speed and fraction jitter alone are not news
        port.tick()
        assertEquals(2, notices.size)
        host.resend(PEER)
        assertEquals(3, notices.size)
        assertEquals(PEER, notices.last().first)
    }

    @Test fun theSenderWritesTheRequestBeforeItIsSentAndResendsTheSameId() {
        val file = File(dir, "out.json")
        val out = OutgoingJobs.open(file) { clock }
        val job = out.create("dev", "Phone", "https://x.com/v", JSONObject("""{"type":"audio","target":"mp3","quality":"best"}"""), null, null)
        assertEquals("sending", job.state)
        val reopened = OutgoingJobs.open(file) { clock }
        assertEquals(job.requestId, reopened.openFor("dev").single().requestId)
        reopened.applyView(job.requestId, RemoteJobView("completed", 100.0, "Title"))
        assertTrue(reopened.openFor("dev").isEmpty())
        reopened.update(job.requestId) { it.state = "failed" } // a finished job does not change again
        assertEquals("completed", reopened.get(job.requestId)!!.state)
        reopened.dismiss(job.requestId)
        assertNull(reopened.get(job.requestId))
    }

    @Test fun payloadHashIgnoresKeyOrderOnly() {
        val a = payloadHash("u", JSONObject("""{"a":1,"b":{"x":1,"y":2}}"""), null, null)
        val b = payloadHash("u", JSONObject("""{"b":{"y":2,"x":1},"a":1}"""), null, null)
        assertEquals(a, b)
        assertNotEquals(a, payloadHash("u", JSONObject("""{"a":2,"b":{"x":1,"y":2}}"""), null, null))
        assertNotEquals(a, payloadHash("v", JSONObject("""{"a":1,"b":{"x":1,"y":2}}"""), null, null))
        assertNotEquals(a, payloadHash("u", JSONObject("""{"a":1,"b":{"x":1,"y":2}}"""), null, "music"))
    }
}

class UrlGuardTest {
    private fun refusedWith(url: String, deps: GuardDeps): String {
        try { UrlGuard.guard(url, deps); fail("accepted $url") } catch (e: UrlRefused) { return e.reason }
        return ""
    }

    private fun deps(map: Map<String, List<String>> = emptyMap(), hop: ((HttpUrl, String) -> Hop)? = { _, _ -> Hop(200, null) }) =
        GuardDeps(resolve = { host -> map[host] ?: throw java.net.UnknownHostException(host) }, hop = hop)

    @Test fun onlyPublicWebLinksPass() {
        val d = deps(mapOf("example.com" to listOf("93.184.216.34")))
        assertEquals("https://example.com/v", UrlGuard.guard("https://example.com/v", d))
        for (bad in listOf("ftp://example.com/x", "file:///etc/passwd", "javascript:alert(1)", "http://user:pass@example.com/", "https://", "not a link", "http://localhost/x")) {
            refusedWith(bad, d)
        }
    }

    @Test fun addressesOnPrivateNetworksAreRefusedWhetherTypedOrResolved() {
        val d = deps(mapOf("sneaky.example" to listOf("10.1.2.3"), "mixed.example" to listOf("93.184.216.34", "192.168.0.9")))
        for (bad in listOf("http://127.0.0.1/", "http://192.168.1.5/x", "http://10.0.0.1/", "http://169.254.169.254/latest", "http://[::1]/", "http://[fd00::1]/", "http://sneaky.example/", "http://mixed.example/")) {
            val reason = refusedWith(bad, d)
            assertTrue("$bad: $reason", reason.startsWith("address:") || reason == "local-name")
        }
    }

    @Test fun aNameThatCannotBeFoundIsRefused() {
        assertEquals("address:unresolved", refusedWith("https://nowhere.example/", deps()))
    }

    @Test fun theConnectionGoesToTheCheckedAddressNotToWhatTheNameSaysNow() {
        var asked = 0
        val d = GuardDeps(
            resolve = { if (asked++ == 0) listOf("93.184.216.34") else listOf("10.0.0.5") }, // a name that changes its answer (DNS rebinding)
            hop = { url, address -> assertEquals("93.184.216.34", address); Hop(200, null) },
        )
        UrlGuard.guard("https://rebind.example/v", d)
        assertEquals(1, asked) // resolved once; the hop used that address
    }

    @Test fun everyRedirectHopIsCheckedBeforeAnythingIsSentThere() {
        val sent = ArrayList<String>()
        val d = GuardDeps(
            resolve = { host -> when (host) { "a.example" -> listOf("93.184.216.34"); "b.example" -> listOf("93.184.216.35"); "evil.example" -> listOf("192.168.0.2"); else -> throw java.net.UnknownHostException(host) } },
            hop = { url, _ ->
                sent += url.host
                when (url.host) {
                    "a.example" -> Hop(302, "https://b.example/next")
                    "b.example" -> Hop(301, "https://evil.example/inside")
                    else -> Hop(200, null)
                }
            },
        )
        val reason = refusedWith("https://a.example/start", d)
        assertTrue(reason.startsWith("address:"))
        assertEquals(listOf("a.example", "b.example"), sent) // nothing was ever sent to the private address
    }

    @Test fun aRedirectToAPublicLinkIsFollowedAndTheFinalLinkIsReturned() {
        val d = GuardDeps(
            resolve = { listOf("93.184.216.34") },
            hop = { url, _ -> if (url.encodedPath == "/start") Hop(302, "/final") else Hop(200, null) },
        )
        assertEquals("https://example.com/final", UrlGuard.guard("https://example.com/start", d))
    }

    @Test fun redirectLoopsStop() {
        val d = GuardDeps(resolve = { listOf("93.184.216.34") }, hop = { _, _ -> Hop(302, "https://example.com/again") }, maxRedirects = 5)
        assertEquals("too-many-redirects", refusedWith("https://example.com/start", d))
    }

    @Test fun aHopThatCannotBeReachedEndsTheCheckWithoutContactingAnythingBeyond() {
        val d = GuardDeps(resolve = { listOf("93.184.216.34") }, hop = { _, _ -> throw java.io.IOException("down") })
        assertEquals("https://example.com/v", UrlGuard.guard("https://example.com/v", d))
    }

    @Test fun theWordsForARefusalNameNoTools() {
        for (reason in listOf("bad-url", "scheme", "credentials", "no-host", "local-name", "too-many-redirects", "address:private", "other")) {
            val words = wordsFor(reason).lowercase()
            for (tool in listOf("yt-dlp", "ffmpeg", "gallery-dl", "aria2")) assertFalse(words.contains(tool))
        }
    }
}

class BackoffTest {
    @Test fun delaysDoubleFromTwoSecondsAndStopAtThirtyMinutes() {
        val mid = { 0.5 } // no jitter at the middle of the range
        assertEquals(listOf(2000L, 4000L, 8000L, 16000L, 32000L, 64000L), (0..5).map { backoffDelay(it, random = mid) })
        assertEquals(30 * 60_000L, backoffDelay(30, random = mid))
        assertEquals(30 * 60_000L, backoffDelay(Int.MAX_VALUE, random = mid))
    }

    @Test fun jitterStaysWithinAQuarterEitherWay() {
        assertEquals(1500L, backoffDelay(0, random = { 0.0 }))
        assertEquals(2500L, backoffDelay(0, random = { 1.0 }))
        repeat(200) {
            val d = backoffDelay(3)
            assertTrue(d in 12_000L..20_000L)
        }
    }

    @Test fun aNegativeCountIsTheFirstTry() {
        assertEquals(2000L, backoffDelay(-5, random = { 0.5 }))
    }

    @Test fun theLongestWaitIsNeverMoreThanThirtyMinutes() {
        assertTrue((1..40).all { backoffDelay(it, random = { 1.0 }) <= 30 * 60_000L })
    }
}
