package app.keepfin.net

import app.keepfin.core.net.NetAction
import app.keepfin.core.net.PeerGrants
import app.keepfin.core.net.defaultGrants
import app.keepfin.folder.writeLocalAtomic
import java.io.File
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// Paired devices (spec 1): `{deviceId, spki pin, name, platform, libraries:[{libraryId, actor, actions}], pairedAt}`, kept device-only in
// `<filesDir>/net/peers.json` and never synced. Removing a peer deletes its pin here; revoking it in the library is the service's job.
// Writes are atomic and serialized. The layout matches the desktop's peers.json, plus `allowMobileFiles` (spec 7).

class PeerLibrary(val libraryId: String, var actor: String, var actions: Set<NetAction>)

class Peer(
    val deviceId: String,
    /** SPKI DER, Base64. */
    val spki: String,
    var name: String,
    var platform: String,
    val pairedAt: Long,
    var lastSeen: Long?,
    var libraries: List<PeerLibrary>,
    /** Last address that worked (a hint for reconnecting; never trusted). */
    var lastHost: String? = null,
    var lastPort: Int? = null,
    /** Since when the device could not be reached (the first failed attempt after it was last seen); null while it answers. */
    var unreachableSince: Long? = null,
    /** File transfers with this device may use mobile data (spec 7); metadata sync always may. */
    var allowMobileFiles: Boolean = false,
    /** Addresses the device told us in `hello` (LAN, then Tailscale), newest first, at most [MAX_ADDRESSES]. Hints only. */
    var addresses: List<String> = emptyList(),
)

const val MAX_ADDRESSES = 6

/** The longest device name kept from a hello. */
const val MAX_NAME = 64

/** True for an address that reaches a device over Tailscale: a 100.64/10 number or a name (MagicDNS). */
fun isTailscaleRoute(host: String): Boolean = routeOf(host) == "tailscale"

/** A hello address worth keeping: a dotted IPv4 number or a plain host name, never a path, a port or text with spaces. */
private fun keepableAddress(a: String): Boolean = a.length in 1..253 && (parseV4(a) != null || Regex("^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$").matches(a))

/** [fresh] first, then what was kept; duplicates dropped, at most [MAX_ADDRESSES], and a couple of Tailscale entries survive a long LAN list. */
fun mergeAddresses(fresh: List<String>, old: List<String>): List<String> {
    val merged = (fresh.filter(::keepableAddress) + old).distinct()
    if (merged.size <= MAX_ADDRESSES) return merged
    val ts = merged.filter(::isTailscaleRoute)
    val lan = merged.filter { !isTailscaleRoute(it) }
    val lanKeep = lan.take(MAX_ADDRESSES - minOf(ts.size, 2))
    val tsKeep = ts.take(MAX_ADDRESSES - lanKeep.size)
    return merged.filter { it in lanKeep || it in tsKeep }
}

/** One address to try, in the order a link tries them. */
class Route(val host: String, val port: Int)

/**
 * Where to reach a paired device, best first: what discovery just saw (DNS-SD or scan), the last address that worked, the stored LAN
 * addresses, then the stored Tailscale addresses and names. Each host once.
 */
fun reconnectOrder(discovered: List<Route>, lastHost: String?, lastPort: Int?, stored: List<String>): List<Route> {
    val port = lastPort ?: DEFAULT_PORT
    val out = ArrayList<Route>()
    out += discovered
    if (lastHost != null) out += Route(lastHost, port)
    out += stored.filter { !isTailscaleRoute(it) }.map { Route(it, port) }
    out += stored.filter { isTailscaleRoute(it) }.map { Route(it, port) }
    return out.distinctBy { "${it.host}:${it.port}" }
}

private val ACTIONS: Map<String, NetAction> = NetAction.entries.filter { it != NetAction.Hello && it != NetAction.Pair }.associateBy { it.wire }

class PeerStore private constructor(private val file: File) {
    private val peers = LinkedHashMap<String, Peer>()

    @Synchronized fun list(): List<Peer> = peers.values.sortedBy { it.pairedAt }

    @Synchronized fun get(deviceId: String): Peer? = peers[deviceId]

    /** The authorization view of a peer (a removed peer is simply absent). */
    @Synchronized fun grants(deviceId: String): PeerGrants? {
        val peer = peers[deviceId] ?: return null
        return PeerGrants(deviceId, false, peer.libraries.associate { it.libraryId to it.actions })
    }

    /** Adds or replaces a peer; pairing again keeps what the person chose before for libraries both sides still share. */
    @Synchronized fun put(peer: Peer) {
        val before = peers[peer.deviceId]
        peer.libraries = peer.libraries.map { entry ->
            val old = before?.libraries?.firstOrNull { it.libraryId == entry.libraryId }
            PeerLibrary(entry.libraryId, entry.actor, old?.actions ?: entry.actions.ifEmpty { defaultGrants() })
        }
        if (before != null) {
            peer.allowMobileFiles = before.allowMobileFiles
            peer.addresses = mergeAddresses(peer.addresses, before.addresses)
        }
        peers[peer.deviceId] = peer
        save()
    }

