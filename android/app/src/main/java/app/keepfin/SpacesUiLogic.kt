package app.keepfin

import app.keepfin.core.spaces.CollectionWords
import app.keepfin.core.spaces.RuleWords
import app.keepfin.core.spaces.Spaces as CoreSpaces
import java.text.DateFormat
import java.util.Date

// Pure helpers behind the Spaces v2 screens (dev-docs/architecture/SPACES-V2.md). No Compose, no Android types: unit-tested on the JVM.

/** One of the eight palette colors as raw ARGB, tuned for light and dark themes. Contrast is checked in SpacesUiLogicTest. */
class ToneSpec(val container: Long, val accent: Long, val onAccent: Long)
class TonePalette(val light: ToneSpec, val dark: ToneSpec)

object SpaceTones {
    val palettes: Map<SpaceColor, TonePalette> = mapOf(
        SpaceColor.COBALT to TonePalette(ToneSpec(0xFFDCE5FB, 0xFF2B59D9, 0xFFFFFFFF), ToneSpec(0xFF243A6E, 0xFFA7C3FF, 0xFF0D2558)),
        SpaceColor.VIOLET to TonePalette(ToneSpec(0xFFE6E1F5, 0xFF5B45B0, 0xFFFFFFFF), ToneSpec(0xFF332C5A, 0xFFC3B6F5, 0xFF241A55)),
        SpaceColor.TEAL to TonePalette(ToneSpec(0xFFD5EBF2, 0xFF1A6F89, 0xFFFFFFFF), ToneSpec(0xFF1B3F4C, 0xFF8CCBE0, 0xFF053544)),
        SpaceColor.OLIVE to TonePalette(ToneSpec(0xFFE6EBCF, 0xFF5E6B14, 0xFFFFFFFF), ToneSpec(0xFF3A4015, 0xFFCDDB7A, 0xFF2A3000)),
        SpaceColor.AMBER to TonePalette(ToneSpec(0xFFFBEBC8, 0xFF8A5A00, 0xFFFFFFFF), ToneSpec(0xFF4A3810, 0xFFF5C763, 0xFF3E2A00)),
        SpaceColor.RUST to TonePalette(ToneSpec(0xFFF8E3D6, 0xFFA34E1F, 0xFFFFFFFF), ToneSpec(0xFF4A2F1E, 0xFFF4B88F, 0xFF4A2308)),
        SpaceColor.BERRY to TonePalette(ToneSpec(0xFFF7DDE5, 0xFFB03A62, 0xFFFFFFFF), ToneSpec(0xFF4D2433, 0xFFF4A6C0, 0xFF5A1230)),
        SpaceColor.SLATE to TonePalette(ToneSpec(0xFFECE9E3, 0xFF5B6170, 0xFFFFFFFF), ToneSpec(0xFF232D3C, 0xFFB7C3D9, 0xFF1F2A3B)),
    )

    /** Spoken name of a swatch, for TalkBack. */
    fun label(color: SpaceColor): String = when (color) {
        SpaceColor.COBALT -> "Cobalt"; SpaceColor.VIOLET -> "Violet"; SpaceColor.TEAL -> "Teal"; SpaceColor.OLIVE -> "Olive"
        SpaceColor.AMBER -> "Amber"; SpaceColor.RUST -> "Rust"; SpaceColor.BERRY -> "Berry"; SpaceColor.SLATE -> "Slate"
    }

    fun label(icon: SpaceIcon): String = when (icon) {
        SpaceIcon.MUSIC -> "Music"; SpaceIcon.VIDEO -> "Video"; SpaceIcon.FOLDER -> "Folder"; SpaceIcon.STAR -> "Star"; SpaceIcon.HEART -> "Heart"
        SpaceIcon.MIC -> "Microphone"; SpaceIcon.BOOK -> "Book"; SpaceIcon.DUMBBELL -> "Workout"; SpaceIcon.MOON -> "Moon"; SpaceIcon.CHILD -> "Kids"
    }

    private fun lum(argb: Long): Double {
        fun f(c: Int): Double { val v = c / 255.0; return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }
        return 0.2126 * f(((argb shr 16) and 255).toInt()) + 0.7152 * f(((argb shr 8) and 255).toInt()) + 0.0722 * f((argb and 255).toInt())
    }

    /** WCAG contrast ratio of two ARGB colors. */
    fun contrast(a: Long, b: Long): Double {
        val x = lum(a)
        val y = lum(b)
        val hi = maxOf(x, y)
        val lo = minOf(x, y)
        return (hi + 0.05) / (lo + 0.05)
    }
}

