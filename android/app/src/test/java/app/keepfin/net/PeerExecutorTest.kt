package app.keepfin.net

import app.keepfin.folder.FakeFolderAccess
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The peer side of a copy (network spec 5): a device never writes into another device's folder directly. */
class PeerExecutorTest {
    private lateinit var folder: FakeFolderAccess
    private lateinit var ex: PeerExecutorImpl
    private var clock = 1_000_000L
    private val owner = "device-a"

    @Before fun setUp() {
        folder = FakeFolderAccess()
        folder.mkdirs(".keepfin")
        folder.publish(".keepfin/folder.json", """{"format":1,"libraryId":"x","replicaId":"r","createdAt":"t","generation":4}""".toByteArray())
        ex = PeerExecutorImpl(folder, { clock }, "Phone", leaseMs = 60_000)
    }

    private fun idOf(bytes: ByteArray) = "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun lock(who: String = owner): String = ex.lock(who, "laptop", "run", clock).getString("token")

    private fun put(token: String, path: String, bytes: ByteArray, mode: String = "new", ifMatch: String? = null, id: String = idOf(bytes), size: Long = bytes.size.toLong(), stream: ByteArray = bytes) =
        ex.put(owner, PutParams(token, path, id, mode, null, ifMatch, size), ByteArrayInputStream(stream))

    private fun status(block: () -> Any?): Int = try { block(); 200 } catch (e: HttpError) { e.status }

    @Test fun oneDeviceHoldsTheCopyAtATime() {
        val first = ex.lock(owner, "laptop", "r1", clock)
        assertTrue(first.getBoolean("ok"))
        assertTrue(ex.state().getBoolean("locked"))
        val second = ex.lock("device-b", "tablet", "r2", clock)
        assertFalse(second.getBoolean("ok"))
        assertEquals("another device", second.getString("holder"))
        // the same device asking again (a retry) takes over its own lease
        val again = ex.lock(owner, "laptop", "r3", clock)
        assertTrue(again.getBoolean("ok"))
        assertTrue(again.getString("token") != first.getString("token"))
        assertEquals(409, status { ex.unlock(owner, first.getString("token")) }) // the old token is gone
        ex.unlock(owner, again.getString("token"))
        assertTrue(ex.lock("device-b", "tablet", "r4", clock).getBoolean("ok"))
    }

    @Test fun aLostLinkLetsGoOfTheLock() {
        lock()
        ex.release(owner)
        assertFalse(ex.state().getBoolean("locked"))
        assertTrue(ex.lock("device-b", "tablet", "r", clock).getBoolean("ok"))
    }

    @Test fun anExpiredLeaseFreesTheCopy() {
        val short = PeerExecutorImpl(folder, { clock }, "Phone", leaseMs = 80)
        val token = short.lock(owner, "laptop", "r", clock).getString("token")
        Thread.sleep(300)
        assertEquals(409, status { short.heartbeat(owner, token) })
        assertTrue(short.lock("device-b", "tablet", "r2", clock).getBoolean("ok"))
    }

    @Test fun aHeartbeatKeepsTheLeaseAlive() {
        val short = PeerExecutorImpl(folder, { clock }, "Phone", leaseMs = 400)
        val token = short.lock(owner, "laptop", "r", clock).getString("token")
        repeat(4) { Thread.sleep(150); short.heartbeat(owner, token) }
        assertTrue(short.state().getBoolean("locked"))
    }

    @Test fun nothingChangesWithoutTheLock() {
        assertEquals(409, status { put("nope", "a.mp3", "x".toByteArray()) })
        assertEquals(409, status { ex.trash(owner, "nope", "a.mp3", null, null) })
        assertEquals(409, status { ex.move(owner, "nope", "a.mp3", "b.mp3", null, null) })
        assertEquals(409, status { ex.appendGeneration(owner, "nope", 5, "r") })
        val token = lock()
        assertEquals(409, status { ex.trash("device-b", token, "a.mp3", null, null) }) // somebody else's token
    }

