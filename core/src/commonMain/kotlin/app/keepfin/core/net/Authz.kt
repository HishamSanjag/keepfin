package app.keepfin.core.net

// The authorization matrix (network spec 9.1 #5): deny by default, per peer and per library. Every request and every
// event payload goes through [authorize]; the transport never decides by itself.

enum class NetAction(val wire: String) {
    /** Reading a library's segments and its event notices. */
    MetadataRead("metadata.read"),

    /** Pushing segments into a library. */
    MetadataWrite("metadata.write"),
    InventoryRead("inventory.read"),

    /** Play from this device: media bytes by content. */
    MediaRead("media.read"),

    /** Copy mutations (create, replace, remove) through the peer's own executor. */
    CopyMutate("copy.mutate"),

    /** Download on... requests. */
    Jobs("jobs"),

    /** Membership admin: asking this device to take part in grants and revocations. */
    MembershipAdmin("membership.admin"),

    /** `hello`: the version and name, for any paired peer. Needs no library. */
    Hello("hello"),

    /** The pairing endpoints, only inside the 2-minute local pairing mode. */
    Pair("pair"),
}

/** What this device lets one paired peer do, by library. Never synced. */
class PeerGrants(
    val deviceId: String,
    val revoked: Boolean,
    val libraries: Map<String, Set<NetAction>>,
)

class AuthzContext(
    /** True only while this device is in explicit local pairing mode. */
    val pairingMode: Boolean,
    /** The pinned peer, or null for a key that is not paired. */
    val peer: PeerGrants?,
)

class Decision(val allow: Boolean, val reason: String) {
    companion object {
        val ALLOW = Decision(true, "ok")
        fun deny(reason: String) = Decision(false, reason)
    }
}

/** What a newly paired peer may do: read and write metadata of the shared libraries, nothing else. */
fun defaultGrants(): Set<NetAction> = setOf(NetAction.MetadataRead, NetAction.MetadataWrite)

fun authorize(ctx: AuthzContext, action: NetAction, library: String?): Decision {
    if (action == NetAction.Pair) {
        // pairing endpoints answer unknown keys, but only in pairing mode and never for anything else
        return if (ctx.pairingMode) Decision.ALLOW else Decision.deny("not-pairing")
    }
    val peer = ctx.peer ?: return Decision.deny("unknown-peer")
    if (peer.revoked) return Decision.deny("revoked")
    if (action == NetAction.Hello) return Decision.ALLOW
    if (library == null) return Decision.deny("no-library")
    val granted = peer.libraries[library] ?: return Decision.deny("library")
    return if (action in granted) Decision.ALLOW else Decision.deny("action")
}

/** An event notice is filtered like the request it stands for: `segments` needs metadata.read, `job` needs jobs. */
fun authorizeEvent(ctx: AuthzContext, kind: String, library: String?): Decision = when (kind) {
    "segments" -> authorize(ctx, NetAction.MetadataRead, library)
    "job" -> authorize(ctx, NetAction.Jobs, library)
    else -> Decision.deny("event")
}
