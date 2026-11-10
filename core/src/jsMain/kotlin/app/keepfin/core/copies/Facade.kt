@file:OptIn(ExperimentalJsExport::class)

package app.keepfin.core.copies

import app.keepfin.core.sync.JArr
import app.keepfin.core.sync.JBool
import app.keepfin.core.sync.JNull
import app.keepfin.core.sync.JNum
import app.keepfin.core.sync.JObj
import app.keepfin.core.sync.JStr
import app.keepfin.core.sync.Jv
import app.keepfin.core.sync.encodeCanonical
import app.keepfin.core.sync.jObj
import app.keepfin.core.sync.parseLoose

// The JavaScript surface of the copies planner (see gui/shared/core/copies.ts). It is JSON-line based: the desired set,
// the two inventories and the baseline arrive as pull functions that return the next JSON line (or null at the end), so
// a 200,000-path inventory never has to exist as one array. Plain numbers cross as doubles (integers within 2^53).
// Results come back as JSON text; a plan keeps its actions inside the handle and gives them out in pages.

// ---- parsing ---------------------------------------------------------------------------------------------------------

private fun obj(line: String, what: String): Map<String, Jv> =
    (parseLoose(line) as? JObj)?.members ?: throw IllegalArgumentException("$what: a line is not a JSON object")

private fun Map<String, Jv>.s(key: String): String? = (this[key] as? JStr)?.value

private fun Map<String, Jv>.req(key: String, what: String): String =
    s(key) ?: throw IllegalArgumentException("$what: '$key' is missing")

private fun Map<String, Jv>.n(key: String): Long = (this[key] as? JNum)?.value ?: 0L

private fun Map<String, Jv>.b(key: String): Boolean = (this[key] as? JBool)?.value == true

private fun statOf(v: Jv?): Stat {
    val m = (v as? JObj)?.members ?: return Stat(0, 0, null)
    return Stat(m.n("size"), m.n("mtimeMs"), m.s("fileId"))
}

private fun invLine(line: String): InvLine {
    val m = obj(line, "inventory")
    return InvLine(m.req("path", "inventory"), m.n("size"), m.n("mtimeMs"), m.s("fileId"))
}

private fun desiredEntry(line: String): DesiredEntry {
    val m = obj(line, "desired")
    return DesiredEntry(m.req("path", "desired"), m.req("itemId", "desired"), m.s("revisionOp"), m.req("contentId", "desired"), m.n("bytes"))
}

private fun baselineEntry(line: String): BaselineEntry {
    val m = obj(line, "baseline")
    return BaselineEntry(
        m.req("path", "baseline"), m.req("itemId", "baseline"), m.s("revisionOp"), m.req("contentId", "baseline"),
        statOf(m["main"]), statOf(m["copy"]), m.n("scannedAtMs"), m.n("generation"),
    )
}

/** A pull source: [open] returns a function that gives the next line, or null at the end. It may be opened several times. */
private fun <T : Any> source(open: () -> (() -> String?), parse: (String) -> T): Source<T> = {
    val next = open()
    object : Iterator<T> {
        private var peeked: String? = null
        private var done = false
        private fun fill() {
            if (peeked == null && !done) {
                val l = next()
                if (l == null) done = true else peeked = l
            }
        }

        override fun hasNext(): Boolean {
            fill()
            return peeked != null
        }

        override fun next(): T {
            fill()
            val l = peeked ?: throw NoSuchElementException()
            peeked = null
            return parse(l)
        }
    }
}

private fun rulesOf(name: String): NameRules =
    NameRules.byId(name) ?: throw IllegalArgumentException("Unknown name rules '$name' (exact, ascii, nfc or unicode)")

private fun modeOf(name: String): CopyMode = when (name) {
    "twoWay" -> CopyMode.TwoWay
    "backup" -> CopyMode.Backup
    "mirror" -> CopyMode.Mirror
    else -> throw IllegalArgumentException("Unknown mode '$name'")
}

private fun generationOf(name: String): Generation = when (name) {
    "equal" -> Generation.Equal
    "higher" -> Generation.Higher
    "lower" -> Generation.Lower
    "unknown" -> Generation.Unknown
    else -> throw IllegalArgumentException("Unknown generation '$name'")
}

private fun strings(items: Collection<String>) = JArr(items.map { JStr(it) })

// ---- the options -----------------------------------------------------------------------------------------------------

