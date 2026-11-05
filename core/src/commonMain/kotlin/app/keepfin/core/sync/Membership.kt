package app.keepfin.core.sync

import app.keepfin.core.net.base64Decode
import app.keepfin.core.net.deviceIdOf
import app.keepfin.core.net.isP256Spki

// Library membership (network spec 9.1 #1 and #2): the trust root of a library. Membership is a pure function of the
// member ops a replica holds (with the device id that signed each one's segment), so every replica that holds the same
// ops reaches the same verdicts, whatever order they arrived in.
//
//   member.genesis {actors:[{actor, seq, lastSegmentHash}], deviceKey}   the migrating or creating device: it becomes
//       the first admin, and freezes the unsigned history (an actor's ops up to `seq` may stay unsigned; no others)
//   member.grant {actor, deviceKey, perms, epoch, sealSeq?}             a member with `admin` adds or changes a member;
//       a member may also rotate its own key (signed by the old key). A key change seals the old key at `sealSeq`
//       (for a self rotation: the rotation op's own seq)
//   member.revoke {actor, cutoff:{seq, segmentHash}}                    ops of the actor above cutoff.seq are not applied
//
// A library with no genesis is not enforced at all (so libraries made before signing keep working). Once a genesis
// exists: unsigned ops beyond the frozen history are set aside (`unsigned`), ops of an actor that is not a member or
// was signed by a key it does not hold are set aside (`unauthorized`), and ops above a revocation cutoff are set aside
// (`revoked`). Set-aside ops are kept, never applied. Revocation goes by sequence, never by time; concurrent
// revocations of one actor all apply (the lowest cutoff wins).

internal const val MEMBER_GENESIS = "member.genesis"
internal const val MEMBER_GRANT = "member.grant"
internal const val MEMBER_REVOKE = "member.revoke"

internal fun isMemberKind(kind: String): Boolean = kind == MEMBER_GENESIS || kind == MEMBER_GRANT || kind == MEMBER_REVOKE

class FrozenActor(val seq: Long, val lastSegmentHash: String)

internal sealed class MemberBody {
    class Genesis(val deviceKey: ByteArray, val deviceId: String, val actors: Map<String, FrozenActor>) : MemberBody()
    class Grant(val actor: String, val deviceKey: ByteArray, val deviceId: String, val perms: Set<String>, val epoch: Long, val sealSeq: Long?) : MemberBody()
    class Revoke(val actor: String, val cutoffSeq: Long, val cutoffHash: String) : MemberBody()
}

internal class MemberParse(val body: MemberBody?, val error: String?)

private fun isSha256Hex(s: String): Boolean = s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }

private fun bad(why: String) = MemberParse(null, why)

private fun keyOf(v: Jv?): ByteArray? {
    val text = (v as? JStr)?.value ?: return null
    val bytes = base64Decode(text) ?: return null
    return if (isP256Spki(bytes)) bytes else null
}

