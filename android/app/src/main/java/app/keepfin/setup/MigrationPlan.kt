package app.keepfin.setup

import app.keepfin.SpaceLogic
import app.keepfin.SpaceType
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Per space: copy the files into the Keepfin folder, or leave them where they are as a linked space. */
internal enum class Choice(val wire: String) {
    MOVE("move"), LEAVE("leave");

    companion object {
        fun of(wire: String?): Choice = entries.firstOrNull { it.wire == wire } ?: LEAVE
    }
}

/** One space in the plan. [newFolder] is the folder in the Keepfin folder (Move) or the virtual name of the linked space (Leave). */
internal data class PlanSpace(val id: String, val name: String, val choice: Choice, val newFolder: String, val legacyFolder: String, val items: Int, val bytes: Long)

/**
 * One earlier item. [oldId] is the earlier library's id (the MediaStore content URI string); [newId] is the new item id.
 * [dest] is the placement: a path in the Keepfin folder (Move) or `linked:<space>/<path>` (Leave).
 */
internal data class PlanItem(
    val oldId: String,
    val newId: String,
    val space: String,
    val choice: Choice,
    val name: String,
    val dest: String,
    val size: Long,
    val owned: Boolean,
)

internal class MigrationPlan(
    val runId: String,
    val createdAt: Long,
    val spaces: List<PlanSpace>,
    val items: List<PlanItem>,
    /** Earlier collection id to the new id. */
    val collectionIds: Map<String, String>,
) {
    val bytesToCopy: Long get() = items.filter { it.choice == Choice.MOVE }.sumOf { it.size }
    val moveCount: Int get() = items.count { it.choice == Choice.MOVE }
    val leaveCount: Int get() = items.count { it.choice == Choice.LEAVE }
    val foreignMoves: Int get() = items.count { it.choice == Choice.MOVE && !it.owned }

    fun toJson(): String {
        val root = JSONObject().put("version", 1).put("runId", runId).put("createdAt", createdAt)
        root.put("spaces", JSONArray().also { a ->
            spaces.forEach { s -> a.put(JSONObject().put("id", s.id).put("name", s.name).put("choice", s.choice.wire).put("newFolder", s.newFolder).put("legacyFolder", s.legacyFolder).put("items", s.items).put("bytes", s.bytes)) }
        })
        root.put("items", JSONArray().also { a ->
            items.forEach { i -> a.put(JSONObject().put("o", i.oldId).put("n", i.newId).put("s", i.space).put("c", i.choice.wire).put("name", i.name).put("d", i.dest).put("z", i.size).put("w", i.owned)) }
        })
        root.put("collections", JSONObject().also { o -> collectionIds.forEach { (k, v) -> o.put(k, v) } })
        return root.toString()
    }

    companion object {
        fun fromJson(text: String): MigrationPlan {
            val root = JSONObject(text)
            val spaces = root.getJSONArray("spaces").let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                    PlanSpace(o.getString("id"), o.getString("name"), Choice.of(o.optString("choice")), o.getString("newFolder"), o.optString("legacyFolder"), o.optInt("items"), o.optLong("bytes"))
                }
            }
            val items = root.getJSONArray("items").let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                    PlanItem(o.getString("o"), o.getString("n"), o.getString("s"), Choice.of(o.optString("c")), o.getString("name"), o.getString("d"), o.optLong("z"), o.optBoolean("w", true))
                }
            }
            val cols = root.optJSONObject("collections")
            val map = LinkedHashMap<String, String>()
            cols?.keys()?.forEach { k -> map[k] = cols.getString(k) }
            return MigrationPlan(root.getString("runId"), root.optLong("createdAt"), spaces, items, map)
        }
    }
}

internal object Planner {
    /** Room kept free on the volume after a Move (spec section 5). */
    const val MARGIN_BYTES = 1L shl 30

    private val DEFAULT_FOLDERS = mapOf("downloads" to "Downloads", "music" to "Music", "videos" to "Videos")

    /**
     * The default per space: Move while everything moved so far still fits in [freeBytes] with the margin kept, else Leave.
     * An unknown amount of free space (a volume that cannot be measured) defaults to Move.
     */
    fun defaultChoices(inventory: LegacyInventory, freeBytes: Long?): Map<String, Choice> {
        var room = if (freeBytes == null) Long.MAX_VALUE else freeBytes - MARGIN_BYTES
        val out = LinkedHashMap<String, Choice>()
        for (s in inventory.spaces) {
            if (s.count == 0) { out[s.space.id] = Choice.MOVE; continue }
            if (s.bytes <= room) { out[s.space.id] = Choice.MOVE; room -= s.bytes } else out[s.space.id] = Choice.LEAVE
        }
        return out
    }

