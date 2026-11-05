package app.keepfin.merge

import app.keepfin.folder.writeLocalAtomic
import java.io.File
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// The journal of one combine or separate (dev-docs/plans/LIBRARY-MERGE-CONTRACT.md section 3). It lives outside the Keepfin folder, in this app's
// private storage (`filesDir/merges/<mergeId>.json`), and is replaced atomically (temp file + rename) after every step. Start-up recovery reads
// it before the library opens and decides which library is in charge.

enum class MergeStage(val wire: String) {
    Planned("planned"), Joined("joined"), Describing("describing"), Verified("verified"), Activating("activating"), Active("active"),
    Separating("separating"), Separated("separated"), Cleaned("cleaned");

    /** True while something still has to run (recovery resumes it). */
    val unfinished: Boolean get() = this != Active && this != Separated && this != Cleaned

    companion object {
        fun of(wire: String?): MergeStage = entries.firstOrNull { it.wire == wire } ?: Planned
    }
}

/** The sub-steps of Separate again (contract section 7), in the order they happen. */
enum class SeparateStep(val wire: String) {
    Fenced("fenced"), Written("written"), Published("published"), Reversed("reversed"), PointerBack("pointerBack"),
    Withdrawn("withdrawn"), Remapped("remapped");

    companion object {
        fun of(wire: String?): SeparateStep? = entries.firstOrNull { it.wire == wire }
    }
}

/** What is written before batch [batch] is applied: with it, a crash is resolved by counting the actor's own operations (contract section 3). */
data class Receipt(val batch: Int, val actorSeqBefore: Long, val drafts: Int, val draftsHash: String) {
    fun json(): JSONObject = JSONObject().put("batch", batch).put("actorSeqBefore", actorSeqBefore).put("drafts", drafts).put("draftsHash", draftsHash)

    companion object {
        fun of(o: JSONObject?): Receipt? = o?.let { Receipt(it.getInt("batch"), it.getLong("actorSeqBefore"), it.getInt("drafts"), it.getString("draftsHash")) }
    }
}

/** What the planning pass produced (written once, before any batch is applied). */
data class PlanInfo(val batches: Int, val items: Long, val mapLines: Long, val reviews: Long, val arts: Int, val done: Boolean) {
    fun json(): JSONObject = JSONObject().put("batches", batches).put("items", items).put("mapLines", mapLines).put("reviews", reviews).put("arts", arts).put("done", done)

    companion object {
        fun of(o: JSONObject?): PlanInfo? = o?.let { PlanInfo(it.getInt("batches"), it.getLong("items"), it.getLong("mapLines"), it.getLong("reviews"), it.getInt("arts"), it.getBoolean("done")) }
    }
}

/** What the dry run found, kept with the journal so the review survives a restart (the review rows themselves are in `review-view.jsonl`). */
data class PreviewNumbers(
    val items: Long, val added: Long, val linked: Long, val keptDuplicates: Long, val missingFiles: Long, val differing: Long,
    val playlists: Long, val spaces: Long, val reviewTotal: Long,
    /** Items in the other library (read from the staged copy), or -1 when unknown. */
    val otherItems: Long = -1,
) {
    fun json(): JSONObject = JSONObject().put("items", items).put("added", added).put("linked", linked).put("keptDuplicates", keptDuplicates)
        .put("missingFiles", missingFiles).put("differing", differing).put("playlists", playlists).put("spaces", spaces).put("reviewTotal", reviewTotal)
        .put("otherItems", otherItems)

    companion object {
        fun of(o: JSONObject?): PreviewNumbers? = o?.let {
            PreviewNumbers(
                it.optLong("items"), it.optLong("added"), it.optLong("linked"), it.optLong("keptDuplicates"), it.optLong("missingFiles"), it.optLong("differing"),
                it.optLong("playlists"), it.optLong("spaces"), it.optLong("reviewTotal"), it.optLong("otherItems", -1),
            )
        }
    }
}

