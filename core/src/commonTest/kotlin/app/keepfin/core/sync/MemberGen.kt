package app.keepfin.core.sync

import app.keepfin.core.net.SoftwareSigner
import app.keepfin.core.net.base64Encode
import app.keepfin.core.net.deviceIdOf
import kotlin.random.Random

/** One op as a replica receives it: the op and the device id that signed its segment (null: unsigned). */
class Delivery(val op: Op, val signer: String?)

/** A library member in a test: an actor id, its keys (the last one is current) and its next seq. */
class TestMember(rnd: Random) {
    val actor = uuid(rnd)
    val keys = arrayListOf(SoftwareSigner.generate())
    var seq = 0L
    var epoch = 0L
    val signer: SoftwareSigner get() = keys.last()
    val id: String get() = deviceIdOf(signer.spki)
}

/** Builds membership scenarios by hand and at random. */
class MemberWorld(seed: Int, count: Int = 4) {
    val rnd = Random(seed)
    val members = List(count) { TestMember(rnd) }
    private var tick = 0L

    fun op(m: TestMember, kind: String, body: Map<String, Jv>): Op {
        m.seq++
        return Op(OpId(m.actor, m.seq), Hlc(1_700_000_000_000L + tick++, 0, m.actor), kind, body)
    }

    fun profile(m: TestMember, label: String): Op = op(m, "profile.set", mapOf("field" to JStr(label), "value" to JStr(label)))

    fun genesis(m: TestMember, frozen: Map<TestMember, Long>): Op = op(
        m, "member.genesis",
        mapOf(
            "deviceKey" to JStr(base64Encode(m.signer.spki)),
            "actors" to JArr(frozen.map { (a, s) -> jObj("actor" to JStr(a.actor), "seq" to JNum(s), "lastSegmentHash" to JStr(sha256Hex("seg" + a.actor))) }),
        ),
    )

    fun grant(by: TestMember, target: TestMember, perms: List<String>, key: SoftwareSigner = target.signer, sealSeq: Long? = null): Op {
        val body = LinkedHashMap<String, Jv>()
        body["actor"] = JStr(target.actor)
        body["deviceKey"] = JStr(base64Encode(key.spki))
        body["perms"] = JArr(perms.map { JStr(it) })
        body["epoch"] = JNum(++target.epoch)
        if (sealSeq != null) body["sealSeq"] = JNum(sealSeq)
        return op(by, "member.grant", body)
    }

    fun revoke(by: TestMember, target: TestMember, cutoff: Long): Op =
        op(by, "member.revoke", mapOf("actor" to JStr(target.actor), "cutoff" to jObj("seq" to JNum(cutoff), "segmentHash" to JStr(""))))

    fun d(op: Op, signer: String?) = Delivery(op, signer)

    /** A random history: unsigned history, a genesis, then writes, grants, rotations, revocations and rival genesis ops. */
    fun generate(n: Int): List<Delivery> {
        val out = ArrayList<Delivery>()
        val root = members[0]
        repeat(2 + rnd.nextInt(3)) { out.add(d(profile(root, "h${root.seq}"), null)) }
        out.add(d(genesis(root, mapOf(root to root.seq)), root.id))
        repeat(n) { i ->
            val m = members[if (rnd.nextInt(3) == 0) 0 else rnd.nextInt(members.size)]
            val r = rnd.nextInt(100)
            when {
                r < 55 -> {
                    val roll = rnd.nextInt(100)
                    val signer = if (roll < 82) m.id else if (roll < 90) null else members[rnd.nextInt(members.size)].id
                    out.add(d(profile(m, "p$i"), signer))
                }
                r < 72 -> {
                    val target = members[rnd.nextInt(members.size)]
                    val perms = if (rnd.nextInt(2) == 0) listOf("admin") else listOf("write")
                    val rotate = rnd.nextInt(10) < 3
                    val newKey = if (rotate) SoftwareSigner.generate() else target.signer
                    val signer = if (rnd.nextInt(10) == 0) null else m.id
                    out.add(d(grant(m, target, perms, newKey, if (rotate && m !== target) target.seq else null), signer))
                    if (rotate) target.keys.add(newKey)
                }
                r < 84 -> {
                    val target = members[rnd.nextInt(members.size)]
                    out.add(d(revoke(m, target, rnd.nextLong(target.seq + 1)), m.id))
                }
                r < 88 -> {
                    val frozen = members.associateWith { it.seq }.filter { it.value > 0 }
                    out.add(d(genesis(m, frozen), m.id))
                }
                else -> out.add(d(profile(m, "u$i"), null))
            }
        }
        return out
    }
}
