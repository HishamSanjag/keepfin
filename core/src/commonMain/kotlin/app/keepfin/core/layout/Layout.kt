package app.keepfin.core.layout

import app.keepfin.core.music.round2

// How media cards are sized and how Home and Library are arranged, decided once for both apps (dev-docs/plans/MEDIA-SURFACES.md).

/** The shapes a media card takes; each has its own size rule. A photo's size is its row height, every other kind's is its width. */
enum class CardKind(val wire: String) {
    Video("video"), Square("square"), Round("round"), Mix("mix"), Photo("photo"), Tile("tile");

    companion object {
        fun of(wire: String?): CardKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** What shows under a card. */
enum class CardText(val wire: String) {
    Full("full"), Title("title"), None("none");

    companion object {
        fun of(wire: String?): CardText = entries.firstOrNull { it.wire == wire } ?: Full
    }
}

/** A section shows as one sideways row or as a wrapping grid. */
enum class SectionStyle(val wire: String) {
    Row("row"), Grid("grid");

    companion object {
        fun of(wire: String?): SectionStyle? = entries.firstOrNull { it.wire == wire }
    }
}

enum class Page(val wire: String) {
    Home("home"), Library("library");

    companion object {
        fun of(wire: String?): Page? = entries.firstOrNull { it.wire == wire }
    }
}

data class CardPrefs(val scale: Double = 1.0, val text: CardText = CardText.Full)

data class SectionPref(val id: String, val visible: Boolean, val style: SectionStyle)

data class PageLayout(val page: Page, val sections: List<SectionPref>)

object CardSizes {
    const val MIN_SCALE = 0.8
    const val MAX_SCALE = 1.25
    /** Below this page width (px on Windows, dp on Android) cards keep their compact size: phones. */
    const val COMPACT_BELOW = 600.0
    private const val GROWTH_FROM = 640.0

    private class Rule(val floor: Double, val ceiling: Double, val slope: Double, val compact: Double)

    private val rules = mapOf(
        CardKind.Video to Rule(208.0, 256.0, 0.06, 200.0),
        CardKind.Square to Rule(148.0, 184.0, 0.045, 132.0),
        CardKind.Round to Rule(124.0, 152.0, 0.035, 112.0),
        CardKind.Mix to Rule(168.0, 208.0, 0.05, 148.0),
        CardKind.Photo to Rule(132.0, 164.0, 0.04, 120.0),
        CardKind.Tile to Rule(236.0, 300.0, 0.08, 156.0),
    )

    fun clampScale(scale: Double): Double = if (scale.isNaN()) 1.0 else scale.coerceIn(MIN_SCALE, MAX_SCALE)

    /**
     * A card's size on a page [pageWidth] wide: compact on a phone; otherwise it grows gently with the page up to a ceiling,
     * so a wider page mostly shows more cards and nothing ever jumps. [scale] is the person's Card size.
     */
    fun size(kind: CardKind, pageWidth: Double, scale: Double): Double {
        val rule = rules.getValue(kind)
        val base = if (pageWidth < COMPACT_BELOW) rule.compact else (rule.floor + (pageWidth - GROWTH_FROM) * rule.slope).coerceIn(rule.floor, rule.ceiling)
        return round2(base * clampScale(scale))
    }
}

/** One section a page can hold, in its default place. [fixedStyle] sections (lists, the spaces row) cannot switch between Row and Grid. */
private class SectionDef(val id: String, val style: SectionStyle, val visible: Boolean = true, val fixedStyle: Boolean = false)

object PageLayouts {
    private val home = listOf(
        SectionDef("downloading", SectionStyle.Row, fixedStyle = true),
        SectionDef("jump-back-in", SectionStyle.Grid, fixedStyle = true),
        SectionDef("continue", SectionStyle.Row),
        SectionDef("mixes", SectionStyle.Row),
        SectionDef("new-releases", SectionStyle.Row),
        SectionDef("added-videos", SectionStyle.Row),
        SectionDef("added-music", SectionStyle.Row),
        SectionDef("music-videos", SectionStyle.Row),
        SectionDef("photos", SectionStyle.Row),
    )
    private val library = listOf(
        SectionDef("spaces", SectionStyle.Row, fixedStyle = true),
        SectionDef("playlists", SectionStyle.Row),
        SectionDef("albums", SectionStyle.Row),
        SectionDef("artists", SectionStyle.Row),
        SectionDef("videos", SectionStyle.Row),
        SectionDef("photos", SectionStyle.Row),
        SectionDef("collections", SectionStyle.Row),
    )

    private fun defs(page: Page) = when (page) {
        Page.Home -> home
        Page.Library -> library
    }

    fun defaults(page: Page): PageLayout = PageLayout(page, defs(page).map { SectionPref(it.id, it.visible, it.style) })

    fun canStyle(page: Page, id: String): Boolean = defs(page).firstOrNull { it.id == id }?.fixedStyle == false

    /**
     * A saved layout made whole: the saved order kept, unknown and repeated sections dropped, fixed styles restored, and a
     * section this version added put back in its default place (after the nearest section that comes before it by default).
     */
    fun normalize(page: Page, saved: List<SectionPref>?): PageLayout {
        val defs = defs(page)
        val byId = defs.associateBy { it.id }
        val seen = mutableSetOf<String>()
        val kept = saved.orEmpty().mapNotNull { pref ->
            val def = byId[pref.id] ?: return@mapNotNull null
            if (!seen.add(pref.id)) return@mapNotNull null
            if (def.fixedStyle) pref.copy(style = def.style) else pref
        }.toMutableList()
        defs.forEachIndexed { index, def ->
            if (def.id in seen) return@forEachIndexed
            val before = defs.subList(0, index).map { it.id }
            val at = kept.indexOfLast { it.id in before } + 1
            kept.add(at, SectionPref(def.id, def.visible, def.style))
            seen.add(def.id)
        }
        return PageLayout(page, kept)
    }

    fun toggle(layout: PageLayout, id: String): PageLayout =
        layout.copy(sections = layout.sections.map { if (it.id == id) it.copy(visible = !it.visible) else it })

    fun setStyle(layout: PageLayout, id: String, style: SectionStyle): PageLayout =
        if (!canStyle(layout.page, id)) layout else layout.copy(sections = layout.sections.map { if (it.id == id) it.copy(style = style) else it })

    /** [id] dropped before or after [target]. */
    fun move(layout: PageLayout, id: String, target: String, after: Boolean): PageLayout {
        val moving = layout.sections.firstOrNull { it.id == id } ?: return layout
        if (id == target) return layout
        val rest = layout.sections.filter { it.id != id }.toMutableList()
        val at = rest.indexOfFirst { it.id == target }
        if (at < 0) return layout
        rest.add(if (after) at + 1 else at, moving)
        return layout.copy(sections = rest)
    }

    /** [id] one place earlier (-1) or later (1). */
    fun step(layout: PageLayout, id: String, delta: Int): PageLayout {
        val at = layout.sections.indexOfFirst { it.id == id }
        val to = at + delta
        if (at < 0 || to < 0 || to >= layout.sections.size) return layout
        val next = layout.sections.toMutableList()
        next[at] = layout.sections[to]
        next[to] = layout.sections[at]
        return layout.copy(sections = next)
    }
}
