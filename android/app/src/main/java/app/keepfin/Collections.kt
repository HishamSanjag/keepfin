package app.keepfin

import app.keepfin.core.spaces.Collections as CoreCollections
import app.keepfin.core.spaces.Follow as CoreFollow
import app.keepfin.core.spaces.LibraryCollection as CoreCollection
import app.keepfin.core.spaces.LibraryItem as CoreItem
import app.keepfin.core.spaces.RuleSet
import app.keepfin.core.spaces.SmartRule as CoreRule
import app.keepfin.core.spaces.SmartRules as CoreSmartRules
import app.keepfin.core.spaces.TaggedItem
import app.keepfin.core.spaces.Tags
import org.json.JSONArray
import org.json.JSONObject

// Spaces v2 logic (dev-docs/architecture/SPACES-V2.md): tags, smart rules, collections and the follow check. The decisions (tag rules, smart rules,
// source identity and kind, new-item detection, the confirm gate, follow defaults and the due check) are made by the shared core
// (app.keepfin.core.spaces); this file maps the app's types onto it and keeps the Android-only parts (JSON, notices, job batches).
// No Android types; everything here is unit-tested on the JVM.

/** A tag with how many items in scope carry it. [user] tags are always listed. */
data class ScopedTag(val tag: String, val count: Int, val user: Boolean)

object TagLogic {
    const val MAX_SOURCE_TAGS = Tags.MAX_SOURCE_TAGS
    const val MAX_LENGTH = Tags.MAX_TAG_LENGTH

    /** Lowercase, trimmed, whitespace-collapsed. Null for empty values, links and tags longer than [MAX_LENGTH]. */
    fun clean(raw: String?): String? = raw?.trim('#', ' ', ',', ';')?.let { Tags.clean(it) }

    /** Source tags from genre/tags/categories lists: cleaned, deduped in order, at most [MAX_SOURCE_TAGS]. */
    fun normalize(raw: Iterable<String?>): List<String> = Tags.normalize(raw.toList(), MAX_SOURCE_TAGS)

    /** User tags: the same rules, without the source cap. */
    fun normalizeUser(raw: Iterable<String?>): List<String> = raw.mapNotNull(::clean).distinct()

    /**
     * Tags from yt-dlp's `%(.{tags,genres,categories})j` print line (or a whole info JSON): genres first, then tags, then categories.
     * Anything unreadable (including the literal `null`) is no tags.
     */
    fun fromJson(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            val json = JSONObject(text.trim())
            fun list(key: String): List<String> = json.optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } } }
                ?: json.optString(key).takeIf { json.has(key) && json.opt(key) is String && it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
            normalize(list("genres") + list("genre") + list("tags") + list("categories"))
        } catch (_: Exception) { emptyList() }
    }

    fun all(item: LibraryItem): List<String> = (item.tags + item.userTags).distinct()

    /** Tags in scope used by at least [minItems] items, plus every user tag; most used first, then A to Z. */
    fun inScope(items: List<LibraryItem>, minItems: Int = 2): List<ScopedTag> =
        Tags.inScope(items.map { TaggedItem(it.tags, it.userTags) }, minItems).map { ScopedTag(it.tag, it.count, it.user) }

    /** Items carrying any (or, with [all], every) one of [tags]. */
    fun filter(items: List<LibraryItem>, tags: Set<String>, all: Boolean): List<LibraryItem> =
        if (tags.isEmpty()) items else items.filter { item -> val have = all(item).toSet(); if (all) tags.all { it in have } else tags.any { it in have } }

    /** Adds then removes user tags (all cleaned); keeps first-seen order. */
    fun editUserTags(current: List<String>, add: List<String>, remove: List<String>): List<String> {
        val removed = normalizeUser(remove).toSet()
        return (current + normalizeUser(add)).distinct().filter { it !in removed }
    }
}

