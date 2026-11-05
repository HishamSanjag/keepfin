package app.keepfin.core

import app.keepfin.core.json.JsonBool
import app.keepfin.core.json.JsonNull
import app.keepfin.core.json.JsonNumber
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue
import app.keepfin.core.lyrics.Lyrics
import app.keepfin.core.lyrics.LyricsQuery

// fixtures/engine/lyrics: see SCHEMA.md there.

private fun JsonObject.text(key: String): String? = (members[key] as? JsonString)?.value
private fun JsonObject.num(key: String): Double? = (members[key] as? JsonNumber)?.value

internal fun lyricsHandler(input: JsonObject): JsonValue {
    val query = LyricsQuery(input.text("title") ?: "", input.text("artist") ?: "", input.text("album"), input.num("duration"))
    return when (val op = input.text("op")) {
        "normalize" -> jstr(Lyrics.normalizeKey(input.text("value")!!))
        "clean" -> jstr(Lyrics.cleanTitle(input.text("title")!!, input.text("artist")))
        "getUrl" -> jstr(Lyrics.getUrl(query))
        "searchUrl" -> jstr(Lyrics.searchUrl(query))
        "exact" -> jstr(Lyrics.exactText(show(input.members.getValue("record"))))
        "search" -> jstr(Lyrics.searchText(query, show(input.members.getValue("records"))))
        "retry" -> {
            val status = input.members["status"].let { if (it == null || it is JsonNull) null else (it as JsonNumber).value.toInt() }
            jnum(Lyrics.retryAfterMs(status, input.num("attempt")!!.toInt())?.toDouble())
        }
        "busy" -> JsonBool(Lyrics.isBusy(input.members["status"].let { if (it == null || it is JsonNull) null else (it as JsonNumber).value.toInt() }))
        "outcome" -> jstr(Lyrics.outcome((input.members["found"] as JsonBool).value, (input.members["gaveUpOnBusy"] as JsonBool).value))
        "isTimed" -> JsonBool(Lyrics.isTimed(input.text("text")))
        "preferTimed" -> jstr(Lyrics.preferTimed(input.text("exact"), input.text("found")))
        "outcomeWords" -> jstr(Lyrics.outcomeWords(input.text("outcome")!!))
        "findWords" -> jstr(Lyrics.FIND)
        else -> error("unknown lyrics op $op")
    }
}
