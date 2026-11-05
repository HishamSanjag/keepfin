package app.keepfin.library.repo

import app.keepfin.CollectionKind
import app.keepfin.CollectionLogic
import app.keepfin.Follow
import app.keepfin.FollowMode
import app.keepfin.FollowReport
import app.keepfin.FollowUpdate
import app.keepfin.LibraryCollection
import app.keepfin.SourceIds
import app.keepfin.core.library.LibCollection
import app.keepfin.folder.writeLocalAtomic
import java.io.File
import org.json.JSONObject

/** Collections, follow checks and the follow state kept on this device only. Inner state is reached through [RepoCore]. */
internal class RepoCollections(private val core: RepoCore) {
    // ------------------------------------------------------------------------------------------------ collections

    fun collection(id: String): LibraryCollection? = core.library.value.collections.firstOrNull { it.id == id }

    fun collectionSave(id: String?, name: String, itemIds: List<String>, space: String?): String {
        val clean = name.trim().take(60)
        require(clean.isNotEmpty()) { "Give the list a name." }
        val time = core.now().toDouble()
        var result = id ?: core.newId()
        val known = core.presentIds(itemIds)
        val ids = itemIds.filter { it in known }.distinct()
        val spaceOf = HashMap<String, String>()
        for (chunk in ids.chunked(500)) for (r in core.rd().rows("SELECT id, space FROM lib_index WHERE id IN (SELECT value FROM json_each(?))", listOf(jsonList(chunk)))) spaceOf[r[0] as String] = core.small.modelIds[r[1] as String] ?: (r[1] as String)
        core.commitCollections { list ->
            val existing = id?.let { key -> list.firstOrNull { it.id == key } }
            if (existing == null) {
                val own = space ?: CollectionLogic.ownSpace(ids) { item -> spaceOf[item] }
                list + LibCollection(result, clean, "own", own, ids, createdAt = time, updatedAt = time)
            } else {
                result = existing.id
                list.map { if (it.id == existing.id) it.copy(name = clean, itemIds = ids, updatedAt = time) else it }
            }
        }
        return result
    }

    fun collectionDelete(id: String) {
        core.commitCollections { list -> if (list.any { it.id == id }) list.filter { it.id != id } else list }
    }

    fun collectionAdd(id: String, itemIds: List<String>) {
        val known = core.presentIds(itemIds)
        core.updateCollection(id) { c ->
            val added = itemIds.filter { it in known && it !in c.itemIds }.distinct()
            if (added.isEmpty()) c else c.copy(itemIds = c.itemIds + added, removedItemIds = c.removedItemIds - added.toSet())
        }
    }

    fun collectionRemove(id: String, itemIds: List<String>) {
        val gone = itemIds.toSet()
        core.updateCollection(id) { c ->
            c.copy(itemIds = c.itemIds.filter { it !in gone }, newItemIds = c.newItemIds.filter { it !in gone }, removedItemIds = c.removedItemIds.filter { it !in gone }, coverItemId = c.coverItemId?.takeIf { it !in gone })
        }
    }

    fun collectionReorder(id: String, itemIds: List<String>) {
        core.updateCollection(id) { c ->
            val current = c.itemIds.toSet()
            val ordered = itemIds.distinct().filter { it in current }
            c.copy(itemIds = ordered + c.itemIds.filter { it !in ordered.toSet() })
        }
    }

    fun collectionOpened(id: String) {
        val c = core.small.collections.firstOrNull { it.id == id } ?: return
        if (c.newItemIds.isNotEmpty()) core.updateCollection(id) { it.copy(newItemIds = emptyList()) }
    }

