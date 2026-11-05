package app.keepfin.folder

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.core.sync.JStr
import app.keepfin.proto.ProbeSupport
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [SafFolderAccess] and the file side of [KeepfinStore] against the real provider, on primary storage and the virtual SD
 * card (sm set-virtual-disk true; sm partition disk:7,440 public). The picker is driven with UiAutomator as in the probes.
 */
@RunWith(AndroidJUnit4::class)
class FolderAccessInstrumentedTest {
    private val p = ProbeSupport("KeepfinFolderTest")
    private val dirName = "KeepfinFolderTest"
    private var vol = "primary"
    private var tree: Uri? = null

    @After fun cleanup() {
        p.releaseAll(dirName)
        volLine().takeIf { it.isNotBlank() }?.let { runCatching { p.shell("sm mount public:$it") } }
        runCatching { p.shell("rm -rf ${p.fsPath(vol, dirName)}") }
    }

    private fun volLine(): String = p.shell("sm list-volumes").lines().firstOrNull { it.startsWith("public:") }?.trim()?.split(" ")?.first()?.removePrefix("public:") ?: ""

    private fun fresh(volume: String): SafFolderAccess {
        vol = volume
        p.shell("rm -rf ${p.fsPath(vol, dirName)}")
        p.shell("mkdir -p ${p.fsPath(vol, dirName)}")
        val t = p.pickTree(vol, dirName)
        tree = t
        return SafFolderAccess.open(p.cr, t)
    }

    private fun sdOrFail() = p.sdVolume() ?: throw AssertionError("no virtual SD volume mounted")

    @Test fun primaryBasics() = basics(fresh("primary"), expectFree = true)
    @Test fun sdBasics() = basics(fresh(sdOrFail()), expectFree = true)

    private fun basics(a: SafFolderAccess, expectFree: Boolean) {
        // mkdirs, publish, read back
        a.mkdirs("Music/Rock")
        assertTrue(a.stat("Music/Rock")!!.isDirectory)
        val payload = ByteArray(300_000) { (it * 7).toByte() }
        val result = a.publish("Music/Rock/a.bin", payload)
        assertEquals(payload.size.toLong(), result.size)
        assertArrayEquals(payload, a.read("Music/Rock/a.bin"))
        assertEquals(payload.size.toLong(), a.stat("Music/Rock/a.bin")!!.size)
        // no leftover staged file
        assertEquals(listOf("a.bin"), a.list("Music/Rock").entries.map { it.name })
        // publish never replaces
        try {
            a.publish("Music/Rock/a.bin", ByteArray(3))
            fail("expected NameConflict")
        } catch (e: FolderError.NameConflict) {
        }
        assertArrayEquals(payload, a.read("Music/Rock/a.bin"))
        assertEquals(listOf("a.bin"), a.list("Music/Rock").entries.map { it.name })
        // replace
        a.replace("Music/Rock/note.txt", "one".toByteArray())
        a.replace("Music/Rock/note.txt", "two".toByteArray())
        assertEquals("two", a.read("Music/Rock/note.txt").toString(Charsets.UTF_8))
        assertEquals(listOf("a.bin", "note.txt"), a.list("Music/Rock").entries.map { it.name }.sorted())
        // missing things
        assertNull(a.stat("Music/Rock/none.bin"))
        try {
            a.read("Music/Rock/none.bin")
            fail("expected NotFound")
        } catch (e: FolderError.NotFound) {
        }
        // a hidden empty file (.nomedia)
        a.mkdirs(".keepfin")
        a.publish(".keepfin/.nomedia", ByteArray(0))
        // On primary storage the system fills a new .nomedia with the folder path (found by this test), so only its existence counts.
        assertNotNull(a.stat(".keepfin/.nomedia"))
        // remove a file and a directory tree
        a.remove("Music/Rock/note.txt")
        assertNull(a.stat("Music/Rock/note.txt"))
        a.remove("Music")
        assertNull(a.stat("Music/Rock/a.bin"))
        assertNull(a.stat("Music"))
        a.remove("Music") // already gone: fine
        if (expectFree) assertNotNull(a.freeBytes())
    }

    @Test fun deleteEverythingRemovesTheWholeKeepfinFolderThenResets() {
        val a = fresh("primary")
        a.mkdirs("Music/Viking"); a.mkdirs(".keepfin/trash/x")
        a.publish("Music/Viking/song.mp3", ByteArray(50_000))
        a.publish(".keepfin/folder.json", "{}".toByteArray())
        a.publish(".keepfin/trash/x/old.mp3", ByteArray(10))
        var reset = false
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val error = app.keepfin.ui.settings.StartOver.deleteEverything(context, tree.toString()) { reset = true; true }
        assertNull(error)
        assertTrue(reset)
        // nothing of the folder is left on the disk: no media, no library data, no trash
        assertEquals("", p.shell("ls -A ${p.fsPath(vol, dirName)} 2>/dev/null").trim())
    }

