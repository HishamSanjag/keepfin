@file:OptIn(ExperimentalJsExport::class)

package app.keepfin.core.sync

// The JavaScript surface of the sync core (see gui/shared/core/sync.ts). It is string- and JSON-based: ops cross as
// canonical text, results as JSON text or short codes, byte arrays as Int8Array. Data problems come back as codes or
// JSON; programmer errors (an unparseable clock, misordered keys) throw.

@JsExport
fun syncFormat(): Int = FORMAT

@JsExport
fun syncSemantics(): Int = SEMANTICS

// ---- JSON helpers --------------------------------------------------------------------------------------------------

private fun json(v: Jv): String = encodeCanonical(v)

private fun strings(items: Collection<String>) = JArr(items.map { JStr(it) })

private fun parseObject(text: String, what: String): Map<String, Jv> =
    ((parseLoose(text) ?: throw IllegalArgumentException("$what is not valid JSON")) as? JObj)?.members
        ?: throw IllegalArgumentException("$what is not a JSON object")

private fun parseStrings(text: String, what: String): List<String> {
    val arr = (parseLoose(text) as? JArr)?.items ?: throw IllegalArgumentException("$what is not a JSON array")
    return arr.map { (it as? JStr)?.value ?: throw IllegalArgumentException("$what holds a non-string") }
}

private fun parseEquivocations(text: String): Map<String, Long> {
    val out = LinkedHashMap<String, Long>()
    for ((actor, v) in parseObject(text, "equivocations")) {
        out[actor] = (v as? JNum)?.value ?: throw IllegalArgumentException("equivocations holds a non-number")
    }
    return out
}

private fun coverageJson(m: Map<String, Long>) = JObj(m.mapValues { JNum(it.value) })

// ---- the log ---------------------------------------------------------------------------------------------------------

private fun code(a: Admission): String = when (a) {
    is Admission.Admitted -> "admitted"
    is Admission.Duplicate -> "duplicate"
    is Admission.Kept -> "kept"
    is Admission.Invalid -> "invalid:" + a.reason
    is Admission.Equivocation -> "equivocation"
    is Admission.SetAside -> "setAside"
    is Admission.SetAsideBy -> "setAside:" + a.reason
    is Admission.Covered -> "covered"
    is Admission.Malformed -> "malformed:" + a.problem.name
}

/** A decoded checkpoint: pass it to [syncLogFromCheckpoint]. [ok] is false (with [error]) when it was unusable. */
@JsExport
class JsSyncCheckpoint internal constructor(internal val checkpoint: Checkpoint?, val error: String?) {
    val ok: Boolean get() = checkpoint != null

    fun coverageJson(): String = json(coverageJson(checkpoint?.coverage ?: emptyMap()))

    fun maxHlc(): String? = checkpoint?.maxHlc?.wire()
}

@JsExport
class JsSyncLog internal constructor(private val log: OpLog) {
    /** Admits one canonical op line. Returns a code: admitted, duplicate, kept, invalid:<reason>, equivocation, setAside, covered, malformed:<problem>. */
    fun admit(opText: String, signer: String? = null): String = code(log.admit(opText, signer))

    fun admitBytes(bytes: ByteArray, signer: String? = null): String = code(log.admit(bytes, signer))

    /** The membership the member ops held so far define (see [membershipJson]). */
    fun membershipJson(): String = membershipJson(log.membership)

    fun versionVectorJson(): String = json(coverageJson(log.versionVector))

    fun pendingJson(): String = json(JObj(log.pending.mapValues { (_, seqs) -> JArr(seqs.sorted().map { JNum(it) }) }))

    fun setAsideJson(): String = json(coverageJson(log.setAside))

    fun findingsJson(): String = json(strings(allFindings().sorted()))

    fun maxHlc(): String? = log.maxHlc?.wire()

    /** The canonical text held for [opId] (`actor:seq`), or null. */
    fun encoded(opId: String): String? = OpId.parse(opId)?.let { log.encoded(it) }

    /** The canonical bytes of the state (the checkpoint body line), for comparing states exactly. */
    fun stateBodyBytes(): ByteArray {
        val sink = ByteSink()
        StateWriter.write(sink, log.liveState)
        return sink.buf.copyOf(sink.size)
    }

    fun checkpointBytes(library: String): ByteArray {
        installNativeSha()
        return encodeCheckpoint(log.checkpoint(library))
    }

    fun viewsJson(): String = log.viewsJson()

    private fun allFindings(): Set<String> = log.allFindings()

