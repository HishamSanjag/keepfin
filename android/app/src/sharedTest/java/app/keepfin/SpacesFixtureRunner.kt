package app.keepfin

import app.keepfin.core.spaces.CollectionWords
import app.keepfin.core.spaces.Collections
import app.keepfin.core.spaces.Follow
import app.keepfin.core.spaces.LibraryCollection
import app.keepfin.core.spaces.LibraryItem
import app.keepfin.core.spaces.PlaceStatus
import app.keepfin.core.spaces.MusicVideoRows
import app.keepfin.core.spaces.PreviewWords
import app.keepfin.core.spaces.RuleSet
import app.keepfin.core.spaces.RuleWords
import app.keepfin.core.spaces.SmartRule
import app.keepfin.core.spaces.SmartRules
import app.keepfin.core.spaces.Songs
import app.keepfin.core.spaces.Space
import app.keepfin.core.spaces.Spaces
import app.keepfin.core.spaces.TaggedItem
import app.keepfin.core.spaces.VideoSource
import app.keepfin.core.spaces.Tags
import app.keepfin.core.spaces.WhereWords
import org.json.JSONArray
import org.json.JSONObject

/*
 * Compiled into BOTH the JVM unit tests (src/test) and the device tests (src/androidTest), like ProvidersFixtureRunner.
 */

/**
 * Runs every case of fixtures/engine/spaces through the shared core (app.keepfin.core.spaces) exactly as
 * fixtures/engine/spaces/SCHEMA.md says. One implementation serves both apps, so cases limited to a platform (the metered-network
 * check) run too.
 */
object SpacesFixtureRunner {
    class Result(val ran: Int, val failures: List<String>)

    private const val DIR = "engine/spaces"
    private const val DAY_MS = 86_400_000L
    private const val WORDS_NOW = 1_800_000_000_000L

    /** The fixtures' date: the screens write it their own way, the core only decides where it appears. */
    private fun wordsDate(time: Long): String = "date $time"

