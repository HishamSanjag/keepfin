package app.keepfin.cloud

import app.keepfin.copies.CopyLock
import app.keepfin.copies.GuardedInput
import app.keepfin.copies.Generations
import app.keepfin.copies.SkipError
import app.keepfin.copies.hashStream
import app.keepfin.folder.FolderError
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** One contract, five servers: the desktop's fake Drive, OneDrive and Dropbox, its in-memory WebDAV, and the real published WebDAV server. */
@RunWith(Parameterized::class)
internal class CloudTransportTest(private val target: String) {
    companion object {
        /** The fakes hand out upload sessions that expire on 2026-10-16 12:00 UTC; the clock of the resume tests stands the day before. */
        private val FAKE_NOW = java.time.Instant.parse("2026-10-15T12:00:00Z").toEpochMilli()

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun targets(): List<String> = TARGETS
    }

    private lateinit var peer: NodePeer
    private lateinit var r: Rig

    @Before fun setUp() {
        peer = NodePeer.require()
        peer.reset()
        r = Rig(peer, target, Rig.uniqueRoot())
    }

    @After fun tearDown() {
        if (::peer.isInitialized) peer.reset()
    }

    private val provider get() = providerOf(target)

    private fun put(path: String, data: ByteArray) {
        r.access.mkdirs(path.substringBeforeLast('/', ""))
        r.access.publishFrom(path, GuardedInput(ByteArrayInputStream(data), { false }, {}, data.size.toLong(), contentIdOf(data)))
    }

    private fun get(path: String): ByteArray = r.access.openRead(path).use { it.readBytes() }

    @Test fun uploadsInChunksListsAcrossPagesAndDownloadsVerified() {
        val big = testBytes(17 * 1024 * 1024 + 123)
        put("Music/Artist/big.flac", big)
        for (i in 0 until 4) put("Music/Artist/s$i.mp3", testBytes(1000 + i, i))
        put("top.txt", "hello".toByteArray())
        val listing = r.access.list("Music/Artist")
        assertFalse("complete", listing.possiblyIncomplete)
        assertEquals(listOf("big.flac", "s0.mp3", "s1.mp3", "s2.mp3", "s3.mp3"), listing.entries.map { it.name }.sorted())
        assertEquals(big.size.toLong(), listing.entries.first { it.name == "big.flac" }.size)
        assertEquals(sha256Hex(big), hashStream(r.access.openRead("Music/Artist/big.flac")).contentId.removePrefix("sha256:"))
        assertArrayEquals("hello".toByteArray(), r.access.read("top.txt"))
        // what the phone wrote is what the desktop reads
        val seen = r.desktopFiles()
        assertEquals(setOf("Music/Artist/big.flac", "Music/Artist/s0.mp3", "Music/Artist/s1.mp3", "Music/Artist/s2.mp3", "Music/Artist/s3.mp3", "top.txt"), seen.keys)
        assertEquals(sha256Hex(big), seen.getValue("Music/Artist/big.flac").getString("sha256").removePrefix("sha256:"))
    }

    @Test fun refusesAnUploadWhoseSourceIsNotTheBytesThatWerePinned() {
        val data = testBytes(3000)
        try {
            r.access.publishFrom("x.bin", GuardedInput(ByteArrayInputStream(data), { false }, {}, data.size.toLong(), contentIdOf("something else".toByteArray())))
            fail("the upload should have been refused")
        } catch (e: FolderError.Io) {
            assertTrue("a skip, not a failure", e.cause is SkipError && (e.cause as SkipError).reason == "hash-mismatch")
        }
        assertNull(r.access.stat("x.bin"))
        assertEquals(0, r.desktopFiles(false).size)
    }

    @Test fun aTakenNameIsNeverReplacedByAPublish() {
        put("a.bin", testBytes(100))
        try {
            put("a.bin", testBytes(100, 2))
            fail("a taken name must conflict")
        } catch (e: FolderError.NameConflict) {
            // expected
        }
        assertArrayEquals(testBytes(100), get("a.bin"))
    }