    /** `{all, items, playlists, spaces, sets, places, profile, findings}`: the keys touched since the last call. */
    fun takeTouchedJson(): String {
        val t = log.takeTouched()
        return json(
            jObj(
                "all" to JBool(t.all),
                "items" to strings(t.items),
                "playlists" to strings(t.playlists),
                "spaces" to strings(t.spaces),
                "sets" to strings(t.sets),
                "places" to strings(t.places),
                "profile" to JBool(t.profile),
                "findings" to JBool(t.findings),
            ),
        )
    }

    /** The partial views (JSON) of the keys in [touchedJson] (the shape [takeTouchedJson] returns). */
    fun viewsForJson(touchedJson: String): String {
        val m = parseObject(touchedJson, "touched")
        fun list(key: String): List<String> = (m[key] as? JArr)?.items?.map { (it as? JStr)?.value ?: throw IllegalArgumentException("touched.$key holds a non-string") } ?: emptyList()
        val touched = Touched(
            all = (m["all"] as? JBool)?.value == true,
            items = list("items"),
            playlists = list("playlists"),
            spaces = list("spaces"),
            sets = list("sets"),
            places = list("places"),
            profile = (m["profile"] as? JBool)?.value == true,
            findings = (m["findings"] as? JBool)?.value == true,
        )
        return log.partialViewsJson(touched)
    }

    /** What a remove or delete observes for the keys in [queryJson] (`{items?, playlists?, spaces?, setElements?: [[set, element]]}`). */
    fun observedJson(queryJson: String): String {
        val q = parseObject(queryJson, "query")
        fun list(key: String): List<String> = (q[key] as? JArr)?.items?.map { (it as? JStr)?.value ?: throw IllegalArgumentException("query.$key holds a non-string") } ?: emptyList()
        val pairs = ((q["setElements"] as? JArr)?.items ?: emptyList()).map {
            val pair = (it as? JArr)?.items
            val set = (pair?.getOrNull(0) as? JStr)?.value
            val el = (pair?.getOrNull(1) as? JStr)?.value
            if (set == null || el == null) throw IllegalArgumentException("query.setElements holds a bad pair")
            Pair(set, el)
        }
        val o = log.observed(list("items"), list("playlists"), list("spaces"), pairs)
        fun tags(ids: List<OpId>) = JArr(ids.map { JStr(it.toString()) })
        return json(
            jObj(
                "items" to JObj(o.items.mapValues { jObj("tags" to tags(it.value.tags), "heads" to tags(it.value.heads)) }),
                "playlists" to JObj(o.playlists.mapValues { tags(it.value) }),
                "spaces" to JObj(o.spaces.mapValues { tags(it.value) }),
                "setElements" to JObj(o.setElements.mapValues { (_, els) -> JObj(els.mapValues { tags(it.value) }) }),
            ),
        )
    }

    /** The shards whose part is out of date (all of them until each was written once), as a JSON array of names. */
    fun dirtyShardsJson(): String = json(strings(log.dirtyShards()))

    /** The part file of [shard] (marks it clean), or null for a name that is not a shard. */
    fun partBytes(library: String, shard: String): ByteArray? {
        installNativeSha()
        return log.partBytes(library, shard)
    }
}

/** Collects the parts of a checkpoint (sync spec 10.4): [add] each readable file, then [build] the log. */
@JsExport
class JsSyncParts internal constructor(private val library: String) {
    private val reader = PartsReader(library)

    /** Returns `ok`, or the error code of a part that cannot be used (it is then ignored, as a reader must). */
    fun add(bytes: ByteArray): String {
        installNativeSha()
        return when (val r = decodePart(bytes, library)) {
            is PartResult.Ok -> { reader.add(r.part); "ok" }
            is PartResult.Err -> checkpointCode(r.error)
        }
    }

    /** The log over the merged parts; null when unusable (an equivocation at or below a coverage any part claims). */
    fun build(equivocationsJson: String): JsSyncLog? {
        val eq = parseEquivocations(equivocationsJson)
        return OpLog.fromMerged(reader.build(), eq)?.let { JsSyncLog(it) }
    }
}

@JsExport
fun syncPartsCreate(library: String): JsSyncParts = JsSyncParts(library)

/** A new, empty log. */
@JsExport
fun syncLogCreate(): JsSyncLog = JsSyncLog(OpLog())

/** Starts a log from a decoded checkpoint; null when it is unusable (another semantics, or an equivocation at or below its coverage). */
@JsExport
fun syncLogFromCheckpoint(checkpoint: JsSyncCheckpoint, equivocationsJson: String): JsSyncLog? {
    val cp = checkpoint.checkpoint ?: return null
    return OpLog.fromCheckpoint(cp, parseEquivocations(equivocationsJson))?.let { JsSyncLog(it) }
}