    @Test fun aFileMovesIntoAnotherFolderWithoutACopy() {
        val a = fresh("primary")
        a.mkdirs("Music"); a.mkdirs(".keepfin/trash/x")
        a.publish("Music/song.mp3", ByteArray(300_000) { (it % 251).toByte() })
        assertTrue(a.moveInto("Music/song.mp3", ".keepfin/trash/x"))
        assertEquals(null, a.stat("Music/song.mp3"))
        assertEquals(300_000L, a.stat(".keepfin/trash/x/song.mp3")?.size)
        assertEquals(1L, (a.read(".keepfin/trash/x/song.mp3")[1]).toLong())
        // a taken name there: nothing moves and the caller copies instead
        a.publish("Music/song.mp3", "again".toByteArray())
        assertFalse(a.moveInto("Music/song.mp3", ".keepfin/trash/x"))
        assertEquals("again", a.read("Music/song.mp3").toString(Charsets.UTF_8))
    }

    @Test fun primaryRenames() = renames(fresh("primary"))
    @Test fun sdRenames() = renames(fresh(sdOrFail()))

    private fun renames(a: SafFolderAccess) {
        a.publish("x.bin", "x".toByteArray())
        a.publish("y.bin", "y".toByteArray())
        // a taken name is a conflict, undone: both files are untouched
        try {
            a.rename("x.bin", "y.bin")
            fail("expected NameConflict")
        } catch (e: FolderError.NameConflict) {
        }
        assertEquals("x", a.read("x.bin").toString(Charsets.UTF_8))
        assertEquals("y", a.read("y.bin").toString(Charsets.UTF_8))
        assertEquals(listOf("x.bin", "y.bin"), a.list("").entries.map { it.name }.sorted())
        // a plain rename
        a.rename("x.bin", "z.bin")
        assertNull(a.stat("x.bin"))
        assertEquals("x", a.read("z.bin").toString(Charsets.UTF_8))
        // a case-only rename goes through a temporary name
        a.publish("Foo.bin", "foo".toByteArray())
        a.rename("Foo.bin", "foo.bin")
        assertEquals(listOf("foo.bin", "y.bin", "z.bin"), a.list("").entries.map { it.name }.sorted())
        assertEquals("foo", a.read("foo.bin").toString(Charsets.UTF_8))
        // a renamed directory: children are found again by path
        a.mkdirs("D/E")
        a.publish("D/E/f.bin", "f".toByteArray())
        a.rename("D", "D2")
        assertNull(a.stat("D/E/f.bin"))
        assertEquals("f", a.read("D2/E/f.bin").toString(Charsets.UTF_8))
    }

    @Test fun otherProvidersAreRefused() {
        val cr = p.cr
        val downloads = Uri.parse("content://com.android.providers.downloads.documents/tree/downloads")
        try {
            SafFolderAccess.open(cr, downloads)
            fail("expected UnsupportedProvider")
        } catch (e: FolderError.UnsupportedProvider) {
            assertEquals("com.android.providers.downloads.documents", e.authority)
        }
    }

    @Test fun aRevokedGrantIsNeedsAccess() {
        val a = fresh("primary")
        a.publish("a.bin", "a".toByteArray())
        p.cr.releasePersistableUriPermission(tree!!, p.rwFlags)
        try {
            a.list("")
            fail("expected NeedsAccess")
        } catch (e: FolderError.NeedsAccess) {
        }
        try {
            a.publish("b.bin", "b".toByteArray())
            fail("expected NeedsAccess")
        } catch (e: FolderError.NeedsAccess) {
        }
        // and opening again without the grant says the same
        try {
            SafFolderAccess.open(p.cr, tree!!)
            fail("expected NeedsAccess")
        } catch (e: FolderError.NeedsAccess) {
        }
    }

