package app.keepfin.where

import app.keepfin.copies.CopyCardUi
import app.keepfin.copies.CopyHoldsUi
import app.keepfin.core.copies.CopyMode
import app.keepfin.copies.CopyStatus
import app.keepfin.core.spaces.PlaceStatus
import app.keepfin.core.spaces.WhereWords as CoreWhere
import app.keepfin.folder.sql.WhereHolding
import app.keepfin.folder.sql.WhereSingleRow
import app.keepfin.net.PairedDeviceUi
import app.keepfin.ui.Format
import kotlinx.coroutines.flow.StateFlow

// "Where your files are" (dev-docs/plans/WHERE-FILES.md): one row per place that holds part of this library, with what it holds in words,
// the size and its state, and one warning per kind of file that only one place has. The words are the shared core's (WhereWords), the same as on Windows, with "this phone".

enum class PlaceKind { THIS_DEVICE, DEVICE, DRIVE, CLOUD }

/** Where a tap on a row goes: a paired device opens its card in Devices; a drive or cloud copy opens its copy in Storage. */
sealed interface WhereTap {
    data class Device(val deviceId: String) : WhereTap
    data class Copy(val placeId: String) : WhereTap
}

data class WherePlace(
    val id: String,
    val kind: PlaceKind,
    /** The platform of a device (windows, android...), for its icon. */
    val platform: String?,
    val name: String,
    /** "Everything", "Music, Favourites", "Road trip and 2 more". */
    val holds: String,
    val bytes: Long,
    /** "up to date 2 min ago", "copying · 40 of 210", "waiting for Wi-Fi", "last copied 3 days ago · not plugged in", "needs a decision". */
    val state: String,
    val tap: WhereTap?,
    /** A copy's type: "Backup", "Mirror" or "Two-way". */
    val type: String? = null,
) {
    /** "12.4 GB · up to date". */
    val sizeAndState: String get() = Format.bytes(bytes) + " · " + state
}

enum class SingleKind { SONGS, VIDEOS, OTHER }

/** Items of one kind that only one place has. [onThisPhone] places can be fixed from here (Keep a copy…); [spaceIds] are the spaces to preselect. */
data class WhereSingle(val kind: SingleKind, val count: Long, val place: String, val onThisPhone: Boolean, val spaceIds: Set<String>) {
    val sentence: String get() = WhereWords.single(kind, count, place)
}

data class WhereFilesUi(
    val ready: Boolean = false,
    val places: List<WherePlace> = emptyList(),
    val singles: List<WhereSingle> = emptyList(),
    /** This phone is the only place and has files: "Everything is on this phone only…". */
    val onePlaceOnly: Boolean = false,
    /** What the music videos beside songs take on this phone; the Storage list shows it as its own line when above 0. */
    val musicVideoBytes: Long = 0L,
    /** The library's files (what the safety sentence is about; 0 means nothing to keep safe yet). */
    val files: Long = 0L,
)

/** One place that holds an item, for its details: [offline] places still count (the library remembers them). */
data class ItemPlace(val name: String, val offline: Boolean, val thisDevice: Boolean = false, /** The paired device behind this place, when it is one (Play from names the holder with it). */ val deviceId: String? = null)

/** The component's source: a state, and the places of one item (blocking: call off the main thread). */
interface WhereFilesSource {
    val state: StateFlow<WhereFilesUi>
    fun placesOf(itemId: String): List<ItemPlace>
}

/** A tap that crosses between Settings > Devices and Settings > Storage waits here until the other page has used it. */
sealed interface WhereIntent {
    data class Device(val deviceId: String) : WhereIntent
    data class Copy(val placeId: String) : WhereIntent
    data class Keep(val spaceIds: Set<String>) : WhereIntent
}

object WhereWords {
    const val TITLE = "Where your files are"
    const val THIS_PHONE = "this phone"
    const val KEEP = "Keep a copy…"

    /** What the page says when this phone is the only place. */
    val ONE_PLACE: String = CoreWhere.onePlace(THIS_PHONE)

    /** The type a copy shows beside its name, as on Windows. */
    fun type(m: CopyMode): String = when (m) {
        CopyMode.TwoWay -> "Two-way"
        CopyMode.Backup -> "Backup"
        CopyMode.Mirror -> "Mirror"
    }