// ---- files -----------------------------------------------------------------------------------------------------------

private fun segmentCode(e: SegmentError): Pair<String, Int> = when (e) {
    is SegmentError.BadMagic -> "badMagic" to 0
    is SegmentError.NewerFormat -> "newerFormat" to 0
    is SegmentError.TooLarge -> "tooLarge" to 0
    is SegmentError.Bom -> "bom" to 0
    is SegmentError.MalformedUtf8 -> "malformedUtf8" to 0
    is SegmentError.CarriageReturn -> "carriageReturn" to 0
    is SegmentError.BadHeader -> "badHeader" to 0
    is SegmentError.Bounds -> "bounds" to 0
    is SegmentError.CountMismatch -> "countMismatch" to 0
    is SegmentError.MissingFinalNewline -> "missingFinalNewline" to 0
    is SegmentError.TrailingData -> "trailingData" to 0
    is SegmentError.Checksum -> "checksum" to 0
    is SegmentError.WrongLibrary -> "wrongLibrary" to 0
    is SegmentError.BadOp -> "badOp" to e.line
    is SegmentError.NonCanonical -> "nonCanonical" to e.line
    is SegmentError.DuplicateKey -> "duplicateKey" to e.line
    is SegmentError.WrongActor -> "wrongActor" to e.line
    is SegmentError.BadTrailer -> "badTrailer" to 0
    is SegmentError.BadSignature -> "badSignature" to 0
    is SegmentError.SeqGap -> "seqGap" to e.line
}

/** `{ok, error?, line?, actor, first, last, ops: [canonical op text]}`. A segment with invalid or unknown ops still decodes. */
@JsExport
fun syncDecodeSegment(bytes: ByteArray, library: String): String = when (val r = decodeSegment(bytes, library)) {
    is SegmentResult.Ok -> json(
        jObj(
            "ok" to JBool(true),
            "actor" to JStr(r.actor),
            "first" to JNum(r.first),
            "last" to JNum(r.last),
            "ops" to strings(r.ops.map { it.encode() }),
            "signer" to (r.signer?.let { JStr(it) } ?: JNull),
            "key" to (r.key?.let { JStr(app.keepfin.core.net.base64Encode(it)) } ?: JNull),
        ),
    )
    is SegmentResult.Err -> {
        val (error, line) = segmentCode(r.error)
        json(jObj("ok" to JBool(false), "error" to JStr(error), "line" to JNum(line.toLong())))
    }
}

/**
 * [opsJson] is a JSON array of canonical op texts: one actor's ops, contiguous. Throws on anything else. With [pkcs8] and
 * [spki] (the device key as DER) the segment is written as KFSEG 2, signed.
 */
@JsExport
fun syncEncodeSegment(library: String, actor: String, opsJson: String, pkcs8: ByteArray? = null, spki: ByteArray? = null): ByteArray {
    val ops = parseStrings(opsJson, "ops").map {
        (decodeOp(it) as? OpDecode.Ok)?.op ?: throw IllegalArgumentException("an op is not canonical op text")
    }
    val signer = if (pkcs8 != null && spki != null) app.keepfin.core.net.SoftwareSigner(app.keepfin.core.net.EcKeyPair(spki, pkcs8)) else null
    return encodeSegment(library, actor, ops, signer)
}

private fun checkpointCode(e: CheckpointError): String = when (e) {
    is CheckpointError.BadMagic -> "badMagic"
    is CheckpointError.NewerFormat -> "newerFormat"
    is CheckpointError.TooLarge -> "tooLarge"
    is CheckpointError.Bom -> "bom"
    is CheckpointError.MalformedUtf8 -> "malformedUtf8"
    is CheckpointError.CarriageReturn -> "carriageReturn"
    is CheckpointError.BadHeader -> "badHeader"
    is CheckpointError.MissingFinalNewline -> "missingFinalNewline"
    is CheckpointError.TrailingData -> "trailingData"
    is CheckpointError.Checksum -> "checksum"
    is CheckpointError.WrongLibrary -> "wrongLibrary"
    is CheckpointError.OtherSemantics -> "otherSemantics"
    is CheckpointError.NonCanonical -> "nonCanonical"
    is CheckpointError.BadShape -> "badShape"
}

/** Decodes a checkpoint file. Check [JsSyncCheckpoint.ok]; pass it to [syncLogFromCheckpoint]. */
@JsExport
fun syncDecodeCheckpoint(bytes: ByteArray, library: String): JsSyncCheckpoint {
    installNativeSha()
    return when (val r = decodeCheckpoint(bytes, library)) {
        is CheckpointResult.Ok -> JsSyncCheckpoint(r.checkpoint, null)
        is CheckpointResult.Err -> JsSyncCheckpoint(null, checkpointCode(r.error))
    }
}