/** The state of Separate again inside the journal. */
data class SeparateState(
    val step: SeparateStep? = null,
    /** Items B brought that the peer holds files of: true keeps them there (the default), false removes them. */
    val keepOnPeer: Boolean = true,
    /** The person chose "Separate anyway" because no T device could be reached: nothing is published. */
    val anyway: Boolean = false,
    /** The T-side removal batches (contract 7.2) and the reverse batches into S (7.4) are numbered separately. */
    val tBatches: Int = 0,
    val tApplied: Int = 0,
    val tReceipt: Receipt? = null,
    val sBatches: Int = 0,
    val sApplied: Int = 0,
    val sReceipt: Receipt? = null,
    /** The last sequence number of B's actor in T that the peer must hold durably before the pointer goes back. */
    val publishedSeq: Long = 0,
    val sActorSeqBefore: Long = 0,
    val withdrawn: Boolean = false,
) {
    fun json(): JSONObject = JSONObject().put("step", step?.wire ?: JSONObject.NULL).put("keepOnPeer", keepOnPeer).put("anyway", anyway)
        .put("tBatches", tBatches).put("tApplied", tApplied).put("tReceipt", tReceipt?.json() ?: JSONObject.NULL)
        .put("sBatches", sBatches).put("sApplied", sApplied).put("sReceipt", sReceipt?.json() ?: JSONObject.NULL)
        .put("publishedSeq", publishedSeq).put("sActorSeqBefore", sActorSeqBefore).put("withdrawn", withdrawn)

    companion object {
        fun of(o: JSONObject?): SeparateState? = o?.let {
            SeparateState(
                SeparateStep.of(it.optString("step").ifEmpty { null }), it.optBoolean("keepOnPeer", true), it.optBoolean("anyway", false),
                it.optInt("tBatches"), it.optInt("tApplied"), Receipt.of(it.optJSONObject("tReceipt")),
                it.optInt("sBatches"), it.optInt("sApplied"), Receipt.of(it.optJSONObject("sReceipt")),
                it.optLong("publishedSeq"), it.optLong("sActorSeqBefore"), it.optBoolean("withdrawn"),
            )
        }
    }
}

/**
 * Everything recovery needs to continue a combine on its own. S is this device's own library, T the main library it joins, A the device that
 * holds T. Identity fields of T ([tReplicaId], [tActor]) are known once the join finished.
 */
data class MergeJournal(
    val mergeId: String,
    val stage: MergeStage,
    /** The person (or a lost folder) paused it: recovery does not run until it is resumed. */
    val paused: Boolean = false,
    val peerDeviceId: String,
    val keepMine: Boolean,
    val treeUri: String,
    val sProfileId: String,
    /** The profile T gets when it takes over (its device directory already holds T's state while staging). */
    val tProfileId: String,
    val sLibraryId: String,
    val sReplicaId: String,
    val sActor: String,
    val sName: String,
    /** Where S keeps its records at the start (`""` is `.keepfin/` itself). */
    val sRecords: String,
    val tLibraryId: String,
    val tName: String,
    val tReplicaId: String? = null,
    val tActor: String? = null,
    val startedAt: Long,
    val plan: PlanInfo? = null,
    val receipt: Receipt? = null,
    /** Batches applied so far: the next one to apply is this index. */
    val applied: Int = 0,
    val artCopied: Int = 0,
    val mapPublished: Boolean = false,
    val activationSeq: Long? = null,
    val activatedAt: Long? = null,
    val keepUntil: Long? = null,
    val profileSwitched: Boolean = false,
    val outsideRemapped: Boolean = false,
    val separate: SeparateState? = null,
    /** The last failure, shown with Resume. */
    val error: String? = null,
    /** False while only the review (the dry run) was made: the person has not pressed Combine, so recovery leaves it alone. */
    val started: Boolean = true,
    val preview: PreviewNumbers? = null,
) {
    fun json(): JSONObject = JSONObject().put("v", 1).put("mergeId", mergeId).put("stage", stage.wire).put("paused", paused)
        .put("peerDeviceId", peerDeviceId).put("keepMine", keepMine).put("treeUri", treeUri).put("sProfileId", sProfileId).put("tProfileId", tProfileId)
        .put("sLibraryId", sLibraryId).put("sReplicaId", sReplicaId).put("sActor", sActor).put("sName", sName).put("sRecords", sRecords)
        .put("tLibraryId", tLibraryId).put("tName", tName).put("tReplicaId", tReplicaId ?: JSONObject.NULL).put("tActor", tActor ?: JSONObject.NULL)
        .put("startedAt", startedAt).put("plan", plan?.json() ?: JSONObject.NULL).put("receipt", receipt?.json() ?: JSONObject.NULL)
        .put("applied", applied).put("artCopied", artCopied).put("mapPublished", mapPublished)
        .put("activationSeq", activationSeq ?: JSONObject.NULL).put("activatedAt", activatedAt ?: JSONObject.NULL).put("keepUntil", keepUntil ?: JSONObject.NULL)
        .put("profileSwitched", profileSwitched).put("outsideRemapped", outsideRemapped)
        .put("separate", separate?.json() ?: JSONObject.NULL).put("error", error ?: JSONObject.NULL)
        .put("started", started).put("preview", preview?.json() ?: JSONObject.NULL)

    companion object {
        fun parse(text: String): MergeJournal? = try {
            val o = JSONObject(text)
            fun s(k: String): String? = o.optString(k, "").takeIf { it.isNotEmpty() && !o.isNull(k) }
            fun l(k: String): Long? = if (o.has(k) && !o.isNull(k)) o.optLong(k) else null
            MergeJournal(
                mergeId = o.getString("mergeId"), stage = MergeStage.of(o.optString("stage")), paused = o.optBoolean("paused"),
                peerDeviceId = o.getString("peerDeviceId"), keepMine = o.optBoolean("keepMine"), treeUri = o.getString("treeUri"),
                sProfileId = o.getString("sProfileId"), tProfileId = o.getString("tProfileId"), sLibraryId = o.getString("sLibraryId"),
                sReplicaId = o.getString("sReplicaId"), sActor = o.getString("sActor"), sName = o.optString("sName"), sRecords = o.optString("sRecords"),
                tLibraryId = o.getString("tLibraryId"), tName = o.optString("tName"), tReplicaId = s("tReplicaId"), tActor = s("tActor"),
                startedAt = o.optLong("startedAt"), plan = PlanInfo.of(o.optJSONObject("plan")), receipt = Receipt.of(o.optJSONObject("receipt")),
                applied = o.optInt("applied"), artCopied = o.optInt("artCopied"), mapPublished = o.optBoolean("mapPublished"),
                activationSeq = l("activationSeq"), activatedAt = l("activatedAt"), keepUntil = l("keepUntil"),
                profileSwitched = o.optBoolean("profileSwitched"), outsideRemapped = o.optBoolean("outsideRemapped"),
                separate = SeparateState.of(o.optJSONObject("separate")), error = s("error"),
                started = o.optBoolean("started", true), preview = PreviewNumbers.of(o.optJSONObject("preview")),
            )
        } catch (e: JSONException) {
            null
        }
    }
}