    fun collectionFollow(id: String, update: FollowUpdate) {
        val current = core.small.collections.firstOrNull { it.id == id }?.toCollection(core.follows) ?: throw IllegalArgumentException("This collection no longer exists.")
        require(current.kind != CollectionKind.OWN && current.source != null) { "Only downloaded playlists, albums and channels can be followed." }
        require(!CollectionLogic.infinite(current.source.url)) { "Radio mixes never end, so they can't be followed." }
        require(update.intervalHours == null || update.intervalHours in setOf(1, 6, 12, 24, 168)) { "Choose how often to check." }
        require(update.confirmAbove == null || update.confirmAbove in 0..1000) { "Choose how many new items download without asking." }
        val base = current.follow ?: CollectionLogic.defaultFollow(current.kind, false) ?: Follow()
        val next = base.copy(
            mode = update.mode ?: base.mode, intervalHours = update.intervalHours ?: base.intervalHours, wifiOnly = update.wifiOnly ?: base.wifiOnly,
            notify = update.notify ?: base.notify, confirmAbove = update.confirmAbove ?: base.confirmAbove,
            lastError = if (update.mode != null && update.mode != FollowMode.OFF) null else base.lastError,
            pending = if (update.mode == FollowMode.OFF) null else base.pending,
        )
        core.updateCollection(id) { it.copy(follow = next.toLib()) }
        if (next.lastError != base.lastError) recordFollowResult(id, next.lastCheckedAt, next.lastError)
        core.host.followSettingsChanged()
    }

    fun collectionCheck(id: String?) = core.host.requestCheck(id, review = false)

    fun collectionReview(id: String) = core.host.requestCheck(id, review = true)

    // ------------------------------------------------------------------------------------------------ follow checks (FollowEngine)

    fun markFollowJobs(jobIds: Collection<String>) { core.followJobs.addAll(jobIds) }

    /** Source keys an item answers to ("youtube:<id>", "spotify:<id>"...), for matching it against a source list. */
    fun sourceKeysOf(itemId: String): Set<String> {
        val item = core.libItem(itemId) ?: return emptySet()
        return SourceIds.of(item.sourceUrl) + listOfNotNull(item.sourceId)
    }

    fun report(report: FollowReport) { core.reports.tryEmit(report) }

    fun collectionsNow(): List<LibraryCollection> = core.library.value.collections

    /**
     * Changes one collection the way a follow check does (follow settings, seen keys, removed-upstream ids, new items). The check results
     * (last checked, last error) stay on this device; the rest is written as operations. Null when the collection is gone.
     */
    fun mutateCollection(id: String, transform: (LibraryCollection) -> LibraryCollection): LibraryCollection? {
        val current = core.small.collections.firstOrNull { it.id == id } ?: return null
        val before = current.toCollection(core.follows)
        val after = transform(before)
        val device = after.follow
        if (device != null && (device.lastCheckedAt != before.follow?.lastCheckedAt || device.lastError != before.follow?.lastError)) recordFollowResult(id, device.lastCheckedAt, device.lastError)
        core.updateCollection(id) { c ->
            c.copy(follow = after.follow?.toLib(), seen = after.seen, newItemIds = after.newItemIds, removedItemIds = after.removedItemIds)
        }
        return core.small.collections.firstOrNull { it.id == id }?.toCollection(core.follows)
    }

    // ------------------------------------------------------------------------------------------------ device-only follow state

    private fun followFile() = File(core.deviceDir, "follow-state.json")

    fun readFollows(): Map<String, FollowDevice> {
        val f = followFile()
        if (!f.isFile) return emptyMap()
        return try {
            val json = JSONObject(f.readText(Charsets.UTF_8))
            json.keys().asSequence().associateWith { key ->
                val o = json.getJSONObject(key)
                FollowDevice(if (o.has("lastCheckedAt")) o.optLong("lastCheckedAt") else null, if (o.has("lastError")) o.optString("lastError") else null)
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** The follow check results of a collection stay on this device (never synced). */
    fun recordFollowResult(id: String, checkedAt: Long?, error: String?) {
        val next = HashMap(core.follows)
        if (checkedAt == null && error == null) next.remove(id) else next[id] = FollowDevice(checkedAt, error)
        core.follows = next
        val json = JSONObject()
        for ((key, v) in next) json.put(key, JSONObject().also { o -> v.lastCheckedAt?.let { o.put("lastCheckedAt", it) }; v.lastError?.let { o.put("lastError", it) } })
        writeLocalAtomic(followFile(), json.toString().toByteArray(Charsets.UTF_8))
        runCatching { core.publish() }
    }
}
