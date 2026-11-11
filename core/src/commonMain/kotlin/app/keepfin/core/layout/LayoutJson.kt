package app.keepfin.core.layout

import app.keepfin.core.json.JsonArray
import app.keepfin.core.json.JsonBool
import app.keepfin.core.json.JsonNumber
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue
import app.keepfin.core.json.jsonQuote
import app.keepfin.core.json.parseJson

// The saved form of page layouts and card prefs, the same text on both apps. Anything unreadable falls back to the defaults.

object LayoutJson {
    private fun JsonValue?.field(name: String): JsonValue? = (this as? JsonObject)?.members?.get(name)
    private fun JsonValue?.str(): String? = (this as? JsonString)?.value

    private fun read(text: String?): JsonValue? = if (text.isNullOrBlank()) null else runCatching { parseJson(text) }.getOrNull()

    fun readLayout(page: Page, text: String?): PageLayout {
        val sections = (read(text).field("sections") as? JsonArray)?.items?.mapNotNull { v ->
            SectionPref(
                id = v.field("id").str() ?: return@mapNotNull null,
                visible = (v.field("visible") as? JsonBool)?.value ?: true,
                style = SectionStyle.of(v.field("style").str()) ?: SectionStyle.Row,
            )
        }
        return PageLayouts.normalize(page, sections)
    }

    fun writeLayout(layout: PageLayout): String =
        layout.sections.joinToString(",", "{\"page\":${jsonQuote(layout.page.wire)},\"sections\":[", "]}") {
            "{\"id\":${jsonQuote(it.id)},\"visible\":${it.visible},\"style\":${jsonQuote(it.style.wire)},\"canStyle\":${PageLayouts.canStyle(layout.page, it.id)}}"
        }

    fun readCardPrefs(text: String?): CardPrefs {
        val value = read(text)
        val scale = (value.field("scale") as? JsonNumber)?.value ?: 1.0
        return CardPrefs(CardSizes.clampScale(scale), CardText.of(value.field("text").str()))
    }

    fun writeCardPrefs(prefs: CardPrefs): String = "{\"scale\":${prefs.scale},\"text\":${jsonQuote(prefs.text.wire)}}"
}
