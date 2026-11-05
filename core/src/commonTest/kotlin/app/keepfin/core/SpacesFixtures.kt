package app.keepfin.core

import app.keepfin.core.json.JsonArray
import app.keepfin.core.json.JsonBool
import app.keepfin.core.json.JsonNull
import app.keepfin.core.json.JsonNumber
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue
import app.keepfin.core.spaces.CollectionWords
import app.keepfin.core.spaces.Collections
import app.keepfin.core.spaces.Follow
import app.keepfin.core.spaces.LibraryCollection
import app.keepfin.core.spaces.LibraryItem
import app.keepfin.core.spaces.RuleSet
import app.keepfin.core.spaces.PlaceStatus
import app.keepfin.core.spaces.RuleWords
import app.keepfin.core.spaces.SmartRule
import app.keepfin.core.spaces.SmartRules
import app.keepfin.core.spaces.MusicVideoRows
import app.keepfin.core.spaces.PreviewWords
import app.keepfin.core.spaces.Songs
import app.keepfin.core.spaces.Space
import app.keepfin.core.spaces.Spaces
import app.keepfin.core.spaces.TaggedItem
import app.keepfin.core.spaces.VideoSource
import app.keepfin.core.spaces.Tags
import app.keepfin.core.spaces.WhereWords

// fixtures/engine/spaces: see SCHEMA.md there.

private const val DAY_MS = 86_400_000L

private fun JsonObject.string(key: String): String? = (members[key] as? JsonString)?.value

private fun JsonObject.number(key: String): Double? = (members[key] as? JsonNumber)?.value

private fun JsonObject.bool(key: String): Boolean? = (members[key] as? JsonBool)?.value

private fun JsonObject.obj(key: String): JsonObject? = members[key] as? JsonObject

private fun JsonObject.list(key: String): List<JsonValue> = (members[key] as? JsonArray)?.items ?: emptyList()

private fun JsonObject.strings(key: String): List<String> = list(key).mapNotNull { (it as? JsonString)?.value }

private fun List<String>.toJson(): JsonValue = arr(map { jstr(it) })

private fun spaceOf(raw: JsonObject) = Space(raw.string("id")!!, raw.string("type") ?: "folder", raw.bool("pinned"))

private fun ruleSetOf(r: JsonObject) = RuleSet(
    matchAll = r.string("match") == "all",
    rules = r.list("rules").map { (it as JsonObject).let { rule -> SmartRule(rule.string("field")!!, rule.strings("values"), rule.number("days") ?: 0.0) } },
    onlyKind = r.string("onlyKind"), excludeSpaces = r.strings("excludeSpaces"),
)

/** `names` is {field: {value: name}}: the names a screen looks up for collection and space ids. */
private fun namesOf(input: JsonObject): (String, String) -> String? = { field, value -> input.obj("names")?.obj(field)?.string(value) }