/** What a smart rule can see besides the item: when "now" is and which collections hold which items. */
data class SmartContext(val now: Long = System.currentTimeMillis(), val collectionsOfItem: Map<String, Set<String>> = emptyMap()) {
    companion object {
        fun of(collections: List<LibraryCollection>, now: Long = System.currentTimeMillis()): SmartContext {
            val map = HashMap<String, MutableSet<String>>()
            for (c in collections) for (id in c.itemIds) map.getOrPut(id) { HashSet() }.add(c.id)
            return SmartContext(now, map)
        }
    }
}

internal fun SmartRule.toCore(): CoreRule = when (this) {
    is SmartRule.Values -> CoreRule(field.wire, values)
    is SmartRule.AddedWithin -> CoreRule("addedWithin", days = days.toDouble())
    SmartRule.Favorite -> CoreRule("favorite")
    SmartRule.Unplayed -> CoreRule("unplayed")
}

internal fun SmartRules.toCore() = RuleSet(match == SmartMatch.ALL, rules.map { it.toCore() }, onlyKind?.wire, excludeSpaces)

private fun CoreRule.toApp(): SmartRule = when (field) {
    "addedWithin" -> SmartRule.AddedWithin(days.toInt())
    "favorite" -> SmartRule.Favorite
    "unplayed" -> SmartRule.Unplayed
    else -> SmartRule.Values(SmartField.entries.first { it.wire == field }, values)
}

internal fun RuleSet.toApp() = SmartRules(
    if (matchAll) SmartMatch.ALL else SmartMatch.ANY, rules.map { it.toApp() }, onlyKind?.let { wire -> LibraryItemKind.entries.firstOrNull { it.wire == wire } }, excludeSpaces,
)

object SmartLogic {
    private fun LibraryItem.toCore() = CoreItem(id, space, kind.wire, tags, userTags, artist, albumArtist, album, favorite, plays, addedAt, sourceUrl, sourceId)

    fun matches(item: LibraryItem, rule: SmartRule, ctx: SmartContext): Boolean =
        CoreSmartRules.matches(RuleSet(false, listOf(rule.toCore())), item.toCore(), ctx.now) { ctx.collectionsOfItem[it].orEmpty() }

    /** A smart space with no rules gathers nothing (an empty "all" must not mean "everything"). */
    fun matches(item: LibraryItem, rules: SmartRules, ctx: SmartContext): Boolean =
        CoreSmartRules.matches(rules.toCore(), item.toCore(), ctx.now) { ctx.collectionsOfItem[it].orEmpty() }

    fun evaluate(items: List<LibraryItem>, rules: SmartRules, ctx: SmartContext): List<LibraryItem> {
        val core = rules.toCore()
        return items.filter { CoreSmartRules.matches(core, it.toCore(), ctx.now) { id -> ctx.collectionsOfItem[id].orEmpty() } }
    }

    /** Live item count of a smart space. */
    fun count(items: List<LibraryItem>, space: Space, ctx: SmartContext): Int = space.rules?.let { r -> evaluate(items, r, ctx).size } ?: 0
}

/** Outcome of comparing a discovered list with the collection's `seen` list. */
sealed interface FollowDecision {
    data object NothingNew : FollowDecision
    /** [keys] are the new source item keys, in source order. */
    data class Download(val keys: List<String>) : FollowDecision
    /** More than `confirmAbove` new items: wait for the user's Review. */
    data class NeedsReview(val count: Int) : FollowDecision
}

object CollectionLogic {
    const val DISCOVERY_CAP = 1000
    const val PAGE_SIZE = 200
    private val intervals = setOf(1, 6, 12, 24, 168)

    /**
     * The order after an Undo of a removal: [now] (what the collection holds now, with any change made since) and each of [removed] back
     * in front of the first song that followed it in [before] and is still there (or at the end). The same as the Windows app.
     */
    fun restoreOrder(before: List<String>, now: List<String>, removed: Set<String>): List<String> {
        val kept = now.filter { it !in removed }
        val present = kept.toHashSet()
        // each removed song is anchored to the next song after it that is still in the collection (null: none, so at the end)
        val anchored = HashMap<String?, MutableList<String>>()
        var anchor: String? = null
        for (id in before.asReversed()) {
            if (id in present) anchor = id
            else if (id in removed) anchored.getOrPut(anchor) { ArrayList() }.add(id)
        }
        // the groups were filled walking backwards
        val out = ArrayList<String>(kept.size + removed.size)
        for (id in kept) { anchored[id]?.let { out.addAll(it.asReversed()) }; out += id }
        anchored[null]?.let { out.addAll(it.asReversed()) }
        return out
    }