/** Validates a member op's body (the schema rules of admission). */
internal fun parseMemberOp(op: Op): MemberParse {
    val b = op.body
    when (op.kind) {
        MEMBER_GENESIS -> {
            val key = keyOf(b["deviceKey"]) ?: return bad("deviceKey is not a P-256 key")
            val list = (b["actors"] as? JArr)?.items ?: return bad("actors is not a list")
            if (list.size > MAX_LIST) return bad("actors is too long")
            val out = LinkedHashMap<String, FrozenActor>()
            for (x in list) {
                val m = (x as? JObj)?.members ?: return bad("actors holds a non-object")
                if (m.keys != setOf("actor", "seq", "lastSegmentHash")) return bad("an actor entry has wrong fields")
                val actor = (m["actor"] as? JStr)?.value ?: return bad("actor is missing")
                if (!isUuid(actor)) return bad("actor is not an id")
                val seq = (m["seq"] as? JNum)?.value ?: return bad("seq is missing")
                if (seq < 1) return bad("seq must be at least 1")
                val hash = (m["lastSegmentHash"] as? JStr)?.value ?: return bad("lastSegmentHash is missing")
                if (!isSha256Hex(hash)) return bad("lastSegmentHash is not a SHA-256")
                if (out.put(actor, FrozenActor(seq, hash)) != null) return bad("an actor is listed twice")
            }
            return MemberParse(MemberBody.Genesis(key, deviceIdOf(key), out), null)
        }
        MEMBER_GRANT -> {
            val actor = (b["actor"] as? JStr)?.value ?: return bad("actor is missing")
            if (!isUuid(actor)) return bad("actor is not an id")
            val key = keyOf(b["deviceKey"]) ?: return bad("deviceKey is not a P-256 key")
            val permsArr = (b["perms"] as? JArr)?.items ?: return bad("perms is not a list")
            if (permsArr.size > 32) return bad("perms is too long")
            val perms = LinkedHashSet<String>()
            for (p in permsArr) {
                val s = (p as? JStr)?.value ?: return bad("perms holds a non-string")
                if (s.isEmpty() || s.length > 32 || !s.all { it in 'a'..'z' || it == '.' || it == '-' }) return bad("a permission name is invalid")
                perms.add(s)
            }
            val epoch = (b["epoch"] as? JNum)?.value ?: return bad("epoch is missing")
            if (epoch < 1) return bad("epoch must be at least 1")
            var seal: Long? = null
            if (b.containsKey("sealSeq")) {
                seal = (b["sealSeq"] as? JNum)?.value ?: return bad("sealSeq is not a number")
                if (seal < 0) return bad("sealSeq is negative")
            }
            val allowed = setOf("actor", "deviceKey", "perms", "epoch", "sealSeq")
            if (!allowed.containsAll(b.keys)) return bad("unknown field")
            return MemberParse(MemberBody.Grant(actor, key, deviceIdOf(key), perms, epoch, seal), null)
        }
        MEMBER_REVOKE -> {
            val actor = (b["actor"] as? JStr)?.value ?: return bad("actor is missing")
            if (!isUuid(actor)) return bad("actor is not an id")
            val cut = (b["cutoff"] as? JObj)?.members ?: return bad("cutoff is missing")
            if (cut.keys != setOf("seq", "segmentHash")) return bad("cutoff has wrong fields")
            val seq = (cut["seq"] as? JNum)?.value ?: return bad("cutoff.seq is missing")
            if (seq < 0) return bad("cutoff.seq is negative")
            val hash = (cut["segmentHash"] as? JStr)?.value ?: return bad("cutoff.segmentHash is missing")
            if (hash.isNotEmpty() && !isSha256Hex(hash)) return bad("cutoff.segmentHash is not a SHA-256")
            if (b.keys != setOf("actor", "cutoff")) return bad("unknown field")
            return MemberParse(MemberBody.Revoke(actor, seq, hash), null)
        }
    }
    return bad("not a member op")
}

/** One member op with the device id that signed its segment (null: an unsigned segment). */
class SignedOp(val op: Op, val signer: String?)

class KeyEntry(val deviceId: String, val spki: ByteArray, var until: Long?)

class MemberInfo(val actor: String, val keys: List<KeyEntry>, val perms: Set<String>, val epoch: Long, val cutoff: Long?) {
    fun fingerprint(): String =
        actor + "|" + keys.joinToString(",") { it.deviceId + "<=" + (it.until ?: "") } + "|" + perms.sorted().joinToString(",") + "|" + epoch + "|" + (cutoff ?: "")
}

private class Rec(val actor: String, var keys: MutableList<KeyEntry>, var perms: Set<String>, var epoch: Long, var cutoff: Long?) {
    fun current(): KeyEntry = keys.last()
    fun info() = MemberInfo(actor, keys.map { KeyEntry(it.deviceId, it.spki, it.until) }, perms, epoch, cutoff)
}

private class Entry(val op: Op, val signer: String?, val body: MemberBody)

private class Fold(val genesis: Entry?, val members: Map<String, Rec>, val effective: Set<OpId>)

/** What changed when a member op was added: [all] when enforcement started, else the actors whose verdicts may differ. */
class MembershipChange(val all: Boolean, val actors: Set<String>) {
    val none: Boolean get() = !all && actors.isEmpty()

    companion object {
        val NONE = MembershipChange(false, emptySet())
    }
}

class Membership {
    private val ops = LinkedHashMap<OpId, Entry>()
    private var fold = Fold(null, emptyMap(), emptySet())
    private var frozen: Map<String, FrozenActor> = emptyMap()