    @Synchronized fun update(deviceId: String, change: (Peer) -> Unit): Peer? {
        val peer = peers[deviceId] ?: return null
        change(peer)
        save()
        return peer
    }

    /** `lastSeen` and the address that worked, written at most once a minute. */
    @Synchronized fun touch(deviceId: String, now: Long, host: String?, port: Int?) {
        val peer = peers[deviceId] ?: return
        if (peer.lastSeen != null && now - peer.lastSeen!! < 60_000 && peer.lastHost == host) return
        peer.lastSeen = now
        if (host != null) {
            peer.lastHost = host
            peer.lastPort = port
        }
        runCatching { save() }
    }

    /** The device answered (clears "unreachable since") or did not (sets it once, to [now]). */
    @Synchronized fun setReachable(deviceId: String, reachable: Boolean, now: Long) {
        val peer = peers[deviceId] ?: return
        if (reachable) {
            if (peer.unreachableSince == null) return
            peer.unreachableSince = null
        } else {
            if (peer.unreachableSince != null) return
            peer.unreachableSince = now
        }
        runCatching { save() }
    }

    /** The addresses a device named in its hello (newest first, at most six). Saved only when they changed. */
    @Synchronized fun learnAddresses(deviceId: String, fresh: List<String>) {
        val peer = peers[deviceId] ?: return
        val next = mergeAddresses(fresh, peer.addresses)
        if (next == peer.addresses) return
        peer.addresses = next
        runCatching { save() }
    }

    /** The name a device gives itself in its hello (it was renamed there). Saved only when it changed; a blank name keeps the old one. */
    @Synchronized fun learnName(deviceId: String, name: String) {
        val peer = peers[deviceId] ?: return
        val next = name.trim().take(MAX_NAME)
        if (next.isEmpty() || next == peer.name) return
        peer.name = next
        runCatching { save() }
    }

    @Synchronized fun remove(deviceId: String): Peer? {
        val peer = peers.remove(deviceId) ?: return null
        save()
        return peer
    }

    private fun save() {
        val array = JSONArray()
        for (p in list()) {
            val libs = JSONArray()
            for (l in p.libraries) libs.put(JSONObject().put("libraryId", l.libraryId).put("actor", l.actor).put("actions", JSONArray(l.actions.map { it.wire })))
            val o = JSONObject().put("deviceId", p.deviceId).put("spki", p.spki).put("name", p.name).put("platform", p.platform).put("pairedAt", p.pairedAt)
                .put("lastSeen", p.lastSeen ?: JSONObject.NULL).put("libraries", libs).put("allowMobileFiles", p.allowMobileFiles)
            p.lastHost?.let { o.put("lastHost", it) }
            p.lastPort?.let { o.put("lastPort", it) }
            p.unreachableSince?.let { o.put("unreachableSince", it) }
            if (p.addresses.isNotEmpty()) o.put("addresses", JSONArray(p.addresses))
            array.put(o)
        }
        writeLocalAtomic(file, JSONObject().put("v", 1).put("peers", array).toString(2).toByteArray(Charsets.UTF_8))
    }

    companion object {
        fun open(dir: File): PeerStore {
            val store = PeerStore(File(dir, "peers.json"))
            val text = try {
                if (store.file.isFile) store.file.readText(Charsets.UTF_8) else return store
            } catch (e: java.io.IOException) {
                return store
            }
            try {
                val root = JSONObject(text)
                if (root.optInt("v") != 1) return store
                val array = root.optJSONArray("peers") ?: return store
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val id = o.optString("deviceId").takeIf { it.isNotEmpty() } ?: continue
                    val spki = o.optString("spki").takeIf { it.isNotEmpty() } ?: continue
                    val libs = ArrayList<PeerLibrary>()
                    val la = o.optJSONArray("libraries") ?: JSONArray()
                    for (j in 0 until la.length()) {
                        val l = la.optJSONObject(j) ?: continue
                        val actions = LinkedHashSet<NetAction>()
                        val aa = l.optJSONArray("actions") ?: JSONArray()
                        for (k in 0 until aa.length()) ACTIONS[aa.optString(k)]?.let { actions += it }
                        libs += PeerLibrary(l.optString("libraryId"), l.optString("actor"), actions)
                    }
                    store.peers[id] = Peer(
                        deviceId = id, spki = spki, name = o.optString("name", "Another device"), platform = o.optString("platform", "unknown"),
                        pairedAt = o.optLong("pairedAt"), lastSeen = if (o.isNull("lastSeen")) null else o.optLong("lastSeen"), libraries = libs,
                        lastHost = o.optString("lastHost").takeIf { it.isNotEmpty() }, lastPort = if (o.has("lastPort")) o.optInt("lastPort") else null,
                        unreachableSince = if (o.has("unreachableSince")) o.optLong("unreachableSince") else null,
                        allowMobileFiles = o.optBoolean("allowMobileFiles", false),
                        addresses = o.optJSONArray("addresses")?.let { a -> mergeAddresses((0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotEmpty() } }, emptyList()).take(MAX_ADDRESSES) } ?: emptyList(),
                    )
                }
            } catch (e: JSONException) {
                // a damaged file is read as no peers; the person pairs again (a pin is never guessed)
            }
            return store
        }
    }
}