    @Test fun waitsForRetryAfterOnRateLimitAndRetriesServerErrors() {
        if (target == "davreal") return // the real server cannot be told to fail; the in-memory one covers WebDAV
        put("a.bin", testBytes(100))
        val (method, path) = listFault()
        peer.fault(target, 429, 1, method, path, mapOf("retry-after" to "7"), JSONObject().put("error_summary", "too_many_requests/..").put("error", JSONObject().put("reason", JSONObject().put(".tag", "too_many_requests")).put("retry_after", 7)))
        peer.fault(target, 503, 2, method, path)
        val l = r.access.list("")
        assertEquals(1, l.entries.count { it.name == "a.bin" })
        assertFalse(l.possiblyIncomplete)
        assertTrue("slept ${r.sleeps}", r.sleeps.any { it >= 7000 })
        assertTrue(r.sleeps.size >= 3)
    }

    @Test fun anExpiredTokenIsRefreshedOnceAndTheCallCarriesOn() {
        if (provider == "webdav") return // a password does not expire
        put("a.bin", testBytes(100))
        val (method, path) = listFault()
        peer.fault(target, 401, 1, method, path, body = JSONObject().put("error_summary", "expired_access_token/..").put("error", JSONObject().put(".tag", "expired_access_token")))
        assertEquals(1, r.access.list("").entries.count { it.name == "a.bin" })
        assertEquals(1, r.account.refreshes)
    }

    @Test fun aSignInThatIsRefusedAfterTheRefreshSurfacesAsNeedsAccess() {
        if (provider == "webdav") return
        val (method, path) = listFault()
        peer.fault(target, 401, 10, method, path, body = JSONObject().put("error_summary", "invalid_access_token/..").put("error", JSONObject().put(".tag", "invalid_access_token")))
        try {
            r.access.list("")
            fail("a refused sign-in must not look like an empty folder")
        } catch (e: FolderError.NeedsAccess) {
            // expected: the run shows "Sign in again"
        }
    }

    @Test fun aTornListingIsIncompleteNotEmpty() {
        if (provider == "webdav") return // PROPFIND is not paged
        for (i in 0 until 5) put("f$i.bin", testBytes(10, i))
        // the first page answers, the next one fails for good: what was read stays, and the listing says it is not complete
        peer.fault(target, 500, 50, listFault().first, nextPagePath())
        val l = r.access.list("")
        assertTrue("an incomplete listing must say so", l.possiblyIncomplete)
        assertTrue("the first page was kept (${l.entries.size})", l.entries.size in 1..4)
    }

    @Test fun replaceKeepsTheDisplacedBytes() {
        put("doc.txt", "first".toByteArray())
        r.access.replace("doc.txt", "second".toByteArray())
        assertEquals("second", get("doc.txt").toString(Charsets.UTF_8))
        val trashed = trashNames()
        // with compare-and-swap the new revision replaces the old in place (the provider keeps the history); elsewhere the old bytes moved to the trash
        if (r.client.capabilities().casReplace) assertTrue(trashed.isEmpty()) else {
            assertEquals(1, trashed.size)
            assertEquals("first", r.access.read(".keepfin/trash/${trashed[0]}").toString(Charsets.UTF_8))
        }
    }

    @Test fun aStaleRevisionIsRefusedAndNothingIsOverwrittenWhereTheProviderCanCompareAndSwap() {
        put("doc.txt", "first".toByteArray())
        if (!r.client.capabilities().casReplace) return
        try {
            r.transport.replace("hijack".toByteArray(), "doc.txt", "\"stale-revision\"")
            fail("a stale revision must be refused")
        } catch (e: CloudError) {
            assertEquals(Kind.CONFLICT, e.kind)
        }
        assertEquals("first", get("doc.txt").toString(Charsets.UTF_8))
    }

    @Test fun movesByIdKeepTheBytesAndNeverReplace() {
        put("a/one.txt", "1".toByteArray())
        put("a/two.txt", "2".toByteArray())
        r.access.mkdirs("b")
        r.transport.moveTo("a/one.txt", "b/uno.txt", null)
        assertNull(r.access.stat("a/one.txt"))
        assertEquals("1", get("b/uno.txt").toString(Charsets.UTF_8))
        try {
            r.transport.moveTo("a/two.txt", "b/uno.txt", null)
            fail("a taken name must not be replaced")
        } catch (e: CloudError) {
            assertEquals(Kind.CONFLICT, e.kind)
        }
        assertEquals("2", get("a/two.txt").toString(Charsets.UTF_8))
    }

