package app.keepfin.proto

import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Probe 2: MediaStore visibility of files created through SAF, and the generation query. */
@RunWith(AndroidJUnit4::class)
class MediaStoreVisibilityProtoTest {
    private val p = ProbeSupport("KeepfinProbe2")
    private val rel = "Music/KeepfinProbe2"
    private val pathPrefix = "Music/KeepfinProbe2/"

    @After fun cleanup() { p.releaseAll("KeepfinProbe2"); if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("keep") != "1") runCatching { p.shell("rm -rf /sdcard/$rel") } }

    private data class Row(val id: Long, val size: Long, val owner: String?, val genAdded: Long, val genMod: Long, val pending: Int, val mediaType: Int)

    private fun rows(): Map<String, Row> {
        val m = HashMap<String, Row>()
        val u = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.MediaColumns.GENERATION_ADDED, MediaStore.MediaColumns.GENERATION_MODIFIED, MediaStore.MediaColumns.IS_PENDING, MediaStore.Files.FileColumns.MEDIA_TYPE)
        p.cr.query(u, proj, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$pathPrefix%"), null)?.use { c ->
            while (c.moveToNext()) m[c.getString(1)] = Row(c.getLong(0), c.getLong(2), c.getString(3), c.getLong(4), c.getLong(5), c.getInt(6), c.getInt(7))
        }
        return m
    }

    private fun rowsSince(gen: Long): Int {
        val u = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return p.cr.query(u, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.GENERATION_MODIFIED} > ?", arrayOf("$pathPrefix%", gen.toString()), null)?.use { it.count } ?: -1
    }

    /** Polls until [ok] holds; returns elapsed ms or -1 on timeout. */
    private fun waitFor(maxMs: Long, ok: () -> Boolean): Long {
        val t0 = System.nanoTime()
        while ((System.nanoTime() - t0) / 1_000_000 < maxMs) { if (ok()) return (System.nanoTime() - t0) / 1_000_000; Thread.sleep(25) }
        return -1
    }

    private fun wav(seconds: Int): ByteArray { val n = 44100 * 4 * seconds; return p.wavHeader(n.toLong()) + ByteArray(n) }

    @Test fun mediaStoreVisibility() {
        // a probe from phase 0, run on purpose only (-Pandroid.testInstrumentationRunnerArguments.probes=true): it waits on the media scanner for many minutes
        org.junit.Assume.assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("probes") == "true")
        val sdk = android.os.Build.VERSION.SDK_INT
        val grant = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("perm") == "1"
        if (grant) { listOf("READ_MEDIA_AUDIO", "READ_MEDIA_VIDEO").forEach { p.inst.uiAutomation.grantRuntimePermission(p.ctx.packageName, "android.permission.$it") } }
        p.log("RUN with media permission requested=$grant")
        val perms = listOf("READ_MEDIA_AUDIO", "READ_MEDIA_VIDEO", "READ_EXTERNAL_STORAGE").associateWith { p.ctx.checkSelfPermission("android.permission.$it") == PackageManager.PERMISSION_GRANTED }
        p.log("API $sdk permissions granted=$perms")
        p.shell("rm -rf /sdcard/$rel"); p.shell("mkdir -p /sdcard/$rel")
        val tree = p.pickTree("primary", rel)
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val n = 20

        val gen0 = MediaStore.getGeneration(p.ctx, MediaStore.VOLUME_EXTERNAL_PRIMARY)
        p.log("baseline: generation=$gen0 rows=${rows().size}")

        // A. create n files through SAF, no scan call
        val t0 = System.nanoTime()
        val uris = ArrayList<Uri>()
        val lat = ArrayList<Long>()
        for (i in 0 until n) {
            val u = p.create(tree, rootId, "audio/x-wav", "a$i.wav")!!
            p.cr.openOutputStream(u)!!.use { it.write(wav(1)) }
            uris += u
            lat += waitFor(30_000) { rows().containsKey("a$i.wav") }
        }
        val createMs = (System.nanoTime() - t0) / 1_000_000
        val first = waitFor(30_000) { rows().isNotEmpty() }
        val all = waitFor(30_000) { rows().size >= n }
        val sample = rows().values.firstOrNull()
        p.log("A (no scan): created $n wav files via SAF in $createMs ms; first row visible after ${first} ms, all $n after ${all} ms (-1 = not within 120 s); sample row=$sample; per-file latency from stream close to row visible (ms, -1 = never) min/median/max=${lat.minOrNull()}/${lat.sorted()[lat.size / 2]}/${lat.maxOrNull()}")
        val gen1 = MediaStore.getGeneration(p.ctx, MediaStore.VOLUME_EXTERNAL_PRIMARY)
        p.log("A: generation $gen0 -> $gen1; rows with generation_modified > gen0: ${rowsSince(gen0)}")

        // B. modify one file (grow it), poll for the size to change
        val before = rows()["a3.wav"]?.size
        p.cr.openOutputStream(uris[3], "wt")!!.use { it.write(wav(2)) }
        val modMs = waitFor(30_000) { rows()["a3.wav"]?.size.let { it != null && it != before } }
        p.log("B modify: size $before -> ${rows()["a3.wav"]?.size}, visible after $modMs ms; rows modified since gen1: ${rowsSince(gen1)}; generation now ${MediaStore.getGeneration(p.ctx, MediaStore.VOLUME_EXTERNAL_PRIMARY)}")

        // C. delete one file
        val gen2 = MediaStore.getGeneration(p.ctx, MediaStore.VOLUME_EXTERNAL_PRIMARY)
        DocumentsContract.deleteDocument(p.cr, uris[5])
        val delMs = waitFor(30_000) { rows()["a5.wav"] == null }
        p.log("C delete: row gone after $delMs ms (-1 = still present at 120 s); rows now ${rows().size}; generation $gen2 -> ${MediaStore.getGeneration(p.ctx, MediaStore.VOLUME_EXTERNAL_PRIMARY)}. Deletions leave no row, so generation_modified cannot report them")

        // D. same creation, then MediaScannerConnection.scanFile on the mapped path right away
        val sub = p.create(tree, rootId, DocumentsContract.Document.MIME_TYPE_DIR, "scanned")!!
        val subId = DocumentsContract.getDocumentId(sub)
        val gen3 = MediaStore.getGeneration(p.ctx, MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val paths = ArrayList<String>()
        val tS = System.nanoTime()
        for (i in 0 until n) {
            val u = p.create(tree, subId, "audio/x-wav", "b$i.wav")!!
            p.cr.openOutputStream(u)!!.use { it.write(wav(1)) }
            paths += "/storage/emulated/0/$rel/scanned/b$i.wav"
        }
        val latch = java.util.concurrent.CountDownLatch(n)
        var scanNull = 0
        val tScan = System.nanoTime()
        MediaScannerConnection.scanFile(p.ctx, paths.toTypedArray(), null) { _, uri -> if (uri == null) scanNull++; latch.countDown() }
        val scanDone = latch.await(120, java.util.concurrent.TimeUnit.SECONDS)
        val scanMs = (System.nanoTime() - tScan) / 1_000_000
        val visible = waitFor(30_000) { rows().keys.count { it.startsWith("b") } >= n }
        p.log("D scanFile: callbacks finished=$scanDone in $scanMs ms (null uris=$scanNull); all $n rows visible ${visible} ms after the scan call started (create+write took ${(tScan - tS) / 1_000_000} ms); rows modified since gen3: ${rowsSince(gen3)}")

        // E. rename detection and a file created by shell (not SAF) as control, all without scan
        p.shell("cp /sdcard/$rel/a0.wav /sdcard/$rel/shell_copy.wav")
        val shellMs = waitFor(60_000) { rows().containsKey("shell_copy.wav") }
        p.log("E control: file created by the shell user (not SAF) visible after $shellMs ms; owner=${rows()["shell_copy.wav"]?.owner}")
        p.log("owners seen: ${rows().values.map { it.owner }.distinct()}; pending values: ${rows().values.map { it.pending }.distinct()}; media types: ${rows().values.map { it.mediaType }.distinct()}")

        p.log("DONE")
    }
}