    /** True once a valid genesis is held: the rules below apply. */
    val active: Boolean get() = fold.genesis != null

    /** The member ops held, in the order they came (for persisting). */
    fun records(): List<SignedOp> = ops.values.map { SignedOp(it.op, it.signer) }

    fun has(id: OpId): Boolean = ops.containsKey(id)

    fun members(): Map<String, MemberInfo> = fold.members.mapValues { it.value.info() }

    fun frozenHistory(): Map<String, FrozenActor> = frozen

    /** The SPKI of a key of any member, by device id, or null. */
    fun keyOf(deviceId: String): ByteArray? {
        for (r in fold.members.values) for (k in r.keys) if (k.deviceId == deviceId) return k.spki
        return null
    }

    /** The highest seq of [actor] that revocation still lets stand, or null when the actor is not revoked. */
    fun cutoff(actor: String): Long? = fold.members[actor]?.cutoff

    /**
     * Adds a member op that admission judged valid. [signer] is the device id of the verified signature of the
     * segment it came in, or null for an unsigned segment. Returns what changed in the verdicts of other ops.
     */
    fun add(op: Op, signer: String?): MembershipChange {
        if (ops.containsKey(op.id)) return MembershipChange.NONE
        val parsed = parseMemberOp(op).body ?: return MembershipChange.NONE
        ops[op.id] = Entry(op, signer, parsed)
        val before = fold
        fold = compute()
        frozen = fold.genesis?.let { (it.body as MemberBody.Genesis).actors } ?: emptyMap()
        if (before.genesis == null && fold.genesis != null) return MembershipChange(true, emptySet())
        val was = before.genesis
        val now = fold.genesis
        if (was != null && now != null && was.op.id != now.op.id) return MembershipChange(true, emptySet())
        val changed = LinkedHashSet<String>()
        for ((actor, r) in fold.members) {
            val old = before.members[actor]
            if (old == null || old.info().fingerprint() != r.info().fingerprint()) changed.add(actor)
        }
        for (actor in before.members.keys) if (!fold.members.containsKey(actor)) changed.add(actor)
        return MembershipChange(false, changed)
    }

    /**
     * Why the op [actor]:[seq] signed by [signer] must be set aside, or null when it may be applied: `unsigned` (a segment
     * without a signature after genesis, beyond the frozen history), `unauthorized` (not a member, or a key the actor
     * does not hold for that seq) or `revoked` (above the cutoff of a revocation). Always null while no genesis is held.
     */
    fun verdict(actor: String, seq: Long, signer: String?): String? {
        val g = fold.genesis ?: return null
        val rec = fold.members[actor]
        val cut = rec?.cutoff
        if (cut != null && seq > cut) return "revoked"
        val freeze = frozen[actor]
        if (signer == null) return if (freeze != null && seq <= freeze.seq) null else "unsigned"
        if (rec == null) return if (g.op.id.actor == actor) null else "unauthorized"
        for (k in rec.keys) if (k.deviceId == signer && (k.until == null || seq <= k.until!!)) return null
        return "unauthorized"
    }

    /** The verdict for a member op itself: only an op the membership counted as effective stands. */
    fun memberOpVerdict(id: OpId, signer: String?): String? {
        if (fold.genesis == null) return null
        if (fold.effective.contains(id)) return null
        val cut = fold.members[id.actor]?.cutoff
        if (cut != null && id.seq > cut) return "revoked"
        return if (signer == null) "unsigned" else "unauthorized"
    }

    // ---- the computation --------------------------------------------------------------------------------------------

    private fun compute(): Fold {
        var genesis: Entry? = null
        val sorted = ops.values.sortedWith { a, b ->
            val c = a.op.hlc.compareTo(b.op.hlc)
            if (c != 0) c else a.op.id.compareTo(b.op.id)
        }
        for (e in sorted) {
            val body = e.body as? MemberBody.Genesis ?: continue
            if (e.signer == null || e.signer != body.deviceId) continue
            if (genesis == null) genesis = e // the lowest (hlc, id) among valid genesis ops
        }
        if (genesis == null) return Fold(null, emptyMap(), emptySet())
        val excluded = HashSet<OpId>()
        while (true) {
            val f = foldOnce(genesis, sorted, excluded)
            val more = ArrayList<OpId>()
            for (id in f.effective) {
                if (id == genesis.op.id) continue
                val cut = f.members[id.actor]?.cutoff ?: continue
                if (id.seq > cut) more.add(id)
            }
            if (more.isEmpty()) return f
            excluded.addAll(more)
        }
    }