    /** "Everything", "Music, Favourites", "Road trip and 2 more", or "Nothing yet". */
    fun holds(h: CopyHoldsUi, spaces: List<Pair<String, String>>, playlists: List<Pair<String, String>>): String = CoreWhere.holds(
        h.all, h.spaces.map { id -> spaces.firstOrNull { it.first == id }?.second ?: "A space" }, h.favourites,
        h.playlists.map { id -> playlists.firstOrNull { it.first == id }?.second ?: "A playlist" }, null, null,
    )

    /** What a place with [items] of [total] holds, counted from the placements: everything, or how many files. */
    fun heldOf(items: Long, total: Long): String = CoreWhere.holds(false, emptyList(), false, emptyList(), items, total)

    /** "just now", "2 min ago", "3 h ago", "yesterday", "3 days ago", "2 weeks ago". */
    fun ago(now: Long, at: Long): String = CoreWhere.ago(at, now)

    /** "12 videos are only on Desk PC", "1 song is only on this phone". */
    fun single(kind: SingleKind, n: Long, place: String): String =
        CoreWhere.single(when (kind) { SingleKind.SONGS -> "songs"; SingleKind.VIDEOS -> "videos"; SingleKind.OTHER -> "other" }, n, place)

    fun deviceState(p: PairedDeviceUi, now: Long): String = when {
        p.online && p.lastSync != null -> CoreWhere.state(PlaceStatus("upToDate", p.lastSync.at), false, now)
        p.online -> "catching up"
        else -> CoreWhere.state(PlaceStatus("offline", p.lastSeen ?: p.unreachableSince), false, now)
    }

    /** The state of a copy on a drive, a cloud account or a paired device. */
    fun copyState(c: CopyCardUi, now: Long): String {
        fun state(status: PlaceStatus) = CoreWhere.state(status, true, now)
        val waiting = (c.cloud?.waiting ?: c.peer?.waiting)?.let(CoreWhere::reason)
        return when (c.status) {
            CopyStatus.SYNCING -> state(PlaceStatus("copying", done = c.progress?.done ?: 0, total = c.progress?.total ?: 0))
            CopyStatus.UP_TO_DATE -> state(PlaceStatus("upToDate", c.lastVerifiedAt))
            CopyStatus.WAITING -> waiting ?: "${c.waiting} waiting"
            CopyStatus.PROBLEM -> c.problem?.short ?: "some files not copied"
            CopyStatus.REVIEW, CopyStatus.HELD, CopyStatus.PREVIEW, CopyStatus.REFUSED -> state(PlaceStatus("needsDecision"))
            CopyStatus.PAUSED -> "paused"
            CopyStatus.NEW -> "not copied yet"
            CopyStatus.DISCONNECTED -> when {
                c.cloud?.needsSignIn == true -> "sign in again"
                c.cloud != null || c.peer != null -> waiting ?: state(PlaceStatus("offline", c.lastVerifiedAt))
                else -> state(PlaceStatus("offline", c.lastVerifiedAt, unplugged = true))
            }
        }
    }

    /** The per-item line: "On: this phone, Desk PC (offline), Google Drive" or "Only on this phone". Null when no place holds it. */
    fun itemLine(places: List<ItemPlace>): String? =
        if (places.isEmpty()) null else CoreWhere.itemLine(places.map { it.name }, places.map { it.offline })
}

/** A stable 64-bit hash of an item id, for sets of ids a copy keeps without holding the strings. */
object WhereIds {
    fun hash(id: String): Long {
        var h = -3750763034362895579L
        for (c in id) { h = h xor c.code.toLong(); h *= 1099511628211L }
        return h
    }
}

/** Everything the words are made from. The engine reads these from the library, the copies and the devices; tests make them directly. */
class WhereInputs(
    val selfReplica: String,
    val libraryId: String?,
    val totalItems: Long,
    val holdings: List<WhereHolding>,
    /** Items held by exactly one replica, with the ones a copy also keeps already taken out. */
    val singles: List<WhereSingleRow>,
    val copies: List<CopyCardUi>,
    /** Per copy: items and bytes it keeps. */
    val copyHeld: Map<String, Pair<Long, Long>>,
    val devices: List<PairedDeviceUi>,
    val spaces: List<Pair<String, String>>,
    val playlists: List<Pair<String, String>>,
    val now: Long,
    val musicVideoBytes: Long = 0L,
)

