package app.keepfin.core.library

import app.keepfin.core.DirEntry
import app.keepfin.core.compareSubset
import app.keepfin.core.json.JsonArray
import app.keepfin.core.json.JsonBool
import app.keepfin.core.json.JsonNull
import app.keepfin.core.json.JsonNumber
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue
import app.keepfin.core.json.parseJson
import app.keepfin.core.listDirectory
import app.keepfin.core.readTextFile
import app.keepfin.core.requireFixturesRoot
import app.keepfin.core.sync.Admission
import app.keepfin.core.sync.Hlc
import app.keepfin.core.sync.JArr
import app.keepfin.core.sync.JBool
import app.keepfin.core.sync.JNull
import app.keepfin.core.sync.JNum
import app.keepfin.core.sync.JObj
import app.keepfin.core.sync.JStr
import app.keepfin.core.sync.Jv
import app.keepfin.core.sync.Op
import app.keepfin.core.sync.OpId
import app.keepfin.core.sync.OpLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs fixtures/library-mapping on every target: the operations the mapping makes for a change and the model it reads back
 * from views. The same files run through gui/desktop/store/mapping.ts (mapping.fixtures.test.ts), so the two are proven equal.
 */
class LibraryMappingTest {
    private fun mappingRoot(): String = requireFixturesRoot().replace('\\', '/').trimEnd('/').substringBeforeLast('/') + "/library-mapping"

    // ---------------------------------------------------------------------------------------------- JSON helpers

    private fun JsonValue?.obj(): JsonObject? = this as? JsonObject
    private fun JsonObject.str(key: String): String? = (members[key] as? JsonString)?.value
    private fun JsonObject.num(key: String): Double? = (members[key] as? JsonNumber)?.value
    private fun JsonObject.bool(key: String): Boolean? = (members[key] as? JsonBool)?.value
    private fun JsonObject.list(key: String): List<JsonValue>? = (members[key] as? JsonArray)?.items
    private fun JsonObject.strs(key: String): List<String>? = list(key)?.map { (it as JsonString).value }

    private fun toJv(v: JsonValue): Jv = when (v) {
        is JsonNull -> JNull
        is JsonBool -> JBool(v.value)
        is JsonString -> JStr(v.value)
        is JsonNumber -> JNum(v.value.toLong())
        is JsonArray -> JArr(v.items.map { toJv(it) })
        is JsonObject -> JObj(LinkedHashMap<String, Jv>().also { m -> for ((k, x) in v.members) m[k] = toJv(x) })
    }

    private fun fromJv(v: Jv): JsonValue = when (v) {
        is JNull -> JsonNull
        is JBool -> JsonBool(v.value)
        is JStr -> JsonString(v.value)
        is JNum -> JsonNumber(v.value.toDouble())
        is JArr -> JsonArray(v.items.map { fromJv(it) })
        is JObj -> JsonObject(LinkedHashMap<String, JsonValue>().also { m -> for ((k, x) in v.members) m[k] = fromJv(x) })
    }

    private fun strings(list: List<String>): JsonValue = JsonArray(list.map { JsonString(it) })

    // ---------------------------------------------------------------------------------------------- model <-> JSON

    private fun readItem(o: JsonObject) = LibItem(
        id = o.str("id")!!, space = o.str("space")!!, kind = o.str("kind") ?: "other", title = o.str("title") ?: "",
        artist = o.str("artist"), album = o.str("album"), albumArtist = o.str("albumArtist"),
        track = o.num("track"), disc = o.num("disc"), year = o.num("year"), genre = o.str("genre"),
        duration = o.num("duration"), width = o.num("width"), height = o.num("height"),
        chapters = o.list("chapters")?.map { (it as JsonObject).let { c -> LibChapter(c.num("start")!!, c.str("title")!!) } },
        gain = o.num("gain"), subtitles = o.strs("subtitles"), hasLyrics = o.bool("hasLyrics"),
        sourceUrl = o.str("sourceUrl"), sourceId = o.str("sourceId"), addedAt = o.num("addedAt"), tags = o.strs("tags"),
        lastPlayedAt = o.num("lastPlayedAt"), position = o.num("position"), watched = o.bool("watched"),
        favorite = o.bool("favorite") == true, userTags = o.strs("userTags") ?: emptyList(), plays = o.num("plays"),
        place = o.str("place"),
    )