    private fun foldOnce(genesis: Entry, sorted: List<Entry>, excluded: Set<OpId>): Fold {
        val g = genesis.body as MemberBody.Genesis
        val members = LinkedHashMap<String, Rec>()
        members[genesis.op.id.actor] = Rec(genesis.op.id.actor, arrayListOf(KeyEntry(g.deviceId, g.deviceKey, null)), setOf("admin"), 0, null)
        val effective = LinkedHashSet<OpId>()
        effective.add(genesis.op.id)
        for (e in sorted) {
            if (e.body is MemberBody.Genesis || excluded.contains(e.op.id) || e.signer == null) continue
            val author = members[e.op.id.actor] ?: continue
            val authorOk = author.keys.any { it.deviceId == e.signer && (it.until == null || e.op.id.seq <= it.until!!) } &&
                (author.cutoff == null || e.op.id.seq <= author.cutoff!!)
            if (!authorOk) continue
            val isAdmin = "admin" in author.perms
            when (val b = e.body) {
                is MemberBody.Grant -> {
                    val target = members[b.actor]
                    if (target == null) {
                        if (isAdmin) {
                            members[b.actor] = Rec(b.actor, arrayListOf(KeyEntry(b.deviceId, b.deviceKey, null)), b.perms, b.epoch, null)
                            effective.add(e.op.id)
                        }
                    } else if (target.cutoff == null && b.epoch > target.epoch) {
                        val sameKey = target.current().deviceId == b.deviceId
                        if (sameKey && isAdmin) {
                            target.perms = b.perms
                            target.epoch = b.epoch
                            effective.add(e.op.id)
                        } else if (!sameKey && (isAdmin || b.actor == e.op.id.actor)) {
                            val selfRotation = b.actor == e.op.id.actor
                            val seal = if (selfRotation) e.op.id.seq else b.sealSeq
                            if (seal != null) {
                                val old = target.current()
                                old.until = if (old.until == null) seal else minOf(old.until!!, seal)
                                target.keys.add(KeyEntry(b.deviceId, b.deviceKey, null))
                                if (!selfRotation) target.perms = b.perms
                                target.epoch = b.epoch
                                effective.add(e.op.id)
                            }
                        }
                    }
                }
                is MemberBody.Revoke -> {
                    val target = members[b.actor]
                    if (isAdmin && target != null) {
                        target.cutoff = if (target.cutoff == null) b.cutoffSeq else minOf(target.cutoff!!, b.cutoffSeq)
                        effective.add(e.op.id)
                    }
                }
                else -> {}
            }
        }
        return Fold(genesis, members, effective)
    }
}

/** The membership as canonical JSON, for the hosts' UI and for tests. */
fun membershipJson(m: Membership): String {
    val members = LinkedHashMap<String, Jv>()
    for ((actor, info) in m.members().entries.sortedBy { it.key }) {
        members[actor] = jObj(
            "cutoff" to (info.cutoff?.let { JNum(it) } ?: JNull),
            "epoch" to JNum(info.epoch),
            "keys" to JArr(
                info.keys.map {
                    jObj(
                        "device" to JStr(it.deviceId),
                        "key" to JStr(app.keepfin.core.net.base64Encode(it.spki)),
                        "until" to (it.until?.let { u -> JNum(u) } ?: JNull),
                    )
                },
            ),
            "perms" to JArr(info.perms.sorted().map { JStr(it) }),
        )
    }
    val frozen = LinkedHashMap<String, Jv>()
    for ((actor, f) in m.frozenHistory().entries.sortedBy { it.key }) frozen[actor] = jObj("lastSegmentHash" to JStr(f.lastSegmentHash), "seq" to JNum(f.seq))
    return encodeCanonical(jObj("active" to JBool(m.active), "frozen" to JObj(frozen), "members" to JObj(members)))
}