/** Plain-words descriptions of smart rules ("Tag is lofi or chill") and the counts around them. The words are the shared core's, `RuleWords`. */
object RuleText {
    private val noNames: (SmartField, String) -> String? = { _, _ -> null }

    private fun names(nameOf: (SmartField, String) -> String?): (String, String) -> String? =
        { field, value -> SmartField.entries.firstOrNull { it.wire == field }?.let { nameOf(it, value) } }

    fun fieldLabel(field: SmartField): String = RuleWords.fieldLabel(field.wire)

    fun kindLabel(wire: String): String = RuleWords.kindLabel(wire)

    /** Words for one stored value: collection and space ids become names, kinds become Songs / Videos / Photos. */
    fun valueLabel(field: SmartField, value: String, nameOf: (SmartField, String) -> String? = noNames): String = RuleWords.valueLabel(field.wire, value, names(nameOf))

    fun describe(rule: SmartRule, nameOf: (SmartField, String) -> String? = noNames): String = RuleWords.describe(rule.toCore(), names(nameOf))

    /** "Tag is lofi or chill" for one rule; several rules are joined with "and" or "or" following the match mode. */
    fun describe(rules: SmartRules, nameOf: (SmartField, String) -> String? = noNames): String = RuleWords.describe(rules.toCore(), names(nameOf))

    /** "58 songs", "1 video", "12 items": the noun follows the kinds actually matched. */
    fun countLabel(items: List<LibraryItem>): String = RuleWords.countLabel(
        items.size, items.count { it.kind == LibraryItemKind.AUDIO }, items.count { it.kind == LibraryItemKind.VIDEO },
        items.count { it.kind == LibraryItemKind.IMAGE }, items.count { it.kind == LibraryItemKind.OTHER },
    )

    /** The same label from totals (a smart space is counted in the index, not listed). */
    fun countLabel(counts: Counts): String = RuleWords.countLabel(counts.total, counts.audio, counts.video, counts.image, counts.other)

    /** The same from counts per space. */
    fun origin(bySpace: Map<String, Int>, nameOf: (String) -> String?): String = RuleWords.origin(bySpace, nameOf)

    /** "From Music (51) and Downloads (7)": where the matches live, biggest first. */
    fun origin(items: List<LibraryItem>, nameOf: (String) -> String?): String = origin(items.groupingBy { it.space }.eachCount(), nameOf)
}

/** Hub (home grid) helpers. */
object HubLogic {
    /** Ids after moving [id] one place later ([forward]) or earlier; unchanged at the ends. */
    fun move(ids: List<String>, id: String, forward: Boolean): List<String> = CoreSpaces.move(ids, id, forward)

    /** Ids after dragging the card at [from] onto the card at [to]. */
    fun drag(ids: List<String>, from: Int, to: Int): List<String> {
        if (from !in ids.indices || to !in ids.indices || from == to) return ids
        return ids.toMutableList().also { it.add(to, it.removeAt(from)) }
    }

    /** The "3 new" badge on home counts items a check added. Items held back for a review are not in the library yet; the collection shows "Review N" instead. */
    fun newCount(collections: List<LibraryCollection>): Int = collections.sumOf { c -> c.newItemIds.size }

    /** New items waiting in the collections of one space (home card line "3 new · 31 songs"). */
    fun newInSpace(collections: List<LibraryCollection>, spaceId: String): Int = newCount(collections.filter { it.space == spaceId })

    /** Collections for the home shelf: those with news first, then the most recently changed. */
    fun shelf(collections: List<LibraryCollection>): List<LibraryCollection> =
        collections.sortedWith(compareByDescending<LibraryCollection> { it.newItemIds.size + (it.follow?.pending?.count ?: 0) > 0 }.thenByDescending { it.updatedAt })
}

/** Words for collections and their follow status: the shared core's (CollectionWords), with the phone's date and its own types mapped on. */
object CollectionText {
    private fun date(time: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(time))

    fun kindLabel(c: LibraryCollection): String = CollectionWords.kind(c.kind.wire)

    fun intervalLabel(hours: Int): String = CollectionWords.interval(hours)

    /** "Following · every 6 hours" / "Checks when you tap Check now" / "Not following". Null for lists that cannot be followed. */
    fun followLine(c: LibraryCollection): String? =
        CollectionWords.followLine(c.kind.wire, c.source?.url, c.follow?.mode?.wire, c.follow?.intervalHours ?: 6)

    /** "12 min ago", "3 h ago", "yesterday" or a date. */
    fun ago(timestamp: Long, now: Long = System.currentTimeMillis()): String = CollectionWords.ago(timestamp, now, ::date)