    fun run(files: FixtureFiles): Result {
        val failures = ArrayList<String>()
        var ran = 0
        for (file in files.list(DIR).filter { it.endsWith(".json") }.sorted()) {
            val label = "spaces/${file.removeSuffix(".json")}"
            val cases = JSONObject(files.read("$DIR/$file")).getJSONArray("cases")
            val seen = HashSet<String>()
            for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index)
                val name = case.getString("name")
                require(seen.add(name)) { "duplicate case name \"$name\" in $label" }
                ran++
                val failure = try {
                    MusicFixtureRunner.assertSubset(execute(case.getJSONObject("input")), case.opt("expected"), name)
                    null
                } catch (e: Throwable) {
                    e.message ?: e::class.java.simpleName
                }
                if (failure != null) failures.add("$label: $name\n      $failure")
            }
        }
        return Result(ran, failures)
    }

    private fun JSONArray?.strings(): List<String> = this?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { _ -> !a.isNull(it) } } } ?: emptyList()
    private fun JSONArray?.objects(): List<JSONObject> = this?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
    private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
    private fun JSONObject.dbl(key: String): Double? = if (has(key) && !isNull(key)) getDouble(key) else null
    private fun JSONObject.bool(key: String): Boolean? = if (has(key) && !isNull(key)) getBoolean(key) else null
    private fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().also { o -> pairs.forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) } }
    private fun arr(items: Iterable<Any?>) = JSONArray().also { a -> items.forEach { a.put(it ?: JSONObject.NULL) } }

    private fun spaceOf(raw: JSONObject) = Space(raw.getString("id"), raw.str("type") ?: "folder", raw.bool("pinned"))

    private fun ruleSetOf(r: JSONObject) = RuleSet(
        matchAll = r.str("match") == "all",
        rules = r.optJSONArray("rules").objects().map { SmartRule(it.getString("field"), it.optJSONArray("values").strings(), it.dbl("days") ?: 0.0) },
        onlyKind = r.str("onlyKind"), excludeSpaces = r.optJSONArray("excludeSpaces").strings(),
    )

    private fun execute(input: JSONObject): Any? = when (val op = input.getString("op")) {
        "smart" -> {
            val now = input.getLong("now")
            val items = input.getJSONArray("items").objects().map { i ->
                LibraryItem(
                    id = i.getString("id"), space = i.str("space") ?: "music", kind = i.str("kind") ?: "audio", tags = i.optJSONArray("tags").strings(),
                    userTags = i.optJSONArray("userTags").strings(), artist = i.str("artist"), albumArtist = i.str("albumArtist"), album = i.str("album"),
                    favorite = i.bool("favorite") == true, plays = (i.dbl("plays") ?: 0.0).toInt(), addedAt = now - ((i.dbl("addedDaysAgo") ?: 0.0) * DAY_MS).toLong(),
                    sourceUrl = i.str("sourceUrl"), sourceId = i.str("sourceId"),
                )
            }
            val rules = ruleSetOf(input.getJSONObject("rules"))
            val membership = input.optJSONObject("collectionsOf")
            obj("ids" to arr(items.filter { SmartRules.matches(rules, it, now) { id -> membership?.optJSONArray(id).strings() } }.map { it.id }))
        }
        "normalizeTags" -> {
            val raw = input.getJSONArray("raw")
            arr(Tags.normalize((0 until raw.length()).map { if (raw.isNull(it)) null else raw.getString(it) }))
        }
        "sourceTags" -> {
            val info = input.getJSONObject("info")
            fun values(key: String): List<String?> = when (val v = info.opt(key)) {
                is JSONArray -> (0 until v.length()).map { if (v.isNull(it)) null else v.optString(it) }
                is String -> listOf(v)
                else -> emptyList()
            }
            arr(Tags.normalize(values("genres") + values("genre") + values("tags") + values("categories")))
        }
        "scopeTags" -> {
            val items = input.getJSONArray("items").objects().map { TaggedItem(it.optJSONArray("tags").strings(), it.optJSONArray("userTags").strings()) }
            arr(Tags.inScope(items, input.dbl("minItems")?.toInt() ?: 2).map { obj("tag" to it.tag, "count" to it.count, "user" to it.user) })
        }
        "newKeys" -> obj("keys" to arr(Collections.newKeys(input.getJSONArray("entries").strings(), input.getJSONArray("seen").strings())))
        "itemKey" -> Collections.itemKey(input.getString("url"))
        "gate" -> Collections.gate(input.getInt("count"), input.getInt("confirmAbove"), input.bool("approved") == true).wire
        "removed" -> {
            val members = input.getJSONArray("members").objects()
            val sourceIds = members.associate { it.getString("id") to listOfNotNull(it.str("sourceId")) }
            obj("ids" to arr(Collections.removedFromSource(members.map { it.getString("id") }, { sourceIds.getValue(it) }, input.getJSONArray("discovered").strings().toSet())))
        }
        "order" -> {
            val index = input.getJSONObject("index")
            obj("ids" to arr(Collections.sortBySourceOrder(input.getJSONArray("ids").strings()) { if (index.has(it)) index.getInt(it) else null }))
        }
        "migratePlaylist" -> {
            val p = input.getJSONObject("playlist")
            val c = Collections.fromPlaylist(p.getString("id"), p.getString("name"), p.getJSONArray("itemIds").strings(), p.optLong("createdAt"), p.optLong("updatedAt"), input.getString("space"))
            obj("id" to c.id, "name" to c.name, "kind" to c.kind, "space" to c.space, "itemIds" to arr(c.itemIds), "seen" to arr(c.seen), "createdAt" to c.createdAt, "updatedAt" to c.updatedAt)
        }
        "sameSource" -> obj("same" to (Collections.canonicalUrl(input.getString("a")) == Collections.canonicalUrl(input.getString("b"))))
        "canonical" -> Collections.canonicalSource(input.getString("url")).let { obj("url" to it.url, "id" to it.id) }
        "collectionKind" -> input.getString("url").let { obj("kind" to Collections.kindOf(it), "infinite" to Collections.isInfinite(it)) }
        "defaultFollow" -> {
            val f = Collections.defaultFollow(input.getString("kind"), input.str("url"), if (input.has("keepInSyncHours")) input.getInt("keepInSyncHours") else null)
            obj("mode" to f.mode, "intervalHours" to f.intervalHours, "wifiOnly" to f.wifiOnly, "notify" to f.notify, "confirmAbove" to f.confirmAbove)
        }
        "due" -> {
            val list = input.getJSONArray("collections").objects().map { c ->
                val f = c.optJSONObject("follow")
                LibraryCollection(
                    id = c.getString("id"), name = c.getString("id"), kind = c.str("kind") ?: "playlist", space = "music", itemIds = emptyList(), sourceUrl = c.str("sourceUrl"),
                    follow = f?.let { Follow(it.getString("mode"), it.dbl("intervalHours")?.toInt() ?: 6, it.bool("wifiOnly") ?: true, true, 20, it.dbl("lastCheckedAt")?.toLong(), it.optJSONObject("pending") != null) },
                )
            }
            obj("ids" to arr(list.filter { Collections.isDue(it, input.getLong("now"), input.optBoolean("metered", false)) }.map { it.id }))
        }
        "home" -> {
            val list = input.getJSONArray("spaces").objects().map(::spaceOf)
            obj("shown" to arr(Spaces.home(list).map { it.id }), "more" to Spaces.more(list))
        }
        "move" -> obj("order" to arr(Spaces.move(input.getJSONArray("ids").strings(), input.getString("id"), input.getBoolean("forward"))))
        "reorder" -> {
            val result = Spaces.reorder(input.getJSONArray("spaces").objects().map(::spaceOf), input.getJSONArray("ids").strings())
            if (result == null) obj("error" to true) else obj("order" to arr(result.map { it.id }))
        }
        "route" -> {
            val routes = input.getJSONObject("routes")
            Spaces.defaultSpace(input.getString("url"), routes.getString("music"), routes.getString("other"), input.getJSONArray("spaces").objects().map(::spaceOf))
        }
        "ruleWords" -> {
            val names = input.optJSONObject("names")
            RuleWords.describe(ruleSetOf(input.getJSONObject("rules"))) { field, value -> names?.optJSONObject(field)?.str(value) }
        }
        "tagRules" -> {
            val r = RuleWords.tagFilterRules(input.getJSONArray("tags").strings(), input.str("match") == "all", input.str("space"), input.str("onlyKind"))
            obj(
                "match" to if (r.matchAll) "all" else "any", "rules" to arr(r.rules.map { obj("field" to it.field, "values" to arr(it.values)) }),
                "onlyKind" to r.onlyKind,
            )
        }
        "tagName" -> RuleWords.tagFilterName(input.getJSONArray("tags").strings())
        "countLabel" -> RuleWords.countLabel(input.getInt("total"), input.optInt("audio"), input.optInt("video"), input.optInt("image"), input.optInt("other"))
        "origin" -> {
            val by = input.getJSONObject("bySpace")
            val names = input.getJSONObject("names")
            RuleWords.origin(by.keys().asSequence().associateWith { by.getInt(it) }) { names.str(it) }
        }
        "collectionWord" -> CollectionWords.kind(input.getString("kind"))
        "everyWords" -> CollectionWords.everyWords(input.getInt("hours"))
        "intervalLabel" -> CollectionWords.interval(input.getInt("hours"))
        "followLine" -> CollectionWords.followLine(
            input.getString("kind"), input.str("sourceUrl"), input.str("mode"), input.optInt("intervalHours", 6), input.str("press") ?: "tap",
        ) ?: JSONObject.NULL
        "ago" -> CollectionWords.ago(WORDS_NOW - input.getLong("ageMs"), WORDS_NOW, ::wordsDate)
        "checkedLine" -> CollectionWords.checkedLine(
            input.dbl("ageMs")?.let { WORDS_NOW - it.toLong() }, input.str("lastError"), input.optInt("newCount", 0), input.str("noun") ?: "song", WORDS_NOW, ::wordsDate,
        )
        "rowSubtitle" -> CollectionWords.rowSubtitle(
            input.getString("kind"), input.str("sourceUrl"), input.str("noun") ?: "song", input.getInt("present"), input.str("mode"),
            input.dbl("ageMs")?.let { WORDS_NOW - it.toLong() }, WORDS_NOW, ::wordsDate, input.optJSONArray("extras").strings(),
        )
        "switchStart" -> Songs.switchStart(input.dbl("position") ?: Double.NaN, input.dbl("otherLength"))
        "musicVideoRows" -> obj(
            "rows" to arr(
                MusicVideoRows.list(
                    input.optJSONArray("items").objects().map {
                        VideoSource(it.getString("id"), it.str("kind") ?: "audio", it.str("title") ?: "", it.str("artist"), it.bool("hasVideo") == true, it.dbl("videoDuration"), it.dbl("videoSize"), (it.dbl("addedAt") ?: 0.0).toLong())
                    },
                ).map { r ->
                    obj("id" to r.id, "itemId" to r.itemId, "title" to r.title, "artist" to r.artist, "duration" to r.duration, "size" to r.size, "addedAt" to r.addedAt.toDouble())
                },
            ),
        )
        "musicVideoRowId" -> MusicVideoRows.rowId(input.getString("itemId"))
        "musicVideoItemId" -> MusicVideoRows.itemIdOf(input.getString("rowId"))
        "musicVideoWords" -> arr(
            listOf(
                MusicVideoRows.LABEL, MusicVideoRows.SHELF, MusicVideoRows.SEE_ALL, MusicVideoRows.GO_TO_SONG, MusicVideoRows.DELETE,
                MusicVideoRows.DELETE_ASK, MusicVideoRows.JOIN_NOTE, MusicVideoRows.HAS_VIDEO_NOTE, MusicVideoRows.KEEP_LINK, MusicVideoRows.HAS_VIDEO_ERROR,
            ),
        )
        "previewWords" -> arr(listOf(PreviewWords.BOTH_LOOKING, PreviewWords.BOTH_NONE, PreviewWords.BOTH_FOUND, PreviewWords.BOTH_LINK))
        "playlistWords" -> arr(listOf(PreviewWords.PLAYLIST_ADD, PreviewWords.PLAYLIST_NONE, PreviewWords.PLAYLIST_NEW))
        "playlistLabel" -> PreviewWords.playlistLabel(input.str("name"))
        "extrasCount" -> PreviewWords.extrasCount(
            input.bool("subtitles") == true, input.bool("cut") == true, input.bool("sponsor") == true, input.bool("chapters") == true,
            input.bool("inFile") == true, input.bool("beside") == true, input.bool("queue") == true,
        )
        "extrasLabel" -> PreviewWords.extrasLabel(input.getInt("changed"))
        "whereAgo" -> WhereWords.ago(WORDS_NOW - input.getLong("ageMs"), WORDS_NOW)
        "whereHolds" -> WhereWords.holds(
            input.bool("all") == true, input.optJSONArray("spaces").strings(), input.bool("favourites") == true, input.optJSONArray("playlists").strings(),
            input.dbl("held")?.toLong(), input.dbl("of")?.toLong(),
        )
        "whereState" -> WhereWords.state(
            PlaceStatus(
                input.getString("state"), input.dbl("ageMs")?.let { WORDS_NOW - it.toLong() }, input.optInt("done", 0), input.optInt("total", 0),
                input.str("reason"), input.bool("unplugged") == true, input.optInt("count", 0),
            ),
            input.bool("toCopy") == true, WORDS_NOW,
        )
        "whereReason" -> WhereWords.reason(input.getString("text"))
        "whereSingle" -> WhereWords.single(input.getString("kind"), input.getLong("count"), input.getString("place"))
        "whereOnePlace" -> WhereWords.onePlace(input.getString("thisName"))
        "whereItemLine" -> input.getJSONArray("places").objects().let { places -> WhereWords.itemLine(places.map { it.getString("name") }, places.map { it.bool("offline") == true }) }
        else -> throw AssertionError("unknown spaces op $op")
    }
}
