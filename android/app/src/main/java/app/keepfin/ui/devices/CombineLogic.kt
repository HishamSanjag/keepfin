package app.keepfin.ui.devices

import app.keepfin.merge.CombineDryRun
import app.keepfin.merge.CombineState
import app.keepfin.merge.MergeService
import app.keepfin.merge.ReviewRow
import app.keepfin.merge.SeparatePreview
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import kotlinx.coroutines.flow.StateFlow

// Combining two libraries on the phone (dev-docs/plans/LIBRARY-MERGE.md): the same flow and sentences as the Windows screens
// (gui/web/settings/devices/CombineDialogs.tsx, combineLogic.ts), with "this phone" for "this PC". This file is the pure part and the
// seam to the merge service; the screens are in CombineScreens.kt.

/** What the combine screens ask of the merge service. [ServiceDeviceCombine] is the real one; UI tests pass a fake. Every call may block: call off the main thread. */
interface DeviceCombine {
    val state: StateFlow<CombineState>

    /** Starts the review (the dry run) for [deviceId]. Returns the reason in words when it cannot be done now, or null. */
    fun review(deviceId: String): String?

    /** The person pressed Combine. Returns an error in words, or null. */
    fun start(deviceId: String, keepMine: Boolean): String?
    fun pause()
    fun resume()

    /** Drops the review, or a combine that is still planning. Returns an error in words, or null. */
    fun cancel(): String?
    fun separatePreview(): SeparatePreview?
    fun separate(keepOnPeer: Boolean, anyway: Boolean): String?
    fun keepCombined(): String?
}

class ServiceDeviceCombine(private val service: MergeService) : DeviceCombine {
    override val state: StateFlow<CombineState> get() = service.state

    override fun review(deviceId: String): String? = try { service.combinePreview(deviceId).refusal } catch (e: Exception) { e.message ?: "That did not work." }
    override fun start(deviceId: String, keepMine: Boolean): String? = try { service.combineStart(deviceId, keepMine); null } catch (e: Exception) { e.message ?: "That did not work." }
    override fun pause() = service.combinePause()
    override fun resume() = service.combineResume()
    override fun cancel(): String? = try { service.combineCancel(); null } catch (e: Exception) { e.message ?: "That did not work." }
    override fun separatePreview(): SeparatePreview? = try { service.separatePreview() } catch (e: Exception) { null }
    override fun separate(keepOnPeer: Boolean, anyway: Boolean): String? = try { service.separate(keepOnPeer, anyway); null } catch (e: Exception) { e.message ?: "That did not work." }
    override fun keepCombined(): String? = try { service.keepCombined(); null } catch (e: Exception) { e.message ?: "That did not work." }
}

enum class CombinePhase { Idle, Looking, Ready, Running, Paused, Failed, Done, Separating }

/** The phase of a merge state, from the one fact each field holds. */
internal fun phaseOf(s: CombineState): CombinePhase {
    if (s.mergeId == null) return CombinePhase.Idle
    return when (s.stage) {
        "separated", "cleaned" -> CombinePhase.Idle
        "active" -> CombinePhase.Done
        "separating" -> if (s.paused) pausedOrFailed(s) else CombinePhase.Separating
        else -> when {
            !s.started -> when {
                s.error != null -> CombinePhase.Failed
                s.preview != null -> CombinePhase.Ready
                else -> CombinePhase.Looking
            }
            s.paused -> pausedOrFailed(s)
            else -> CombinePhase.Running
        }
    }
}

private const val FOLDER_WAIT = "Waiting for the Keepfin folder"

private fun waitingForFolder(s: CombineState) = s.waitingForFolder || s.error == FOLDER_WAIT
private fun peerGone(s: CombineState) = s.peerUnreachable || s.error?.contains("cannot be reached") == true

/** A paused merge is waiting for something (the person, the folder, the other device); anything else it stopped on is a failure with one sentence. */
private fun pausedOrFailed(s: CombineState): CombinePhase =
    if (s.error == null || waitingForFolder(s) || peerGone(s)) CombinePhase.Paused else CombinePhase.Failed

/** Why a paused combine waits, in one phrase. */
internal fun pausedReason(s: CombineState, name: String): String = when {
    waitingForFolder(s) -> FOLDER_WAIT
    peerGone(s) -> "$name is not reachable"
    else -> "You paused it."
}

