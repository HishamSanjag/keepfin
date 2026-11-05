package app.keepfin.copies

import app.keepfin.folder.FolderAccess
import app.keepfin.folder.FolderError
import org.json.JSONObject

private val RESERVED = Regex("^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?$", RegexOption.IGNORE_CASE)
private const val MAX_COMPONENT = 200

/** One component spelled so that Windows, FAT and exFAT accept it (a card or drive may be read there). Returns the component itself when it is fine. */
internal fun safeComponent(name: String): String {
    val sb = StringBuilder()
    for (ch in name) sb.append(if (ch.code < 32 || "<>:\"|?*\\".indexOf(ch) >= 0) '_' else ch)
    var out = sb.toString()
    if (RESERVED.matches(out)) out = "_$out"
    var end = out.length
    while (end > 0 && (out[end - 1] == '.' || out[end - 1] == ' ')) end--
    if (end < out.length) out = out.substring(0, end) + "_".repeat(out.length - end)
    if (out.length > MAX_COMPONENT) {
        val dot = out.lastIndexOf('.')
        val ext = if (dot > 0 && out.length - dot <= 16) out.substring(dot) else ""
        out = out.substring(0, MAX_COMPONENT - ext.length) + ext
    }
    return out
}

/**
 * The durable name map of a copy (spec 5.4): `.keepfin/names.v1.jsonl` maps library paths to physical names. A name is written to
 * the map BEFORE it is used, and every reader of the copy applies it. A phone's file system is rarely picky, but a card that is
 * read on Windows is, so the same rules apply as on the desktop.
 */
internal class NameMap private constructor(private val access: FolderAccess?) {
    private val forward = HashMap<String, String>()
    private val backward = HashMap<String, String>()

    companion object {
        const val FILE = ".keepfin/names.v1.jsonl"

        fun identity(): NameMap = NameMap(null)

        fun load(access: FolderAccess): NameMap {
            val map = NameMap(access)
            val text = try {
                access.read(FILE).toString(Charsets.UTF_8)
            } catch (e: FolderError.NotFound) {
                ""
            }
            for (line in text.split('\n')) {
                if (line.isBlank()) continue
                try {
                    val o = JSONObject(line)
                    map.remember(o.getString("lib"), o.getString("phys"))
                } catch (e: Exception) {
                    // a torn last line
                }
            }
            return map
        }
    }

    private fun remember(lib: String, phys: String) {
        forward[lib] = phys
        backward[phys.lowercase()] = lib
    }

    val size: Int get() = forward.size

    /** The physical relative path of [lib] as far as the map knows (nothing is added). */
    fun physical(lib: String): String {
        if (access == null || forward.isEmpty()) return lib
        var prefix = ""
        val out = ArrayList<String>()
        for (part in lib.split('/')) {
            prefix = if (prefix.isEmpty()) part else "$prefix/$part"
            val mapped = forward[prefix]
            if (mapped != null) {
                out.clear()
                out.addAll(mapped.split('/'))
            } else out.add(part)
        }
        return out.joinToString("/")
    }

    /** The library path of a physical relative path (the reverse of [physical]). */
    fun library(phys: String): String {
        if (access == null || backward.isEmpty()) return phys
        var prefix = ""
        var libPrefix = ""
        for (part in phys.split('/')) {
            prefix = if (prefix.isEmpty()) part else "$prefix/$part"
            val hit = backward[prefix.lowercase()]
            libPrefix = hit ?: if (libPrefix.isEmpty()) part else "$libPrefix/$part"
        }
        return libPrefix
    }

    /** The physical path for [lib], mapping the names the file system cannot take. New mappings are durable before this returns. */
    @Synchronized
    fun resolve(lib: String): String {
        if (access == null) return lib
        var libPrefix = ""
        var physPrefix = ""
        for (part in lib.split('/')) {
            libPrefix = if (libPrefix.isEmpty()) part else "$libPrefix/$part"
            val known = forward[libPrefix]
            if (known != null) {
                physPrefix = known
                continue
            }
            val safe = safeComponent(part)
            val candidate = if (physPrefix.isEmpty()) safe else "$physPrefix/$safe"
            if (safe != part) add(libPrefix, candidate)
            physPrefix = candidate
        }
        return physPrefix
    }

    private fun add(lib: String, phys: String) {
        val a = access ?: return
        val old = try {
            a.read(FILE).toString(Charsets.UTF_8)
        } catch (e: FolderError.NotFound) {
            ""
        }
        val line = JSONObject().put("lib", lib).put("phys", phys).toString()
        a.mkdirs(".keepfin")
        a.replace(FILE, (old + (if (old.isNotEmpty() && !old.endsWith("\n")) "\n" else "") + line + "\n").toByteArray(Charsets.UTF_8))
        remember(lib, phys)
    }
}