/**
 * One planning run's settings, as JSON: `{mode, rules, generation, mainComplete, copyComplete, previewConfirmed, audit,
 * timestampGranularityMs, freeMain, freeCopy, deleteGuardPercent, confirmedDeletes: [paths], deleted: [itemIds],
 * placementMoves: [{itemId, from, to}], caps: {completeEnumeration, stableIds, conditionalMutation}}`. Missing fields take
 * the planner's defaults (a folder transport, two-way off: [mode] is required).
 */
private fun inputFrom(
    optionsJson: String,
    desired: () -> (() -> String?),
    main: () -> (() -> String?),
    copy: () -> (() -> String?),
    baseline: () -> (() -> String?),
): PlannerInput {
    val o = obj(optionsJson, "options")
    val deleted = ((o["deleted"] as? JArr)?.items ?: emptyList()).mapNotNull { (it as? JStr)?.value }.toHashSet()
    val moves = ((o["placementMoves"] as? JArr)?.items ?: emptyList()).mapNotNull {
        val m = (it as? JObj)?.members ?: return@mapNotNull null
        PlacementMove(m.req("itemId", "placementMoves"), m.req("from", "placementMoves"), m.req("to", "placementMoves"))
    }
    val c = (o["caps"] as? JObj)?.members ?: emptyMap()
    val def = Capabilities.folder
    val caps = Capabilities(
        completeEnumeration = (c["completeEnumeration"] as? JBool)?.value ?: def.completeEnumeration,
        stableIds = (c["stableIds"] as? JBool)?.value ?: def.stableIds,
        conditionalMutation = (c["conditionalMutation"] as? JBool)?.value ?: def.conditionalMutation,
    )
    fun flag(key: String, default: Boolean) = (o[key] as? JBool)?.value ?: default
    return PlannerInput(
        mode = modeOf(o.req("mode", "options")),
        options = PlaceOptions(
            deleteGuardPercent = (o["deleteGuardPercent"] as? JNum)?.value?.toInt() ?: 10,
            companions = (o["companions"] as? JBool)?.value != false,
            // the caller passes the place's choice (a part copy that never chose: false)
            musicVideos = (o["musicVideos"] as? JBool)?.value ?: true,
        ),
        rules = rulesOf(o.s("rules") ?: "ascii"),
        desired = source(desired, ::desiredEntry),
        main = source(main, ::invLine),
        copy = source(copy, ::invLine),
        baseline = source(baseline, ::baselineEntry),
        library = SimpleLibraryFacts(deleted, moves),
        mainComplete = flag("mainComplete", true),
        copyComplete = flag("copyComplete", true),
        generation = generationOf(o.s("generation") ?: "equal"),
        previewConfirmed = flag("previewConfirmed", false),
        confirmedDeletes = ((o["confirmedDeletes"] as? JArr)?.items ?: emptyList()).mapNotNull { (it as? JStr)?.value }.toHashSet(),
        caps = caps,
        timestampGranularityMs = (o["timestampGranularityMs"] as? JNum)?.value ?: 2000L,
        audit = flag("audit", false),
        freeMain = (o["freeMain"] as? JNum)?.value,
        freeCopy = (o["freeCopy"] as? JNum)?.value,
    )
}

// ---- the results -----------------------------------------------------------------------------------------------------

private fun opt(v: String?): Jv = if (v == null) JNull else JStr(v)

private fun actionJson(a: Action): JObj = jObj(
    "kind" to JStr(a.kind.name),
    "path" to JStr(a.path),
    "toPath" to opt(a.toPath),
    "itemId" to opt(a.itemId),
    "revisionOp" to opt(a.revisionOp),
    "contentId" to opt(a.contentId),
    "bytes" to JNum(a.bytes),
    "displacedBytes" to JNum(a.displacedBytes),
    "expect" to (a.expect?.let { jObj("contentId" to opt(it.contentId), "fileId" to opt(it.fileId)) } ?: JNull),
    "archive" to opt(a.archive?.name),
    "reason" to opt(a.reason?.name),
    "review" to JBool(a.review),
)

private fun reviewJson(r: ReviewItem): JObj = jObj(
    "kind" to JStr(r.kind.name),
    "path" to JStr(r.path),
    "itemId" to opt(r.itemId),
    "detail" to JStr(r.detail),
    "action" to (r.action?.let { actionJson(it) } ?: JNull),
)

