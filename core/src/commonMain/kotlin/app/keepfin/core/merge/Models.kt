package app.keepfin.core.merge

import app.keepfin.core.library.*
import app.keepfin.core.sync.*

/** Observed bytes on B. Null size/mtime never proves that the recorded hash is current. */
data class FileEvidence(
    val readable: Boolean,
    val size: Long? = null,
    val mtime: Long? = null,
    /** Hash computed from these observed bytes by the host, when hints did not match. */
    val hash: String? = null,
)

data class SItem(
    val fields: LibItem,
    val added: Hlc,
    val revisionHash: String?,
    val size: Long?,
    val mtimeHint: Long?,
    val pathOnB: String?,
    val file: FileEvidence,
    val placements: Map<String, String> = emptyMap(),
    val fieldClocks: Map<String, Hlc> = emptyMap(),
    /** Used by the reverse direction; never inferred from added.wall. */
    val addActor: String = added.actor,
    val addSeq: Long = 0,
) {
    val id: String get() = fields.id
}

data class TItem(
    val fields: LibItem,
    val added: Hlc,
    val revisionHash: String?,
    val size: Long?,
    val mtimeHint: Long? = null,
    val placements: Map<String, String> = emptyMap(),
    val fieldClocks: Map<String, Hlc> = emptyMap(),
) {
    val id: String get() = fields.id
}

/** Per-item metadata travels with each item page instead of one library-sized metadata object. */
data class SMetadata(
    val favourite: Boolean = false,
    val tags: List<String> = emptyList(),
    val plays: Double? = null,
    val lastPlayedAt: Double? = null,
    val position: Double? = null,
    val watched: Boolean? = null,
)

enum class MapHow(val wire: String) { Linked("linked"), Added("added"), KeptDuplicate("kept-duplicate") }
data class MapLine(val s: String, val t: String, val how: MapHow) {
    fun json(): String = encodeCanonical(jObj("s" to JStr(s), "t" to JStr(t), "how" to JStr(how.wire)))
}

enum class ReviewKind(val wire: String) {
    ManyMatches("manyMatches"), DuplicateInS("duplicateInS"), DifferingField("differingField"),
    MissingFile("missingFile"), SameNamePlaylist("sameNamePlaylist"), SameNameSpace("sameNameSpace"),
}

/** manyMatches is emitted once per alternative, avoiding a library-sized match array. */
data class ReviewEntry(
    val kind: ReviewKind,
    val s: String,
    val t: String? = null,
    val related: String? = null,
    val field: String? = null,
    val sourceValue: Jv? = null,
    val targetValue: Jv? = null,
)

data class NeedsHash(val s: String, val path: String, val size: Long?, val mtime: Long?)
data class PageResult(val needsHash: List<NeedsHash>) {
    val accepted: Boolean get() = needsHash.isEmpty()
}

/** Copies concern records/art only. Media sidecars and placements remain at their original paths. */
data class ArtFile(val ownerKind: String, val ownerId: String, val source: String, val name: String)
data class ArtCopy(val ownerKind: String, val ownerId: String, val source: String, val target: String)

data class MergeConfig(val mergeId: String, val actor: String, val replicaId: String, val firstSpaceOrder: Long = 0) {
    init {
        require(mergeId.isNotEmpty() && isUuid(actor) && isUuid(replicaId))
        require(firstSpaceOrder in 0..MAX_SAFE_INTEGER)
    }
}

/** Host supplies SQLite/disk implementations. lookup is ordered by item.add HLC, then id. */
interface THashIndex {
    fun lookup(hash: String): Sequence<String>
    fun item(id: String): TItem
    fun sameName(kind: String, name: String): Sequence<String> = emptySequence()
}

/** Scratch data and the lasting map live outside the planner, keeping its working memory page-bounded. */
interface MergeLedger {
    fun firstSource(hash: String): String?
    fun rememberContent(hash: String, source: String)
    fun mapped(source: String): MapLine?
    fun record(line: MapLine)
}

interface MergeSink {
    fun batch(batch: Batch)
    fun map(line: MapLine)
    fun review(entry: ReviewEntry)
    fun art(copy: ArtCopy)
}

/** Test/small-library adapters. Production hosts should back the index and ledger with disk. */
class InMemoryTHashIndex(
    items: Iterable<TItem>,
    private val names: Map<Pair<String, String>, List<String>> = emptyMap(),
) : THashIndex {
    private val byId = items.associateBy { it.id }
    private val hashes = byId.values.filter { it.revisionHash != null }.groupBy { it.revisionHash!! }
        .mapValues { (_, rows) -> rows.sortedWith(compareBy<TItem> { it.added }.thenBy { it.id }).map { it.id } }
    override fun lookup(hash: String): Sequence<String> = (hashes[hash] ?: emptyList()).asSequence()
    override fun item(id: String): TItem = byId.getValue(id)
    override fun sameName(kind: String, name: String): Sequence<String> = (names[kind to name] ?: emptyList()).sorted().asSequence()
}

class InMemoryMergeLedger : MergeLedger {
    private val content = HashMap<String, String>()
    private val lines = HashMap<String, MapLine>()
    override fun firstSource(hash: String): String? = content[hash]
    override fun rememberContent(hash: String, source: String) { content[hash] = source }
    override fun mapped(source: String): MapLine? = lines[source]
    override fun record(line: MapLine) {
        require(line.s !in lines) { "source item already planned" }
        lines[line.s] = line
    }
}

class CollectedMerge : MergeSink {
    val batches = ArrayList<Batch>()
    val map = ArrayList<MapLine>()
    val review = ArrayList<ReviewEntry>()
    val art = ArrayList<ArtCopy>()
    override fun batch(batch: Batch) { batches.add(batch) }
    override fun map(line: MapLine) { map.add(line) }
    override fun review(entry: ReviewEntry) { review.add(entry) }
    override fun art(copy: ArtCopy) { art.add(copy) }
}
