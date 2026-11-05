package app.keepfin.core.sync

/** One operation. Unknown kinds are kept but not applied; invalid bodies are kept and reported (see [OpLog]). */
data class Op(val id: OpId, val hlc: Hlc, val kind: String, val body: Map<String, Jv>) {
    fun toJson(): Jv = JObj(
        mapOf(
            "body" to JObj(body),
            "hlc" to JStr(hlc.wire()),
            "id" to JStr(id.toString()),
            "kind" to JStr(kind),
        ),
    )

    /** The canonical text of this op (one segment line, without its LF). */
    fun encode(): String {
        // the same text as encodeCanonical(toJson()), written without building the envelope tree (keys: body, hlc, id, kind)
        val sb = StringBuilder(192)
        sb.append("{\"body\":")
        writeCanonical(sb, JObj(body))
        sb.append(",\"hlc\":")
        appendQuoted(sb, hlc.wire())
        sb.append(",\"id\":")
        appendQuoted(sb, id.toString())
        sb.append(",\"kind\":")
        appendQuoted(sb, kind)
        sb.append('}')
        return sb.toString()
    }

    /** The canonical bytes of this op (UTF-8, no BOM). */
    fun encodeBytes(): ByteArray = encode().encodeToByteArray()

    companion object {
        /** The kinds of SEMANTICS 1. */
        val KINDS = setOf(
            "item.add", "item.set", "item.delete", "item.revise", "item.place",
            "set.add", "set.remove",
            "playlist.add", "playlist.set", "playlist.delete",
            "space.add", "space.set", "space.delete",
            "entry.insert", "entry.move", "entry.remove",
            "place.set", "profile.set",
            "member.genesis", "member.grant", "member.revoke",
        )
    }
}

enum class OpProblem { MalformedUtf8, Bom, Syntax, BadNumber, NotCanonical, DuplicateKey, BadEnvelope }

sealed class OpDecode {
    class Ok(val op: Op) : OpDecode()
    class Bad(val problem: OpProblem) : OpDecode()
}

/** The op envelope: exactly body (object), hlc, id, kind. The id's actor must be a UUID. Bodies are judged later. */
internal fun opFromJson(v: Jv): Op? {
    val m = (v as? JObj)?.members ?: return null
    if (m.size != 4) return null
    val body = (m["body"] as? JObj)?.members ?: return null
    val hlc = Hlc.parse((m["hlc"] as? JStr)?.value ?: return null) ?: return null
    val id = OpId.parse((m["id"] as? JStr)?.value ?: return null) ?: return null
    val kind = (m["kind"] as? JStr)?.value ?: return null
    return Op(id, hlc, kind, body)
}

internal fun problemOf(p: CanonicalProblem): OpProblem = when (p) {
    CanonicalProblem.MalformedUtf8 -> OpProblem.MalformedUtf8
    CanonicalProblem.Bom -> OpProblem.Bom
    CanonicalProblem.Syntax -> OpProblem.Syntax
    CanonicalProblem.BadNumber -> OpProblem.BadNumber
    CanonicalProblem.NotCanonical -> OpProblem.NotCanonical
    CanonicalProblem.DuplicateKey -> OpProblem.DuplicateKey
}

/** Strict decode of one op line (no LF): canonical bytes and a valid envelope. */
fun decodeOp(bytes: ByteArray): OpDecode = when (val r = decodeCanonicalBytes(bytes)) {
    is CanonicalResult.Bad -> OpDecode.Bad(problemOf(r.problem))
    is CanonicalResult.Ok -> {
        val op = opFromJson(r.value)
        if (op == null) OpDecode.Bad(OpProblem.BadEnvelope) else OpDecode.Ok(op)
    }
}

fun decodeOp(text: String): OpDecode = decodeOp(text.encodeToByteArray())
