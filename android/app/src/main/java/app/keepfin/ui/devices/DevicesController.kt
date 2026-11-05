package app.keepfin.ui.devices

import android.app.Application
import app.keepfin.EngineApi
import app.keepfin.KeepfinApplication
import app.keepfin.copies.CopiesRuntime
import app.keepfin.core.net.NetAction
import app.keepfin.merge.MergeRuntime
import app.keepfin.folder.FolderAccess
import app.keepfin.net.DevicesState
import app.keepfin.net.NetRuntime
import app.keepfin.net.RemoveResult
import kotlinx.coroutines.flow.StateFlow

/**
 * What Settings -> Devices asks of the network service. The page talks to this, so UI tests drive every state with a fake and the
 * app wires the real service ([ServiceDevicesController]). Every call may block a little (disk, network): the page calls them off the
 * main thread.
 */
interface DevicesController {
    val state: StateFlow<DevicesState>
    fun enable()
    fun disable()
    fun setKeepReachable(on: Boolean)
    fun rename(name: String)
    fun openPairing()
    fun closePairing()
    fun showQr()

    /** The Devices page is open (and the app in front): look for other devices on the network now and every 20 seconds, until [stopLooking]. */
    fun startLooking()
    fun stopLooking()

    /** An address (`name:port`), or a `keepfin://` link from a scanned code. Returns an error in words, or null when pairing began. */
    fun beginAsk(text: String): String?
    fun decide(id: String, match: Boolean)
    fun dismissPairing()
    fun remove(deviceId: String): RemoveResult

    /** "<name> connected" was not meant: the same removal as [remove], and the pairing notice closes. */
    fun undoPair(deviceId: String): RemoveResult

    /** Sync now: try the device again (a device that is back catches up), or all of them when it is already connected. */
    fun syncNow(deviceId: String)
    fun setAccess(deviceId: String, libraryId: String, actions: List<NetAction>)
    fun setAllowMobileFiles(deviceId: String, allow: Boolean)
    fun shareLibrary(deviceId: String, libraryId: String)

    /** Takes in a library a paired device shares into [target] (a new or empty folder). Returns an error in words, or null on success. */
    fun joinLibrary(deviceId: String, libraryId: String, target: FolderAccess, treeUri: String): String?
    fun cancelJoin()
    fun dismissJoin()
    fun allowJob(jobId: String)
    fun declineJob(jobId: String)
    fun dismissRemoteJob(requestId: String)

    /** The copies engine for the setup after pairing and the copy status on a device card; null where this build has none. */
    val copies: DeviceCopies? get() = null

    /** Combining two libraries: the review, the progress and Separate again; null where this build has none. */
    val combine: DeviceCombine? get() = null
}

class ServiceDevicesController(private val app: Application, private val engine: EngineApi) : DevicesController {
    private val service get() = NetRuntime.service(app, engine)
    override val state: StateFlow<DevicesState> get() = service.state

    override fun enable() = service.enable()
    override fun disable() = service.disable()
    override fun setKeepReachable(on: Boolean) = service.setKeepReachable(on)
    override fun rename(name: String) = service.rename(name)
    override fun openPairing() = service.openPairing()
    override fun closePairing() = service.closePairing()
    override fun showQr() = service.showQr()
    override fun startLooking() = service.startLooking()
    override fun stopLooking() = service.stopLooking()

    override fun beginAsk(text: String): String? = try {
        service.beginAsk(text)
        null
    } catch (e: Exception) {
        e.message ?: "That did not work."
    }

    override fun decide(id: String, match: Boolean) = service.decide(id, match)
    override fun dismissPairing() = service.dismissPairing()
    override fun remove(deviceId: String): RemoveResult = service.remove(deviceId)
    override fun undoPair(deviceId: String): RemoveResult = service.undoPair(deviceId)
    override fun syncNow(deviceId: String) { service.resumeLink(deviceId); service.kickAll() }
    override fun setAccess(deviceId: String, libraryId: String, actions: List<NetAction>) = service.setAccess(deviceId, libraryId, actions)
    override fun setAllowMobileFiles(deviceId: String, allow: Boolean) = service.setAllowMobileFiles(deviceId, allow)
    override fun shareLibrary(deviceId: String, libraryId: String) = service.shareLibrary(deviceId, libraryId)

    override fun joinLibrary(deviceId: String, libraryId: String, target: FolderAccess, treeUri: String): String? = try {
        service.joinLibrary(deviceId, libraryId, target, treeUri)
        null
    } catch (e: Exception) {
        e.message ?: "The library could not be taken in."
    }

    override fun cancelJoin() = service.cancelJoin()
    override fun dismissJoin() = service.dismissJoin()
    override fun allowJob(jobId: String) { NetRuntime.jobPort()?.allow(jobId) }
    override fun declineJob(jobId: String) { NetRuntime.jobPort()?.decline(jobId) }
    override fun dismissRemoteJob(requestId: String) = service.dismissJob(requestId)

    override val combine: DeviceCombine? by lazy { runCatching { ServiceDeviceCombine(MergeRuntime.service(app)) }.getOrNull() }

    /** Made once, off the main thread (the page builds this controller there): the manager opens files and may start. */
    override val copies: DeviceCopies? by lazy {
        runCatching {
            val m = CopiesRuntime.of(app as KeepfinApplication)
            m?.start()
            m?.let { ManagerDeviceCopies(it) }
        }.getOrNull()
    }
}
