package app.keepfin.core.sync

// Interpreting one operation: validation (spec 4.1) and the delta it produces. Validity depends only on the
// op's bytes and the semantics version, so every replica judges the same op the same way.

const val MAX_OP_BYTES = 256 * 1024
const val MAX_JSON_DEPTH = 32
const val MAX_LIST = 10_000
const val MAX_NAME = 200
const val MAX_PATH = 1024

sealed class Interpretation {
    /** A valid op of a known kind, with the delta it contributes. */
    class Applied(val delta: State) : Interpretation()

    /** An unknown kind (a newer semantics): keep, do not apply. */
    object Unknown : Interpretation()

    /** The body breaks the kind's schema. */
    class Invalid(val reason: String) : Interpretation()
}

private class Reject(val reason: String) : Exception(reason)

private fun reject(reason: String): Nothing = throw Reject(reason)

private fun Map<String, Jv>.uuid(key: String): String {
    val s = (this[key] as? JStr)?.value ?: reject("$key is missing")
    if (!isUuid(s)) reject("$key is not an id")
    return s
}

private fun Map<String, Jv>.name(key: String): String {
    val s = (this[key] as? JStr)?.value ?: reject("$key is missing")
    if (s.isEmpty() || s.length > MAX_NAME) reject("$key must be 1..$MAX_NAME characters")
    return s
}

private fun Map<String, Jv>.value(key: String): Jv = this[key] ?: reject("$key is missing")

private fun Map<String, Jv>.opIds(key: String, self: OpId?): Set<OpId> {
    val arr = (this[key] as? JArr)?.items ?: reject("$key is not a list")
    if (arr.size > MAX_LIST) reject("$key is too long")
    val out = LinkedHashSet<OpId>()
    for (x in arr) {
        val id = OpId.parse((x as? JStr)?.value ?: reject("$key holds a non-string")) ?: reject("$key holds a bad op id")
        if (id == self) reject("$key holds the op's own id")
        out.add(id)
    }
    return compactSet(out)
}

private fun Map<String, Jv>.fractionalKey(): String {
    val s = (this["key"] as? JStr)?.value ?: reject("key is missing")
    if (!Fractional.isValidKey(s)) reject("key is not a valid order key")
    return s
}

private fun checkPath(path: String) {
    if (path.isEmpty() || path.length > MAX_PATH) reject("path length")
    if (path[0] == '/' || path.contains('\\')) reject("path must be relative and use /")
    for (part in path.split('/')) {
        if (part.isEmpty() || part == "." || part == "..") reject("path segment")
    }
}

private fun Map<String, Jv>.creationFields(op: Op): Map<String, Register> {
    val raw = this["fields"] ?: return emptyMap()
    val obj = (raw as? JObj)?.members ?: reject("fields is not an object")
    val out = LinkedHashMap<String, Register>()
    for ((k, v) in obj) {
        if (k.isEmpty() || k.length > MAX_NAME) reject("field name length")
        out[k] = Register(op.hlc, op.id, v)
    }
    return compactMap(out)
}

private fun reg(op: Op, value: Jv) = Register(op.hlc, op.id, value)

/** Validates [this] and builds its delta. */
fun Op.interpret(): Interpretation {
    if (hlc.actor != id.actor) return Interpretation.Invalid("hlc actor differs from the op actor")
    if (kind !in Op.KINDS) return Interpretation.Unknown
    return try {
        Interpretation.Applied(buildDelta(this))
    } catch (e: Reject) {
        Interpretation.Invalid(e.reason)
    }
}

/** The delta of a valid, known op; the empty state for an unknown or invalid one. */
fun Op.toDelta(): State = (interpret() as? Interpretation.Applied)?.delta ?: State.EMPTY