/** `filesDir/merges/`: one `<mergeId>.json` per merge, and `<mergeId>/` next to it for the plan files. */
class JournalStore(val dir: File) {
    fun file(mergeId: String) = File(dir, "$mergeId.json")

    /** The scratch and plan files of one merge (batches, the S-to-T map, the review). */
    fun workDir(mergeId: String) = File(dir, mergeId)

    @Synchronized fun write(journal: MergeJournal) {
        dir.mkdirs()
        writeLocalAtomic(file(journal.mergeId), journal.json().toString(2).toByteArray(Charsets.UTF_8))
    }

    @Synchronized fun read(mergeId: String): MergeJournal? {
        val f = file(mergeId)
        if (!f.isFile) return null
        return try { MergeJournal.parse(f.readText(Charsets.UTF_8)) } catch (e: java.io.IOException) { null }
    }

    /** Every journal on this device, newest first. */
    @Synchronized fun all(): List<MergeJournal> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.toList().orEmpty()).mapNotNull { f ->
            try { MergeJournal.parse(f.readText(Charsets.UTF_8)) } catch (e: java.io.IOException) { null }
        }.sortedByDescending { it.startedAt }

    /** The merge that is running, paused or waiting to be resumed (at most one at a time). */
    fun unfinished(): MergeJournal? = all().firstOrNull { it.stage.unfinished }

    /** The merge the person confirmed that has not finished: a review nobody pressed Combine on is not one (recovery leaves it alone). */
    fun running(): MergeJournal? = all().firstOrNull { it.stage.unfinished && it.started }

    /** The combine that is active (Separate again is possible) or finished lately. */
    fun active(): MergeJournal? = all().firstOrNull { it.stage == MergeStage.Active }

    /** Hash results of the dry run and the real run (outside the work directory: planning clears that one). */
    fun hashFile(mergeId: String) = File(dir, "$mergeId.hashes.db")

    @Synchronized fun deleteWork(mergeId: String) {
        workDir(mergeId).deleteRecursively()
        hashFile(mergeId).delete()
    }

    fun update(mergeId: String, change: (MergeJournal) -> MergeJournal): MergeJournal {
        val now = read(mergeId) ?: throw IllegalStateException("The merge journal is missing.")
        val next = change(now)
        write(next)
        return next
    }
}

internal fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { opt(it) as? String }
