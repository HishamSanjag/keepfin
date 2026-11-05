package app.keepfin.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import app.keepfin.ItemOrder
import app.keepfin.ItemQuery
import app.keepfin.LibraryBrowse
import app.keepfin.LibraryItem
import app.keepfin.PageDirection
import app.keepfin.PageKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext

// Paging 3 over the library's keyset pages (decision D-A4). A list reads rows of one ordered, filtered view a page at a time, never
// the whole list; a commit of the store invalidates the source, and the list reloads around the row at the top of the screen. The
// page cache is bounded ([PagingConfig.maxSize]).

/** The Paging source of one query: pages are keyset pages of [LibraryBrowse.page]. */
class ItemSource(private val browse: LibraryBrowse, private val query: ItemQuery) : PagingSource<PageKey, LibraryItem>() {
    override val jumpingSupported: Boolean get() = false

    override fun getRefreshKey(state: PagingState<PageKey, LibraryItem>): PageKey? {
        val anchor = state.anchorPosition ?: return null
        // reload from half a page above the row on screen, so the row stays inside the page that comes back
        val item = state.closestItemToPosition((anchor - state.config.initialLoadSize / 2).coerceAtLeast(0)) ?: return null
        return browse.keyOf(item, query.order)
    }

    private fun read(params: LoadParams<PageKey>): LoadResult<PageKey, LibraryItem> = try {
        val r = when (params) {
            is LoadParams.Refresh -> if (params.key == null) browse.page(query, PageDirection.FIRST, null, params.loadSize) else browse.page(query, PageDirection.AT, params.key, params.loadSize)
            is LoadParams.Append -> browse.page(query, PageDirection.AFTER, params.key, params.loadSize)
            is LoadParams.Prepend -> browse.page(query, PageDirection.BEFORE, params.key, params.loadSize)
        }
        LoadResult.Page(r.items, r.prev, r.next)
    } catch (e: Throwable) {
        LoadResult.Error(e)
    }

    override suspend fun load(params: LoadParams<PageKey>): LoadResult<PageKey, LibraryItem> =
        if (browse.cheap) read(params) else withContext(Dispatchers.IO) { read(params) }
}

private class Current { @Volatile var source: ItemSource? = null }

/** The rows of [query] as a Paging list. It reloads (keeping its place) when the library changes. */
@Composable
fun rememberItemPaging(browse: LibraryBrowse, query: ItemQuery, pageSize: Int = 40): LazyPagingItems<LibraryItem> {
    val current = remember(browse, query) { Current() }
    val flow = remember(browse, query) {
        Pager(
            PagingConfig(pageSize = pageSize, prefetchDistance = pageSize, enablePlaceholders = false, initialLoadSize = pageSize * 2, maxSize = pageSize * 10),
        ) { ItemSource(browse, query).also { current.source = it } }.flow
    }
    val items = flow.collectAsLazyPagingItems()
    LaunchedEffect(browse, query) {
        browse.changes.drop(1).collectLatest {
            if (!browse.cheap) delay(120) // a burst of changes reloads once
            current.source?.invalidate()
        }
    }
    return items
}

/**
 * The last answer of every library read on screen (bounded), so a page opened again draws at once with what it showed last time
 * and refreshes behind it, instead of starting blank (or with an empty state) for the moment the read takes.
 */
private object BrowseMemo {
    private class Slot(val browse: LibraryBrowse, val site: Class<*>, val key: Any?) {
        override fun equals(other: Any?) = other is Slot && other.browse === browse && other.site == site && other.key == key
        override fun hashCode() = (System.identityHashCode(browse) * 31 + site.hashCode()) * 31 + key.hashCode()
    }
    private class Answer(val value: Any?)
    private val answers = object : LinkedHashMap<Slot, Answer>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Slot, Answer>?) = size > 160
    }

    /** The remembered answer, or [missing] when this read never finished. */
    @Suppress("UNCHECKED_CAST")
    fun <T> get(browse: LibraryBrowse, site: Class<*>, key: Any?, missing: T): T = synchronized(answers) { answers[Slot(browse, site, key)]?.let { it.value as T } ?: missing }

    fun put(browse: LibraryBrowse, site: Class<*>, key: Any?, value: Any?) { synchronized(answers) { answers[Slot(browse, site, key)] = Answer(value) } }
}

