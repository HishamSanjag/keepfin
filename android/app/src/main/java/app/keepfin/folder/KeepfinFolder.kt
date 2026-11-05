package app.keepfin.folder

import org.json.JSONException
import org.json.JSONObject

/** The content of `.keepfin/folder.json` (the same format as the desktop `gui/desktop/store/keepfinFolder.ts`). */
data class FolderJson(
    val format: Int,
    val libraryId: String,
    val replicaId: String,
    val createdAt: String,
    /** Format 2: the records root of the active library below `.keepfin/` (`""` is `.keepfin/` itself). */
    val records: String = "",
    /** Format 2: every library this folder holds or held, with its role (dev-docs/plans/LIBRARY-MERGE-CONTRACT.md section 1). */
    val libraries: List<FolderLibrary> = emptyList(),
) {
    /** Where the active library keeps its records. */
    val layout: FolderLayout get() = FolderLayout(records)
}

/** One entry of format 2's `libraries`: [role] is `active`, `previous` (frozen, kept until [keepUntil]) or `left`. */
data class FolderLibrary(
    val libraryId: String,
    val replicaId: String,
    val records: String,
    val role: String,
    val keepUntil: String? = null,
    val since: String? = null,
    val mergeId: String? = null,
) {
    val layout: FolderLayout get() = FolderLayout(records)
}

sealed class OpenFolderResult {
    /** [readOnly] is true when a newer Keepfin made the folder: this version reads it but never writes. */
    class Ok(val folder: FolderJson, val readOnly: Boolean) : OpenFolderResult()
    object Missing : OpenFolderResult()
    object NotAKeepfinFolder : OpenFolderResult()
    object Corrupt : OpenFolderResult()
}

enum class ReplicaCheck { Ok, Cloned }

/**
 * Creating, opening and validating a Keepfin folder through [FolderAccess]. Layout: `Music/ Videos/ Downloads/` plus
 * `.keepfin/{folder.json, README.txt, .nomedia, actors/, ops/, checkpoints/, art/, trash/}`.
 */
object KeepfinFolders {
    /** The format a folder that was never combined is written in (an older Keepfin reads it). */
    const val FORMAT = 1

    /** The newest format this version reads and writes; a combined folder is format 2. */
    const val MAX_FORMAT = 2
    const val DATA_DIR = ".keepfin"
    const val FOLDER_JSON = "$DATA_DIR/folder.json"
    const val README_TEXT = "Keepfin's library data. Do not edit; deleting it loses playlists and history."
    val DEFAULT_SPACE_FOLDERS = listOf("Music", "Videos", "Downloads")
    private val DATA_SUBDIRS = listOf("actors", "ops", "checkpoints", "art", "trash")

    /** The file text: `JSON.stringify(value, null, 2)` of the desktop, so both apps write byte-identical files. Format 1 is written as it always was. */
    fun folderJsonText(json: FolderJson): String {
        val q = JSONObject::quote
        val head = "{\n  \"format\": ${json.format},\n  \"libraryId\": ${q(json.libraryId)},\n" +
            "  \"replicaId\": ${q(json.replicaId)},\n  \"createdAt\": ${q(json.createdAt)}"
        if (json.format < 2) return "$head\n}"
        val libs = json.libraries.joinToString(",\n") { l ->
            val fields = ArrayList<String>()
            fields += "      \"libraryId\": ${q(l.libraryId)}"
            fields += "      \"replicaId\": ${q(l.replicaId)}"
            fields += "      \"records\": ${q(l.records)}"
            fields += "      \"role\": ${q(l.role)}"
            l.keepUntil?.let { fields += "      \"keepUntil\": ${q(it)}" }
            l.since?.let { fields += "      \"since\": ${q(it)}" }
            l.mergeId?.let { fields += "      \"mergeId\": ${q(it)}" }
            "    {\n" + fields.joinToString(",\n") + "\n    }"
        }
        val list = if (json.libraries.isEmpty()) "[]" else "[\n$libs\n  ]"
        return "$head,\n  \"records\": ${q(json.records)},\n  \"libraries\": $list\n}"
    }

    /** The layout for [mergeId]-independent paths of the library [libraryId] this folder holds (null when it is not listed). */
    fun entryOf(json: FolderJson, libraryId: String): FolderLibrary? = json.libraries.firstOrNull { it.libraryId == libraryId }

    /** Makes the directories of a records root (the staging of a combined-in library, or a new folder). */
    fun createRecords(access: FolderAccess, layout: FolderLayout) {
        for (dir in layout.subdirs) access.mkdirs(dir)
    }
    /** Creates the folder tree. Throws [IllegalStateException] when it already is a Keepfin folder. */
    fun create(access: FolderAccess, libraryId: String, replicaId: String, nowIso: String): FolderJson {
        if (access.stat(FOLDER_JSON) != null) throw IllegalStateException("This folder is already a Keepfin folder.")
        access.mkdirs(DATA_DIR)
        for (sub in DATA_SUBDIRS) access.mkdirs("$DATA_DIR/$sub")
        val json = FolderJson(FORMAT, libraryId, replicaId, nowIso)
        access.publish(FOLDER_JSON, folderJsonText(json).toByteArray(Charsets.UTF_8))
        access.publish("$DATA_DIR/README.txt", README_TEXT.toByteArray(Charsets.UTF_8))
        // other apps (gallery, music players) must not index the library data
        access.publish("$DATA_DIR/.nomedia", ByteArray(0))
        for (space in DEFAULT_SPACE_FOLDERS) access.mkdirs(space)
        return json
    }