    // ---- identity ------------------------------------------------------------------------------------------------

    /** Stable key for a source link: its canonical link. Used to reuse a collection; one collection per source. */
    fun sourceKey(url: String): String = CoreCollections.canonicalUrl(url)

    /**
     * One link per source: playlist ids on YouTube (any host, any watch/music form), channel handles, music-service links; tracking removed.
     * The stored link is `https://www.youtube.com/playlist?list=<id>`, `https://www.youtube.com/@handle`, `https://open.spotify.com/playlist/<id>`...
     */
    fun canonicalSource(url: String): CollectionSource = CoreCollections.canonicalSource(url).let { CollectionSource(it.url, Providers.name(url), it.id) }

    /** Source key of one item: "youtube:<id>", "spotify:<id>"... when the link names a recording, else the link without fragment and trailing slash. */
    fun itemKey(url: String): String = CoreCollections.itemKey(url)

    fun kindOf(url: String, groupKind: String? = null): CollectionKind = when (groupKind ?: collectionKind(url)) {
        "album" -> CollectionKind.ALBUM
        "channel" -> CollectionKind.CHANNEL
        else -> CollectionKind.PLAYLIST
    }

    /** playlist | album | channel | mix from the link alone (mirrors the downloads UI label). */
    fun collectionKind(url: String): String = if (CoreCollections.isInfinite(url)) "mix" else CoreCollections.kindOf(url)

    /** Radio mixes (and a group the UI labelled "mix") are infinite: never followed. */
    fun infinite(url: String, groupKind: String? = null): Boolean = groupKind == "mix" || CoreCollections.isInfinite(url)

    // ---- follow defaults and scheduling ---------------------------------------------------------

    /**
     * Following is opt-in: playlists, channels and albums start with follow off; own lists and infinite mixes have none.
     * The user turns it on with "Keep in sync" when downloading ([followFor]) or later in Follow settings.
     */
    fun defaultFollow(kind: CollectionKind, infinite: Boolean): Follow? = when {
        kind == CollectionKind.OWN || infinite -> null
        else -> Follow(mode = FollowMode.OFF)
    }

    /**
     * The follow settings a collection gets when it is created or downloaded into again: [current] (or the opt-in default), switched to automatic
     * with [sync]'s options when "Keep in sync" was on. Albums, own lists and infinite mixes never follow, so [sync] is ignored for them.
     */
    fun followFor(kind: CollectionKind, infinite: Boolean, sync: SyncRequest?, current: Follow? = null): Follow? {
        val base = current ?: defaultFollow(kind, infinite)
        // An interval that is not offered is ignored (follow stays as it was), like the shared fixtures say.
        if (sync == null || !CoreCollections.isOfferedInterval(sync.intervalHours) || !CoreCollections.canKeepInSync(kind.wire, infinite)) return base
        return (base ?: Follow()).copy(mode = FollowMode.AUTO, intervalHours = sync.intervalHours, wifiOnly = sync.wifiOnly, lastError = null)
    }

    /** Whether the review screen offers "Keep in sync" for this link: playlists and channels only (no albums, mixes or single items). */
    fun syncOffered(url: String, groupKind: String? = null): Boolean =
        kindOf(url, groupKind).let { CoreCollections.canKeepInSync(it.wire, infinite(url, groupKind)) }

    fun followable(c: LibraryCollection): Boolean = CoreCollections.isFollowable(c.kind.wire, c.source?.url)

    fun interval(hours: Int): Int = CoreCollections.interval(hours)

