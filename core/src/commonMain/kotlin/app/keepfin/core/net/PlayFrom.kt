package app.keepfin.core.net

// "Play from": which paired device an item that is not on this device plays from, and the words for it (network spec 9.4).
// The holders are the devices the library's placements say have the file (what the sync data already knows), not every device that
// allows playing. Pure: the apps gather the facts, the core decides the same way for both.

/** A paired device that holds the item. [near] is true when it is reached on this network (not through Tailscale). [lastSeen] is epoch ms or null. */
class PlayHolder(val name: String, val online: Boolean, val offersMedia: Boolean, val near: Boolean, val lastSeen: Long?)

enum class PlayHintKind(val id: String) {
    /** A connected holder that lets this device play its files. */
    Peer("peer"),

    /** A holder is connected but does not let this device play its files. */
    NotAllowed("notAllowed"),

    /** Every holder is away. */
    Away("away"),

    /** No paired device is known to have it. */
    Unknown("unknown"),
}

/** [holder] is an index into the holders that were given (-1 for [PlayHintKind.Unknown]). */
class PlayHint(val kind: PlayHintKind, val holder: Int)

object PlayFrom {
    /**
     * A connected holder that allows playing wins (one on this network before one through Tailscale, then the order given); else a connected holder
     * that does not allow it; else the most recently seen away holder; else nobody is known.
     */
    fun choose(holders: List<PlayHolder>): PlayHint {
        fun best(list: List<Int>): Int? = list.firstOrNull { holders[it].near } ?: list.firstOrNull()
        val all = holders.indices.toList()
        best(all.filter { holders[it].online && holders[it].offersMedia })?.let { return PlayHint(PlayHintKind.Peer, it) }
        best(all.filter { holders[it].online })?.let { return PlayHint(PlayHintKind.NotAllowed, it) }
        var away = -1
        for (i in all) if (away < 0 || (holders[i].lastSeen ?: Long.MIN_VALUE) > (holders[away].lastSeen ?: Long.MIN_VALUE)) away = i
        return if (away >= 0) PlayHint(PlayHintKind.Away, away) else PlayHint(PlayHintKind.Unknown, -1)
    }

    /** The small line under an item: [here] is "this PC" or "this phone". */
    fun text(hint: PlayHint, holders: List<PlayHolder>, here: String): String {
        val name = if (hint.holder in holders.indices) holders[hint.holder].name else ""
        return when (hint.kind) {
            PlayHintKind.Peer -> "On $name"
            PlayHintKind.NotAllowed -> "On $name (playing from it is off)"
            PlayHintKind.Away -> "On $name (not reachable)"
            PlayHintKind.Unknown -> "Not on $here. No paired device is known to have it."
        }
    }
}