    /** Reads and validates `folder.json`. Provider failures ([FolderError]) propagate: they never mean "not a Keepfin folder". */
    fun open(access: FolderAccess): OpenFolderResult {
        if (access.stat("") == null) return OpenFolderResult.Missing
        val bytes = try {
            access.read(FOLDER_JSON)
        } catch (e: FolderError.NotFound) {
            val aside = try {
                access.list(DATA_DIR).entries.firstOrNull { it.name.startsWith("folder.json.") && it.name.endsWith(".old") }
            } catch (e2: FolderError.NotFound) {
                null
            }
            // a switch that was cut short leaves the previous file under an .old name
            if (aside != null) access.read("$DATA_DIR/${aside.name}") else return OpenFolderResult.NotAKeepfinFolder
        }
        return parse(bytes)
    }

    fun parse(bytes: ByteArray): OpenFolderResult {
        var text = bytes.toString(Charsets.UTF_8)
        if (text.isNotEmpty() && text[0] == '﻿') text = text.substring(1)
        val obj = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return OpenFolderResult.Corrupt
        }
        val formatValue = obj.opt("format")
        val format = (formatValue as? Number)?.let { if (it.toDouble() == it.toLong().toDouble()) it.toInt() else null }
        if (format == null || format < 1) return OpenFolderResult.Corrupt
        fun text(key: String): String? = (obj.opt(key) as? String)?.takeIf { it.isNotEmpty() }
        val libraryId = text("libraryId")
        val replicaId = text("replicaId")
        val createdAt = text("createdAt")
        if (libraryId == null || replicaId == null || createdAt == null) return OpenFolderResult.Corrupt
        var records = ""
        var libraries = emptyList<FolderLibrary>()
        if (format >= 2) {
            records = (obj.opt("records") as? String).orEmpty()
            if (records.startsWith("/") || records.endsWith("/") || records.contains("..") || records.contains('\\')) return OpenFolderResult.Corrupt
            val array = obj.optJSONArray("libraries")
            if (array != null) {
                val out = ArrayList<FolderLibrary>()
                for (i in 0 until array.length()) {
                    val l = array.optJSONObject(i) ?: return OpenFolderResult.Corrupt
                    fun s(key: String): String? = (l.opt(key) as? String)?.takeIf { it.isNotEmpty() }
                    val lib = s("libraryId")
                    val rep = s("replicaId")
                    val role = s("role")
                    val rec = (l.opt("records") as? String).orEmpty()
                    if (lib == null || rep == null || role == null || rec.startsWith("/") || rec.endsWith("/") || rec.contains("..") || rec.contains('\\')) return OpenFolderResult.Corrupt
                    out += FolderLibrary(lib, rep, rec, role, s("keepUntil"), s("since"), s("mergeId"))
                }
                libraries = out
            }
        }
        return OpenFolderResult.Ok(FolderJson(format, libraryId, replicaId, createdAt, records, libraries), readOnly = format > MAX_FORMAT)
    }

    /**
     * A folder whose replica id this device already knows under another place was copied: [knownReplicas] maps a replica id
     * to the tree URI it was first seen at. A copy must get its own replica id ([reassignReplica]).
     */
    fun checkReplica(folder: FolderJson, treeUri: String, knownReplicas: Map<String, String>): ReplicaCheck {
        val known = knownReplicas[folder.replicaId] ?: return ReplicaCheck.Ok
        return if (known == treeUri) ReplicaCheck.Ok else ReplicaCheck.Cloned
    }

    fun reassignReplica(access: FolderAccess, folder: FolderJson, newReplicaId: String, nowIso: String): FolderJson {
        check(folder.format <= MAX_FORMAT) { "A newer Keepfin folder is read-only." }
        val updated = folder.copy(replicaId = newReplicaId, libraries = folder.libraries.map { if (it.libraryId == folder.libraryId && it.role == "active") it.copy(replicaId = newReplicaId) else it })
        access.replace(FOLDER_JSON, folderJsonText(updated).toByteArray(Charsets.UTF_8))
        val note = "{\n  \"previousReplicaId\": ${JSONObject.quote(folder.replicaId)},\n  \"at\": ${JSONObject.quote(nowIso)}\n}"
        access.mkdirs(updated.layout.actors)
        access.replace("${updated.layout.actors}/clone-$newReplicaId.json", note.toByteArray(Charsets.UTF_8))
        return updated
    }
}
