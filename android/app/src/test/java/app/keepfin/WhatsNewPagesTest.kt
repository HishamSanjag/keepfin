package app.keepfin

import app.keepfin.ui.updates.WhatsNewPage
import app.keepfin.ui.updates.WhatsNewPages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsNewPagesTest {
    private val json = """
        {"version":"1.1.0","pages":[
          {"id":"photos","windows":{"title":"W","text":"w"},"android":{"title":"Save photos","text":"Tap a photo."}},
          {"id":"desktop-only","windows":{"title":"W","text":"w"}},
          {"id":"blank","android":{"title":"","text":"x"}},
          {"id":"mixes","android":{"title":" Mixes ","text":" On this phone. "}}
        ]}
    """.trimIndent()

    @Test fun keepsOnlyPagesWithAnAndroidEntryInOrder() {
        assertEquals(listOf(WhatsNewPage("photos", "Save photos", "Tap a photo."), WhatsNewPage("mixes", "Mixes", "On this phone.")), WhatsNewPages.parse(json, "1.1.0"))
    }

    @Test fun anotherVersionHasNoPages() = assertTrue(WhatsNewPages.parse(json, "1.2.0").isEmpty())

    @Test fun brokenOrEmptyTextHasNoPages() {
        assertTrue(WhatsNewPages.parse("", "1.1.0").isEmpty())
        assertTrue(WhatsNewPages.parse("{not json", "1.1.0").isEmpty())
        assertTrue(WhatsNewPages.parse("""{"version":"1.1.0"}""", "1.1.0").isEmpty())
        assertTrue(WhatsNewPages.parse("""{"version":"1.1.0","pages":[1,"x",null]}""", "1.1.0").isEmpty())
    }
}
