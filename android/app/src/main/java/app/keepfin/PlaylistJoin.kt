package app.keepfin

/**
 * "Add to playlist" from the download review: when a job is saved, the library item it made (or the one that was already there) joins the
 * playlist chosen in the review. Only the person's own playlists take part; one deleted since the review is skipped without an error, and an
 * item already in the playlist is not added twice.
 */
internal object PlaylistJoin {
    /** Adds [itemIds] to [playlist] through [add] when it still exists as one of the person's own playlists. Returns how many were added. */
    fun join(playlist: String?, itemIds: List<String>, collection: (String) -> LibraryCollection?, add: (String, List<String>) -> Unit): Int {
        val target = playlist?.let(collection)?.takeIf { it.kind == CollectionKind.OWN } ?: return 0
        val fresh = itemIds.distinct().filter { it !in target.itemIds }
        if (fresh.isEmpty()) return 0
        add(target.id, fresh)
        return fresh.size
    }
}
