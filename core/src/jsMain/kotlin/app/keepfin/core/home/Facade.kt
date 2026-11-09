@file:OptIn(ExperimentalJsExport::class)

package app.keepfin.core.home

import app.keepfin.core.json.JsonArray
import app.keepfin.core.json.JsonBool
import app.keepfin.core.json.JsonNumber
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue
import app.keepfin.core.json.jsonQuote
import app.keepfin.core.json.parseJson

// The JavaScript surface of Home's mixes and follow feed (see gui/shared/core/home.ts): JSON text in, JSON text out.

private fun JsonValue?.str(): String? = (this as? JsonString)?.value
private fun JsonValue?.num(): Double? = (this as? JsonNumber)?.value
private fun JsonValue?.flag(): Boolean = (this as? JsonBool)?.value == true
private fun JsonValue.field(name: String): JsonValue? = (this as? JsonObject)?.members?.get(name)
private fun items(text: String): List<JsonValue> = (parseJson(text) as? JsonArray)?.items ?: emptyList()
private fun strings(list: List<String>) = list.joinToString(",", "[", "]") { jsonQuote(it) }

@JsExport
fun homeMixesJson(songsJson: String, now: Double, utcOffsetMinutes: Int, limit: Int): String {
    val songs = items(songsJson).mapNotNull { v ->
        MixSong(
            id = v.field("id").str() ?: return@mapNotNull null,
            artist = v.field("artist").str(),
            plays = v.field("plays").num()?.toInt() ?: 0,
            lastPlayedAt = v.field("lastPlayedAt").num()?.toLong(),
            addedAt = v.field("addedAt").num()?.toLong() ?: 0L,
            favorite = v.field("favorite").flag(),
        )
    }
    return Mixes.build(songs, now.toLong(), utcOffsetMinutes, limit).joinToString(",", "[", "]") {
        "{\"id\":${jsonQuote(it.id)},\"title\":${jsonQuote(it.title)},\"blurb\":${jsonQuote(it.blurb)},\"ids\":${strings(it.ids)}}"
    }
}

private fun releases(text: String): List<FeedRelease> = items(text).mapNotNull { v ->
    FeedRelease(
        id = v.field("id").str() ?: return@mapNotNull null,
        artistKey = v.field("artistKey").str() ?: return@mapNotNull null,
        title = v.field("title").str().orEmpty(),
        foundAt = v.field("foundAt").num()?.toLong() ?: return@mapNotNull null,
        kept = v.field("kept").flag(),
    )
}

@JsExport
fun feedGroupsJson(releasesJson: String, now: Double): String =
    ReleaseFeed.group(releases(releasesJson), now.toLong()).joinToString(",", "[", "]") { "{\"label\":${jsonQuote(it.label)},\"ids\":${strings(it.ids)}}" }

@JsExport
fun feedArtistsWithNew(releasesJson: String, now: Double): Array<String> = ReleaseFeed.artistsWithNew(releases(releasesJson), now.toLong()).toTypedArray()

@JsExport
fun feedFresh(known: Array<String>, found: Array<String>, firstCheck: Boolean): Array<String> = ReleaseFeed.fresh(known.toSet(), found.toList(), firstCheck).toTypedArray()

@JsExport
fun feedAgeWords(foundAt: Double, now: Double): String = ReleaseFeed.ageWords(foundAt.toLong(), now.toLong())

/** The channel id for [key] among search-result artists (parallel arrays of names and channel ids), or an empty string. */
@JsExport
fun feedPickChannel(names: Array<String>, ids: Array<String>, key: String): String =
    ReleaseFeed.pickChannel(names.toList().zip(ids.toList()), key) ?: ""

/** A release title split into its kind (Album, Single, EP, or empty) and the title. */
@JsExport
fun feedSplitKind(title: String): Array<String> = ReleaseFeed.splitKind(title).let { arrayOf(it.first ?: "", it.second) }

/** The key an artist is followed under, or an empty string when the name has none. */
@JsExport
fun followKey(name: String): String = ReleaseFeed.artistKey(name) ?: ""
