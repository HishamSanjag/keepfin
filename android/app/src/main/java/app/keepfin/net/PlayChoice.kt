package app.keepfin.net

import app.keepfin.core.net.PlayFrom
import app.keepfin.core.net.PlayHint
import app.keepfin.core.net.PlayHintKind
import app.keepfin.core.net.PlayHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A paired device that shares the open library, as far as playing from it is concerned. [near] is true on this network, false through Tailscale. */
class PlayCandidate(val name: String, val reachable: Boolean, val offersMedia: Boolean, val lastSeen: Long?, val deviceId: String = "", val near: Boolean = true)

/** Where a library item plays from (network spec 9.4 "Play from"). */
sealed interface PlayChoice {
    /** The file is on this phone: it always plays from here. */
    data object Local : PlayChoice

    /** Not on this phone; [name] holds it, is reachable and lets this phone play its files. */
    data class Peer(val name: String) : PlayChoice

    /** Not on this phone; [name] holds it and is reachable, but does not let this phone play its files. */
    data class NotAllowed(val name: String) : PlayChoice

    /** Not on this phone; the device that holds it is not reachable now. */
    data class Unreachable(val name: String, val lastSeen: Long?) : PlayChoice

    /** Not on this phone and no paired device is known to hold it. */
    data object None : PlayChoice
}

/** Pure choice and wording for "Play from". The decision and the words are the shared core (core net/PlayFrom.kt), the same as on Windows. */
object PlaySource {
    private const val HERE = "this phone"

    /** Local wins; else the core picks among the devices that hold the item (a connected one that allows playing, on this network first). */
    fun choose(hasLocal: Boolean, candidates: List<PlayCandidate>): PlayChoice {
        if (hasLocal) return PlayChoice.Local
        val holders = candidates.map { PlayHolder(it.name, it.reachable, it.offersMedia, it.near, it.lastSeen) }
        val hint = PlayFrom.choose(holders)
        val holder = candidates.getOrNull(hint.holder)
        return when (hint.kind) {
            PlayHintKind.Peer -> PlayChoice.Peer(holder!!.name)
            PlayHintKind.NotAllowed -> PlayChoice.NotAllowed(holder!!.name)
            PlayHintKind.Away -> PlayChoice.Unreachable(holder!!.name, holder.lastSeen)
            PlayHintKind.Unknown -> PlayChoice.None
        }
    }

    /** The small line under an item that is not on this phone. */
    fun hint(choice: PlayChoice): String? = when (choice) {
        PlayChoice.Local -> null
        is PlayChoice.Peer -> text(PlayHintKind.Peer, choice.name)
        is PlayChoice.NotAllowed -> text(PlayHintKind.NotAllowed, choice.name)
        is PlayChoice.Unreachable -> text(PlayHintKind.Away, choice.name)
        PlayChoice.None -> text(PlayHintKind.Unknown, "")
    }

    private fun text(kind: PlayHintKind, name: String): String =
        PlayFrom.text(PlayHint(kind, if (kind == PlayHintKind.Unknown) -1 else 0), listOf(PlayHolder(name, true, true, true, null)), HERE)

    /** What a tap says when the file cannot be played now; null when it can. */
    fun blocked(choice: PlayChoice, now: Long): String? = when (choice) {
        PlayChoice.Local, is PlayChoice.Peer -> null
        is PlayChoice.NotAllowed -> "${choice.name} has this file, but it does not let this phone play its files. Turn on playing from it in Devices."
        is PlayChoice.Unreachable ->
            if (choice.lastSeen != null) "${choice.name} is not reachable. It was last seen ${ago(choice.lastSeen, now)}." else "${choice.name} is not reachable."
        PlayChoice.None -> "This file is not on this phone, and no paired device is known to have it."
    }

    fun ago(at: Long, now: Long): String {
        val seconds = ((now - at) / 1000).coerceAtLeast(0)
        return when {
            seconds < 60 -> "just now"
            seconds < 3600 -> plural(seconds / 60, "minute")
            seconds < 86_400 -> plural(seconds / 3600, "hour")
            else -> plural(seconds / 86_400, "day")
        }
    }

    private fun plural(n: Long, unit: String) = if (n == 1L) "1 $unit ago" else "$n ${unit}s ago"
}

/** What the player and the screens know about playing from another device. */
object PeerPlayback {
    const val EXTRA_REMOTE = "keepfin.remote"

    /** `keepfin-peer://content/<hex>?name=..`: a file of the library that is not on this phone, looked for on paired devices when it opens. */
    const val CONTENT_HOST = "content"

    /** The player address of an item that is not on this phone, from its `sha256:<hex>` content id. */
    fun contentUri(contentId: String, name: String, library: String? = null): String? {
        val hex = Regex("^sha256:([0-9a-f]{64})$").matchEntire(contentId)?.groupValues?.get(1) ?: return null
        fun enc(text: String) = java.net.URLEncoder.encode(text, "UTF-8")
        return "keepfin-peer://$CONTENT_HOST/$hex?name=${enc(name)}" + (library?.let { "&library=${enc(it)}" } ?: "")
    }

    private val fromFlow = MutableStateFlow<String?>(null)

    /** The device the player is reading from right now ("Streaming from <device>"); null when nothing streams. */
    val streamingFrom: StateFlow<String?> get() = fromFlow

    @Volatile private var failure: String? = null

    /** UI tests decide where items that are not on this phone play from; null reads the live devices. */
    @Volatile var forced: PlayChoice? = null

    internal fun streaming(name: String?) { fromFlow.value = name }

    internal fun failed(message: String) { failure = message }

    /** The plain-words reason the last peer stream failed, once. */
    fun takeFailure(): String? = failure.also { failure = null }

    /**
     * The choice for an item of the open library that is not on this phone, from the live devices. With [holderIds] (the paired devices
     * the library placements say hold the item) only those are considered; null means the item is not known (every device that shares
     * the library counts).
     */
    fun choiceFor(holderIds: Set<String>? = null): PlayChoice {
        forced?.let { return it }
        val service = NetRuntime.peek() ?: return PlayChoice.None
        val library = NetRuntime.libraries().firstOrNull() ?: return PlayChoice.None
        val candidates = service.playCandidates(library.libraryId).let { all -> if (holderIds == null) all else all.filter { it.deviceId in holderIds } }
        return PlaySource.choose(false, candidates)
    }

    /** The paired devices the library says hold [itemId]; empty when none is known. Blocking: call off the main thread. */
    fun holdersOf(where: app.keepfin.where.WhereFilesSource?, itemId: String): Set<String> =
        runCatching { where?.placesOf(itemId).orEmpty().mapNotNull { it.deviceId }.toSet() }.getOrDefault(emptySet())

    /** A message when tapping an item that is not on this phone cannot play it now; null when it can. Blocking (reads the placements). When no holder is known the tap still tries every device that shares the library, as the player does. */
    fun blockedMessage(where: app.keepfin.where.WhereFilesSource? = null, itemId: String? = null, now: Long = System.currentTimeMillis()): String? =
        PlaySource.blocked(choiceFor(itemId?.let { holdersOf(where, it) }?.takeIf { it.isNotEmpty() }), now)
}