/**
 * A value read from the library, read again (off the main thread) when the library changes. It starts with the last answer of the
 * same read (the same call site and [key]); [initial] shows only until a read has ever finished.
 */
@Composable
fun <T> rememberBrowsed(browse: LibraryBrowse, key: Any?, initial: T, load: (LibraryBrowse) -> T): State<T> {
    // Each call site's lambda has its own class: with the key it names one read.
    val site = load.javaClass
    val start = remember(browse, key) { BrowseMemo.get(browse, site, key, initial) }
    return produceState(start, browse, key) {
        var first = true
        browse.changes.collectLatest {
            if (!first && !browse.cheap) delay(150) // a burst of changes reads once
            first = false
            val read = if (browse.cheap) load(browse) else withContext(Dispatchers.IO) { load(browse) }
            BrowseMemo.put(browse, site, key, read)
            value = read
        }
    }
}

/** The first [limit] rows of [query] (a shelf). */
@Composable
fun rememberTop(browse: LibraryBrowse, query: ItemQuery, limit: Int): State<List<LibraryItem>> =
    rememberBrowsed(browse, query to limit, emptyList()) { it.top(query, limit) }

/** What each smart space among [spaces] gathers, counted by kind in the index. */
@Composable
fun rememberSmartCounts(ui: LibraryUi, spaces: List<app.keepfin.Space>): State<Map<String, app.keepfin.Counts>> {
    val smart = spaces.filter { it.smart && it.rules != null }
    return rememberBrowsed(ui.browse, smart.map { it.id to it.rules }, emptyMap()) { b -> smart.associate { it.id to b.counts(ItemQuery(smart = it.rules)) } }
}

/** Totals by kind for every space (one read shared by every page that shows space cards). */
@Composable
fun rememberSpaceCounts(browse: LibraryBrowse): State<Map<String, app.keepfin.Counts>> = rememberBrowsed(browse, "spaces", emptyMap()) { it.spaceCounts() }

/** How many items the library holds; null until the first count (so a page can tell "empty" from "not read yet"). */
@Composable
fun rememberTotal(browse: LibraryBrowse): State<Int?> = rememberBrowsed(browse, "total", null) { it.count(ItemQuery()) }

@Composable
fun rememberSpaceHead(browse: LibraryBrowse, query: ItemQuery): State<SpaceHead?> =
    rememberBrowsed(browse, query, null) { SpaceHead(it.counts(query), it.tags(query, 2)) }

/**
 * What a space page needs before it draws anything under its toolbar: what it holds and its most used tags, read together. Null until
 * read, so a page never shows "nothing here yet" for a frame and then its songs, nor a tag row that pushes the list down after it drew.
 */
class SpaceHead(val counts: app.keepfin.Counts, val tags: List<app.keepfin.ScopedTag>)

@Composable
fun rememberCount(browse: LibraryBrowse, query: ItemQuery): State<Int> =
    rememberBrowsed(browse, query, 0) { it.count(query) }

@Composable
fun rememberCounts(browse: LibraryBrowse, query: ItemQuery): State<app.keepfin.Counts> =
    rememberBrowsed(browse, query, app.keepfin.Counts()) { it.counts(query) }

/** The items with these ids, in this order (a collection's songs, a few covers). Reads again when the ids or the library change. */
@Composable
fun rememberByIds(browse: LibraryBrowse, ids: List<String>): State<List<LibraryItem>> =
    rememberBrowsed(browse, ids, emptyList()) { it.byIds(ids) }

@Composable
fun rememberItem(browse: LibraryBrowse, id: String?): State<LibraryItem?> =
    rememberBrowsed(browse, id, null) { if (id == null) null else it.item(id) }

/** A read that has finished: [value] is null when there was nothing to find. (A state that is itself null has not been read yet.) */
class Loaded<T>(val value: T?)

/** How many songs of a window go into a queue around the song that was tapped (before, and from it on). */
const val QUEUE_BEFORE = 100
const val QUEUE_AFTER = 400

/** The loaded rows of a Paging list, in order (the current window). */
fun LazyPagingItems<LibraryItem>.loaded(): List<LibraryItem> = itemSnapshotList.items

val ItemOrder.isSortedByTitle: Boolean get() = this == ItemOrder.TITLE