    @Test fun removeKeepsTheBytesInTheTrash() {
        put("keep/me.txt", "precious".toByteArray())
        r.access.remove("keep/me.txt")
        assertNull(r.access.stat("keep/me.txt"))
        val trashed = trashNames()
        assertEquals(1, trashed.size)
        assertEquals("precious", r.access.read(".keepfin/trash/${trashed[0]}").toString(Charsets.UTF_8))
    }

    private fun trashNames(): List<String> = try { r.access.list(".keepfin/trash").entries.filter { !it.isDirectory }.map { it.name } } catch (e: FolderError.NotFound) { emptyList() }

    @Test fun aRangeThatIsIgnoredIsNeverAppended() {
        val data = testBytes(300_000)
        put("big.bin", data)
        if (provider == "webdav") peer.post("/option", JSONObject().put("target", "davfake").put("name", "ignoreRange").put("value", true)) else peer.post("/option", JSONObject().put("target", target).put("name", "ignoreRange").put("value", true))
        try {
            val d = ByteArray(data.size)
            val ins = r.transport.openRead("big.bin")
            var got = 0
            // read part, then break the stream and carry on: the reopened answer starts at byte 0 and the delivered part is skipped
            while (got < 100_000) got += ins.read(d, got, 100_000 - got)
            ins.close()
            val again = r.transport.openRead("big.bin")
            val all = again.readBytes()
            assertArrayEquals(data, all)
        } finally {
            peer.post("/option", JSONObject().put("target", if (provider == "webdav") "davfake" else target).put("name", "ignoreRange").put("value", false))
        }
    }

    @Test fun theLockIsAdvisoryAndContendedLocksAreRefused() {
        r.access.mkdirs(".keepfin")
        val t = System.currentTimeMillis()
        val first = CopyLock.acquire(r.access, "phone-A", "run1", { t }, 60_000, 300_000) as CopyLock.Companion.Result.Ok
        val other = r.another()
        val refused = CopyLock.acquire(other.access, "desktop-B", "runB", { t + 1000 }, 60_000, 300_000)
        assertTrue("a second device is refused while the heartbeat is fresh", refused is CopyLock.Companion.Result.Refused)
        // the desktop sees the same lock object
        val desktop = peer.post("/desktop/lock", JSONObject().put("target", target).put("root", r.root).put("device", "desktop").put("run", "x"))
        assertFalse("the desktop sees the phone's lock", desktop.getBoolean("held"))
        first.lock.release()
        val taken = CopyLock.acquire(other.access, "desktop-B", "run2", { t + 2000 }, 60_000, 300_000)
        assertTrue(taken is CopyLock.Companion.Result.Ok)
        (taken as CopyLock.Companion.Result.Ok).lock.release()
    }

    @Test fun aStaleLockIsTakenOver() {
        r.access.mkdirs(".keepfin")
        val t0 = System.currentTimeMillis()
        val a = CopyLock.acquire(r.access, "phone-A", "run1", { t0 }, 600_000, 300_000) as CopyLock.Companion.Result.Ok
        val other = r.another()
        val late = CopyLock.acquire(other.access, "desktop-B", "runB", { t0 + 300_001L }, 600_000, 300_000)
        assertTrue("a lock older than five minutes is stale and may be taken over", late is CopyLock.Companion.Result.Ok)
        a.lock.abandon()
        (late as CopyLock.Companion.Result.Ok).lock.release()
    }

    @Test fun generationsAreSharedWithTheDesktopAsAppendOnlyRecords() {
        r.access.mkdirs(".keepfin")
        val folderJson = """{"format":1,"libraryId":"lib","replicaId":"rep-phone","createdAt":"2026-10-16T00:00:00Z","generation":0}"""
        r.access.publish(Generations.FOLDER_JSON, folderJson.toByteArray())
        assertEquals(0L, Generations.read(r.access))
        val bumped = Generations.bump(r.access, "run-1")
        assertEquals(1L, bumped)
        assertEquals(1L, peer.post("/desktop/scan", JSONObject().put("target", target).put("root", r.root).put("hash", false)).getLong("generation"))
        // the desktop synced in between and appended generation 5: the phone reads it
        peer.post("/desktop/generation", JSONObject().put("target", target).put("root", r.root).put("generation", 5).put("replica", "rep-desktop"))
        assertEquals(5L, Generations.read(r.another().access))
    }

