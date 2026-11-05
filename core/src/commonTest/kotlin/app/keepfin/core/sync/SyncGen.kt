package app.keepfin.core.sync

import kotlin.random.Random

fun hex(rnd: Random, n: Int): String {
    val sb = StringBuilder()
    repeat(n) { sb.append("0123456789abcdef"[rnd.nextInt(16)]) }
    return sb.toString()
}

fun uuid(rnd: Random): String = hex(rnd, 8) + "-" + hex(rnd, 4) + "-" + hex(rnd, 4) + "-" + hex(rnd, 4) + "-" + hex(rnd, 12)

/** The join of the deltas of valid, known ops (invalid and unknown ops contribute nothing). */
fun stateOf(ops: Iterable<Op>): State {
    var s = State.EMPTY
    for (o in ops) s = s.join(o.toDelta())
    return s
}

fun logOf(ops: Iterable<Op>): OpLog {
    val log = OpLog()
    for (o in ops) log.admit(o)
    return log
}

/** Random operations over shared ids, from several actors with overlapping clocks. */
class OpGen(seed: Int, actorCount: Int) {
    val rnd = Random(seed)
    val actors = List(actorCount) { uuid(rnd) }
    val items = List(5) { uuid(rnd) }
    val playlists = List(3) { uuid(rnd) }
    val spaces = List(3) { uuid(rnd) }
    val places = List(3) { uuid(rnd) }
    private val entryIds = List(8) { uuid(rnd) }
    private val keys = listOf("a0", "a1", "a1", "a2", "Zz", "b00", "a0V")
    private val nextSeq = HashMap<String, Long>()
    private val lastHlc = HashMap<String, Hlc>()
    private val itemAdds = HashMap<String, MutableList<OpId>>()
    private val playlistAdds = HashMap<String, MutableList<OpId>>()
    private val spaceAdds = HashMap<String, MutableList<OpId>>()
    private val setAdds = HashMap<String, MutableList<OpId>>()
    private val setNames = listOf("favourites", "tags:x")
    private val elements = listOf("e1", "e2", "e3")
    private val plays = HashMap<String, Long>()
    private val revises = HashMap<String, MutableList<OpId>>()

    fun contentPool(item: String): List<String> = List(3) { "sha256:" + sha256Hex("$item/$it") }
    fun entryPlaylist(i: Int) = playlists[i % playlists.size]
    fun entryItem(i: Int) = items[(i * 3 + 1) % items.size]

    private fun <T> pick(list: List<T>): T = list[rnd.nextInt(list.size)]

    private fun subset(list: List<OpId>): List<Jv> = list.filter { rnd.nextInt(3) != 0 }.map { JStr(it.toString()) }

    private fun value(): Jv = when (rnd.nextInt(9)) {
        0 -> JStr("plain")
        1 -> JStr("café 😀")
        2 -> JStr("lone \uD800 high \uDC00 low \u0001 \" \\ \n")
        3 -> JNum(rnd.nextInt(2000) - 1000L)
        4 -> JNum(if (rnd.nextBoolean()) MAX_SAFE_INTEGER else -MAX_SAFE_INTEGER)
        5 -> JNull
        6 -> JBool(rnd.nextBoolean())
        7 -> JArr(listOf(JNum(1), JStr("x"), JNull))
        else -> jObj("b" to JNum(2), "a" to JStr("ÿ"), "퟿" to JBool(true), "" to JNull)
    }

    private fun clock(actor: String): Hlc {
        val now = 1_700_000_000_000L + rnd.nextInt(4)
        val last = lastHlc[actor]
        val other = actors.firstOrNull { it != actor && lastHlc[it] != null }
        val hlc = if (rnd.nextInt(6) == 0 && other != null) {
            Hlc.receive(last, lastHlc.getValue(other), now, actor)
        } else {
            Hlc.tick(last, now, actor)
        }
        lastHlc[actor] = hlc
        return hlc
    }

    private fun make(actor: String, kind: String, body: Map<String, Jv>, hlcActor: String = actor): Op {
        val seq = (nextSeq[actor] ?: 0L) + 1
        nextSeq[actor] = seq
        return Op(OpId(actor, seq), clock(actor).copy(actor = hlcActor), kind, body)
    }

    private fun creationFields(): Map<String, Jv> {
        if (rnd.nextInt(3) == 0) return emptyMap()
        val out = LinkedHashMap<String, Jv>()
        out["media"] = JStr(pick(listOf("audio", "video", "image", "other")))
        if (rnd.nextBoolean()) out["space"] = JStr(pick(spaces))
        if (rnd.nextBoolean()) out["title"] = value()
        return out
    }

    private fun fieldsBody(base: Map<String, Jv>): Map<String, Jv> {
        val f = creationFields()
        return if (f.isEmpty()) base else base + ("fields" to JObj(f))
    }