    /**
     * Collections the periodic worker should check now: automatic, followable, not waiting for a review, at least their interval
     * since the last check (an error counts as a check, so it is not retried before the next interval); Wi-Fi-only ones are skipped on a metered network.
     */
    fun due(collections: List<LibraryCollection>, now: Long, metered: Boolean): List<LibraryCollection> = collections.filter { c ->
        val follow = c.follow ?: return@filter false
        val core = CoreCollection(
            c.id, c.name, c.kind.wire, c.space, emptyList(), emptyList(), c.source?.url,
            CoreFollow(follow.mode.wire, follow.intervalHours, follow.wifiOnly, follow.notify, follow.confirmAbove, follow.lastCheckedAt, follow.pending != null),
        )
        CoreCollections.isDue(core, now, metered)
    }

    // ---- new items ------------------------------------------------------------------------------

    /** Keys of [discovered] (in order, deduped) that the collection has never seen: deselected, deleted and downloaded items all count as seen. */
    fun newKeys(discovered: List<String>, seen: Collection<String>): List<String> {
        // Seen keys recorded before keys dropped fragments and trailing slashes still count: they are normalised the same way.
        val known = seen.mapTo(HashSet()) { itemKey(it) }
        val out = LinkedHashSet<String>()
        for (key in discovered) if (key !in known) out.add(key)
        return out.toList()
    }

    /** The confirmAbove gate: up to `confirmAbove` new items download; more wait for Review (unless already [confirmed]). */
    fun decide(newKeys: List<String>, confirmAbove: Int, confirmed: Boolean = false): FollowDecision = when (CoreCollections.gate(newKeys.size, confirmAbove, confirmed)) {
        app.keepfin.core.spaces.Gate.NONE -> FollowDecision.NothingNew
        app.keepfin.core.spaces.Gate.ENQUEUE -> FollowDecision.Download(newKeys)
        app.keepfin.core.spaces.Gate.CONFIRM -> FollowDecision.NeedsReview(newKeys.size)
    }

    /** Item ids whose source key is no longer in the source (only call with a complete discovery). Items with no known source key are never marked. */
    fun removedUpstream(itemIds: List<String>, keysOf: (String) -> Set<String>, present: Set<String>): List<String> =
        CoreCollections.removedFromSource(itemIds, keysOf, present)

    // ---- order ----------------------------------------------------------------------------------

    /**
     * Inserts [id] (source position [index]) into [itemIds] so the list stays in source order. Ids whose position is unknown keep their place
     * and an unknown [index] appends. Already present ids are left where they are.
     */
    fun insertInOrder(itemIds: List<String>, id: String, index: Int?, indexOf: (String) -> Int?): List<String> {
        if (id in itemIds) return itemIds
        if (index == null) return itemIds + id
        val at = itemIds.indexOfFirst { other -> indexOf(other)?.let { it > index } == true }
        return if (at < 0) itemIds + id else itemIds.take(at) + id + itemIds.drop(at)
    }

    /** Items of a downloaded collection sorted by `collectionIndex` (unknown last, stable). */
    fun ordered(itemIds: List<String>, indexOf: (String) -> Int?): List<String> = CoreCollections.sortBySourceOrder(itemIds, indexOf)

    // ---- migration ------------------------------------------------------------------------------

    /** A playlist from library.json (id, name, itemIds, createdAt, updatedAt) becomes a collection of kind own. */
    fun fromPlaylist(id: String, name: String, itemIds: List<String>, createdAt: Long, updatedAt: Long, space: String = "music"): LibraryCollection =
        CoreCollections.fromPlaylist(id, name, itemIds, createdAt, updatedAt, space).let {
            LibraryCollection(it.id, it.name, CollectionKind.OWN, it.space, it.itemIds, createdAt = it.createdAt, updatedAt = it.updatedAt)
        }