// ---- ops, clocks, keys -------------------------------------------------------------------------------------------------

/**
 * Builds one canonical op. Returns `{ok:true, text}` or `{ok:false, error}` when the ids, clock or body break the
 * rules admission would apply (the same code judges both). Unknown kinds are allowed.
 */
@JsExport
fun syncMakeOp(actor: String, seq: Double, hlcWire: String, kind: String, bodyJson: String): String {
    fun fail(why: String) = json(jObj("ok" to JBool(false), "error" to JStr(why)))
    val hlc = parseWire(hlcWire) ?: return fail("bad clock")
    val body = OpTextReader(bodyJson, false).readObjectOnly()
        ?: ((parseLoose(bodyJson)) as? JObj)?.members
        ?: return fail("body is not a JSON object")
    val s = seq.toLong()
    if (s.toDouble() != seq || s < 1 || s > MAX_SAFE_INTEGER) return fail("bad seq")
    val op = Op(OpId(actor, s), hlc, kind, body)
    if (!isUuid(actor)) return fail("actor is not an id")
    // The text is built once and judged directly. A body nested very deeply goes the long way (admission of the
    // encoded line), where the strict decoder has its own depth limit.
    val text = op.encode()
    val size = utf8LengthOrMinusOne(text)
    if (size < 0 || jsonDepth(JObj(body)) > 400) {
        return when (val a = OpLog().admit(op)) {
            is Admission.Invalid -> fail(a.reason)
            is Admission.Malformed -> fail("malformed: " + a.problem.name)
            else -> json(jObj("ok" to JBool(true), "text" to JStr(op.encode())))
        }
    }
    val judged = judgeOp(op, size)
    if (judged is Interpretation.Invalid) return fail(judged.reason)
    val sb = StringBuilder(text.length + 24)
    sb.append("{\"ok\":true,\"text\":")
    appendQuoted(sb, text)
    sb.append('}')
    return sb.toString()
}

/**
 * `Hlc.parse` without its intermediate strings: the same clocks for the same text (13 digits, `.`, 6 digits, `.`,
 * a non-empty actor; the wire form of what it reads is always the text itself).
 */
private fun parseWire(text: String): Hlc? {
    if (text.length < 22 || text[13] != '.' || text[20] != '.') return null
    var wall = 0.0
    for (i in 0 until 13) {
        val c = text[i].code
        if (c < 48 || c > 57) return null
        wall = wall * 10.0 + (c - 48)
    }
    var counter = 0
    for (i in 14 until 20) {
        val c = text[i].code
        if (c < 48 || c > 57) return null
        counter = counter * 10 + (c - 48)
    }
    return Hlc(wall.toLong(), counter, text.substring(21))
}

/** The clock for a local write. Throws on an unparseable clock or when the wall time is exhausted. */
@JsExport
fun syncHlcTick(lastWire: String?, nowMs: Double, actor: String): String {
    val last = lastWire?.let { parseWire(it) ?: throw IllegalArgumentException("bad clock: $it") }
    return Hlc.tick(last, nowMs.toLong(), actor).wire()
}

/** The clock after admitting a remote op. Throws on an unparseable clock or when the wall time is exhausted. */
@JsExport
fun syncHlcReceive(lastWire: String?, remoteWire: String, nowMs: Double, actor: String): String {
    val last = lastWire?.let { parseWire(it) ?: throw IllegalArgumentException("bad clock: $it") }
    val remote = parseWire(remoteWire) ?: throw IllegalArgumentException("bad clock: $remoteWire")
    return Hlc.receive(last, remote, nowMs.toLong(), actor).wire()
}

/** A fractional key strictly between [a] and [b] (null is open). Throws on misordered or malformed keys. */
@JsExport
fun syncKeyBetween(a: String?, b: String?): String = Fractional.keyBetween(a, b)

@JsExport
fun syncIsValidKey(key: String): Boolean = Fractional.isValidKey(key)

/** `{newKey, moves: [{entry, key}]}` for placing inside a tied group. [followersJson] is a JSON array of entry ids. */
@JsExport
fun syncPlaceInTie(tieKey: String, next: String?, followersJson: String): String {
    val p = Fractional.placeInTie(tieKey, next, parseStrings(followersJson, "followers"))
    return json(jObj("newKey" to JStr(p.newKey), "moves" to JArr(p.moves.map { jObj("entry" to JStr(it.entry), "key" to JStr(it.key)) })))
}