    @Test fun aNewFileIsStagedCheckedAndPublishedAndNeverReplacesAnother() {
        val token = lock()
        val bytes = "hello world".toByteArray()
        val result = put(token, "Music/a.mp3", bytes)
        assertEquals("hello world", String(folder.files.getValue("Music/a.mp3")))
        assertTrue(result.getString("revision").startsWith("11-"))
        assertTrue(result.isNull("preservedAs"))
        assertEquals(409, status { put(token, "Music/a.mp3", "other".toByteArray()) })
        assertEquals("hello world", String(folder.files.getValue("Music/a.mp3")))
        assertTrue("a staged file was left behind: ${folder.files.keys}", folder.files.keys.none { it.contains(".kftmp") })
    }

    @Test fun bytesThatDoNotMatchTheContentIdAreRefusedAndLeaveNothing() {
        val token = lock()
        val bytes = "hello world".toByteArray()
        assertEquals(422, status { put(token, "a.mp3", bytes, stream = "hello wurld".toByteArray()) })
        assertEquals(400, status { put(token, "a.mp3", bytes, size = 50) }) // arrived short
        assertEquals(413, status { put(token, "a.mp3", bytes, size = 3) }) // more than announced
        assertEquals(400, status { put(token, "a.mp3", bytes, id = "sha256:zz") })
        assertEquals(400, status { put(token, "a.mp3", bytes, id = "md5:" + "a".repeat(32)) })
        assertFalse(folder.files.containsKey("a.mp3"))
        assertTrue(folder.files.keys.none { it.contains(".kftmp") })
    }

    @Test fun aReplaceNeedsTheContentTheCallerSawAndKeepsTheOldBytes() {
        val token = lock()
        val old = "first version".toByteArray()
        put(token, "a.mp3", old)
        val replacement = "second version".toByteArray()
        // the file was edited on this device since the caller looked: nothing is replaced
        assertEquals(412, status { put(token, "a.mp3", replacement, mode = "replace", ifMatch = idOf("something else".toByteArray())) })
        assertEquals("first version", String(folder.files.getValue("a.mp3")))
        val result = put(token, "a.mp3", replacement, mode = "replace", ifMatch = idOf(old))
        assertEquals("second version", String(folder.files.getValue("a.mp3")))
        val kept = result.getString("preservedAs")
        assertEquals("first version", String(folder.files.getValue(".keepfin/trash/net/$kept")))
        assertEquals(412, status { put(token, "gone.mp3", replacement, mode = "replace", ifMatch = idOf(old)) }) // nothing to replace
    }

    @Test fun trashKeepsTheBytesAndNeverDeletes() {
        val token = lock()
        val bytes = "to trash".toByteArray()
        put(token, "Music/t.mp3", bytes)
        assertEquals(412, status { ex.trash(owner, token, "Music/t.mp3", null, idOf("different".toByteArray())) })
        assertTrue(folder.files.containsKey("Music/t.mp3"))
        val name = ex.trash(owner, token, "Music/t.mp3", null, idOf(bytes)).getString("trashedAs")
        assertFalse(folder.files.containsKey("Music/t.mp3"))
        assertEquals("to trash", String(folder.files.getValue(".keepfin/trash/net/$name")))
        assertEquals(404, status { ex.trash(owner, token, "Music/t.mp3", null, null) })
    }

    @Test fun aMoveRenamesWithinTheFolderOnlyOverNothing() {
        val token = lock()
        val bytes = "move me".toByteArray()
        put(token, "Music/m.mp3", bytes)
        put(token, "Music/other.mp3", "other".toByteArray())
        assertEquals(409, status { ex.move(owner, token, "Music/m.mp3", "Music/other.mp3", null, idOf(bytes)) })
        assertEquals(412, status { ex.move(owner, token, "Music/m.mp3", "Music/n.mp3", null, idOf("no".toByteArray())) })
        ex.move(owner, token, "Music/m.mp3", "Music/n.mp3", null, idOf(bytes))
        assertFalse(folder.files.containsKey("Music/m.mp3"))
        ex.move(owner, token, "Music/n.mp3", "Other/deep/n.mp3", null, idOf(bytes)) // across folders
        assertEquals("move me", String(folder.files.getValue("Other/deep/n.mp3")))
        assertEquals(404, status { ex.move(owner, token, "Music/zzz.mp3", "Music/y.mp3", null, null) })
    }