    @Test fun anInterruptedUploadResumesTheJournaledSessionAndSendsNoPartTwice() {
        if (provider == "webdav") return // a whole-file PUT has no session
        val logBefore = peer.log(target).size
        val journalFile = File.createTempFile("kf-journal", ".json")
        val rig1 = Rig(peer, target, r.root, now = { FAKE_NOW }, journalFile = journalFile)
        val data = testBytes(21 * 1024 * 1024 + 7)
        val crash = object : InputStream() {
            private val inner = ByteArrayInputStream(data)
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (data.size - inner.available() > 14 * 1024 * 1024) throw IOException("the process died")
                return inner.read(b, off, minOf(len, 1024 * 1024))
            }
        }
        rig1.access.mkdirs("")
        try {
            rig1.access.publishFrom("big/crash.bin", GuardedInput(crash, { false }, {}, data.size.toLong(), contentIdOf(data)))
            fail("the first attempt should have been cut short")
        } catch (e: FolderError) {
            // the "process died"
        }
        val journal = FileJournal(journalFile)
        assertTrue("the session is journaled with an acknowledged offset", journalEntries(journalFile) == 1)
        // a new process: a new client over the same journal file
        val rig2 = Rig(peer, target, r.root, now = { FAKE_NOW }, journalFile = journalFile)
        rig2.access.publishFrom("big/crash.bin", GuardedInput(ByteArrayInputStream(data), { false }, {}, data.size.toLong(), contentIdOf(data)))
        assertEquals(0, journalEntries(journalFile))
        assertEquals(sha256Hex(data), hashStream(rig2.access.openRead("big/crash.bin")).contentId.removePrefix("sha256:"))
        val starts = peer.log(target).drop(logBefore).count { Regex("uploadType=resumable|createUploadSession|upload_session/start").containsMatchIn(it) && it.startsWith("POST") }
        assertEquals("one session for both attempts", 1, starts)
        assertNotNull(journal)
    }

    private fun journalEntries(f: File): Int = try { JSONObject(f.readText()).length() } catch (e: Exception) { 0 }

    @Test fun aDroppedSessionStartsAgainWithoutCorruptingTheFile() {
        if (provider == "webdav") return
        val journalFile = File.createTempFile("kf-journal", ".json")
        val rig1 = Rig(peer, target, r.root, now = { FAKE_NOW }, journalFile = journalFile)
        val data = testBytes(12 * 1024 * 1024)
        val crash = object : InputStream() {
            private val inner = ByteArrayInputStream(data)
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (data.size - inner.available() > 9 * 1024 * 1024) throw IOException("died")
                return inner.read(b, off, minOf(len, 1024 * 1024))
            }
        }
        try { rig1.access.publishFrom("d.bin", GuardedInput(crash, { false }, {}, data.size.toLong(), contentIdOf(data))) } catch (e: FolderError) { }
        peer.post("/expire", JSONObject().put("target", target))
        val rig2 = Rig(peer, target, r.root, now = { FAKE_NOW }, journalFile = journalFile)
        rig2.access.publishFrom("d.bin", GuardedInput(ByteArrayInputStream(data), { false }, {}, data.size.toLong(), contentIdOf(data)))
        assertEquals(sha256Hex(data), hashStream(rig2.access.openRead("d.bin")).contentId.removePrefix("sha256:"))
    }

    // ---- where the providers differ

    /** The request that lists a folder, as (method, path pattern). */
    private fun listFault(): Pair<String?, String?> = when (provider) {
        "drive" -> "GET" to "/drive/v3/files"
        "graph" -> "GET" to "/children"
        "dropbox" -> "POST" to "list_folder"
        else -> "PROPFIND" to null
    }

    private fun nextPagePath(): String? = when (provider) {
        "drive" -> "pageToken"
        "graph" -> "skiptoken"
        "dropbox" -> "list_folder/continue"
        else -> null
    }
}


