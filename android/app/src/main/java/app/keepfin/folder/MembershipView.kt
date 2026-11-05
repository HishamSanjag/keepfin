package app.keepfin.folder

import app.keepfin.core.sync.Membership

/** One key a member may sign with: its device id, and the last seq it may sign when it was replaced by a newer key (`until`). */
class MemberKeyView(val deviceId: String, val until: Long?)

class MemberView(val actor: String, val perms: Set<String>, val epoch: Long, val cutoff: Long?, val keys: List<MemberKeyView>) {
    val isAdmin: Boolean get() = "admin" in perms

    /** True when this member holds [deviceId] as its current key and is not revoked. */
    fun holds(deviceId: String): Boolean = cutoff == null && keys.any { it.deviceId == deviceId && it.until == null }
}

/** The library's membership (network spec 9.1 #1): inactive until a `member.genesis` is held. */
class MembershipView(val active: Boolean, val members: Map<String, MemberView>) {
    companion object {
        val INACTIVE = MembershipView(false, emptyMap())

        fun of(m: Membership): MembershipView =
            if (!m.active) INACTIVE
            else MembershipView(true, m.members().mapValues { (_, i) -> MemberView(i.actor, i.perms, i.epoch, i.cutoff, i.keys.map { MemberKeyView(it.deviceId, it.until) }) })
    }
}

/** The first grant written with the membership root: [actor] may write with [spkiBase64] (SPKI, Base64) and the permissions [perms]. */
class MemberGrant(val actor: String, val spkiBase64: String, val perms: List<String>)