private fun spaceJson(s: SpaceCheck): JObj = jObj(
    "needed" to JNum(s.needed),
    "free" to (s.free?.let { JNum(it) } ?: JNull),
    "enough" to (s.enough?.let { JBool(it) } ?: JNull),
)

/** A finished plan. The actions stay in the handle; read them with [actionsPage]. */
@JsExport
class JsCopiesPlan internal constructor(private val plan: Plan) {
    val actionCount: Int get() = plan.actions.size
    val reviewCount: Int get() = plan.review.size
    val held: Boolean get() = plan.held
    val holdReason: String? get() = plan.holdReason
    val additiveOnly: Boolean get() = plan.additiveOnly

    /** A JSON array of up to [limit] actions starting at [offset]. */
    fun actionsPage(offset: Int, limit: Int): String {
        val end = minOf(plan.actions.size, offset + limit)
        val items = ArrayList<Jv>()
        for (i in offset until end) items.add(actionJson(plan.actions[i]))
        return encodeCanonical(JArr(items))
    }

    fun reviewPage(offset: Int, limit: Int): String {
        val end = minOf(plan.review.size, offset + limit)
        val items = ArrayList<Jv>()
        for (i in offset until end) items.add(reviewJson(plan.review[i]))
        return encodeCanonical(JArr(items))
    }

    /** `{counts, bytes, destructive, copyFiles, mainSpace, copySpace}` */
    fun summaryJson(): String {
        val s = plan.summary
        return encodeCanonical(
            jObj(
                "counts" to JObj(s.counts.entries.associate { it.key.name to JNum(it.value.toLong()) }),
                "bytes" to JObj(s.bytes.entries.associate { it.key.name to JNum(it.value) }),
                "destructive" to JNum(s.destructive.toLong()),
                "copyFiles" to JNum(s.copyFiles.toLong()),
                "mainSpace" to spaceJson(s.mainSpace),
                "copySpace" to spaceJson(s.copySpace),
            ),
        )
    }
}

/** What the planner could not decide yet: the hashes to compute. [ready] is false and [plan] is null. */
@JsExport
class JsCopiesOutcome internal constructor(private val outcome: PlanOutcome) {
    val ready: Boolean get() = outcome is PlanOutcome.Ready

    val plan: JsCopiesPlan? get() = (outcome as? PlanOutcome.Ready)?.let { JsCopiesPlan(it.plan) }

    val requestCount: Int get() = (outcome as? PlanOutcome.NeedsHashes)?.requests?.size ?: 0

    /** A JSON array of up to [limit] `{side, path, size, mtimeMs, why}` starting at [offset]. */
    fun requestsPage(offset: Int, limit: Int): String {
        val reqs = (outcome as? PlanOutcome.NeedsHashes)?.requests ?: return "[]"
        val end = minOf(reqs.size, offset + limit)
        val items = ArrayList<Jv>()
        for (i in offset until end) {
            val r = reqs[i]
            items.add(jObj("side" to JStr(r.side.name), "path" to JStr(r.path), "size" to JNum(r.size), "mtimeMs" to JNum(r.mtimeMs), "why" to JStr(r.why)))
        }
        return encodeCanonical(JArr(items))
    }
}

/**
 * Plans one relationship. [hash] answers `(side "Main"|"Copy", path, size, mtimeMs)` with a known `sha256:<hex>` or null.
 * Each source is `() => (() => string | null)`: open a stream, then pull JSON lines from it. The planner may open a
 * source more than once. Sources must be sorted by (name key, path) with the rules named in the options.
 */
@JsExport
fun copiesPlan(
    optionsJson: String,
    desired: () -> (() -> String?),
    main: () -> (() -> String?),
    copy: () -> (() -> String?),
    baseline: () -> (() -> String?),
    hash: (side: String, path: String, size: Double, mtimeMs: Double) -> String?,
): JsCopiesOutcome {
    val input = inputFrom(optionsJson, desired, main, copy, baseline)
    val evidence = Evidence { side, line -> hash(side.name, line.path, line.size.toDouble(), line.mtimeMs.toDouble()) }
    return JsCopiesOutcome(Planner.plan(input, evidence))
}

/** The name key of [path] under [rules] (`exact` or `ascii`): what inventories are sorted by. */
@JsExport
fun copiesNameKey(rules: String, path: String): String = rulesOf(rules).key(path)

/** The conflict name of [path] for a version whose content id is [contentId]: `name (conflict 1a2b3c4d).ext`. */
@JsExport
fun copiesConflictPath(path: String, contentId: String): String = Names.conflictPath(path, contentId)

