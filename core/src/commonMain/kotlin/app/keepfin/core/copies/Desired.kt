package app.keepfin.core.copies

import app.keepfin.core.sync.ItemStatus
import app.keepfin.core.sync.JArr
import app.keepfin.core.sync.JBool
import app.keepfin.core.sync.JNum
import app.keepfin.core.sync.JObj
import app.keepfin.core.sync.JStr
import app.keepfin.core.sync.Jv
import app.keepfin.core.sync.State
import app.keepfin.core.sync.headsOf
import app.keepfin.core.sync.itemStatus
import app.keepfin.core.sync.playlistSongs
import app.keepfin.core.sync.setMembers

/** How a destination compares and spells names (probed once per volume: case sensitivity, Unicode equivalence). */
interface NameRules {
    /** Two paths with the same key are the same name on the destination. */
    fun key(path: String): String

    companion object {
        /** A case-sensitive destination. */
        val exact: NameRules = object : NameRules {
            override fun key(path: String): String = path
        }

        /** A case-sensitive destination that still never gets two spellings of one name (composed and decomposed accents: `nfc`). */
        val composed: NameRules = object : NameRules {
            override fun key(path: String): String = composedKey(path)
        }

        /** A destination that ignores letter case (NTFS, FAT, exFAT, cloud accounts), composed and decomposed accents alike (`unicode`). */
        val folded: NameRules = object : NameRules {
            override fun key(path: String): String = foldedKey(path)
        }

        /** The rule a host names by its id (`exact`, `ascii`, `nfc`, `unicode`), or null. */
        fun byId(id: String): NameRules? = when (id) {
            "exact" -> exact
            "ascii" -> caseInsensitiveAscii
            "nfc" -> composed
            "unicode" -> folded
            else -> null
        }

        /** A destination that ignores ASCII case only (the older rule; kept for relationships that were written with it). */
        val caseInsensitiveAscii: NameRules = object : NameRules {
            override fun key(path: String): String {
                var needs = false
                for (c in path) if (c in 'A'..'Z') { needs = true; break }
                if (!needs) return path
                val sb = StringBuilder(path.length)
                for (c in path) sb.append(if (c in 'A'..'Z') Char(c.code + 32) else c)
                return sb.toString()
            }
        }
    }
}

object Names {
    private fun tagOf(contentId: String): String {
        val hex = contentId.substringAfter(':', contentId)
        return if (hex.length >= 8) hex.substring(0, 8) else hex
    }

    /** `dir/name.ext` becomes `dir/name (word tag).ext` with the tag from the content id. */
    fun withTag(path: String, word: String, contentId: String): String {
        val slash = path.lastIndexOf('/')
        val dot = path.lastIndexOf('.')
        val hasExt = dot > slash + 1
        val stem = if (hasExt) path.substring(0, dot) else path
        val ext = if (hasExt) path.substring(dot) else ""
        return "$stem ($word ${tagOf(contentId)})$ext"
    }

    /** Where a second head of a content conflict lives. */
    fun conflictPath(path: String, contentId: String): String = withTag(path, "conflict", contentId)

    /** Where a different file that arrived under an occupied name is imported as its own item. */
    fun copyPath(path: String, contentId: String): String = withTag(path, "copy", contentId)
}

private fun Map<String, Jv>.str(key: String): String? = (this[key] as? JStr)?.value

private fun Map<String, Jv>.strings(key: String): Set<String> =
    ((this[key] as? JArr)?.items ?: emptyList()).mapNotNull { (it as? JStr)?.value }.toSet()

