package app.keepfin.proto

import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.keepfin.MainActivity
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Phase 0 prototype B: listing a large folder through the Storage Access Framework (real picker, real provider). */
@RunWith(AndroidJUnit4::class)
class SafListingProtoTest {
    private val tag = "KeepfinProto"
    private val inst: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private val root = "/sdcard/Download/KeepfinSafBench"

    // 200 artists x 10 albums x 10 tracks = 20,000 files in 2,200 directories (plus the root).
    private val artists = 200
    private val albums = 10
    private val tracks = 10

    private fun shell(cmd: String): String {
        val p = inst.uiAutomation.executeShellCommandRwe(cmd)
        p[1].close()
        val out = FileInputStream(p[0].fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
        val err = FileInputStream(p[2].fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
        p[0].close(); p[2].close()
        if (err.isNotBlank()) Log.i(tag, "B: shell stderr for [${cmd.take(60)}]: ${err.take(300)}")
        return out
    }

    private fun createTree() {
        Log.i(tag, "B: probe echo=[${shell("echo hi").trim()}] id=[${shell("id").trim()}] ls=[${shell("ls /sdcard/Download").trim().replace("\n", ",")}]")
        val existing = shell("find $root -type f").lines().count { it.isNotBlank() }
        if (existing == artists * albums * tracks) { Log.i(tag, "B: reusing existing tree with $existing files"); return }
        shell("rm -rf $root")
        val names = (1..tracks).joinToString(" ") { "t%02d.mp3".format(it) }
        val t0 = System.nanoTime()
        // The shell command line has no quote handling, so one mkdir and one touch per album (2 x 2000 calls).
        for (a in 1..artists) for (b in 1..albums) {
            val d = "$root/Artist$a/Album$b"
            shell("mkdir -p $d")
            shell("touch " + (1..tracks).joinToString(" ") { "$d/t%02d.mp3".format(it) })
        }
        val n = shell("find $root -type f").lines().count { it.isNotBlank() }.toString()
        Log.i(tag, "B: created $n files in ${(System.nanoTime() - t0) / 1e9} s (expected ${artists * albums * tracks})")
        assertEquals((artists * albums * tracks).toString(), n)
    }

    @After fun cleanup() {
        runCatching { ctx.contentResolver.persistedUriPermissions.filter { it.uri.toString().contains("KeepfinSafBench") }.forEach { ctx.contentResolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        // Deleting 20,000 files from inside the instrumented process once crashed the run after every measurement had
        // finished. Cleanup must never fail the prototype; if it does not finish, remove the tree from the host with
        // `adb shell rm -rf /sdcard/Download/KeepfinSafBench`.
        if (InstrumentationRegistry.getArguments().getString("keep") != "1") {
            runCatching { shell("rm -rf $root") }
                .onSuccess { Log.i(tag, "B: cleaned up test tree") }
                .onFailure { Log.w(tag, "B: cleanup failed; remove the tree from the host", it) }
        }
    }

    private fun pickTree(): Uri {
        val initial = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/KeepfinSafBench")
        var result: Uri? = null
        val done = CountDownLatch(1)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { act ->
                val launcher = act.activityResultRegistry.register("keepfin-proto-tree", ActivityResultContracts.OpenDocumentTree()) { uri -> result = uri; done.countDown() }
                launcher.launch(initial)
            }
            fun dump(why: String) {
                val out = java.io.ByteArrayOutputStream(); device.dumpWindowHierarchy(out)
                val txt = Regex("(?:text|content-desc|resource-id)=\"([^\"]+)\"").findAll(out.toString()).map { it.groupValues[1] }.distinct().joinToString(" | ")
                Log.i(tag, "B: picker hierarchy ($why): $txt")
            }
            val use = device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)use this folder"))), 20_000)
            if (use == null) { dump("no 'Use this folder'"); throw AssertionError("picker button not found") }
            Log.i(tag, "B: picker shown, tapping 'Use this folder'")
            use.click()
            val allow = device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)allow"))), 10_000)
            if (allow == null) { dump("no 'Allow'"); throw AssertionError("allow dialog not found") }
            Log.i(tag, "B: tapping 'Allow'")
            allow.click()
            assertTrue("picker result", done.await(15, TimeUnit.SECONDS))
        }
        val uri = assertNotNull(result).let { result!! }
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        Log.i(tag, "B: tree uri=$uri persisted=${ctx.contentResolver.persistedUriPermissions.any { it.uri == uri }}")
        return uri
    }

    private data class Scan(val files: Int, val dirs: Int, val queries: Int, val ms: Double, val dirMtime: HashMap<String, Long>, val dirKids: HashMap<String, List<String>>, val peakMb: Double, val dirFiles: HashMap<String, Int> = HashMap())

    private val proj = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    private fun usedMb() = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576.0

    /** (a) Recursive walk, one ContentResolver.query per directory. */
    private fun walkResolver(tree: Uri): Scan {
        val cr = ctx.contentResolver
        val mtimes = HashMap<String, Long>(); val kids = HashMap<String, List<String>>(); val dfiles = HashMap<String, Int>()
        var files = 0; var dirs = 0; var queries = 0; var peak = usedMb()
        val keep = ArrayList<String>() // hold names/ids like a real index would
        val t0 = System.nanoTime()
        val stack = ArrayDeque<String>(); stack.add(DocumentsContract.getTreeDocumentId(tree))
        while (stack.isNotEmpty()) {
            val id = stack.removeLast(); dirs++
            val sub = ArrayList<String>(); var nHere = 0
            cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), proj, null, null, null)?.use { c ->
                queries++
                while (c.moveToNext()) {
                    val docId = c.getString(0); val name = c.getString(1); val mime = c.getString(2)
                    val size = c.getLong(3); val mod = c.getLong(4)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) { stack.add(docId); sub += docId; mtimes[docId] = mod } else { files++; nHere++; keep += docId; keep += name; if (size < 0) throw AssertionError() }
                }
            }
            kids[id] = sub; dfiles[id] = nHere
            if (dirs % 500 == 0) peak = maxOf(peak, usedMb())
        }
        val ms = (System.nanoTime() - t0) / 1e6
        return Scan(files, dirs, queries, ms, mtimes, kids, maxOf(peak, usedMb()), dfiles)
    }

    /** (b) Same walk with DocumentFile, reading the same five fields per entry. */
    private fun walkDocumentFile(tree: Uri): Scan {
        var files = 0; var dirs = 0; var peak = usedMb()
        val keep = ArrayList<String>()
        val t0 = System.nanoTime()
        val stack = ArrayDeque<DocumentFile>(); stack.add(DocumentFile.fromTreeUri(ctx, tree)!!)
        while (stack.isNotEmpty()) {
            val d = stack.removeLast(); dirs++
            for (f in d.listFiles()) {
                val name = f.name; val isDir = f.isDirectory; val size = f.length(); val mod = f.lastModified()
                if (isDir) stack.add(f) else { files++; keep += name.orEmpty(); if (size < 0 || mod < 0) throw AssertionError() }
            }
            if (dirs % 500 == 0) peak = maxOf(peak, usedMb())
        }
        return Scan(files, dirs, -1, (System.nanoTime() - t0) / 1e6, HashMap(), HashMap(), maxOf(peak, usedMb()))
    }

    /** (c) Re-scan reusing the previous scan: a directory whose mtime is unchanged is not re-listed (its cached sub-directories are re-checked with a one-row query). */
    private fun incremental(tree: Uri, prev: Scan): Scan {
        val cr = ctx.contentResolver
        val mtimes = HashMap<String, Long>(); val kids = HashMap<String, List<String>>(); val dfiles = HashMap<String, Int>()
        var files = 0; var dirs = 0; var listings = 0; var singles = 0
        val t0 = System.nanoTime()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        // directory file counts are cached too, so unchanged directories add their old count
        val stack = ArrayDeque<Pair<String, Long?>>(); stack.add(rootId to null) // null mtime = root (always listed)
        while (stack.isNotEmpty()) {
            val (id, mod) = stack.removeLast(); dirs++
            val cachedMod = prev.dirMtime[id]
            if (mod != null && cachedMod == mod && prev.dirKids.containsKey(id)) {
                files += prev.dirFiles[id] ?: 0; dfiles[id] = prev.dirFiles[id] ?: 0
                for (k in prev.dirKids[id]!!) {
                    var km = -1L
                    cr.query(DocumentsContract.buildDocumentUriUsingTree(tree, k), arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c -> singles++; if (c.moveToFirst()) km = c.getLong(0) }
                    stack.add(k to km)
                }
                mtimes[id] = mod; kids[id] = prev.dirKids[id]!!
            } else {
                val sub = ArrayList<String>(); var n = 0
                cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), proj, null, null, null)?.use { c ->
                    listings++
                    while (c.moveToNext()) {
                        val docId = c.getString(0)
                        if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) { stack.add(docId to c.getLong(4)); sub += docId } else { n++ }
                    }
                }
                files += n; dfiles[id] = n; kids[id] = sub; if (mod != null) mtimes[id] = mod
            }
        }
        Log.i(tag, "B: incremental: dirs visited=$dirs, directories listed=$listings, one-row mtime queries=$singles")
        return Scan(files, dirs, listings + singles, (System.nanoTime() - t0) / 1e6, mtimes, kids, usedMb(), dfiles)
    }

    private fun report(label: String, s: Scan) =
        Log.i(tag, "B: $label files=${s.files} dirs=${s.dirs} queries=${s.queries} time=${"%.0f".format(s.ms)} ms rate=${"%.0f".format(s.files / (s.ms / 1000))} files/s heapMb=${"%.0f".format(s.peakMb)}")

    @Test fun safListing() {
        // a measurement (20,000 files and the real folder picker), not a regression test: run with -e probes true, like the other probes
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("probes") == "true")
        Log.i(tag, "B: API ${Build.VERSION.SDK_INT} model=${Build.MODEL} fingerprint=${Build.FINGERPRINT}")
        createTree()
        val tree = pickTree()
        val total = artists * albums * tracks

        val a1 = walkResolver(tree); report("(a) resolver walk run1 (first listing after creation, cold)", a1)
        assertEquals(total, a1.files)
        val a2 = walkResolver(tree); report("(a) resolver walk run2 (warm)", a2)
        val a3 = walkResolver(tree); report("(a) resolver walk run3 (warm)", a3)

        val b1 = walkDocumentFile(tree); report("(b) DocumentFile walk run1", b1)
        assertEquals(total, b1.files)
        if (b1.ms < 90_000) { val b2 = walkDocumentFile(tree); report("(b) DocumentFile walk run2", b2) }

        val c0 = incremental(tree, a3); report("(c) incremental, nothing changed", c0)
        assertEquals(total, c0.files)

        // Does a directory's LAST_MODIFIED change when a child is added on this provider?
        val before = walkResolver(tree)
        val aId = "primary:Download/KeepfinSafBench/Artist5/Album3"
        val artistId = "primary:Download/KeepfinSafBench/Artist5"
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        Thread.sleep(1500)
        shell("touch $root/Artist5/Album3/new_track.mp3")
        val after = walkResolver(tree)
        Log.i(tag, "B: add file in Artist5/Album3: Album3 mtime ${before.dirMtime[aId]} -> ${after.dirMtime[aId]} (changed=${before.dirMtime[aId] != after.dirMtime[aId]}); Artist5 mtime ${before.dirMtime[artistId]} -> ${after.dirMtime[artistId]} (changed=${before.dirMtime[artistId] != after.dirMtime[artistId]}); files ${before.files} -> ${after.files}")
        // And a new sub-directory in Artist6 (parent should change)
        Thread.sleep(1500)
        shell("mkdir -p $root/Artist6/AlbumNew")
        shell("touch $root/Artist6/AlbumNew/x.mp3")
        val after2 = walkResolver(tree)
        assertEquals(before.files + 2, after2.files)
        val a6 = "primary:Download/KeepfinSafBench/Artist6"
        Log.i(tag, "B: add directory in Artist6: Artist6 mtime ${after.dirMtime[a6]} -> ${after2.dirMtime[a6]} (changed=${after.dirMtime[a6] != after2.dirMtime[a6]}); files ${after.files} -> ${after2.files}")

        // Incremental after the two changes, seeded with the pre-change scan
        val c1 = incremental(tree, before)
        report("(c) incremental, 1 file added to Album3 and 1 new directory in Artist6", c1)
        assertEquals(after2.files, c1.files)
        val full = walkResolver(tree); report("(a) full resolver walk, same moment, for comparison", full)
        assertTrue(rootId.isNotEmpty())
        Log.i(tag, "B: DONE")
    }
}
