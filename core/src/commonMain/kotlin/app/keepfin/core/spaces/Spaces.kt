package app.keepfin.core.spaces

import app.keepfin.core.providers.Providers

/** The spaces hub: what shows on home, reordering and where a download goes by default. */
object Spaces {
    const val HOME_LIMIT = 5
    val BUILTIN_IDS = listOf("downloads", "music", "videos")

    const val FAVORITES_ID = "favorites"

    /**
     * Kept spaces: every library has them and none can be removed (renamed and edited only). The three folder built-ins, which hold
     * files, and Favorites, a smart space of everything hearted. Only the built-ins are folders: the folder-only rules (never smart,
     * a folder, a download target) apply to [BUILTIN_IDS], not to Favorites.
     */
    val KEPT_IDS = BUILTIN_IDS + FAVORITES_ID

    /** Favorites' name, color and icon when Keepfin makes it (both apps read these, so the space looks the same everywhere). */
    const val FAVORITES_NAME = "Favorites"
    const val FAVORITES_COLOR = "berry"
    const val FAVORITES_ICON = "heart"

    fun isKept(id: String): Boolean = id in KEPT_IDS

    /**
     * The kept spaces [ids] does not hold, in [KEPT_IDS] order. Missing ones are made again by id alone: two devices that both add
     * one make the same space (the same id, so the same wire id), and a renamed one is still there.
     */
    fun missingKept(ids: Collection<String>): List<String> = KEPT_IDS.filter { it !in ids }

    /** An explicit pin, else the built-ins. */
    fun isPinned(space: Space): Boolean = space.pinned ?: (space.id in BUILTIN_IDS)

    /** Spaces on home: the pinned ones in hub order (at most five); with no pins, the first five. */
    fun home(spaces: List<Space>): List<Space> {
        val pinned = spaces.filter(::isPinned)
        return (if (pinned.isEmpty()) spaces else pinned).take(HOME_LIMIT)
    }

    /** How many spaces "All spaces · N more" stands for. */
    fun more(spaces: List<Space>): Int = (spaces.size - home(spaces).size).coerceAtLeast(0)

    /** [ids] with [id] moved one place later ([forward]) or earlier; unchanged at the ends, or when [id] is not there. */
    fun move(ids: List<String>, id: String, forward: Boolean): List<String> {
        val from = ids.indexOf(id)
        val to = if (forward) from + 1 else from - 1
        if (from < 0 || to !in ids.indices) return ids
        return ids.toMutableList().also { it.add(to, it.removeAt(from)) }
    }

    /**
     * The hub order for [ids]: every id must exist once (otherwise the caller's list is out of date and null is returned); spaces the
     * list leaves out keep their order at the end.
     */
    fun reorder(spaces: List<Space>, ids: List<String>): List<Space>? {
        val byId = spaces.associateBy { it.id }
        val taken = HashSet<String>()
        val ordered = ArrayList<Space>()
        for (id in ids) {
            val space = byId[id]
            if (space == null || !taken.add(id)) return null
            ordered.add(space)
        }
        return ordered + spaces.filter { it.id !in taken }
    }

    /** Links from music services and YouTube Music are music by source, whatever format is chosen. */
    fun isMusicSource(url: String): Boolean =
        Providers.musicLink(url) != null || Providers.isShortMusicLink(url) || Providers.isYouTubeMusic(url)

    /**
     * The space a download goes to when the user does not pick one: the music route for music sources (and for anything the caller
     * already knows is [music]), else the other route. A smart space is never a target; a stale route falls back to the first folder space.
     */
    fun defaultSpace(url: String, musicRoute: String, otherRoute: String, spaces: List<Space>, music: Boolean = false): String {
        val wanted = if (music || isMusicSource(url)) musicRoute else otherRoute
        val targets = spaces.filter { it.type != "smart" }
        return if (targets.any { it.id == wanted }) wanted else (targets.firstOrNull()?.id ?: "downloads")
    }
}
