package app.keepfin.core.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LayoutTest {
    private fun ids(layout: PageLayout) = layout.sections.map { it.id }
    private val homeIds = ids(PageLayouts.defaults(Page.Home))

    @Test
    fun aPhoneKeepsTheCompactSize() {
        assertEquals(200.0, CardSizes.size(CardKind.Video, 360.0, 1.0))
        assertEquals(132.0, CardSizes.size(CardKind.Square, 599.0, 1.0))
    }

    @Test
    fun aWiderPageGrowsCardsGentlyUpToTheCeiling() {
        val sizes = listOf(700.0, 900.0, 1100.0, 1300.0, 3000.0).map { CardSizes.size(CardKind.Video, it, 1.0) }
        assertEquals(sizes.sorted(), sizes)
        assertEquals(256.0, sizes.last())
        // never a jump: 10 px more page never adds more than a pixel to a card
        val steps = (700..2000 step 10).map { CardSizes.size(CardKind.Square, it.toDouble(), 1.0) }
        assertTrue(steps.zipWithNext().all { (a, b) -> b - a in 0.0..1.0 })
    }

    @Test
    fun cardSizeScalesWithinItsLimits() {
        assertEquals(CardSizes.size(CardKind.Round, 1000.0, 1.0) * 1.25, CardSizes.size(CardKind.Round, 1000.0, 9.0), 0.01)
        assertEquals(CardSizes.size(CardKind.Round, 1000.0, 1.0) * 0.8, CardSizes.size(CardKind.Round, 1000.0, 0.1), 0.01)
        assertEquals(CardSizes.size(CardKind.Round, 1000.0, 1.0), CardSizes.size(CardKind.Round, 1000.0, Double.NaN))
    }

    @Test
    fun nothingSavedGivesTheDefaults() {
        assertEquals(PageLayouts.defaults(Page.Library), LayoutJson.readLayout(Page.Library, null))
        assertEquals(PageLayouts.defaults(Page.Home), LayoutJson.readLayout(Page.Home, "not json"))
    }

    @Test
    fun aSavedOrderIsKeptAndANewSectionReturnsToItsDefaultPlace() {
        val saved = listOf(
            SectionPref("photos", true, SectionStyle.Grid),
            SectionPref("continue", false, SectionStyle.Row),
            SectionPref("gone-in-this-version", true, SectionStyle.Row),
            SectionPref("continue", true, SectionStyle.Grid),
        )
        val layout = PageLayouts.normalize(Page.Home, saved)
        // every section once; the saved two keep their order and settings; the rest follow their defaults
        assertEquals(homeIds.toSet(), ids(layout).toSet())
        assertEquals(homeIds.size, layout.sections.size)
        assertTrue(ids(layout).indexOf("photos") < ids(layout).indexOf("continue"))
        assertFalse(layout.sections.first { it.id == "continue" }.visible)
        assertEquals(SectionStyle.Grid, layout.sections.first { it.id == "photos" }.style)
        // "mixes" comes after "continue" by default, so it is put right after it
        assertEquals(ids(layout).indexOf("continue") + 1, ids(layout).indexOf("mixes"))
    }

    @Test
    fun fixedSectionsKeepTheirStyle() {
        val layout = PageLayouts.normalize(Page.Library, listOf(SectionPref("spaces", true, SectionStyle.Grid)))
        assertEquals(SectionStyle.Row, layout.sections.first { it.id == "spaces" }.style)
        assertEquals(layout, PageLayouts.setStyle(layout, "spaces", SectionStyle.Grid))
        assertEquals(SectionStyle.Grid, PageLayouts.setStyle(layout, "albums", SectionStyle.Grid).sections.first { it.id == "albums" }.style)
    }

    @Test
    fun movingHidingAndStepping() {
        val home = PageLayouts.defaults(Page.Home)
        assertEquals(listOf("photos", "downloading"), ids(PageLayouts.move(home, "photos", "downloading", false)).take(2))
        assertEquals("downloading", ids(PageLayouts.move(home, "downloading", "photos", true)).last())
        assertEquals(home, PageLayouts.move(home, "photos", "missing", true))
        assertEquals(listOf("jump-back-in", "downloading"), ids(PageLayouts.step(home, "downloading", 1)).take(2))
        assertEquals(home, PageLayouts.step(home, "downloading", -1))
        assertFalse(PageLayouts.toggle(home, "mixes").sections.first { it.id == "mixes" }.visible)
    }

    @Test
    fun theSavedTextRoundTrips() {
        val layout = PageLayouts.toggle(PageLayouts.step(PageLayouts.defaults(Page.Home), "photos", -1), "continue")
        assertEquals(layout, LayoutJson.readLayout(Page.Home, LayoutJson.writeLayout(layout)))
        val prefs = CardPrefs(1.1, CardText.Title)
        assertEquals(prefs, LayoutJson.readCardPrefs(LayoutJson.writeCardPrefs(prefs)))
        assertEquals(CardPrefs(1.25, CardText.Full), LayoutJson.readCardPrefs("{\"scale\":4,\"text\":\"loud\"}"))
    }
}