    /**
     * Downloaded playlists from the job history: one collection per group (merged when two groups share a source link), holding the saved files that
     * still exist in download order. Groups whose files are all gone are skipped. Follow starts off: the items the user deselected back then are unknown,
     * so a first check would download them. Existing collections win over groups with the same id or source.
     */
    fun fromJobGroups(jobs: List<Job>, itemByJob: Map<String, LibraryItem>, existing: List<LibraryCollection>, now: Long): List<LibraryCollection> {
        val out = ArrayList(existing)
        val byGroup = jobs.filter { it.group != null }.groupBy { it.group!!.id }
        for ((groupId, members) in byGroup) {
            val group = members.first().group!!
            val sorted = members.sortedBy { it.createdAt }
            val withItems = sorted.mapNotNull { job -> itemByJob[job.id]?.let { job to it } }
            if (withItems.isEmpty() || out.any { it.id == groupId }) continue
            val key = group.url.takeIf { it.isNotBlank() }?.let(::sourceKey)
            val keys = sorted.map { itemKey(it.url) }.distinct()
            val same = key?.let { k -> out.indexOfFirst { c -> c.kind != CollectionKind.OWN && c.source?.let { sourceKey(it.url) } == k } } ?: -1
            val ids = withItems.map { it.second.id }
            if (same >= 0) {
                val c = out[same]
                out[same] = c.copy(itemIds = (c.itemIds + ids).distinct(), seen = (c.seen + keys).distinct(), updatedAt = now)
                continue
            }
            val kind = kindOf(group.url, group.kind)
            val mix = infinite(group.url, group.kind)
            out.add(LibraryCollection(
                id = groupId, name = group.title.ifBlank { "Collection" }.take(80), kind = kind, space = withItems.first().second.space, itemIds = ids.distinct(),
                coverItemId = withItems.firstOrNull { it.second.art != null }?.second?.id ?: ids.first(),
                source = group.url.takeIf { it.isNotBlank() }?.let(::canonicalSource), follow = defaultFollow(kind, mix),
                seen = keys, createdAt = sorted.first().createdAt.takeIf { it > 0 } ?: now, updatedAt = now,
            ))
        }
        return out
    }

    /** Space shown for an own list: the first item's space, else "music". */
    fun ownSpace(itemIds: List<String>, spaceOf: (String) -> String?): String = itemIds.firstNotNullOfOrNull(spaceOf) ?: "music"

    // ---- JSON ----------------------------------------------------------------------------------