    @Test fun onlyNamesInsideTheFolderCanBeNamed() {
        val token = lock()
        val bytes = "x".toByteArray()
        val bad = listOf(
            "", "/abs.mp3", "../x.mp3", "a/../../x.mp3", "a//b.mp3", "./a.mp3", "a/./b.mp3", "a\\b.mp3", "c:\\x.mp3", "a:b.mp3", "a*.mp3", "a?.mp3", "a<b>.mp3", "a|b.mp3", "name.", "name ",
            ".keepfin/x", ".KEEPFIN/lock", "Keepfin Exports/a.m3u8", "a/b.kftmp-1", "x".repeat(300) + ".mp3", "a/" + "d/".repeat(600) + "f.mp3", "tmp.part", "Thumbs.db",
        )
        for (name in bad) {
            assertEquals("put $name", 400, status { put(token, name, bytes) })
            assertEquals("trash $name", 400, status { ex.trash(owner, token, name, null, null) })
            assertEquals("move $name", 400, status { ex.move(owner, token, name, "ok.mp3", null, null) })
            assertEquals("open $name", 400, status { ex.open(name) })
        }
        assertTrue("nothing was written: ${folder.files.keys}", folder.files.keys.all { it.startsWith(".keepfin/") })
    }

    @Test fun theValidNameRuleAgreesWithTheDesktop() {
        for (ok in listOf("a.mp3", "Music/Artist/Song (feat. X).flac", "日本語/曲.mp3", "a b/c d.mp3")) assertTrue(ok, PeerExecutorImpl.validRelative(ok))
        for (no in listOf("", "..", "a/..", ".keepfin", ".keepfin/a")) assertFalse(no, PeerExecutorImpl.validRelative(no))
    }

    @Test fun generationsOnlyGoUpAndOnlyUnderTheLock() {
        val token = lock()
        val up = ex.appendGeneration(owner, token, 5, "replica")
        assertTrue(up.getBoolean("ok"))
        assertEquals(5, up.getLong("generation"))
        val stale = ex.appendGeneration(owner, token, 5, "replica")
        assertFalse(stale.getBoolean("ok"))
        assertEquals(5, stale.getLong("generation"))
        assertEquals(400, status { ex.appendGeneration(owner, token, -1, "replica") })
        assertEquals(5, ex.state().getLong("generation"))
    }

    @Test fun aFolderWithoutAGenerationFileReportsNone() {
        val bare = PeerExecutorImpl(FakeFolderAccess(), { clock })
        assertTrue(bare.state().isNull("generation"))
        val t = bare.lock(owner, "d", "r", clock).getString("token")
        assertFalse(bare.appendGeneration(owner, t, 1, "r").getBoolean("ok"))
    }

    @Test fun aFileOfTheCopyCanBeReadFromAnyOffset() {
        val token = lock()
        put(token, "a.bin", ByteArray(100) { it.toByte() })
        val opened = ex.open("a.bin")
        assertEquals(100, opened.size)
        val tail = opened.open(90).readBytes()
        assertEquals(10, tail.size)
        assertEquals(90, tail[0].toInt())
        assertEquals(404, status { ex.open("missing.bin") })
        assertNull(null)
    }

    @Test fun roomIsCheckedBeforeBytesArrive() {
        val small = object : app.keepfin.folder.FolderAccess by folder {
            override fun freeBytes(): Long? = 1024L
        }
        val tight = PeerExecutorImpl(small, { clock })
        val token = tight.lock(owner, "d", "r", clock).getString("token")
        val bytes = "x".toByteArray()
        try {
            tight.put(owner, PutParams(token, "a.mp3", idOf(bytes), "new", null, null, 1), ByteArrayInputStream(bytes))
            fail()
        } catch (e: HttpError) {
            assertEquals(507, e.status)
        }
    }
}