/** The collision name for a different file arriving under an occupied name: `name (copy 1a2b3c4d).ext`. */
@JsExport
fun copiesCopyPath(path: String, contentId: String): String = Names.copyPath(path, contentId)

@JsExport
class JsOnePlace(val kind: String, val count: Int, val place: String, val here: Boolean)

@JsExport
class JsSafety(val tone: String, val title: String, val detail: String, val keepCopy: Boolean)

@JsExport
fun copiesLibrarySafety(places: Int, files: Int, only: Array<JsOnePlace>, deviceName: String): JsSafety =
    librarySafety(places, files, only.map { OnePlace(it.kind, it.count, it.place, it.here) }, deviceName).let { JsSafety(it.tone, it.title, it.detail, it.keepCopy) }

@JsExport
fun copiesFilesSummary(paths: Array<String>): String = filesSummary(paths.toList())

// ---- companions (dev-docs/plans/COPIES-COMPANIONS.md) ----

private fun fieldsOf(json: String): Map<String, Jv> = (parseLoose(json) as? JObj)?.members ?: emptyMap()

/** The companion suffix of the file [name] beside the media file [mediaName] (`.lrc`, `.en.srt`, `.info.json`), or null. */
@JsExport
fun copiesCompanionSuffix(mediaName: String, name: String): String? = companionSuffix(mediaName, name)

/** The item field that records the companion [suffix]. */
@JsExport
fun copiesCompanionField(suffix: String): String = companionField(suffix)

/** Where the companion [suffix] of the media file at [mediaPath] lives. */
@JsExport
fun copiesCompanionPath(mediaPath: String, suffix: String): String = companionPath(mediaPath, suffix)

@JsExport
class JsCompanion(val suffix: String, val content: String, val size: Double)

/** The companions an item's fields (a JSON object; only the `companion:` ones are read) record. */
@JsExport
fun copiesCompanionsOf(fieldsJson: String): Array<JsCompanion> =
    companionsOf(fieldsOf(fieldsJson)).map { JsCompanion(it.suffix, it.content, it.size.toDouble()) }.toTypedArray()

@JsExport
class JsCompanionEntry(val path: String, val itemId: String, val revisionOp: String, val contentId: String, val bytes: Double)

/** The desired-set candidates of one media entry with [heads] heads and the companion fields in [fieldsJson]. */
@JsExport
fun copiesCompanionCandidates(itemId: String, mediaPath: String, heads: Int, fieldsJson: String, videos: Boolean = true, others: Boolean = true): Array<JsCompanionEntry> =
    companionCandidates(itemId, mediaPath, heads, fieldsOf(fieldsJson), videos, others).map { JsCompanionEntry(it.path, it.itemId, it.revisionOp ?: "", it.contentId, it.bytes.toDouble()) }.toTypedArray()

/**
 * [copiesCompanionCandidates] for many fields at once, as parallel lists (one per field: its item, the item's path and head
 * count, the field's name, content and size; "" and -1 when the value has none): one call for a whole library.
 */
@JsExport
fun copiesCompanionCandidatesOf(
    itemIds: Array<String>, mediaPaths: Array<String>, heads: IntArray, fields: Array<String>, contents: Array<String>, sizes: DoubleArray, videos: Boolean = true,
    others: Boolean = true,
): Array<JsCompanionEntry> {
    val out = ArrayList<JsCompanionEntry>()
    for (i in itemIds.indices) {
        val e = companionCandidate(itemIds[i], mediaPaths[i], heads[i], fields[i], contents[i], sizes[i].toLong(), videos, others) ?: continue
        out.add(JsCompanionEntry(e.path, e.itemId, e.revisionOp ?: "", e.contentId, e.bytes.toDouble()))
    }
    return out.toTypedArray()
}

/** Which candidates (parallel [paths] and [itemIds]) a desired set keeps, as indices: one per name, never a media file's ([isMediaKey] takes a name key). */
@JsExport
fun copiesKeepCompanions(paths: Array<String>, itemIds: Array<String>, isMediaKey: (String) -> Boolean): IntArray =
    keptCompanionIndices(paths.asList(), itemIds.asList(), isMediaKey).toIntArray()
/** The name key companions are told apart by (letter case ignored). */
@JsExport
fun copiesCompanionKey(path: String): String = companionKey(path)

