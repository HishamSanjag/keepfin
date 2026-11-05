package app.keepfin.proto

import android.app.Instrumentation
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.keepfin.MainActivity
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Shared helpers for the phase 2 Android probes (SAF tree picking, shell, document helpers). */
class ProbeSupport(val tag: String) {
    val inst: Instrumentation = InstrumentationRegistry.getInstrumentation()
    val ctx: Context = inst.targetContext
    val cr: ContentResolver = ctx.contentResolver
    val device: UiDevice = UiDevice.getInstance(inst)
    val authority = "com.android.externalstorage.documents"
    val rwFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    fun log(s: String) { Log.i(tag, s); runCatching { java.io.File(ctx.filesDir, "probe.log").appendText(s + "\n") } }

    fun shell(cmd: String): String {
        val p = inst.uiAutomation.executeShellCommandRwe(cmd)
        p[1].close()
        val out = FileInputStream(p[0].fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
        val err = FileInputStream(p[2].fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
        p[0].close(); p[2].close()
        if (err.isNotBlank()) log("shell stderr [${cmd.take(60)}]: ${err.take(200)}")
        return out
    }

    /** The volume id of the first public (virtual SD) volume, e.g. "43D6-1A23", or null. */
    fun sdVolume(): String? =
        shell("sm list-volumes").lines().firstOrNull { it.startsWith("public:") && it.contains("mounted") }
            ?.trim()?.split(" ")?.last()?.takeIf { it != "null" }

    /** Directory path on a volume: "primary" -> /sdcard, else /storage/<id>. */
    fun fsPath(vol: String, rel: String) = (if (vol == "primary") "/sdcard" else "/storage/$vol") + "/" + rel

    /** Opens the system picker at [vol]:[rel], taps "Use this folder"/"Allow", persists the grant and returns the tree URI. */
    fun pickTree(vol: String, rel: String): Uri {
        // A picker right after a card remount can return a result without a grant (the provider is still rebuilding its roots): try again.
        var last: Throwable? = null
        repeat(3) { attempt ->
            try { return pickTreeOnce(vol, rel) } catch (e: SecurityException) { last = e; log("pickTree attempt ${attempt + 1} failed: ${e.message?.take(120)}"); Thread.sleep(3000) }
        }
        throw last!!
    }

    private fun pickTreeOnce(vol: String, rel: String): Uri {
        val initial = DocumentsContract.buildDocumentUri(authority, "$vol:$rel")
        var result: Uri? = null
        val done = CountDownLatch(1)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { act ->
                val launcher = act.activityResultRegistry.register("keepfin-proto2-" + System.nanoTime(), ActivityResultContracts.OpenDocumentTree()) { uri -> result = uri; done.countDown() }
                launcher.launch(initial)
            }
            fun dump(why: String) {
                val out = java.io.ByteArrayOutputStream(); device.dumpWindowHierarchy(out)
                val txt = Regex("(?:text|content-desc)=\"([^\"]+)\"").findAll(out.toString()).map { it.groupValues[1] }.distinct().joinToString(" | ")
                log("picker hierarchy ($why): ${txt.take(600)}")
            }
            val use = device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)use this folder"))), 20_000)
            if (use == null) { dump("no 'Use this folder'"); throw AssertionError("picker button not found") }
            use.click()
            val allow = device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)allow"))), 10_000)
            if (allow == null) { dump("no 'Allow'"); throw AssertionError("allow dialog not found") }
            allow.click()
            if (!done.await(15, TimeUnit.SECONDS)) throw AssertionError("picker result")
        }
        val uri = result!!
        cr.takePersistableUriPermission(uri, rwFlags)
        log("tree uri=$uri persisted=${cr.persistedUriPermissions.any { it.uri == uri }}")
        return uri
    }

    fun releaseAll(match: String) {
        runCatching { cr.persistedUriPermissions.filter { it.uri.toString().contains(match) }.forEach { cr.releasePersistableUriPermission(it.uri, rwFlags) } }
    }

    fun rootDoc(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    fun create(tree: Uri, parentDocId: String, mime: String, name: String): Uri? =
        DocumentsContract.createDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, parentDocId), mime, name)

    /** Child names (display names) of a directory document id. */
    fun children(tree: Uri, parentDocId: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) out += c.getString(0) to c.getString(1)
        }
        return out
    }

    /** A valid 16-bit stereo 44.1 kHz PCM WAV header for [dataBytes] bytes of audio. */
    fun wavHeader(dataBytes: Long): ByteArray {
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt((36 + dataBytes).toInt()); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(2); h.putInt(44100); h.putInt(44100 * 4); h.putShort(4); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(dataBytes.toInt())
        return h.array()
    }
}