/** The merge state of [deviceId] when it is about that device and something is going on. */
internal fun combineFor(state: CombineState?, deviceId: String): CombineState? =
    state?.takeIf { it.mergeId != null && it.peerDeviceId == deviceId && phaseOf(it) != CombinePhase.Idle }

internal fun count(n: Long): String = NumberFormat.getIntegerInstance().format(n)

internal fun plural(n: Long, noun: String): String = "${count(n)} $noun${if (n == 1L) "" else "s"}"

internal fun longDate(at: Long): String = DateFormat.getDateInstance(DateFormat.LONG).format(Date(at))

enum class Keep { Mine, Theirs }

/** The library whose name, spaces and settings stay: the other device's when it is a PC or its size is not known, else the one with more items. */
internal fun defaultKeep(preview: CombineDryRun?, otherIsPc: Boolean): Keep {
    if (preview == null || otherIsPc || preview.otherItems < 0) return Keep.Theirs
    return if (preview.items > preview.otherItems) Keep.Mine else Keep.Theirs
}

/** "Galaxy S24 has 1,326 items. This phone has 210 items. Combine them into one library that both keep up to date?" */
internal fun combineQuestion(preview: CombineDryRun, name: String): String {
    val mine = "This phone has ${plural(preview.items, "item")}."
    val theirs = if (preview.otherItems >= 0) "$name has ${plural(preview.otherItems, "item")}." else "$name and this phone each have their own library."
    return "$theirs $mine Combine them into one library that both keep up to date?"
}

private fun are(n: Long, noun: String, tail: String) = "${plural(n, noun)} ${if (n == 1L) "is" else "are"} $tail"

/** The review's lines, in order; a line whose count is zero is left out. */
internal fun reviewFacts(p: CombineDryRun, name: String): List<String> {
    val lines = ArrayList<String>()
    if (p.added > 0) lines += are(p.added, "item", "added")
    if (p.linked > 0) lines += "${are(p.linked, "item", "already in $name’s library")} (same file): counted once"
    if (p.keptDuplicates > 0) lines += "${plural(p.keptDuplicates, "item")} ${if (p.keptDuplicates == 1L) "is" else "are"} the same file twice here: both are kept"
    if (p.differing > 0) lines += "${plural(p.differing, "item")} ${if (p.differing == 1L) "has" else "have"} a different title or detail: $name’s stays"
    if (p.missingFiles > 0) lines += "${plural(p.missingFiles, "file")} could not be found on this phone: ${if (p.missingFiles == 1L) "it comes" else "they come"} along without a file"
    if (p.playlists > 0) lines += "${plural(p.playlists, "playlist")} come${if (p.playlists == 1L) "s" else ""} along"
    if (p.spaces > 0) lines += "${plural(p.spaces, "space")} come${if (p.spaces == 1L) "s" else ""} along"
    lines += "Favourites and play counts come along"
    return lines
}

internal enum class TileKey { Added, Linked, Twice }

/** One tile of the review picture: [sentence] is the whole sentence, for screen readers. */
internal class ReviewTile(val key: TileKey, val count: Long, val label: String, val sentence: String)

/** A thing that comes along; [icon] is list, layers, heart or play. */
internal class ReviewChip(val icon: String, val text: String)

/** The review as a picture (the same as Windows' reviewPicture): [total] is null until the other library's size is known. Zero counts are left out. */
internal class ReviewPicture(val total: Long?, val tiles: List<ReviewTile>, val along: List<ReviewChip>, val check: List<String>)

internal fun reviewPicture(p: CombineDryRun, name: String): ReviewPicture {
    val facts = reviewFacts(p, name)
    fun sentence(part: String) = facts.firstOrNull { it.contains(part) } ?: ""
    val tiles = ArrayList<ReviewTile>()
    if (p.added > 0) tiles += ReviewTile(TileKey.Added, p.added, "added", sentence("added"))
    if (p.linked > 0) tiles += ReviewTile(TileKey.Linked, p.linked, "already there", sentence("counted once"))
    if (p.keptDuplicates > 0) tiles += ReviewTile(TileKey.Twice, p.keptDuplicates, "kept twice", sentence("twice here"))
    val along = ArrayList<ReviewChip>()
    if (p.playlists > 0) along += ReviewChip("list", plural(p.playlists, "playlist"))
    if (p.spaces > 0) along += ReviewChip("layers", plural(p.spaces, "space"))
    along += ReviewChip("heart", "favourites")
    along += ReviewChip("play", "play counts")
    val check = ArrayList<String>()
    if (p.differing > 0) check += sentence("different title")
    if (p.missingFiles > 0) check += sentence("could not be found")
    // the combined library: the other library plus what is new here (linked items are counted once)
    val total = if (p.otherItems >= 0) p.otherItems + p.added + p.keptDuplicates else null
    return ReviewPicture(total, tiles, along, check)
}

