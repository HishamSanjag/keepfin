package app.keepfin

/**
 * The decisions behind the Undo of library actions, kept free of Android so they are tested on the JVM. Every function takes the
 * facts captured when the action ran (never a list a screen held) and the state at the moment Undo is pressed, and answers what to
 * change so that only what that one action did is reverted and every later change stays.
 */
object UndoLogic {
    /** One tag change to make on [ids]: add [add], remove [remove]. */
    data class TagStep(val ids: List<String>, val add: List<String>, val remove: List<String>)

    /**
     * The inverse of "add [add] and remove [remove]" on items that carried [before] (id to user tags) when it ran: per item, the tags the
     * action added that the item did not have are removed, and the tags it removed that the item had are added back. Items the action
     * did not change give no step; items that need the same change are grouped.
     */
    fun tagInverse(before: Map<String, List<String>>, add: List<String>, remove: List<String>): List<TagStep> {
        val groups = LinkedHashMap<Pair<List<String>, List<String>>, MutableList<String>>()
        for ((id, tags) in before) {
            val after = TagLogic.editUserTags(tags, add, remove)
            val added = after.filter { it !in tags }
            val removed = tags.filter { it !in after }
            if (added.isEmpty() && removed.isEmpty()) continue
            groups.getOrPut(removed to added) { ArrayList() } += id
        }
        // inverse: what was removed comes back, what was added goes
        return groups.map { (change, ids) -> TagStep(ids, add = change.first, remove = change.second) }
    }

    /**
     * Undo of a move to [target]: [origins] is the space each item came from when the move ran. Items go back to their origin, grouped
     * by it; an item that is not in [target] now (moved again since) or that was already there stays where it is.
     */
    fun moveBack(origins: Map<String, String?>, target: String, spaceNow: (String) -> String?): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        for ((id, origin) in origins) {
            if (origin == null || origin == target) continue
            if (spaceNow(id) != target) continue
            out.getOrPut(origin) { ArrayList() } += id
        }
        return out
    }

    /**
     * The tag edit that undoes [edit], an edit of [before]; null when it cannot be undone exactly (the edit replaced the cover, which
     * cannot be put back) or changed nothing. Blank text clears a tag; 0 clears a number.
     */
    fun editInverse(before: LibraryItem, edit: TagEdit): TagEdit? {
        if (edit.coverUri != null) return null
        fun text(wanted: String?, old: String?) = if (wanted == null) null else (old ?: "").takeIf { wanted.trim() != (old ?: "") }
        fun number(wanted: Int?, old: Int?) = if (wanted == null) null else (old ?: 0).takeIf { it != wanted }
        val inverse = TagEdit(
            title = text(edit.title, before.title), artist = text(edit.artist, before.artist), album = text(edit.album, before.album),
            albumArtist = text(edit.albumArtist, before.albumArtist), genre = text(edit.genre, before.genre),
            track = number(edit.track, before.track), disc = number(edit.disc, before.disc), year = number(edit.year, before.year),
        )
        return inverse.takeIf { it != TagEdit() }
    }

    /** [ids] with [id] put back where it was in [before] (after the closest earlier one that is still in [now]; at the front when none is). */
    fun reinsert(before: List<String>, now: List<String>, id: String): List<String> {
        var anchor: String? = null
        for (i in before.indexOf(id) - 1 downTo 0) if (before[i] in now) { anchor = before[i]; break }
        val out = now.filter { it != id }.toMutableList()
        out.add(if (anchor == null) 0 else out.indexOf(anchor) + 1, id)
        return out
    }

    /** The music and other routes after the space [removed] is deleted (what the library falls back to), given the spaces that remain. */
    fun routesAfterRemoval(kept: List<Space>, musicRoute: String, otherRoute: String, removed: String): Pair<String, String> {
        fun route(chosen: String, fallback: String) =
            if (kept.any { it.id == chosen && it.id != removed && it.type == SpaceType.FOLDER }) chosen
            else if (kept.any { it.id == fallback && it.type == SpaceType.FOLDER }) fallback else "downloads"
        return route(musicRoute, "music") to route(otherRoute, "downloads")
    }

    /**
     * The routes to set when a removed space comes back: a route that pointed to [removed] goes back to it only while it still reads what
     * the removal made of it ([afterRemoval]); a route changed since stays as the user set it.
     */
    fun routesAfterRestore(removed: String, before: Pair<String, String>, afterRemoval: Pair<String, String>, now: Pair<String, String>): Pair<String, String> =
        (if (before.first == removed && now.first == afterRemoval.first) removed else now.first) to
            (if (before.second == removed && now.second == afterRemoval.second) removed else now.second)

    /** The notice for a restore that left some files in the trash: how many came back and why the others did not. */
    fun restoreProblem(total: Int, result: RestoreResult): String? {
        if (result.problems.isEmpty()) return null
        val back = result.restored.size
        val head = if (back == 0) "Nothing could be brought back." else "Brought back $back of $total."
        return head + " " + result.problems.first() + if (result.problems.size > 1) " (and ${result.problems.size - 1} more)" else ""
    }

    /** "Deleted “X”" or "Deleted N files", with the part about where files outside the Keepfin folder went when some of them were. */
    /** Delete for good: Undo still works while the notice shows; then the files are erased. */
    fun deletedForGoodNotice(titles: List<String>, count: Int): String {
        val what = if (count == 1) (if (titles.isNotEmpty()) "“${titles[0]}”" else "1 file") else "$count files"
        return "Deleted $what for good. Undo works until this notice closes."
    }

    fun deletedNotice(titles: List<String>, count: Int, undoable: Int): String {
        val head = if (count == 1) (if (titles.isNotEmpty()) "Deleted “${titles[0]}”" else "Deleted 1 file") else "Deleted $count files"
        return when {
            undoable == count -> head
            undoable == 0 -> "$head. ${if (count == 1) "It was" else "They were"} removed from the phone for good."
            else -> "$head. Files outside the Keepfin folder were removed from the phone for good."
        }
    }
}
