package app.keepfin.cloud

import app.keepfin.copies.writeFileAtomic
import org.json.JSONObject
import java.io.File

/**
 * The session handle and the acknowledged offset of every resumable upload, so a restart continues instead of starting again
 * (dev-docs/plans/KEEPFIN-CLOUD-SPEC.md section 4). A session handle carries no token (the Authorization header is added per request), so
 * the file holds nothing secret. [prefixSha] is the SHA-256 of the first [offset] bytes: a restart reads those bytes again and carries
 * on only if they are the same, so a file that changed underneath is never glued together from two versions.
 */
internal class JournalEntry(val session: JSONObject, val offset: Long, val total: Long, val prefixSha: String, val createdAtMs: Long, val expiresAtMs: Long?)

internal interface UploadJournal {
    fun get(key: String): JournalEntry?
    fun put(key: String, entry: JournalEntry)
    fun update(key: String, offset: Long, prefixSha: String)
    fun remove(key: String)
}

internal class MemoryJournal : UploadJournal {
    val entries = LinkedHashMap<String, JournalEntry>()
    @Synchronized override fun get(key: String) = entries[key]
    @Synchronized override fun put(key: String, entry: JournalEntry) { entries[key] = entry }
    @Synchronized override fun update(key: String, offset: Long, prefixSha: String) {
        entries[key]?.let { entries[key] = JournalEntry(it.session, offset, it.total, prefixSha, it.createdAtMs, it.expiresAtMs) }
    }
    @Synchronized override fun remove(key: String) { entries.remove(key) }
}

/** A journal in one JSON file, replaced atomically on every change. */
internal class FileJournal(private val file: File) : UploadJournal {
    private var cache: JSONObject? = null

    @Synchronized private fun load(): JSONObject {
        cache?.let { return it }
        val o = try { JSONObject(file.readText()) } catch (e: Exception) { JSONObject() }
        cache = o
        return o
    }

    @Synchronized private fun save() {
        file.parentFile?.mkdirs()
        writeFileAtomic(file, (cache ?: JSONObject()).toString().toByteArray(Charsets.UTF_8))
    }

    @Synchronized override fun get(key: String): JournalEntry? {
        val o = load().optJSONObject(key) ?: return null
        return JournalEntry(
            o.getJSONObject("session"), o.getLong("offset"), o.getLong("total"), o.optString("prefixSha"), o.optLong("createdAtMs"),
            if (o.has("expiresAtMs") && !o.isNull("expiresAtMs")) o.getLong("expiresAtMs") else null,
        )
    }

    @Synchronized override fun put(key: String, entry: JournalEntry) {
        val o = JSONObject().put("session", entry.session).put("offset", entry.offset).put("total", entry.total).put("prefixSha", entry.prefixSha).put("createdAtMs", entry.createdAtMs)
        entry.expiresAtMs?.let { o.put("expiresAtMs", it) }
        load().put(key, o)
        save()
    }

    @Synchronized override fun update(key: String, offset: Long, prefixSha: String) {
        val o = load().optJSONObject(key) ?: return
        o.put("offset", offset).put("prefixSha", prefixSha)
        save()
    }

    @Synchronized override fun remove(key: String) {
        if (load().has(key)) {
            load().remove(key)
            save()
        }
    }
}