/** One review entry in plain words: names, never ids. */
internal fun reviewEntry(line: ReviewRow, name: String): String {
    val s = when {
        line.title != null -> "“${line.title}”"
        line.kind == "sameNamePlaylist" || line.kind == "sameNameSpace" -> "with the same name"
        else -> "One item"
    }
    return when (line.kind) {
        "manyMatches" -> "$s matches several items in $name’s library. It is counted once, with the oldest."
        "duplicateInS" -> "$s is the same file twice on this phone. Both are kept."
        "differingField" -> "$s: the ${line.field ?: "detail"} is different. $name’s stays."
        "missingFile" -> "$s could not be found on this phone. It comes along without a file."
        "sameNamePlaylist" -> if (line.title != null) "A playlist called $s is on both. Both are kept." else "A playlist with the same name is on both. Both are kept."
        "sameNameSpace" -> if (line.title != null) "A space called $s is on both. Both are kept." else "A space with the same name is on both. Both are kept."
        else -> "$s is listed for review."
    }
}

/** "Combining libraries · 1,230 of 1,450", "Combining paused · Laptop is not reachable", "Combined · Separate again until 20 November 2026". */
internal fun combineLine(s: CombineState, name: String, now: Long = System.currentTimeMillis()): String {
    val withCount = if (s.total > 0) " · ${count(s.done)} of ${count(s.total)}" else ""
    return when (phaseOf(s)) {
        CombinePhase.Idle -> ""
        CombinePhase.Looking -> "Looking at both libraries$withCount"
        CombinePhase.Ready -> "Ready to combine · nothing has changed yet"
        CombinePhase.Running -> "Combining libraries$withCount"
        CombinePhase.Paused -> (if (s.stage == "separating") "Separating paused" else "Combining paused") + " · ${pausedReason(s, name)}"
        CombinePhase.Failed -> (if (s.stage == "separating") "Separating stopped" else "Combining stopped") + " · ${s.error ?: "Something went wrong."}"
        CombinePhase.Separating -> "Separating libraries…"
        CombinePhase.Done -> {
            val until = s.keepUntil
            if (until != null && until > now) "Combined · Separate again until ${longDate(until)}" else "Combined"
        }
    }
}

/** Whether Separate again is still on offer for this combine. */
internal fun canSeparate(s: CombineState?, now: Long = System.currentTimeMillis()): Boolean =
    s != null && phaseOf(s) == CombinePhase.Done && (s.keepUntil ?: 0L) > now

/** The Separate dialog's sentences, from what goes back. */
internal class SeparateFacts(val goesBack: String, val addedSince: String?, val held: String?, val unreachable: String?, val keepLabel: String, val removeLabel: String)

internal fun separateFacts(p: SeparatePreview, name: String): SeparateFacts = SeparateFacts(
    goesBack = "${plural(p.brought, "item")} this phone brought ${if (p.brought == 1L) "is" else "are"} taken out of $name’s library. This phone goes back to its own library.",
    addedSince = if (p.addedSince > 0) "${plural(p.addedSince, "item")} added here since combining ${if (p.addedSince == 1L) "comes" else "come"} back to this phone’s own library." else null,
    held = if (p.heldByPeer > 0) "$name has copies of ${plural(p.heldByPeer, "item")} this phone brought." else null,
    unreachable = if (!p.peerReachable) "$name is not reachable. Separate anyway: $name keeps everything this phone brought." else null,
    keepLabel = "Keep them on $name",
    removeLabel = "Remove them",
)

/** How many days Separate again stays available, as a phrase. */
internal fun daysPhrase(days: Int): String = "${count(days.toLong())} ${if (days == 1) "day" else "days"}"