    private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null
    private fun JSONObject.long(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null
    private fun JSONArray?.strings(): List<String> = this?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotEmpty) } } ?: emptyList()

    fun encode(c: LibraryCollection): JSONObject = JSONObject().put("id", c.id).put("name", c.name).put("kind", c.kind.wire).put("space", c.space)
        .put("itemIds", JSONArray(c.itemIds)).put("coverItemId", c.coverItemId)
        .put("source", c.source?.let { JSONObject().put("url", it.url).put("provider", it.provider).put("id", it.id) })
        .put("follow", c.follow?.let(::encode)).put("seen", JSONArray(c.seen)).put("newItemIds", JSONArray(c.newItemIds))
        .put("removedItemIds", JSONArray(c.removedItemIds)).put("createdAt", c.createdAt).put("updatedAt", c.updatedAt)

    private fun encode(f: Follow): JSONObject = JSONObject().put("mode", f.mode.wire).put("intervalHours", f.intervalHours).put("wifiOnly", f.wifiOnly)
        .put("notify", f.notify).put("confirmAbove", f.confirmAbove).put("lastCheckedAt", f.lastCheckedAt).put("lastError", f.lastError)
        .put("pending", f.pending?.let { JSONObject().put("count", it.count).put("at", it.at) })

    fun decode(json: JSONObject): LibraryCollection? {
        val id = json.str("id") ?: return null
        val kind = CollectionKind.entries.firstOrNull { it.wire == json.optString("kind") } ?: CollectionKind.OWN
        val source = json.optJSONObject("source")?.let { s -> s.str("url")?.let { CollectionSource(it, s.str("provider") ?: Providers.name(it), s.str("id")) } }
        val follow = json.optJSONObject("follow")?.let { f ->
            Follow(
                mode = FollowMode.entries.firstOrNull { it.wire == f.optString("mode") } ?: FollowMode.OFF, intervalHours = interval(f.optInt("intervalHours", 6)),
                wifiOnly = f.optBoolean("wifiOnly", true), notify = f.optBoolean("notify", true), confirmAbove = f.optInt("confirmAbove", 20).coerceIn(0, 1000),
                lastCheckedAt = f.long("lastCheckedAt"), lastError = f.str("lastError"),
                pending = f.optJSONObject("pending")?.let { p -> PendingCheck(p.optInt("count"), p.optLong("at")) },
            )
        }
        return LibraryCollection(
            id = id, name = json.str("name") ?: "Collection", kind = kind, space = json.str("space") ?: "music",
            itemIds = json.optJSONArray("itemIds").strings().distinct(), coverItemId = json.str("coverItemId"), source = source,
            follow = if (kind == CollectionKind.OWN) null else follow, seen = json.optJSONArray("seen").strings(),
            newItemIds = json.optJSONArray("newItemIds").strings(), removedItemIds = json.optJSONArray("removedItemIds").strings(),
            createdAt = json.optLong("createdAt"), updatedAt = json.optLong("updatedAt"),
        )
    }

    // ---- notifications -----------------------------------------------------------------------------

    /** "28 new in Gym Mix · Review" */
    fun reviewText(count: Int, name: String) = "$count new in $name · Review"
    fun newText(count: Int, name: String) = if (count == 1) "1 new in $name" else "$count new in $name"

    /**
     * The in-app notice for a finished check, or null to stay quiet. "Check now" always answers; a background check only speaks when it found something.
     * "Found 3 new songs in Gym Mix · Downloading" is only said when those downloads were really queued.
     */
    fun checkNotice(report: FollowReport): CheckNotice? {
        val noun = if (report.noun == "song") "song" else "item"
        return when {
            report.error != null -> if (report.manual) CheckNotice("Couldn't check ${report.name}. ${report.error}", null) else null
            report.held > 0 -> CheckNotice(reviewText(report.held, report.name), NoticeAction.REVIEW)
            report.found > 0 -> CheckNotice("Found ${report.found} new ${if (report.found == 1) noun else noun + "s"} in ${report.name} · Downloading", NoticeAction.SHOW)
            report.manual -> CheckNotice("No new ${noun}s in ${report.name}", null)
            else -> null
        }
    }

    private const val BATCH_GAP_MS = 10_000L

    /**
     * "Downloading 2 of 3 new": the batch of this collection's jobs that is still running. A batch is a run of jobs queued together (a check or a
     * download queues them within milliseconds of each other); every batch with an active job counts. Null when nothing is active.
     * The fraction uses only what the engine reported: finished jobs count full, others their own percent.
     */
    fun downloadProgress(jobs: List<Job>, collectionId: String): DownloadProgress? {
        val mine = jobs.filter { it.collectionId == collectionId }.sortedBy { it.createdAt }
        if (mine.none { it.status.active }) return null
        val batches = ArrayList<MutableList<Job>>()
        for (job in mine) {
            val last = batches.lastOrNull()
            if (last != null && job.createdAt - last.last().createdAt <= BATCH_GAP_MS) last.add(job) else batches.add(mutableListOf(job))
        }
        val batch = batches.filter { b -> b.any { it.status.active } }.flatten()
        val done = batch.count { it.status == JobStatus.COMPLETED }
        val sum = batch.sumOf { (if (it.status == JobStatus.COMPLETED) 100f else it.percent.coerceIn(0f, 100f)).toDouble() }
        return DownloadProgress(done, batch.size, (sum / (100.0 * batch.size)).toFloat().coerceIn(0f, 1f), batch.filter { it.status.active })
    }
}

/** What the collection page shows while its new items download. [active] are the jobs still running or waiting, for their state rows. */
data class DownloadProgress(val done: Int, val total: Int, val fraction: Float, val active: List<Job>) {
    val label: String get() = "Downloading $done of $total new"
}

/** "Keep in sync" chosen at download time: how often to check and whether to wait for Wi-Fi. */
data class SyncRequest(val intervalHours: Int = 6, val wifiOnly: Boolean = true)

/** What one finished follow check found, for the in-app notice. [noun] is "song" or "item". */
data class FollowReport(val collectionId: String, val name: String, val found: Int, val held: Int, val error: String?, val manual: Boolean, val noun: String = "song")

enum class NoticeAction { SHOW, REVIEW }
data class CheckNotice(val text: String, val action: NoticeAction?)
