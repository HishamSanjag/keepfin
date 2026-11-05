package app.keepfin.copies

import app.keepfin.folder.FolderAccess
import app.keepfin.net.NetService
import app.keepfin.net.PeerCopyTransport
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Copies on paired devices, the phone's side (network spec 5, copies spec 9; the desktop's is `PeerBridge` in gui/desktop/net/peerPlace.ts).
 * Which devices there are and what each lets this phone do come from the live links of the network service; a copy is opened as a
 * [PeerFolderAccess] whose files go over the pinned connection and whose bookkeeping stays on this phone.
 */
internal class PeerRuntime(
    private val service: () -> NetService?,
    /** This phone's name, shown to the other device in its lock. */
    private val deviceName: () -> String,
    /** The library this phone's copies are of. */
    private val libraryId: () -> String?,
) : PeerHooks {
    private val opened = ConcurrentHashMap<String, PeerFolderAccess>()

    private val needed = listOf("inventory.read", "media.read", "copy.mutate")

    override fun isPeer(address: String?): Boolean = PeerAddress.isPeer(address)

    private fun choice(svc: NetService, deviceId: String, libraryId: String?): PeerChoiceUi {
        val peer = svc.peers.get(deviceId) ?: return PeerChoiceUi(deviceId, "A device", false, false, "That device is not paired with this one any more.")
        val link = svc.links[deviceId]
        val online = link?.online == true
        val offered = libraryId?.let { id -> link?.lastHello?.libraries?.firstOrNull { it.libraryId == id } }
        val missing = if (offered == null) needed else needed.filter { it !in offered.actions }
        val why = when {
            !online -> "Not connected right now."
            offered == null -> "${peer.name} does not share this library with you."
            missing.isNotEmpty() -> "On ${peer.name}, turn on \"Keep a copy on this device\" for this device in Settings > Devices."
            else -> null
        }
        return PeerChoiceUi(deviceId, peer.name, online, online && offered != null && missing.isEmpty(), why)
    }

    override fun devices(): List<PeerChoiceUi> {
        val svc = service() ?: return emptyList()
        val id = libraryId()
        return svc.peers.list().map { choice(svc, it.deviceId, id) }
    }

    override fun card(address: String): PeerCardUi? {
        val deviceId = PeerAddress.deviceOf(address) ?: return null
        val svc = service() ?: return PeerCardUi(deviceId, "A device", false, "Devices are not running on this phone right now.")
        val c = choice(svc, deviceId, libraryId())
        val metered = c.allowed && !svc.canMoveFiles(deviceId)
        val waiting = when {
            metered -> "Waiting for Wi-Fi: this phone is on mobile data, and ${c.name} is not set to take files over mobile data."
            !c.allowed -> c.why ?: "${c.name} is not connected right now."
            else -> null
        }
        return PeerCardUi(deviceId, c.name, c.online, waiting?.let { if (!c.online) "${c.name} is not connected right now. The copy continues when it is." else it })
    }

    override fun label(address: String): String {
        val deviceId = PeerAddress.deviceOf(address) ?: return "A device"
        return service()?.peers?.get(deviceId)?.name ?: "A device"
    }

    override fun meta(stateDir: File): FolderAccess = LocalDirAccess(File(stateDir, "peer"))

    override fun open(address: String, stateDir: File, libraryId: String): FolderAccess {
        val deviceId = PeerAddress.deviceOf(address) ?: throw app.keepfin.folder.FolderError.UnsupportedProvider(address)
        val key = "$deviceId|$libraryId|${stateDir.absolutePath}"
        opened[key]?.let { return it }
        synchronized(this) {
            opened[key]?.let { return it }
            val transport = PeerCopyTransport(
                clientOf = { service()?.links?.get(deviceId)?.takeIf { it.online }?.withClient { it } },
                libraryId = libraryId,
                device = deviceName(),
                gate = { service()?.let { s -> if (s.canMoveFiles(deviceId)) null else "Waiting for Wi-Fi: this phone is on mobile data." } },
            )
            val access = PeerFolderAccess(transport, meta(stateDir), File(stateDir, "peer-staging"))
            opened[key] = access
            return access
        }
    }

    /** Forgets the access made for a copy that was removed. */
    override fun forget(stateDir: File) {
        val tail = "|${stateDir.absolutePath}"
        opened.keys.filter { it.endsWith(tail) }.forEach { opened.remove(it) }
    }
}
