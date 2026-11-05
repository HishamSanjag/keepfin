package app.keepfin.net

// What Settings -> Devices shows (mirrors gui/shared/devices.ts). Immutable snapshots; the service publishes a new one when anything changes.

class ThisDevice(val name: String, val fingerprint: String, val platform: String)

/** [actor] is the identity the device writes this library as (empty until it joined): how its placements are told apart. */
class PairedLibraryUi(val libraryId: String, val name: String, val actions: List<String>, val actor: String = "")

class ShareableUi(val libraryId: String, val name: String)

class PairedDeviceUi(
    val deviceId: String,
    val fingerprint: String,
    val name: String,
    val platform: String,
    val pairedAt: Long,
    val lastSeen: Long?,
    val online: Boolean,
    val libraries: List<PairedLibraryUi>,
    /** Since when the device could not be reached ("Not reachable since"); null while it answers. */
    val unreachableSince: Long?,
    val address: String?,
    /** Whether this device accepts "Download on..." from here, and where it may be saved. */
    val jobsAllowed: Boolean,
    val jobSpaces: List<Pair<String, String>>,
    val shareable: List<ShareableUi>,
    val allowMobileFiles: Boolean,
    /** How the device is reached right now: lan | tailscale | none (not reachable). */
    val route: String = "none",
    /** When this phone last finished syncing the library with it (since Keepfin started); null before the first pass. */
    val lastSync: LastSyncUi? = null,
)

class LastSyncUi(val at: Long, val pulled: Int, val pushed: Int)

/** [route]: lan | tailscale, how the device was found (a 100.64/10 address or a name is Tailscale). */
class DiscoveredUi(val hint: String, val name: String, val host: String, val port: Int, val route: String = routeOf(host))

/** What this phone is connected to: a Wi-Fi/Ethernet network, and Tailscale (an up interface with a 100.64/10 address, which is [tailscaleAddress]). */
class NetworkUi(val lan: Boolean, val tailscaleAddress: String?) {
    val tailscale: Boolean get() = tailscaleAddress != null

    /** Neither a local network nor Tailscale: no other device can reach this phone or be reached. */
    val none: Boolean get() = !lan && tailscaleAddress == null
}

/** role: asking | answering. stage: connecting | comparing | waiting | approving | done | failed. */
data class PairingUi(
    val id: String,
    val method: String,
    val role: String,
    val stage: String,
    val peerName: String,
    val peerFingerprint: String,
    val sas: String?,
    val error: String?,
    val expiresAt: Long,
    /** The other device asked on its own while no window was open here ("<name> wants to connect"). */
    val unrequested: Boolean = false,
    /** A scanned code authorized it: nobody is asked here; show "<name> connected" with Undo once done. */
    val autoApproved: Boolean = false,
    /** The other device's id (answering side), so Undo can remove it. */
    val peerDeviceId: String = "",
)

/** A one-use pairing link for the QR and, for someone without a camera, the typed [code] (XXXXX-XXXXX) that does the same; both renew together. */
class QrUi(val link: String, val expiresAt: Long, val code: String? = null)

class OfferUi(val deviceId: String, val deviceName: String, val libraryId: String, val name: String, val online: Boolean)

/** stage: folder | asking | pulling | done | failed. */
class JoinUi(
    val deviceId: String,
    val deviceName: String,
    val libraryId: String,
    val name: String,
    val stage: String,
    val segments: Int,
    val error: String?,
    val resumable: Boolean,
)

class RemoteJobRowUi(
    val requestId: String,
    val deviceId: String,
    val deviceName: String,
    val url: String,
    val state: String,
    val percent: Double,
    val title: String?,
    val label: String?,
    val totalBytes: Long?,
    val error: String?,
    val createdAt: Long,
)

class LibraryInfoUi(val libraryId: String, val name: String, val signed: Boolean, val admin: Boolean)

class DevicesState(
    val available: Boolean,
    val thisDevice: ThisDevice,
    val enabled: Boolean,
    val port: Int?,
    val portError: String?,
    val libraries: List<LibraryInfoUi>,
    val paired: List<PairedDeviceUi>,
    val discovered: List<DiscoveredUi>,
    val pairingUntil: Long?,
    val pairing: PairingUi?,
    val qr: QrUi?,
    val notes: List<String>,
    val offers: List<OfferUi>,
    val join: JoinUi?,
    val remoteJobs: List<RemoteJobRowUi>,
    /** Jobs other devices asked this phone for ("From <device>"), with the ones waiting for the person here. */
    val incomingJobs: List<IncomingJobRow>,
    val keepReachable: Boolean,
    /** The phone is on mobile data (or another metered network) right now. */
    val metered: Boolean,
    val network: NetworkUi = NetworkUi(lan = true, tailscaleAddress = null),
    /** A scan of the local network is running (Nearby says "Looking on this network…"). */
    val scanning: Boolean = false,
) {
    companion object {
        fun unavailable(name: String) = DevicesState(false, ThisDevice(name, "", "android"), false, null, null, emptyList(), emptyList(), emptyList(), null, null, null, emptyList(), emptyList(), null, emptyList(), emptyList(), false, false)
    }
}

class PlayFromResult(val uri: String, val deviceName: String, val size: Long)

/** The result of removing a device: the libraries where it was revoked, and the ones where this phone could not (it is not an administrator). */
class RemoveResult(val revoked: List<String>, val notRevoked: List<String>)

/** "host[:port]" with the default port; bracketed IPv6; null when it is not an address. */
fun parseAddress(text: String): Address? {
    val t = text.trim()
    if (t.isEmpty() || t.length > 253 || t.any { it.isWhitespace() } || t.contains("://") || t.contains('/')) return null
    if (t.startsWith("[")) {
        val end = t.indexOf(']')
        if (end < 2) return null
        val host = t.substring(1, end)
        if (!host.all { it.isLetterOrDigit() || it == ':' || it == '.' }) return null
        val rest = t.substring(end + 1)
        if (rest.isEmpty()) return Address(host, DEFAULT_PORT)
        if (!rest.startsWith(":")) return null
        val port = rest.substring(1).toIntOrNull() ?: return null
        return if (port in 1..65535) Address(host, port) else null
    }
    val colons = t.count { it == ':' }
    if (colons > 1) return if (t.all { it.isLetterOrDigit() || it == ':' || it == '.' }) Address(t, DEFAULT_PORT) else null
    val host: String
    var port = DEFAULT_PORT
    if (colons == 1) {
        host = t.substringBefore(':')
        port = t.substringAfter(':').toIntOrNull() ?: return null
        if (port !in 1..65535) return null
    } else host = t
    if (host.isEmpty() || !Regex("^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$").matches(host)) return null
    return Address(host, port)
}

/** An address as shown and stored: IPv6 in brackets, the port only when it is not the default. */
fun formatAddress(host: String, port: Int): String {
    val h = if (host.contains(':')) "[$host]" else host
    return if (port == DEFAULT_PORT) h else "$h:$port"
}
