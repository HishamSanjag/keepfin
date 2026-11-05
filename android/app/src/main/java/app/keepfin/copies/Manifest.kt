package app.keepfin.copies

import app.keepfin.folder.FolderAccess
import app.keepfin.folder.FolderError
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

internal class ManifestResult(val n: Int, val sha256: String, val files: Int, val copyFile: String, val mainFile: String)

internal class ManifestHeader(val placeId: String, val n: Int, val at: Long, val generation: Long, val holdsKey: String, val optionsKey: String)

/**
 * The coverage manifest of a run (spec 6): `places/<placeId>/manifest-<n>.jsonl` on the copy, plus the same file under the main folder's
 * `.keepfin/places/`. A header line records the holds key, the options key and the generation; one line per verified
 * (itemId, revisionOp, contentId, path). The `place.set verified` register points at it with its SHA-256.
 */
internal object Manifest {
    private val NAME = Regex("^manifest-(\\d+)\\.jsonl$")

    private fun dir(placeId: String) = ".keepfin/places/$placeId"

    private fun nextNumber(access: FolderAccess, placeId: String): Int {
        var max = 0
        try {
            for (e in access.list(dir(placeId)).entries) NAME.matchEntire(e.name)?.let { max = maxOf(max, it.groupValues[1].toInt()) }
        } catch (e: FolderError.NotFound) {
            // no manifest yet
        }
        return max + 1
    }

    /** Writes the manifest of a completed run from the relationship's baseline (every entry in it was verified at the generation it records). */
    fun write(
        stateDir: File, main: FolderAccess, copy: FolderAccess, placeId: String, generation: Long, holdsKey: String, optionsKey: String,
        mainReplica: String?, copyReplica: String?, now: Long, tmpDir: File,
    ): ManifestResult {
        val n = maxOf(nextNumber(copy, placeId), nextNumber(main, placeId))
        val name = "manifest-$n.jsonl"
        val local = File(tmpDir, "manifest.tmp").also { it.parentFile?.mkdirs() }
        val digest = MessageDigest.getInstance("SHA-256")
        var files = 0
        LineWriter(local).use { w ->
            fun add(line: String) {
                w.write(line)
                digest.update((line + "\n").toByteArray(Charsets.UTF_8))
            }
            add(JSONObject().put("kind", "manifest").put("placeId", placeId).put("n", n).put("at", now).put("generation", generation).put("holdsKey", holdsKey).put("optionsKey", optionsKey)
                .put("mainReplica", mainReplica ?: JSONObject.NULL).put("copyReplica", copyReplica ?: JSONObject.NULL).toString())
            for (e in Baseline.read(stateDir)) {
                add(JSONObject().put("itemId", e.itemId).put("revisionOp", e.revisionOp ?: JSONObject.NULL).put("contentId", e.contentId).put("path", e.path).toString())
                files++
            }
            w.finish(true)
        }
        val copyFile = "${dir(placeId)}/$name"
        val mainFile = "${dir(placeId)}/$name"
        copy.mkdirs(dir(placeId))
        FileInputStream(local).use { copy.publishFrom(copyFile, it) }
        main.mkdirs(dir(placeId))
        try {
            FileInputStream(local).use { main.publishFrom(mainFile, it) }
        } catch (e: FolderError.NameConflict) {
            // another device wrote the same number first; the copy's own file stands
        }
        local.delete()
        return ManifestResult(n, CONTENT_PREFIX + hexOf(digest.digest()), files, copyFile, mainFile)
    }

    /** The latest manifest of a place on [root]: its header and its lines. Null when there is none. */
    fun latest(root: FolderAccess, placeId: String): Pair<ManifestHeader, String>? {
        val n = nextNumber(root, placeId) - 1
        if (n < 1) return null
        val file = "${dir(placeId)}/manifest-$n.jsonl"
        val head = try {
            root.openRead(file).bufferedReader(Charsets.UTF_8).use { it.readLine() }
        } catch (e: FolderError.NotFound) {
            return null
        } ?: return null
        val o = try {
            JSONObject(head)
        } catch (e: org.json.JSONException) {
            return null
        }
        return ManifestHeader(placeId, n, o.optLong("at"), o.optLong("generation"), o.optString("holdsKey"), o.optString("optionsKey")) to file
    }

    /** A 64-bit hash of what "kept" compares: an item at its revision's content and path. */
    fun keyHash(itemId: String, contentId: String, path: String): Long {
        var h = -0x340d631b7bdddcdbL // FNV-1a offset basis
        fun feed(s: String) {
            for (b in s.toByteArray(Charsets.UTF_8)) {
                h = h xor (b.toLong() and 0xff)
                h *= 0x100000001b3L
            }
            h = h xor 0x0a
            h *= 0x100000001b3L
        }
        feed(itemId)
        feed(contentId)
        feed(path)
        return h
    }

    /** The sorted key hashes of the latest manifest's lines (about 8 bytes a file, so 200,000 items take under 2 MB). */
    fun keptKeys(root: FolderAccess, file: String): LongArray {
        var keys = LongArray(1024)
        var count = 0
        root.openRead(file).bufferedReader(Charsets.UTF_8).use { r ->
            r.readLine() // header
            while (true) {
                val line = r.readLine() ?: break
                if (line.isEmpty()) continue
                val o = try {
                    JSONObject(line)
                } catch (e: org.json.JSONException) {
                    continue
                }
                if (count == keys.size) keys = keys.copyOf(count * 2)
                keys[count++] = keyHash(o.getString("itemId"), o.getString("contentId"), o.getString("path"))
            }
        }
        val out = keys.copyOf(count)
        out.sort()
        return out
    }
}