private fun buildDelta(op: Op): State {
    val b = op.body
    val tag = singleSet(op.id)
    return when (op.kind) {
        "item.add" -> {
            val item = b.uuid("item")
            State(items = singleMap(item to ItemState(exists = OrSet(adds = tag), fields = b.creationFields(op))))
        }
        "item.set" -> {
            val item = b.uuid("item")
            val field = b.name("field")
            State(items = singleMap(item to ItemState(fields = singleMap(field to reg(op, b.value("value"))))))
        }
        "item.delete" -> {
            val item = b.uuid("item")
            val tags = b.opIds("observedTags", op.id)
            val heads = b.opIds("observedHeads", null)
            State(items = singleMap(item to ItemState(exists = OrSet(removes = tags), deletedHeads = heads)))
        }
        "item.revise" -> {
            val item = b.uuid("item")
            val content = (b["content"] as? JStr)?.value ?: reject("content is missing")
            if (!isContentId(content)) reject("content is not a content id")
            val size = (b["size"] as? JNum)?.value ?: reject("size is missing")
            if (size < 0) reject("size is negative")
            val parents = b.opIds("parents", op.id)
            State(items = singleMap(item to ItemState(revisions = singleMap(op.id to RevisionEvent(content, size, parents)))))
        }
        "item.place" -> {
            val item = b.uuid("item")
            val place = b.uuid("place")
            val value: Jv = when (val p = b["path"]) {
                is JNull -> JNull
                is JStr -> { checkPath(p.value); p }
                else -> reject("path is missing")
            }
            State(placements = singleMap(item to singleMap(place to reg(op, value))))
        }
        "set.add" -> {
            val set = b.name("set")
            val element = b.name("element")
            State(sets = singleMap(set to singleMap(element to OrSet(adds = tag))))
        }
        "set.remove" -> {
            val set = b.name("set")
            val element = b.name("element")
            val tags = b.opIds("observedTags", op.id)
            State(sets = singleMap(set to singleMap(element to OrSet(removes = tags))))
        }
        "playlist.add" -> {
            val p = b.uuid("playlist")
            State(playlists = singleMap(p to EntityState(OrSet(adds = tag), b.creationFields(op))))
        }
        "playlist.set" -> {
            val p = b.uuid("playlist")
            val field = b.name("field")
            State(playlists = singleMap(p to EntityState(fields = singleMap(field to reg(op, b.value("value"))))))
        }
        "playlist.delete" -> {
            val p = b.uuid("playlist")
            State(playlists = singleMap(p to EntityState(OrSet(removes = b.opIds("observedTags", op.id)))))
        }
        "space.add" -> {
            val s = b.uuid("space")
            State(spaces = singleMap(s to EntityState(OrSet(adds = tag), b.creationFields(op))))
        }
        "space.set" -> {
            val s = b.uuid("space")
            val field = b.name("field")
            State(spaces = singleMap(s to EntityState(fields = singleMap(field to reg(op, b.value("value"))))))
        }
        "space.delete" -> {
            val s = b.uuid("space")
            State(spaces = singleMap(s to EntityState(OrSet(removes = b.opIds("observedTags", op.id)))))
        }
        "entry.insert" -> {
            val playlist = b.uuid("playlist")
            val entry = b.uuid("entry")
            val item = b.uuid("item")
            val key = b.fractionalKey()
            State(
                entries = singleMap(entry to EntryFacts(playlist, item, op.id)),
                entryKey = singleMap(entry to reg(op, JStr(key))),
            )
        }
        "entry.move" -> {
            val entry = b.uuid("entry")
            State(entryKey = singleMap(entry to reg(op, JStr(b.fractionalKey()))))
        }
        "entry.remove" -> State(removedEntries = singleSet(b.uuid("entry")))
        "place.set" -> {
            val place = b.uuid("place")
            val field = b.name("field")
            State(places = singleMap(place to singleMap(field to reg(op, b.value("value")))))
        }
        "profile.set" -> {
            val field = b.name("field")
            State(profile = singleMap(field to reg(op, b.value("value"))))
        }
        // membership ops change who may write (see Membership), not the library state: their delta is empty
        "member.genesis", "member.grant", "member.revoke" -> {
            val r = parseMemberOp(op)
            if (r.error != null) reject(r.error)
            State.EMPTY
        }
        else -> reject("unknown kind")
    }
}
