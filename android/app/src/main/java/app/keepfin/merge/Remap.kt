package app.keepfin.merge

import app.keepfin.folder.writeLocalAtomic
import java.io.File
import org.json.JSONObject

// Places outside the library that hold library ids (contract sections 5 and 6): after the switch, every one of them is asked for the new id.
//
// Known consumers, with where each is handled:
//  - the download queue `queue.json`: `existing.itemId` (JobStore.kt), `space`, `collectionId`, `existing.space`   -> MergeEnv.remapOutside (AndroidMergeEnv)
//  - the saved playback queue of the player (`PlaybackService.persist`, key `state`)                                -> MergeEnv.remapOutside (AndroidMergeEnv)
//  - `linked.json`, `follow-state.json`, `job-items.json` of the profile's device directory                         -> [DeviceFiles.copyRemapped]
//  - plays buffered by the fence while combining                                                                    -> MergeEnv.replayPlays
// The Activity list, thumbnails and the update state hold no library ids. Copies and their places belong to the library they were made under
// and are listed and set up again under T (dev-docs/plans/LIBRARY-MERGE.md, "Scope").

/** Lookups of a finished map (both sides) over a scratch table built from `map.jsonl`; closed after use. */
class MapLookup(host: app.keepfin.core.sqlstate.SqlHost, private val file: File, scratchPath: String) : AutoCloseable {
    private val scratch = Scratch(host, scratchPath).also { it.loadMap(PlanFiles.mapLines(file)) }

    fun tOf(s: String): String? = scratch.tOf(s)
    fun sOf(t: String): String? = scratch.sOf(t)
    override fun close() = scratch.close()
}

/** The device-only files of a profile, brought to the profile that takes over with their ids replaced. */
object DeviceFiles {
    /** `linked.json`: item id to where its file is. Ids the map does not know are dropped. */
    fun copyRemapped(from: File, to: File, maps: IdMaps, merge: Boolean = false) {
        to.mkdirs()
        remapLinked(File(from, "linked.json"), File(to, "linked.json"), maps, merge)
        remapFollows(File(from, "follow-state.json"), File(to, "follow-state.json"), maps, merge)
        remapJobItems(File(from, "job-items.json"), File(to, "job-items.json"), maps, merge)
    }

    private fun read(file: File): JSONObject? = try { if (file.isFile) JSONObject(file.readText(Charsets.UTF_8)) else null } catch (e: Exception) { null }

    private fun remapLinked(from: File, to: File, maps: IdMaps, merge: Boolean) {
        val src = read(from) ?: return
        val items = src.optJSONObject("items") ?: return
        val out = if (merge) (read(to)?.optJSONObject("items") ?: JSONObject()) else JSONObject()
        for (id in items.keys()) maps.item(id)?.let { out.put(it, items.getJSONObject(id)) }
        writeLocalAtomic(to, JSONObject().put("version", src.optInt("version", 1)).put("items", out).toString().toByteArray(Charsets.UTF_8))
    }

    private fun remapFollows(from: File, to: File, maps: IdMaps, merge: Boolean) {
        val src = read(from) ?: return
        val out = if (merge) (read(to) ?: JSONObject()) else JSONObject()
        for (key in src.keys()) maps.collection(key)?.let { out.put(it, src.getJSONObject(key)) }
        writeLocalAtomic(to, out.toString().toByteArray(Charsets.UTF_8))
    }

    private fun remapJobItems(from: File, to: File, maps: IdMaps, merge: Boolean) {
        val src = read(from) ?: return
        val out = if (merge) (read(to) ?: JSONObject()) else JSONObject()
        for (job in src.keys()) maps.item(src.getString(job))?.let { out.put(job, it) }
        writeLocalAtomic(to, out.toString().toByteArray(Charsets.UTF_8))
    }
}