    private fun writeItem(i: LibItem): JsonValue {
        val m = LinkedHashMap<String, JsonValue>()
        m["id"] = JsonString(i.id); m["space"] = JsonString(i.space); m["kind"] = JsonString(i.kind); m["title"] = JsonString(i.title)
        fun s(k: String, v: String?) { if (v != null) m[k] = JsonString(v) }
        fun n(k: String, v: Double?) { if (v != null) m[k] = JsonNumber(v) }
        fun b(k: String, v: Boolean?) { if (v != null) m[k] = JsonBool(v) }
        s("artist", i.artist); s("album", i.album); s("albumArtist", i.albumArtist)
        n("track", i.track); n("disc", i.disc); n("year", i.year); s("genre", i.genre)
        n("duration", i.duration); n("width", i.width); n("height", i.height)
        if (i.chapters != null) m["chapters"] = JsonArray(i.chapters.map { JsonObject(linkedMapOf("start" to JsonNumber(it.start), "title" to JsonString(it.title))) })
        n("gain", i.gain)
        if (i.subtitles != null) m["subtitles"] = strings(i.subtitles)
        b("hasLyrics", i.hasLyrics); s("sourceUrl", i.sourceUrl); s("sourceId", i.sourceId)
        m["addedAt"] = JsonNumber(i.addedAt ?: 0.0)
        if (i.tags != null) m["tags"] = strings(i.tags)
        n("lastPlayedAt", i.lastPlayedAt); n("position", i.position); b("watched", i.watched)
        m["favorite"] = JsonBool(i.favorite)
        m["userTags"] = strings(i.userTags)
        m["plays"] = JsonNumber(i.plays ?: 0.0)
        m["place"] = if (i.place != null) JsonString(i.place) else JsonNull
        return JsonObject(m)
    }

    private fun readCollection(o: JsonObject) = LibCollection(
        id = o.str("id")!!, name = o.str("name") ?: "", kind = o.str("kind") ?: "own", space = o.str("space")!!,
        itemIds = o.strs("itemIds") ?: emptyList(), seen = o.strs("seen") ?: emptyList(),
        createdAt = o.num("createdAt"), updatedAt = o.num("updatedAt"), coverItemId = o.str("coverItemId"),
        source = o.obj("source")?.let { s -> LibSource(s.str("url")!!, s.str("provider") ?: "", s.str("id")) },
        follow = o.obj("follow")?.let { f -> LibFollow(f.str("mode")!!, f.num("intervalHours")!!, f.bool("wifiOnly") == true, f.bool("notify") == true, f.num("confirmAbove")!!, f.num("pendingCount"), f.num("pendingAt")) },
        newItemIds = o.strs("newItemIds") ?: emptyList(), removedItemIds = o.strs("removedItemIds") ?: emptyList(),
    )

    private fun JsonObject.obj(key: String): JsonObject? = members[key] as? JsonObject

    private fun writeCollection(c: LibCollection): JsonValue {
        val m = LinkedHashMap<String, JsonValue>()
        m["id"] = JsonString(c.id); m["name"] = JsonString(c.name); m["kind"] = JsonString(c.kind); m["space"] = JsonString(c.space)
        m["itemIds"] = strings(c.itemIds); m["seen"] = strings(c.seen)
        m["createdAt"] = JsonNumber(c.createdAt ?: 0.0); m["updatedAt"] = JsonNumber(c.updatedAt ?: 0.0)
        if (c.coverItemId != null) m["coverItemId"] = JsonString(c.coverItemId)
        if (c.source != null) {
            val s = LinkedHashMap<String, JsonValue>()
            s["url"] = JsonString(c.source.url); s["provider"] = JsonString(c.source.provider)
            if (c.source.id != null) s["id"] = JsonString(c.source.id)
            m["source"] = JsonObject(s)
        }
        if (c.follow != null) {
            val f = LinkedHashMap<String, JsonValue>()
            f["mode"] = JsonString(c.follow.mode); f["intervalHours"] = JsonNumber(c.follow.intervalHours)
            f["wifiOnly"] = JsonBool(c.follow.wifiOnly); f["notify"] = JsonBool(c.follow.notify); f["confirmAbove"] = JsonNumber(c.follow.confirmAbove)
            if (c.follow.pendingCount != null) f["pendingCount"] = JsonNumber(c.follow.pendingCount)
            if (c.follow.pendingAt != null) f["pendingAt"] = JsonNumber(c.follow.pendingAt)
            m["follow"] = JsonObject(f)
        }
        m["newItemIds"] = strings(c.newItemIds); m["removedItemIds"] = strings(c.removedItemIds)
        return JsonObject(m)
    }

