package app.keepfin.devicemusic

import app.keepfin.AlbumGroup
import app.keepfin.ArtistGroup
import app.keepfin.Counts
import app.keepfin.DEVICE_SPACE_ID
import app.keepfin.Facet
import app.keepfin.FolderGroup
import app.keepfin.ItemOrder
import app.keepfin.ItemQuery
import app.keepfin.LibraryBrowse
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.PageDirection
import app.keepfin.PageKey
import app.keepfin.PageResult
import app.keepfin.ScopedTag
import app.keepfin.SmartField
import app.keepfin.isDeviceItem
import kotlinx.coroutines.flow.StateFlow

/**
 * The library's reads with the "This device" space added: a query for that space (or an item whose id starts with `dm-`) is answered by the
 * device-only index, everything else by the library's own. The library's own reads never see device songs, so lists of "every space"
 * stay as they were. [device] is null while the feature is off or has no permission.
 */
internal class DeviceAwareBrowse(private val main: LibraryBrowse, private val device: () -> LibraryBrowse?) : LibraryBrowse {
    override val changes: StateFlow<Long> get() = main.changes
    override val cheap: Boolean get() = main.cheap

    private fun onDevice(q: ItemQuery) = q.spaces.size == 1 && q.spaces[0] == DEVICE_SPACE_ID

    private fun target(q: ItemQuery): LibraryBrowse? = if (onDevice(q)) device() else main

    /** Queries over every space also hold this phone's songs (favorites, recent, most played...): the two sources are merged in the list's order. */
    private fun merged(q: ItemQuery): LibraryBrowse? =
        if (q.spaces.isEmpty() && q.folder == null && q.subtree == null && DEVICE_SPACE_ID !in q.excludeSpaces) device() else null

    private fun <T> onPhone(block: LibraryBrowse.() -> T, fallback: T): T = runCatching { device()!!.block() }.getOrDefault(fallback)

    override fun page(query: ItemQuery, direction: PageDirection, key: PageKey?, limit: Int): PageResult {
        val extra = merged(query) ?: return target(query)?.page(query, direction, key, limit) ?: PageResult(emptyList(), null, null)
        val own = main.page(query, direction, key, limit)
        val theirs = runCatching { extra.page(query, direction, key, limit) }.getOrNull()
        if (theirs == null || theirs.items.isEmpty() && theirs.prev == null && theirs.next == null) return own
        val order = query.order
        val keyed = (own.items + theirs.items).map { main.keyOf(it, order) to it }.sortedWith { a, b -> compareKeys(a.first, b.first).let { if (order.descending) -it else it } }
        val back = direction == PageDirection.BEFORE
        val more = keyed.size > limit || (if (back) own.prev != null || theirs.prev != null else own.next != null || theirs.next != null)
        val taken = if (back) keyed.takeLast(limit) else keyed.take(limit)
        val first = taken.firstOrNull()?.first
        val last = taken.lastOrNull()?.first
        val prev = when (direction) { PageDirection.FIRST -> null; PageDirection.BEFORE -> if (more) first else null; else -> first }
        val next = if (back) last else if (more) last else null
        return PageResult(taken.map { it.second }, prev, next)
    }

    private fun compareKeys(a: PageKey, b: PageKey): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val x = a[i]
            val y = b[i]
            val c = if (x is String && y is String) x.compareTo(y) else if (x is Long && y is Long) x.compareTo(y) else (x as Number).toDouble().compareTo((y as Number).toDouble())
            if (c != 0) return c
        }
        return 0
    }

    override fun keyOf(item: LibraryItem, order: ItemOrder): PageKey =
        (if (item.space == DEVICE_SPACE_ID) device() ?: main else main).keyOf(item, order)

    override fun count(query: ItemQuery): Int = (target(query)?.count(query) ?: 0) + if (merged(query) != null) onPhone({ count(query) }, 0) else 0

    override fun counts(query: ItemQuery): Counts {
        val own = target(query)?.counts(query) ?: Counts()
        if (merged(query) == null) return own
        val d = onPhone({ counts(query) }, Counts())
        return Counts(own.total + d.total, own.audio + d.audio, own.video + d.video, own.image + d.image, own.other + d.other, own.bytes + d.bytes)
    }

    override fun spaceCounts(query: ItemQuery): Map<String, Counts> {
        val own = main.spaceCounts(query)
        val onPhone = device()?.counts(query.copy(spaces = listOf(DEVICE_SPACE_ID)))?.takeIf { it.total > 0 } ?: return own
        return own + (DEVICE_SPACE_ID to onPhone)
    }

    override fun facets(field: SmartField, limit: Int): List<Facet> = main.facets(field, limit)
    override fun outsideCounts(space: String): Map<String, Int> = main.outsideCounts(space)

    override fun spacesOf(ids: Collection<String>): Set<String> {
        val (mine, theirs) = ids.partition { !isDeviceItem(it) }
        return main.spacesOf(mine) + if (theirs.isNotEmpty() && device()?.byIds(theirs)?.isNotEmpty() == true) setOf(DEVICE_SPACE_ID) else emptySet()
    }

    override fun byIds(ids: List<String>): List<LibraryItem> {
        if (ids.none(::isDeviceItem)) return main.byIds(ids)
        val found = HashMap<String, LibraryItem>()
        main.byIds(ids.filter { !isDeviceItem(it) }).forEach { found[it.id] = it }
        device()?.byIds(ids.filter(::isDeviceItem))?.forEach { found[it.id] = it }
        return ids.mapNotNull { found[it] }
    }

    override fun item(id: String): LibraryItem? = if (isDeviceItem(id)) device()?.item(id) else main.item(id)

    override fun byJob(jobId: String): LibraryItem? = main.byJob(jobId)
    override fun byUri(uri: String): LibraryItem? = main.byUri(uri)

    override fun albums(query: ItemQuery, after: String?, limit: Int, byArtist: Boolean): List<AlbumGroup> =
        target(query)?.albums(query, after, limit, byArtist).orEmpty()

    override fun albumCount(query: ItemQuery): Int = target(query)?.albumCount(query) ?: 0
    override fun artistCount(query: ItemQuery): Int = target(query)?.artistCount(query) ?: 0

    override fun album(space: String, routeKey: String): AlbumGroup? = (if (space == DEVICE_SPACE_ID) device() else main)?.album(space, routeKey)

    override fun artists(query: ItemQuery, after: String?, limit: Int): List<ArtistGroup> = target(query)?.artists(query, after, limit).orEmpty()

    override fun artist(key: String, space: String?): ArtistGroup? = (if (space == DEVICE_SPACE_ID) device() else main)?.artist(key, space)

    override fun albumsOfArtist(artistKey: String, space: String?): List<AlbumGroup> =
        (if (space == DEVICE_SPACE_ID) device() else main)?.albumsOfArtist(artistKey, space).orEmpty()

    override fun folders(space: String, folder: String): List<FolderGroup> = (if (space == DEVICE_SPACE_ID) device() else main)?.folders(space, folder).orEmpty()

    override fun folderList(space: String, kinds: Set<LibraryItemKind>): List<FolderGroup> =
        (if (space == DEVICE_SPACE_ID) device() else main)?.folderList(space, kinds).orEmpty()

    override fun tags(query: ItemQuery, minItems: Int): List<ScopedTag> = target(query)?.tags(query, minItems).orEmpty()

    override fun duplicates(query: ItemQuery, ids: Collection<String>?, limit: Int): List<List<LibraryItem>> = if (onDevice(query)) emptyList() else main.duplicates(query, ids, limit)
}