    /** Second line of the follow card: "Checked 12 min ago · 3 new songs added", an error, or "Not checked yet". */
    fun checkedLine(c: LibraryCollection, noun: String, now: Long = System.currentTimeMillis()): String =
        CollectionWords.checkedLine(c.follow?.lastCheckedAt, c.follow?.lastError, c.newItemIds.size, noun, now, ::date)

    /** "Playlist · 31 songs · following, checked 12 min ago" style subtitle of a collection row. */
    fun rowSubtitle(c: LibraryCollection, present: Int, noun: String, now: Long = System.currentTimeMillis()): String =
        CollectionWords.rowSubtitle(c.kind.wire, c.source?.url, noun, present, c.follow?.mode?.wire, c.follow?.lastCheckedAt, now, ::date)
}

/** The library picker of "Add to a space". */
object AddPicker {
    /** Item ids an add would act on: picked items plus the items of picked collections, deduped, minus those already in the target space. */
    fun resolve(items: List<LibraryItem>, collections: List<LibraryCollection>, itemIds: Set<String>, collectionIds: Set<String>, targetSpace: String): List<String> {
        val byId = items.associateBy { it.id }
        val all = LinkedHashSet<String>()
        all.addAll(itemIds)
        collections.filter { it.id in collectionIds }.forEach { all.addAll(it.itemIds) }
        return all.filter { id -> byId[id]?.let { it.space != targetSpace } == true }
    }

    fun summary(collectionCount: Int, items: List<LibraryItem>): String {
        val parts = listOfNotNull(
            "$collectionCount ${if (collectionCount == 1) "collection" else "collections"}".takeIf { collectionCount > 0 },
            RuleText.countLabel(items).takeIf { items.isNotEmpty() },
        )
        return parts.joinToString(" · ")
    }
}

/** Builds the smart rules behind "Save as smart space" in the tag filter. */
object TagSmart {
    /**
     * Any of the tags: one tag rule. All of the tags: one rule per tag, matched with "all". [onlyKind] keeps a filter to one kind.
     * [space]: the filter of one space keeps to that space (a music space's video songs stay in). Decided by the core: `RuleWords.tagFilterRules`.
     */
    fun rules(tags: Set<String>, all: Boolean, onlyKind: LibraryItemKind? = null, space: String? = null): SmartRules =
        RuleWords.tagFilterRules(tags.toList(), all, space, onlyKind?.wire).toApp()

    fun suggestedName(tags: Set<String>): String = RuleWords.tagFilterName(tags.toList())
}

/** Sort of the Collections list. Shelf order (news first, then recent activity) is the default. */
enum class CollectionSort(val label: String) { SHELF("Shelf order"), UPDATED("Recently updated"), NAME("Name"), KIND("Kind") }

/** Which kinds of collection the list shows. */
enum class CollectionFilter(val label: String) { ALL("All"), PLAYLISTS("Playlists"), ALBUMS("Albums"), CHANNELS("Channels"), YOURS("Yours") }

object CollectionListLogic {
    private fun kindOrder(k: CollectionKind) = when (k) { CollectionKind.PLAYLIST -> 0; CollectionKind.ALBUM -> 1; CollectionKind.CHANNEL -> 2; CollectionKind.OWN -> 3 }

    fun matches(c: LibraryCollection, filter: CollectionFilter) = when (filter) {
        CollectionFilter.ALL -> true
        CollectionFilter.PLAYLISTS -> c.kind == CollectionKind.PLAYLIST
        CollectionFilter.ALBUMS -> c.kind == CollectionKind.ALBUM
        CollectionFilter.CHANNELS -> c.kind == CollectionKind.CHANNEL
        CollectionFilter.YOURS -> c.kind == CollectionKind.OWN
    }

    /** [shelfOrder] is the list as the shelf shows it; SHELF keeps that order, the others re-sort the filtered list. */
    fun apply(shelfOrder: List<LibraryCollection>, sort: CollectionSort, filter: CollectionFilter): List<LibraryCollection> {
        val shown = shelfOrder.filter { matches(it, filter) }
        return when (sort) {
            CollectionSort.SHELF -> shown
            CollectionSort.UPDATED -> shown.sortedByDescending { it.updatedAt }
            CollectionSort.NAME -> shown.sortedBy { app.keepfin.ui.library.LibraryViews.fold(it.name) }
            CollectionSort.KIND -> shown.sortedWith(compareBy<LibraryCollection> { kindOrder(it.kind) }.thenBy { app.keepfin.ui.library.LibraryViews.fold(it.name) })
        }
    }
}