internal fun spacesHandler(input: JsonObject): JsonValue {
    return when (val op = input.string("op")) {
        "smart" -> {
            val now = input.number("now")!!.toLong()
            val membership = input.obj("collectionsOf")
            val items = input.list("items").map { (it as JsonObject).let { i ->
                LibraryItem(
                    id = i.string("id")!!, space = i.string("space") ?: "music", kind = i.string("kind") ?: "audio",
                    tags = i.strings("tags"), userTags = i.strings("userTags"), artist = i.string("artist"), albumArtist = i.string("albumArtist"), album = i.string("album"),
                    favorite = i.bool("favorite") == true, plays = (i.number("plays") ?: 0.0).toInt(),
                    addedAt = now - ((i.number("addedDaysAgo") ?: 0.0) * DAY_MS).toLong(), sourceUrl = i.string("sourceUrl"), sourceId = i.string("sourceId"),
                )
            } }
            val rules = ruleSetOf(input.obj("rules")!!)
            obj("ids" to items.filter { SmartRules.matches(rules, it, now) { id -> membership?.strings(id) ?: emptyList() } }.map { it.id }.toJson())
        }
        "normalizeTags" -> Tags.normalize(input.list("raw").map { (it as? JsonString)?.value }).toJson()
        "sourceTags" -> {
            val info = input.obj("info")!!
            fun values(key: String): List<String?> = when (val v = info.members[key]) {
                is JsonArray -> v.items.map { (it as? JsonString)?.value }
                is JsonString -> listOf(v.value)
                else -> emptyList()
            }
            Tags.normalize(values("genres") + values("genre") + values("tags") + values("categories")).toJson()
        }
        "scopeTags" -> {
            val items = input.list("items").map { (it as JsonObject).let { i -> TaggedItem(i.strings("tags"), i.strings("userTags")) } }
            arr(Tags.inScope(items, (input.number("minItems") ?: 2.0).toInt()).map { obj("tag" to jstr(it.tag), "count" to jnum(it.count), "user" to JsonBool(it.user)) })
        }
        "newKeys" -> obj("keys" to Collections.newKeys(input.strings("entries"), input.strings("seen")).toJson())
        "itemKey" -> jstr(Collections.itemKey(input.string("url")!!))
        "gate" -> jstr(Collections.gate(input.number("count")!!.toInt(), input.number("confirmAbove")!!.toInt(), input.bool("approved") == true).wire)
        "removed" -> {
            val members = input.list("members").map { it as JsonObject }
            val sourceIds = members.associate { it.string("id")!! to listOfNotNull(it.string("sourceId")) }
            obj("ids" to Collections.removedFromSource(members.map { it.string("id")!! }, { sourceIds.getValue(it) }, input.strings("discovered").toSet()).toJson())
        }
        "order" -> {
            val index = input.obj("index")!!
            obj("ids" to Collections.sortBySourceOrder(input.strings("ids")) { index.number(it)?.toInt() }.toJson())
        }
        "migratePlaylist" -> {
            val p = input.obj("playlist")!!
            val c = Collections.fromPlaylist(
                p.string("id")!!, p.string("name")!!, p.strings("itemIds"), (p.number("createdAt") ?: 0.0).toLong(), (p.number("updatedAt") ?: 0.0).toLong(), input.string("space")!!,
            )
            obj(
                "id" to jstr(c.id), "name" to jstr(c.name), "kind" to jstr(c.kind), "space" to jstr(c.space), "itemIds" to c.itemIds.toJson(),
                "seen" to c.seen.toJson(), "createdAt" to jnum(c.createdAt.toDouble()), "updatedAt" to jnum(c.updatedAt.toDouble()),
            )
        }
        "sameSource" -> obj("same" to JsonBool(Collections.canonicalUrl(input.string("a")!!) == Collections.canonicalUrl(input.string("b")!!)))
        "canonical" -> Collections.canonicalSource(input.string("url")!!).let { obj("url" to jstr(it.url), "id" to jstr(it.id)) }
        "collectionKind" -> {
            val url = input.string("url")!!
            obj("kind" to jstr(Collections.kindOf(url)), "infinite" to JsonBool(Collections.isInfinite(url)))
        }
        "defaultFollow" -> {
            val kind = input.string("kind")!!
            val f = Collections.defaultFollow(kind, input.string("url"), input.number("keepInSyncHours")?.toInt())
            // Own lists have no follow at all on Android; the core still answers with the off default.
            obj(
                "mode" to jstr(f.mode), "intervalHours" to jnum(f.intervalHours), "wifiOnly" to JsonBool(f.wifiOnly),
                "notify" to JsonBool(f.notify), "confirmAbove" to jnum(f.confirmAbove),
            )
        }
        "due" -> {
            val now = input.number("now")!!.toLong()
            val metered = input.bool("metered") == true
            val collections = input.list("collections").map { (it as JsonObject).let { c ->
                val f = c.obj("follow")
                LibraryCollection(
                    id = c.string("id")!!, name = c.string("id")!!, kind = c.string("kind") ?: "playlist", space = "music", itemIds = emptyList(),
                    sourceUrl = c.string("sourceUrl"),
                    follow = f?.let {
                        Follow(
                            it.string("mode")!!, (it.number("intervalHours") ?: 6.0).toInt(), it.bool("wifiOnly") ?: true, true, 20,
                            it.number("lastCheckedAt")?.toLong(), it.obj("pending") != null,
                        )
                    },
                )
            } }
            obj("ids" to collections.filter { Collections.isDue(it, now, metered) }.map { it.id }.toJson())
        }
        "home" -> {
            val spaces = input.list("spaces").map { spaceOf(it as JsonObject) }
            obj("shown" to Spaces.home(spaces).map { it.id }.toJson(), "more" to jnum(Spaces.more(spaces)))
        }
        "move" -> obj("order" to Spaces.move(input.strings("ids"), input.string("id")!!, input.bool("forward") == true).toJson())
        "reorder" -> {
            val result = Spaces.reorder(input.list("spaces").map { spaceOf(it as JsonObject) }, input.strings("ids"))
            if (result == null) obj("error" to JsonBool(true)) else obj("order" to result.map { it.id }.toJson())
        }
        "route" -> {
            val routes = input.obj("routes")!!
            jstr(Spaces.defaultSpace(input.string("url")!!, routes.string("music")!!, routes.string("other")!!, input.list("spaces").map { spaceOf(it as JsonObject) }))
        }
        "ruleWords" -> jstr(RuleWords.describe(ruleSetOf(input.obj("rules")!!), namesOf(input)))
        "tagRules" -> {
            val r = RuleWords.tagFilterRules(input.strings("tags"), input.string("match") == "all", input.string("space"), input.string("onlyKind"))
            val rules = r.rules.map { rule -> obj("field" to jstr(rule.field), "values" to rule.values.toJson()) }
            val members = arrayListOf("match" to jstr(if (r.matchAll) "all" else "any"), "rules" to arr(rules))
            if (r.onlyKind != null) members.add("onlyKind" to jstr(r.onlyKind))
            obj(*members.toTypedArray())
        }
        "tagName" -> jstr(RuleWords.tagFilterName(input.strings("tags")))
        "countLabel" -> jstr(
            RuleWords.countLabel(input.number("total")!!.toInt(), (input.number("audio") ?: 0.0).toInt(), (input.number("video") ?: 0.0).toInt(), (input.number("image") ?: 0.0).toInt(), (input.number("other") ?: 0.0).toInt()),
        )
        "origin" -> {
            val bySpace = LinkedHashMap<String, Int>()
            input.obj("bySpace")!!.members.forEach { (key, value) -> bySpace[key] = (value as JsonNumber).value.toInt() }
            jstr(RuleWords.origin(bySpace) { input.obj("names")?.string(it) })
        }
        "collectionWord" -> jstr(CollectionWords.kind(input.string("kind")!!))
        "everyWords" -> jstr(CollectionWords.everyWords(input.number("hours")!!.toInt()))
        "intervalLabel" -> jstr(CollectionWords.interval(input.number("hours")!!.toInt()))
        "followLine" -> CollectionWords.followLine(
            input.string("kind")!!, input.string("sourceUrl"), input.string("mode"), (input.number("intervalHours") ?: 6.0).toInt(), input.string("press") ?: "tap",
        )?.let { jstr(it) } ?: JsonNull
        "ago" -> jstr(CollectionWords.ago(wordsNow(input) - input.number("ageMs")!!.toLong(), wordsNow(input), ::wordsDate))
        "checkedLine" -> jstr(
            CollectionWords.checkedLine(
                input.number("ageMs")?.let { wordsNow(input) - it.toLong() }, input.string("lastError"), (input.number("newCount") ?: 0.0).toInt(),
                input.string("noun") ?: "song", wordsNow(input), ::wordsDate,
            ),
        )
        "rowSubtitle" -> jstr(
            CollectionWords.rowSubtitle(
                input.string("kind")!!, input.string("sourceUrl"), input.string("noun") ?: "song", input.number("present")!!.toInt(), input.string("mode"),
                input.number("ageMs")?.let { wordsNow(input) - it.toLong() }, wordsNow(input), ::wordsDate, input.strings("extras"),
            ),
        )
        "switchStart" -> jnum(Songs.switchStart(input.number("position") ?: Double.NaN, input.number("otherLength")))
        "musicVideoRows" -> {
            val sources = input.list("items").map { (it as JsonObject).let { i ->
                VideoSource(i.string("id")!!, i.string("kind") ?: "audio", i.string("title") ?: "", i.string("artist"), i.bool("hasVideo") == true, i.number("videoDuration"), i.number("videoSize"), (i.number("addedAt") ?: 0.0).toLong())
            } }
            obj("rows" to arr(MusicVideoRows.list(sources).map { r ->
                obj("id" to jstr(r.id), "itemId" to jstr(r.itemId), "title" to jstr(r.title), "artist" to jstr(r.artist), "duration" to jnum(r.duration), "size" to jnum(r.size), "addedAt" to jnum(r.addedAt.toDouble()))
            }))
        }
        "musicVideoRowId" -> jstr(MusicVideoRows.rowId(input.string("itemId")!!))
        "musicVideoItemId" -> jstr(MusicVideoRows.itemIdOf(input.string("rowId")!!))
        "musicVideoWords" -> arr(listOf(
            MusicVideoRows.LABEL, MusicVideoRows.SHELF, MusicVideoRows.SEE_ALL, MusicVideoRows.GO_TO_SONG, MusicVideoRows.DELETE,
            MusicVideoRows.DELETE_ASK, MusicVideoRows.JOIN_NOTE, MusicVideoRows.HAS_VIDEO_NOTE, MusicVideoRows.KEEP_LINK, MusicVideoRows.HAS_VIDEO_ERROR,
        ).map { jstr(it) })
        "previewWords" -> arr(listOf(PreviewWords.BOTH_LOOKING, PreviewWords.BOTH_NONE, PreviewWords.BOTH_FOUND, PreviewWords.BOTH_LINK).map { jstr(it) })
        "playlistWords" -> arr(listOf(PreviewWords.PLAYLIST_ADD, PreviewWords.PLAYLIST_NONE, PreviewWords.PLAYLIST_NEW).map { jstr(it) })
        "playlistLabel" -> jstr(PreviewWords.playlistLabel(input.string("name")))
        "extrasCount" -> jnum(
            PreviewWords.extrasCount(
                input.bool("subtitles") == true, input.bool("cut") == true, input.bool("sponsor") == true, input.bool("chapters") == true,
                input.bool("inFile") == true, input.bool("beside") == true, input.bool("queue") == true,
            ).toDouble(),
        )
        "extrasLabel" -> jstr(PreviewWords.extrasLabel(input.number("changed")!!.toInt()))
        "whereAgo" -> jstr(WhereWords.ago(wordsNow(input) - input.number("ageMs")!!.toLong(), wordsNow(input)))
        "whereHolds" -> jstr(
            WhereWords.holds(
                input.bool("all") == true, input.strings("spaces"), input.bool("favourites") == true, input.strings("playlists"),
                input.number("held")?.toLong(), input.number("of")?.toLong(),
            ),
        )
        "whereState" -> jstr(
            WhereWords.state(
                PlaceStatus(
                    input.string("state")!!, input.number("ageMs")?.let { wordsNow(input) - it.toLong() }, (input.number("done") ?: 0.0).toInt(),
                    (input.number("total") ?: 0.0).toInt(), input.string("reason"), input.bool("unplugged") == true, (input.number("count") ?: 0.0).toInt(),
                ),
                input.bool("toCopy") == true, wordsNow(input),
            ),
        )
        "whereReason" -> jstr(WhereWords.reason(input.string("text")!!))
        "whereSingle" -> jstr(WhereWords.single(input.string("kind")!!, input.number("count")!!.toLong(), input.string("place")!!))
        "whereOnePlace" -> jstr(WhereWords.onePlace(input.string("thisName")!!))
        "whereItemLine" -> {
            val places = input.list("places").map { it as JsonObject }
            jstr(WhereWords.itemLine(places.map { it.string("name")!! }, places.map { it.bool("offline") == true }))
        }
        else -> error("unknown spaces op $op")
    }
}

/** The time of the words fixtures: `now`, 1800000000000 ms unless the case says otherwise. */
private fun wordsNow(input: JsonObject): Long = input.number("now")?.toLong() ?: 1_800_000_000_000L

/** The fixtures' date: the screens write it their own way, the core only decides where it appears. */
private fun wordsDate(time: Long): String = "date $time"