object WhereCompose {
    /** The device that wrote the placements of a replica: by the actor it joined this library as. */
    fun deviceOf(h: WhereHolding, libraryId: String?, devices: List<PairedDeviceUi>): PairedDeviceUi? =
        devices.firstOrNull { d -> d.libraries.any { l -> (libraryId == null || l.libraryId == libraryId) && l.actor.isNotEmpty() && l.actor in h.actors } }

    fun kindOf(media: String) = when (media) { "audio" -> SingleKind.SONGS; "video" -> SingleKind.VIDEOS; else -> SingleKind.OTHER }

    fun build(i: WhereInputs): WhereFilesUi {
        val self = i.holdings.firstOrNull { it.replica == i.selfReplica }
        val selfItems = self?.items ?: 0L
        val places = ArrayList<WherePlace>()
        places.add(WherePlace("this", PlaceKind.THIS_DEVICE, "android", "This phone", WhereWords.heldOf(selfItems, i.totalItems), self?.bytes ?: 0L, "up to date", null))
        val deviceByReplica = HashMap<String, PairedDeviceUi>()
        for (h in i.holdings) if (h.replica != i.selfReplica) deviceOf(h, i.libraryId, i.devices)?.let { deviceByReplica[h.replica] = it }
        // a device with more than one replica in this library is one place
        val perDevice = LinkedHashMap<String, Triple<PairedDeviceUi, Long, Long>>()
        for (h in i.holdings) {
            val d = deviceByReplica[h.replica] ?: continue
            val old = perDevice[d.deviceId]
            perDevice[d.deviceId] = Triple(d, (old?.second ?: 0L) + h.items, (old?.third ?: 0L) + h.bytes)
        }
        for ((_, t) in perDevice.entries.sortedBy { it.value.first.name.lowercase() }) {
            val (d, items, bytes) = t
            if (items <= 0L) continue
            places.add(WherePlace("device:" + d.deviceId, PlaceKind.DEVICE, d.platform, d.name, WhereWords.heldOf(items, i.totalItems), bytes, WhereWords.deviceState(d, i.now), WhereTap.Device(d.deviceId)))
        }
        for (c in i.copies) {
            val held = i.copyHeld[c.id]
            val kind = if (c.cloud != null) PlaceKind.CLOUD else if (c.peer != null) PlaceKind.DEVICE else PlaceKind.DRIVE
            val peerDevice = c.peer?.let { p -> i.devices.firstOrNull { it.deviceId == p.deviceId } }
            val tap = if (c.peer != null) WhereTap.Device(c.peer.deviceId) else WhereTap.Copy(c.id)
            places.add(WherePlace("copy:" + c.id, kind, peerDevice?.platform, c.name, WhereWords.holds(c.holds, i.spaces, i.playlists), held?.second ?: 0L, WhereWords.copyState(c, i.now), tap, if (c.peer == null) WhereWords.type(c.mode) else null))
        }
        val nameOfReplica: (String) -> String = { r -> if (r == i.selfReplica) WhereWords.THIS_PHONE else deviceByReplica[r]?.name ?: "another device" }
        val grouped = LinkedHashMap<Pair<String, SingleKind>, Triple<Long, MutableSet<String>, Boolean>>()
        for (s in i.singles) {
            if (s.count <= 0L) continue
            val key = s.replica to kindOf(s.media)
            val old = grouped[key]
            val spaces = old?.second ?: LinkedHashSet()
            s.space?.let { spaces.add(it) }
            grouped[key] = Triple((old?.first ?: 0L) + s.count, spaces, s.replica == i.selfReplica)
        }
        val singles = grouped.entries
            .sortedWith(compareBy({ if (it.value.third) 0 else 1 }, { nameOfReplica(it.key.first) }, { it.key.second.ordinal }))
            .map { (k, v) -> WhereSingle(k.second, v.first, nameOfReplica(k.first), v.third, v.second) }
        return WhereFilesUi(true, places, singles, onePlaceOnly = places.size == 1 && selfItems > 0L, musicVideoBytes = i.musicVideoBytes, files = i.totalItems)
    }
}