    @Test fun anUnmountedCardIsUnavailableAndNeverEmpty() {
        val a = fresh(sdOrFail())
        a.mkdirs("Music")
        a.publish("Music/a.bin", "a".toByteArray())
        assertEquals(1, a.list("Music").entries.size)
        val line = volLine()
        p.shell("sm unmount public:$line")
        try {
            var failure: FolderError? = null
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (failure == null && System.nanoTime() < deadline) {
                try {
                    val listing = a.list("Music")
                    // an empty listing must never be reported as complete after the card was seen with a file
                    assertTrue(listing.possiblyIncomplete || listing.entries.isNotEmpty())
                } catch (e: FolderError) {
                    failure = e
                }
                Thread.sleep(250)
            }
            assertTrue("expected Unavailable but got $failure", failure is FolderError.Unavailable)
            try {
                a.publish("Music/b.bin", "b".toByteArray())
                fail("expected a FolderError")
            } catch (e: FolderError.Unavailable) {
            }
        } finally {
            // vold sometimes ignores a mount that comes right after the unmount: ask again until the card is back, so later tests (copies) never find it missing
            val t0 = System.nanoTime()
            var asked = 0L
            while (System.nanoTime() - t0 < 60_000_000_000L && p.sdVolume() == null) {
                if (System.nanoTime() - asked > 3_000_000_000L) { runCatching { p.shell("sm mount public:$line") }; asked = System.nanoTime() }
                Thread.sleep(250)
            }
        }
        // back again: the same grant works and the file is still there
        // the provider serves the card again a moment after vold reports it mounted
        var back: ByteArray? = null
        var cycles = 0L
        val t1 = System.nanoTime()
        while (back == null) {
            try {
                back = a.read("Music/a.bin")
            } catch (e: FolderError.Unavailable) {
                val waited = System.nanoTime() - t1
                if (waited > 90_000_000_000L) throw e
                // the documents provider sometimes keeps "No root" after a remount (an emulator quirk): cycle the card again
                if (waited / 25_000_000_000L > cycles) {
                    cycles++
                    runCatching { p.shell("sm unmount public:$line") }
                    Thread.sleep(2000)
                    runCatching { p.shell("sm mount public:$line") }
                }
                Thread.sleep(500)
            }
        }
        assertEquals("a", back.toString(Charsets.UTF_8))
    }

    // ------------------------------------------------------------------ the store on the real provider

    @Test fun primaryStoreRoundTrip() = storeRoundTrip(fresh("primary"))
    @Test fun sdStoreRoundTrip() = storeRoundTrip(fresh(sdOrFail()))

    private fun storeRoundTrip(a: SafFolderAccess) {
        val actor1 = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val actor2 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val library = "11111111-1111-4111-8111-111111111111"
        val tmp = File(p.ctx.cacheDir, "kf-store-" + System.nanoTime()).also { it.mkdirs() }
        var n = 0
        fun options(actor: String, device: String, ckpt: Int) = StoreOptions(
            folder = a, libraryId = library, actor = actor,
            deviceDir = File(tmp, "dev-$device"), outboxDir = File(tmp, "out-$device"),
            newId = { "cccccccc-cccc-4ccc-8ccc-%012d".format(++n) }, checkpointEvery = ckpt, checkpointDelayMs = 20, flushIdleMs = 3_600_000,
            state = app.keepfin.folder.sql.SqlMergeState(File(tmp, "dev-$device/state.db").path, library, "a1a1a1a1-a1a1-4a1a-8a1a-a1a1a1a1a1a1", null, app.keepfin.folder.sql.AndroidSqlHost()),
        )
        try {
            KeepfinFolders.create(a, library, "rep1", "2026-10-15T10:00:00Z")
            assertTrue(KeepfinFolders.open(a) is OpenFolderResult.Ok)
            val s1 = KeepfinStore.open(options(actor1, "1", ckpt = 5)).get(60, TimeUnit.SECONDS)
            s1.apply((1..8).map { OpDraft("profile.set", mapOf("field" to JStr("name"), "value" to JStr("v$it"))) }).get(60, TimeUnit.SECONDS)
            s1.flush().get(60, TimeUnit.SECONDS)
            val views = s1.viewsJson().get(60, TimeUnit.SECONDS)
            s1.close().get(60, TimeUnit.SECONDS)
            val names = a.list(KeepfinStore.OPS_DIR + "/" + actor1).entries.map { it.name }
            assertEquals(1, names.size)
            assertTrue(names[0].endsWith(".seg"))
            // a checkpoint was written and no staged temp file is left behind
            assertTrue(a.list(KeepfinStore.CKPT_DIR + "/0").entries.any { it.name.endsWith(".ckpt") })
            assertTrue(a.list(KeepfinStore.OPS_DIR + "/" + actor1).entries.none { it.name.endsWith(".tmp") })

            // reopen as another install of the same folder (new device files): same state, from the checkpoint
            val s2 = KeepfinStore.open(options(actor2, "2", ckpt = 5)).get(60, TimeUnit.SECONDS)
            assertEquals(views, s2.viewsJson().get(60, TimeUnit.SECONDS))
            assertTrue(s2.stats!!.checkpoint)
            // and a segment written by it reaches the first one on rescan
            s2.apply(listOf(OpDraft("profile.set", mapOf("field" to JStr("name"), "value" to JStr("from-2"))))).get(60, TimeUnit.SECONDS)
            s2.flush().get(60, TimeUnit.SECONDS)
            val s1b = KeepfinStore.open(options(actor1, "1", ckpt = 5)).get(60, TimeUnit.SECONDS)
            assertEquals(1L, s1b.versionVector().get(60, TimeUnit.SECONDS)[actor2])
            assertEquals(s2.viewsJson().get(60, TimeUnit.SECONDS), s1b.viewsJson().get(60, TimeUnit.SECONDS))
            s1b.close().get(60, TimeUnit.SECONDS)
            s2.close().get(60, TimeUnit.SECONDS)
        } finally {
            tmp.deleteRecursively()
        }
    }
}