    private fun readSpace(o: JsonObject) = LibSpace(
        id = o.str("id")!!, name = o.str("name") ?: o.str("id")!!, kind = o.str("kind") ?: "mixed", folder = o.str("folder") ?: "",
        linked = o.bool("linked") == true, layout = o.str("layout") ?: "flat", naming = o.str("naming") ?: "title",
        skipExisting = o.bool("skipExisting") == true, ignore = o.strs("ignore"), scanFolder = o.bool("scanFolder"),
        type = o.str("type") ?: "folder", color = o.str("color"), icon = o.str("icon"), pinned = o.bool("pinned"),
        rules = o.members["rules"]?.let { toJv(it) },
    )

    private fun writeSpace(s: LibSpace): JsonValue {
        val m = LinkedHashMap<String, JsonValue>()
        m["id"] = JsonString(s.id); m["name"] = JsonString(s.name); m["kind"] = JsonString(s.kind); m["folder"] = JsonString(s.folder)
        m["linked"] = JsonBool(s.linked); m["layout"] = JsonString(s.layout); m["naming"] = JsonString(s.naming)
        m["skipExisting"] = JsonBool(s.skipExisting)
        if (s.ignore != null) m["ignore"] = strings(s.ignore)
        if (s.scanFolder != null) m["scanFolder"] = JsonBool(s.scanFolder)
        m["type"] = JsonString(s.type)
        if (s.color != null) m["color"] = JsonString(s.color)
        if (s.icon != null) m["icon"] = JsonString(s.icon)
        if (s.pinned != null) m["pinned"] = JsonBool(s.pinned)
        if (s.rules != null) m["rules"] = fromJv(s.rules)
        return JsonObject(m)
    }

    private fun readModel(o: JsonObject): LibModel {
        val routes = o.obj("routes")!!
        return LibModel(
            items = o.list("items")?.map { readItem(it as JsonObject) } ?: emptyList(),
            collections = o.list("collections")?.map { readCollection(it as JsonObject) } ?: emptyList(),
            spaces = o.list("spaces")?.map { readSpace(it as JsonObject) } ?: emptyList(),
            routes = LibRoutes(routes.str("music")!!, routes.str("other")!!),
        )
    }

    private fun writeModel(m: LibModel): JsonValue = JsonObject(
        linkedMapOf(
            "items" to JsonArray(m.items.map { writeItem(it) }),
            "collections" to JsonArray(m.collections.map { writeCollection(it) }),
            "spaces" to JsonArray(m.spaces.map { writeSpace(it) }),
            "routes" to JsonObject(linkedMapOf("music" to JsonString(m.routes.music), "other" to JsonString(m.routes.other))),
        ),
    )

    private fun writeDrafts(drafts: List<OpDraft>): JsonValue =
        JsonArray(drafts.map { JsonObject(linkedMapOf("kind" to JsonString(it.kind), "body" to fromJv(JObj(it.body)))) })

    // ---------------------------------------------------------------------------------------------- diff context

    private fun idList(o: JsonObject, key: String): Map<String, List<String>> =
        o.obj(key)?.members?.mapValues { (_, v) -> (v as JsonArray).items.map { (it as JsonString).value } } ?: emptyMap()

