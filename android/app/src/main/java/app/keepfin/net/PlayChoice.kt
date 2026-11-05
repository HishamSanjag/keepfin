package app.keepfin.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A paired device that shares the open library, as far as playing from it is concerned. */
class PlayCandidate(val name: String, val reachable: Boolean, val offersMedia: Boolean, val lastSeen: Long?)

/** Where a library item plays from (network spec 9.4 "Play from"). */
sealed interface PlayChoice {
    /** The file is on this phone: it always plays from here. */
    data object Local : PlayChoice

    /** Not on this phone; [name] is reachable and lets this phone play its files. */
    data class Peer(val name: String) : PlayChoice

    /** Not on this phone; the device that could have it is not reachable now. */
    data class Unreachable(val name: String, val lastSeen: Long?) : PlayChoice

    /** Not on this phone and no paired device is known to share it. */
    data object None : PlayChoice
}

/** Pure choice and wording for "Play from". */
object PlaySource {
    /** Local wins; then a reachable device that offers media; then the most recently seen device that would; else none. */
    fun choose(hasLocal: Boolean, candidates: List<PlayCandidate>): PlayChoice {
        if (hasLocal) return PlayChoice.Local
        candidates.firstOrNull { it.reachable && it.offersMedia }?.let { return PlayChoice.Peer(it.name) }
        val away = candidates.filter { !it.reachable }.maxWithOrNull(compareBy { it.lastSeen ?: Long.MIN_VALUE })
        if (away != null) return PlayChoice.Unreachable(away.name, away.lastSeen)
        return PlayChoice.None
    }

    /** The small line under an item that is not on this phone. */
    fun hint(choice: PlayChoice): String? = when (choice) {
        PlayChoice.Local -> null
        is PlayChoice.Peer -> "On ${choice.name}"
        is PlayChoice.Unreachable -> "On ${choice.name} (not reachable)"
        PlayChoice.None -> "Not on this phone"
    }

    /** What a tap says when the file cannot be played now; null when it can. */
    fun blocked(choice: PlayChoice, now: Long): String? = when (choice) {
        PlayChoice.Local, is PlayChoice.Peer -> null
        is PlayChoice.Unreachable ->
            if (choice.lastSeen != null) "${choice.name} is not reachable. It was last seen ${ago(choice.lastSeen, now)}." else "${choice.name} is not reachable."
        PlayChoice.None -> "This file is not on this phone, and no paired device that has it is available."
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

    /** The choice for an item of the open library that is not on this phone, from the live devices. */
    fun choiceFor(): PlayChoice {
        forced?.let { return it }
        val service = NetRuntime.peek() ?: return PlayChoice.None
        val library = NetRuntime.libraries().firstOrNull() ?: return PlayChoice.None
        return PlaySource.choose(false, service.playCandidates(library.libraryId))
    }

    /** A message when tapping an item that is not on this phone cannot play it now; null when it can. */
    fun blockedMessage(now: Long = System.currentTimeMillis()): String? = PlaySource.blocked(choiceFor(), now)
}