/** Reads a place from the values of its `place.set` registers. Null when the mode is missing or unknown. */
fun placeConfigFrom(placeId: String, fields: Map<String, Jv>): PlaceConfig? {
    val mode = when (fields.str("mode")) {
        "twoWay" -> CopyMode.TwoWay
        "backup" -> CopyMode.Backup
        "mirror" -> CopyMode.Mirror
        else -> return null
    }
    val h = (fields["holds"] as? JObj)?.members ?: emptyMap()
    val holds = Holds(
        all = (h["all"] as? JBool)?.value == true,
        spaces = h.strings("spaces"),
        playlists = h.strings("playlists"),
        favourites = (h["favourites"] as? JBool)?.value == true,
        smart = h.strings("smart"),
    )
    val o = (fields["options"] as? JObj)?.members ?: emptyMap()
    fun flag(k: String) = (o[k] as? JBool)?.value == true
    val defaults = PlaceOptions()
    val options = PlaceOptions(
        playlistFiles = flag("playlistFiles"),
        flat = flag("flat"),
        mp3 = flag("mp3"),
        // a copy holds everything unless the person turned the companions off
        companions = (o["companions"] as? JBool)?.value != false,
        // until the person chooses: a copy of everything carries them, unless it already leaves out the files beside each song
        musicVideos = (o["musicVideos"] as? JBool)?.value ?: (holds.all && (o["companions"] as? JBool)?.value != false),
        versionsKeepDays = (o["versionsKeepDays"] as? JNum)?.value?.toInt() ?: defaults.versionsKeepDays,
        deleteGuardPercent = (o["deleteGuardPercent"] as? JNum)?.value?.toInt() ?: defaults.deleteGuardPercent,
    )
    val every = ((fields["schedule"] as? JObj)?.members?.get("everyHours") as? JNum)?.value?.toInt()
    return PlaceConfig(placeId, fields.str("kind") ?: "folder", fields.str("name") ?: "", fields.str("replica"), mode, holds, options, every)
}

/** The place [placeId] of [state], or null when it does not exist or has no usable mode. */
fun placeConfigOf(state: State, placeId: String): PlaceConfig? {
    val regs = state.places[placeId] ?: return null
    return placeConfigFrom(placeId, regs.mapValues { it.value.value })
}

/** The desired set of a place and the items that have no path on the main folder (they cannot be copied yet). */
class DesiredSet(val entries: List<DesiredEntry>, val withoutPath: List<String>)

/**
 * The desired set D of a place from the merged [state] (spec section 2): every present item that [holds] selects, and
 * the items of held playlists, each at its head revision and at its path on the main folder (`placements[item][mainReplica]`).
 * An item whose heads are in a content conflict is desired with every head: the first (in op order) at the placement
 * path, the others under [Names.conflictPath]. [smart] says whether a smart space selects an item (the rules live in
 * the spaces package). With [companions] (the default) each item also carries its lyrics, subtitles and info file, and with
 * [musicVideos] a song its music video ([companionCandidates], [keepCompanions]; the default is the place's: everything, or
 * none for a part copy). The entries come back sorted by path.
 */
fun desiredSetFrom(
    state: State,
    mainReplica: String,
    holds: Holds,
    companions: Boolean = true,
    smart: (spaceId: String, itemId: String) -> Boolean = { _, _ -> false },
    musicVideos: Boolean = holds.all,
): DesiredSet {
    val favourites: Set<String> = if (holds.favourites) state.setMembers("favourites").toSet() else emptySet()
    val inPlaylists = HashSet<String>()
    for (pl in holds.playlists) for (song in state.playlistSongs(pl)) inPlaylists.add(song.item)
    val entries = ArrayList<DesiredEntry>()
    val candidates = ArrayList<DesiredEntry>()
    val without = ArrayList<String>()
    for ((id, item) in state.items) {
        if (state.itemStatus(id) != ItemStatus.Present) continue
        val space = (item.fields["space"]?.value as? JStr)?.value
        val selected = holds.all ||
            (space != null && space in holds.spaces) ||
            id in favourites ||
            id in inPlaylists ||
            holds.smart.any { smart(it, id) }
        if (!selected) continue
        val path = (state.placements[id]?.get(mainReplica)?.value as? JStr)?.value
        if (path == null) {
            without.add(id)
            continue
        }
        val heads = headsOf(item)
        heads.forEachIndexed { index, head ->
            val e = item.revisions.getValue(head)
            val p = if (index == 0) path else Names.conflictPath(path, e.content)
            entries.add(DesiredEntry(p, id, head.toString(), e.content, e.size))
        }
        if ((companions || musicVideos) && item.fields.keys.any { it.startsWith(COMPANION_FIELD) })
            candidates.addAll(companionCandidates(id, path, heads.size, item.fields.mapValues { it.value.value }, videos = musicVideos, others = companions))
    }
    if (candidates.isNotEmpty()) {
        val mediaKeys = entries.mapTo(HashSet()) { companionKey(it.path) }
        entries.addAll(keepCompanions(candidates) { it in mediaKeys })
    }
    entries.sortBy { it.path }
    without.sort()
    return DesiredSet(entries, without)
}