    fun next(): Op {
        val actor = pick(actors)
        val item = pick(items)
        val roll = rnd.nextInt(100)
        fun s(x: String) = JStr(x)
        val id = OpId(actor, (nextSeq[actor] ?: 0L) + 1)
        return when {
            roll < 10 -> {
                itemAdds.getOrPut(item) { ArrayList() }.add(id)
                make(actor, "item.add", fieldsBody(mapOf("item" to s(item))))
            }
            roll < 20 -> make(actor, "item.set", mapOf("item" to s(item), "field" to s(pick(listOf("title", "artist", "rating"))), "value" to value()))
            roll < 26 -> {
                val field = PLAYS_PREFIX + actor
                val n = (plays[item + field] ?: 0L) + 1
                plays[item + field] = n
                make(actor, "item.set", mapOf("item" to s(item), "field" to s(field), "value" to JNum(n)))
            }
            roll < 32 -> make(
                actor, "item.delete",
                mapOf(
                    "item" to s(item),
                    "observedTags" to JArr(subset(itemAdds[item] ?: emptyList())),
                    "observedHeads" to JArr(subset(revises[item] ?: emptyList())),
                ),
            )
            roll < 40 -> {
                val earlier = revises[item] ?: ArrayList<OpId>().also { revises[item] = it }
                val parents = earlier.filter { rnd.nextInt(3) == 0 }
                earlier.add(id)
                make(
                    actor, "item.revise",
                    mapOf("item" to s(item), "content" to s(pick(contentPool(item))), "size" to JNum(rnd.nextInt(1000).toLong()), "parents" to JArr(parents.map { s(it.toString()) })),
                )
            }
            roll < 45 -> make(actor, "item.place", mapOf("item" to s(item), "place" to s(pick(places)), "path" to if (rnd.nextInt(4) == 0) JNull else s("a/b" + rnd.nextInt(3) + ".mp3")))
            roll < 52 -> {
                val set = pick(setNames)
                val el = pick(elements)
                setAdds.getOrPut("$set|$el") { ArrayList() }.add(id)
                make(actor, "set.add", mapOf("set" to s(set), "element" to s(el)))
            }
            roll < 57 -> {
                val set = pick(setNames)
                val el = pick(elements)
                make(actor, "set.remove", mapOf("set" to s(set), "element" to s(el), "observedTags" to JArr(subset(setAdds["$set|$el"] ?: emptyList()))))
            }
            roll < 61 -> {
                val p = pick(playlists)
                playlistAdds.getOrPut(p) { ArrayList() }.add(id)
                make(actor, "playlist.add", fieldsBody(mapOf("playlist" to s(p))))
            }
            roll < 64 -> make(actor, "playlist.set", mapOf("playlist" to s(pick(playlists)), "field" to s(pick(listOf("name", "kind", "sourceUrl"))), "value" to value()))
            roll < 66 -> {
                val p = pick(playlists)
                make(actor, "playlist.delete", mapOf("playlist" to s(p), "observedTags" to JArr(subset(playlistAdds[p] ?: emptyList()))))
            }
            roll < 69 -> {
                val sp = pick(spaces)
                spaceAdds.getOrPut(sp) { ArrayList() }.add(id)
                make(actor, "space.add", fieldsBody(mapOf("space" to s(sp))))
            }
            roll < 71 -> make(actor, "space.set", mapOf("space" to s(pick(spaces)), "field" to s(pick(listOf("name", "colour"))), "value" to value()))
            roll < 73 -> {
                val sp = pick(spaces)
                make(actor, "space.delete", mapOf("space" to s(sp), "observedTags" to JArr(subset(spaceAdds[sp] ?: emptyList()))))
            }
            roll < 82 -> {
                val i = rnd.nextInt(entryIds.size)
                make(actor, "entry.insert", mapOf("playlist" to s(entryPlaylist(i)), "entry" to s(entryIds[i]), "item" to s(entryItem(i)), "key" to s(pick(keys))))
            }
            roll < 88 -> make(actor, "entry.move", mapOf("entry" to s(pick(entryIds)), "key" to s(pick(keys))))
            roll < 90 -> make(actor, "entry.remove", mapOf("entry" to s(pick(entryIds))))
            roll < 93 -> make(actor, "place.set", mapOf("place" to s(pick(places)), "field" to s(pick(listOf("mode", "name"))), "value" to value()))
            roll < 94 -> make(actor, "profile.set", mapOf("field" to s(pick(listOf("name", "colour"))), "value" to value()))
            roll < 95 -> make(actor, "future.thing", mapOf("x" to value()))
            roll < 96 -> make(actor, "item.set", mapOf("item" to JNum(5))) // wrong types
            roll < 97 -> make(actor, "entry.move", mapOf("entry" to s(pick(entryIds)), "key" to s("a00"))) // bad key
            roll < 98 -> make(actor, "item.place", mapOf("item" to s(item), "place" to s(pick(places)), "path" to s("../x"))) // bad path
            roll < 99 -> make(actor, "item.revise", mapOf("item" to s(item), "content" to s(pick(contentPool(item))), "size" to JNum(1), "parents" to JArr(listOf(s(id.toString()))))) // own id
            else -> make(actor, "profile.set", mapOf("field" to s("name"), "value" to JNull), hlcActor = pick(actors)) // may mismatch hlc actor
        }
    }

    fun generate(n: Int): List<Op> = List(n) { next() }
}