/** A song's music video companion (`.video.mp4`). */
@JsExport
fun copiesIsVideoCompanion(suffix: String): Boolean = isVideoCompanion(suffix)

/** `.video.mp4` for `mp4`; null for a container the apps do not play. */
@JsExport
fun copiesVideoCompanionSuffix(ext: String): String? = videoCompanionSuffix(ext)

/** `Song` for `Song.video.mp4`; null for any other name. */
@JsExport
fun copiesVideoCompanionStem(name: String): String? = videoCompanionStem(name)

// ---- a copy that could not copy some files (CopyProblems.kt) ----

@JsExport
class JsProblemGroup(val text: String, val count: Int)

/** The plain sentence for one failure (what the system said goes in; no code or file name comes out). */
@JsExport
fun copiesFailureReason(reason: String): String = copyFailureReason(reason)

/** The failures grouped by plain reason, the most common first. */
@JsExport
fun copiesProblemGroups(reasons: Array<String>): Array<JsProblemGroup> = copyProblemGroups(reasons.toList()).map { JsProblemGroup(it.text, it.count) }.toTypedArray()

/** "50 files could not be copied". */
@JsExport
fun copiesProblemTitle(count: Int): String = copyProblemTitle(count)

/** "50 files not copied". */
@JsExport
fun copiesProblemShort(count: Int): String = copyProblemShort(count)

/** "Keepfin tries again at every sync." */
@JsExport
fun copiesProblemRetry(): String = COPY_PROBLEM_RETRY

// ---- what a sync the person started did (SyncReport.kt) ----

@JsExport
class JsSyncReport(val title: String, val detail: String, val warn: Boolean)

@JsExport
fun copiesSyncReport(synced: Int, changed: Int, problems: Int, notReached: Array<String>, needOk: Array<String>, cloud: Boolean): JsSyncReport =
    syncReport(synced, changed, problems, notReached.toList(), needOk.toList(), cloud).let { JsSyncReport(it.title, it.detail, it.warn) }

// ---- Free up space… and Remove copy… (CopyActions.kt) ----

@JsExport
fun copiesFreeUpTitle(copy: String): String = CopyActions.freeUpTitle(copy)

@JsExport
fun copiesNothingToFree(versions: Int, deletedFiles: Int): Boolean = CopyActions.nothingToFree(versions, deletedFiles)

@JsExport
fun copiesFreeUpNothingWords(): String = CopyActions.NOTHING_TO_FREE

@JsExport
fun copiesFreeUpSummary(versions: Int, deletedFiles: Int): String = CopyActions.freeUpSummary(versions, deletedFiles)

@JsExport
fun copiesFreeUpKeeps(): String = CopyActions.FREE_UP_KEEPS

@JsExport
fun copiesRemoveTitle(copy: String): String = CopyActions.removeTitle(copy)

@JsExport
fun copiesRemoveLead(): String = CopyActions.REMOVE_LEAD

@JsExport
fun copiesRemoveFilesStay(): String = CopyActions.REMOVE_FILES_STAY

// ---- safety checks of the executors (Checks.kt) ----

/** The largest file a volume of [format] (FAT32, exFAT, NTFS...) can hold, or null when unlimited or unknown. */
@JsExport
fun copiesFileSizeLimit(format: String?): Double? = fileSizeLimit(format)?.toDouble()

@JsExport
fun copiesTooLargeWords(name: String, format: String?): String = fileTooLargeWords(name, format)

/** Whether Windows attribute bits say the bytes of the file are not on the disk (a cloud placeholder). */
@JsExport
fun copiesIsOnlineOnly(attributes: Double): Boolean = isOnlineOnlyWindows(attributes.toLong())

@JsExport
fun copiesOnlineOnlyWords(name: String): String = onlineOnlyWords(name)

@JsExport
fun copiesAuditDue(lastAuditAtMs: Double?, nowMs: Double): Boolean = auditDue(lastAuditAtMs?.toLong(), nowMs.toLong())

/** Detects a time zone or daylight saving shift over the files of a scan: feed [add] each file of the same size, then ask [detected]. */
@JsExport
class JsClockShift {
    private val shift = ClockShift()
    fun add(baselineMtimeMs: Double, currentMtimeMs: Double) = shift.add(baselineMtimeMs.toLong(), currentMtimeMs.toLong())
    val detected: Boolean get() = shift.detected
    val hours: Double get() = shift.hours.toDouble()
}