    /** True when the chosen Moves fit in [freeBytes] with the margin kept. */
    fun fits(plan: MigrationPlan, freeBytes: Long?): Boolean = freeBytes == null || plan.bytesToCopy + MARGIN_BYTES <= freeBytes

    private fun cleanName(name: String): String {
        val cleaned = name.map { if (it in "\\/:*?\"<>|" || it.code < 32) '_' else it }.joinToString("").trim().trim('.').take(180)
        return cleaned.ifEmpty { "file" }
    }

    private fun nameOf(rec: app.keepfin.LibraryCodec.Rec): String {
        val fromRecord = rec.name.takeIf { it.isNotBlank() }
        val fromUri = rec.item.uri.substringAfterLast('/').takeIf { it.isNotBlank() && '.' in it }
        return cleanName(fromRecord ?: fromUri ?: rec.item.title)
    }

    /** The folder of each space in the new library, unique and not colliding with the default folders. */
    fun spaceFolders(inventory: LegacyInventory, choices: Map<String, Choice>): Map<String, String> {
        val taken = HashSet<String>()
        val out = LinkedHashMap<String, String>()
        // built-ins first so a custom folder never takes their default name
        val ordered = inventory.spaces.sortedBy { if (it.space.builtin) 0 else 1 }
        for (s in ordered) {
            val choice = choices[s.space.id] ?: Choice.LEAVE
            val wanted = if (choice == Choice.LEAVE) s.space.folder else DEFAULT_FOLDERS[s.space.id] ?: s.space.folder
            val base = wanted.replace('\\', '/').split('/').filter { it.isNotBlank() }.joinToString("/") { SpaceLogic.segment(it, 60) }.ifEmpty { s.space.id }
            var name = base
            var n = 2
            while (!taken.add(name.lowercase(Locale.ROOT))) { name = "$base $n"; n++ }
            out[s.space.id] = name
        }
        return out
    }

    /**
     * The plan: new ids for every item and collection, a placement for every item. Pure; ids come from [newId].
     * Smart spaces carry over untouched (they hold no files).
     */
    fun plan(inventory: LegacyInventory, choices: Map<String, Choice>, runId: String, now: Long, newId: () -> String): MigrationPlan {
        val folders = spaceFolders(inventory, choices)
        val planSpaces = inventory.spaces.map { s ->
            PlanSpace(s.space.id, s.space.name, choices[s.space.id] ?: Choice.LEAVE, folders.getValue(s.space.id), s.space.folder, s.count, s.bytes)
        }
        val items = ArrayList<PlanItem>(inventory.itemCount)
        for (s in inventory.spaces) {
            val choice = choices[s.space.id] ?: Choice.LEAVE
            val root = folders.getValue(s.space.id)
            val used = HashSet<String>()
            for (rec in s.items) {
                val file = nameOf(rec)
                val sub = rec.item.folder.replace('\\', '/').split('/').filter { it.isNotBlank() }.joinToString("/") { SpaceLogic.segment(it, 80) }
                val dir = listOf(root, sub).filter { it.isNotEmpty() }.joinToString("/")
                var name = file
                var n = 2
                while (!used.add("$dir/${name.lowercase(Locale.ROOT)}")) {
                    val stem = file.substringBeforeLast('.', file)
                    val ext = file.substringAfterLast('.', "")
                    name = if (ext.isEmpty()) "$stem ($n)" else "$stem ($n).$ext"
                    n++
                }
                val dest = if (choice == Choice.MOVE) "$dir/$name" else "linked:${s.space.id}/$sub/$name".replace("//", "/")
                items += PlanItem(rec.item.id, newId(), s.space.id, choice, name, dest, rec.item.size, rec.owned)
            }
        }
        val collections = LinkedHashMap<String, String>()
        for (c in inventory.collections) collections[c.id] = if (app.keepfin.core.sync.isUuid(c.id)) c.id else newId()
        return MigrationPlan(runId, now, planSpaces, items, collections)
    }
}