    private fun contextOf(input: JsonObject): DiffContext {
        val ids = ArrayList((input.strs("ids") ?: emptyList()))
        val plays = input.obj("ownPlays")?.members?.mapValues { (_, v) -> (v as JsonNumber).value.toLong() } ?: emptyMap()
        val songs = input.obj("songs")?.members?.mapValues { (_, v) ->
            (v as JsonArray).items.map { s -> (s as JsonObject).let { Song(it.str("entry")!!, it.str("item")!!, it.str("key")!!) } }
        } ?: emptyMap()
        val obs = input.obj("observed")
        val items = obs?.obj("items")?.members?.mapValues { (_, v) -> (v as JsonObject).let { ObservedTags(it.strs("tags") ?: emptyList(), it.strs("heads") ?: emptyList()) } } ?: emptyMap()
        val members = obs?.obj("members")?.members?.mapValues { (_, v) ->
            (v as JsonObject).members.mapValues { (_, t) -> (t as JsonArray).items.map { (it as JsonString).value } }
        } ?: emptyMap()
        val observed = ObservedAnswer(items, if (obs != null) idList(obs, "playlists") else emptyMap(), if (obs != null) idList(obs, "spaces") else emptyMap(), members)
        return DiffContext(
            actor = input.str("actor")!!, replicaId = input.str("replicaId")!!,
            newId = { if (ids.isEmpty()) fail("the case needs more ids than it lists") else ids.removeAt(0) },
            ownPlays = { plays[it] ?: 0L }, songs = { songs[it] ?: emptyList() }, observed = observed,
        )
    }

    // ---------------------------------------------------------------------------------------------- the cases

    private fun same(actual: JsonValue, expected: JsonValue, label: String, failures: MutableList<String>) {
        compareSubset(actual, expected, label, failures)
        compareSubset(expected, actual, "$label (reverse)", failures)
    }

    /** Applies drafts to a log as one actor and returns the views text. */
    private fun apply(log: OpLog, actor: String, startSeq: Long, drafts: List<OpDraft>): Long {
        var seq = startSeq
        var hlc: Hlc? = null
        val log0 = log.maxHlc
        if (log0 != null) hlc = log0
        var now = 1_700_000_000_000L + startSeq * 1000
        for (d in drafts) {
            hlc = Hlc.tick(hlc, now++, actor)
            val text = Op(OpId(actor, seq), hlc, d.kind, d.body).encode()
            val r = log.admit(text)
            assertTrue(r is Admission.Admitted, "${d.kind} was not admitted: $r")
            seq++
        }
        return seq
    }

    @Test
    fun fixtures() {
        val root = mappingRoot()
        val files = listDirectory(root).filter { it.name.endsWith(".json") }.sortedBy { it.name }
        assertTrue(files.isNotEmpty(), "no fixtures in $root")
        val failures = ArrayList<String>()
        var ran = 0
        var roundTrips = 0
        for (file: DirEntry in files) {
            val doc = parseJson(readTextFile("$root/${file.name}")) as JsonObject
            for (case in (doc.members.getValue("cases") as JsonArray).items) {
                val c = case as JsonObject
                val name = "${file.name}/${c.str("name")}"
                val input = c.obj("input")!!
                val expected = c.members.getValue("expected")
                ran++
                try {
                    when (input.str("op")) {
                        "diff" -> {
                            val before = input.obj("before")?.let { readModel(it) }
                            val after = readModel(input.obj("after")!!)
                            val ctx = contextOf(input)
                            val drafts = LibraryMapping.diff(before, after, ctx)
                            same(writeDrafts(drafts), expected, name, failures)
                            if (input.bool("roundtrip") == true && before == null) {
                                val log = OpLog()
                                apply(log, input.str("actor")!!, 1, drafts)
                                val views = LibraryMapping.parseViews(log.viewsJson())
                                assertTrue(views != null, "$name: views did not parse")
                                val back = LibraryMapping.project(views, input.str("replicaId")!!)
                                same(writeModel(back), writeModel(after), "$name (round trip)", failures)
                                val again = LibraryMapping.diff(back, after, contextOf(input))
                                if (again.isNotEmpty()) failures.add("$name: a second diff found ${again.size} change(s), first ${again[0].kind}")
                                roundTrips++
                            }
                        }
                        "project" -> {
                            val model = LibraryMapping.project(toJv(input.members.getValue("views")), input.str("replicaId")!!)
                            same(writeModel(model), expected, name, failures)
                        }
                        else -> failures.add("$name: unknown op")
                    }
                } catch (e: AssertionError) {
                    failures.add("$name: ${e.message}")
                }
            }
        }
        println("fixtures library-mapping: $ran ran, $roundTrips round trips, ${failures.size} failed")
        assertEquals(0, failures.size, failures.joinToString("\n"))
        assertTrue(ran >= 1)
    }
}
