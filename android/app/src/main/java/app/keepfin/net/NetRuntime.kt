package app.keepfin.net

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.keepfin.BuildConfig
import app.keepfin.EngineApi
import app.keepfin.core.net.DeviceSigner
import app.keepfin.folder.Profile
import app.keepfin.folder.ProfileStore
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Devices on the network, the app's side (dev-docs/plans/KEEPFIN-NETWORK-SPEC.md): one [NetService] per process, made when the Keepfin
 * folder build first needs it. It owns the device key, the list of open libraries the service serves and syncs, the connection
 * watcher, and what runs while the app is open or "Keep this phone reachable" is on. Free of any screen.
 */
object NetRuntime {
    private const val TAG = "KeepfinNet"
    private val libs = CopyOnWriteArrayList<NetLibrary>()
    @Volatile private var identityValue: KeystoreIdentity? = null
    @Volatile private var serviceValue: NetService? = null
    @Volatile private var foreground = false
    private val incomingValue = kotlinx.coroutines.flow.MutableStateFlow<PairingUi?>(null)

    /** Another device asking to pair with this phone (null when none is): the app shows it over every screen. */
    val incomingPairing: kotlinx.coroutines.flow.StateFlow<PairingUi?> get() = incomingValue

    /** Tests and previews set what the app-level pairing dialog shows. */
    internal fun setIncomingForTests(p: PairingUi?) { incomingValue.value = p }

    private fun incomingChanged(app: Application, p: PairingUi?) {
        if (incomingValue.value == p) return
        incomingValue.value = p
        PairingNotifier.update(app, p, foreground)
    }
    private val main = Handler(Looper.getMainLooper())
    private var pauseTask: Runnable? = null

    /** Devices need a phone whose TLS can do 1.3. */
    val available: Boolean get() = Tls.supported

    @Synchronized fun identity(context: Context): KeystoreIdentity =
        identityValue ?: KeystoreIdentity.load(context.applicationContext).also { identityValue = it }

    /** The key this device signs its segments with; null when it cannot be made (the library then runs unsigned). */
    fun signer(context: Context): DeviceSigner? = try {
        identity(context).signer
    } catch (e: Exception) {
        Log.w(TAG, "the device key is not available", e)
        null
    }

    /** The open libraries the service serves (closed ones drop out by themselves). */
    fun libraries(): List<NetLibrary> = libs.filter { it.isOpen }

    fun register(lib: NetLibrary) {
        libs.removeAll { it.libraryId == lib.libraryId }
        libs += lib
        serviceValue?.libraryOpened(lib)
    }

    fun peek(): NetService? = serviceValue

    /** Tests stand a whole in-process stack in as the app's service (the player and the library screens read it from here). */
    internal fun installForTests(service: NetService?, libraries: List<NetLibrary> = emptyList()) {
        serviceValue = service
        libs.clear()
        libs.addAll(libraries)
    }

    /** The service, made on first use. [engine] runs the downloads other devices ask for. */
    @Synchronized fun service(app: Application, engine: EngineApi): NetService {
        serviceValue?.let { return it }
        val deviceDir = File(app.filesDir, "net-service")
        val probe = ConnectivityProbe(app)
        lateinit var svc: NetService
        val jobPort = EngineJobPort(engine, metered = { probe.isMetered() }, free = { runCatching { app.filesDir.usableSpace }.getOrNull() })
        svc = NetService(
            NetServiceDeps(
                deviceDir = deviceDir, identity = identity(app), libraries = { libraries() }, network = probe,
                // the port comes in here: the service starts devices that were on from its constructor, before `svc` is assigned
                discovery = { listening, changed, isPaired ->
                    val id = identity(app)
                    val log = NetLog { Log.i(TAG, it) }
                    val nsd = NsdDiscovery(app, { id.deviceId }, { id.name }, { listening }, isPaired, changed, log)
                    // DNS-SD plus a scan of the local network (many routers drop multicast); the scan walks only Wi-Fi/Ethernet networks
                    val scanner = NetScanner(
                        addresses = { lanAddresses(systemNics().filter { n -> probe.lanInterfaces()?.contains(n.name) == true }) },
                        connector = SocketScanConnector { id }, selfHint = { id.deviceId.take(16) },
                        ownAddresses = { systemNics().flatMap { it.addresses }.toSet() },
                    )
                    CombinedDiscovery(nsd, scanner, isPaired, changed, allowed = { probe.hasLan() }, log = log)
                },
                log = NetLog { Log.i(TAG, it) },
                jobs = jobPort, incomingRows = { originOf -> jobPort.rows(originOf) },
                adoptJoined = { joined, treeUri -> adopt(app, engine, joined, treeUri) },
                keepReachableChanged = { on -> ReachableService.set(app, on) },
                incomingPairing = { p -> incomingChanged(app, p) },
            ),
        )
        serviceValue = svc
        contextForNotice = app
        jobPortValue = jobPort
        return svc
    }

    @Volatile private var contextForNotice: Application? = null
    @Volatile private var jobPortValue: EngineJobPort? = null

    /** The queue behind "Download on..." (the page asks it to allow or decline a waiting job). */
    fun jobPort(): EngineJobPort? = jobPortValue

    /** A library taken in from a paired device becomes a library profile of this phone; it opens now when there was none. */
    private fun adopt(app: Application, engine: EngineApi, joined: Joined, treeUri: String) {
        val profiles = ProfileStore(File(app.filesDir, "profiles.json"))
        val hadCurrent = profiles.current() != null
        val profile = Profile(UUID.randomUUID().toString(), joined.name.ifEmpty { "Library" }, treeUri, joined.libraryId, joined.actor, Instant.now().toString())
        profiles.add(profile, makeCurrent = !hadCurrent)
        if (!hadCurrent) (engine as? app.keepfin.setup.SetupEngine)?.attachFolderLibrary()
    }

    /** The app came to the front (true) or left it (false). Without "Keep this phone reachable" the server rests a minute after the last screen closes. */
    fun foreground(visible: Boolean) {
        foreground = visible
        incomingValue.value?.let { p -> contextForNotice?.let { PairingNotifier.update(it, p, visible) } }
        val svc = serviceValue ?: return
        main.post {
            pauseTask?.let { main.removeCallbacks(it) }
            pauseTask = null
            if (visible) svc.resume()
            else {
                val task = Runnable { if (!foreground) svc.pause() }
                pauseTask = task
                main.postDelayed(task, 60_000)
            }
        }
    }

    /** Starts the service when devices were on before the app was closed. */
    fun startIfNeeded(app: Application, engine: EngineApi) {
        if (!available) return
        if (!File(File(app.filesDir, "net-service"), "net/state.json").isFile) return
        runCatching { service(app, engine) }.onFailure { Log.w(TAG, "devices could not start", it) }
    }
}

/** The phone's connection: reachable at all, metered or not, and a callback when it changes (spec 7: a network change tries every unreachable device at once). */
class ConnectivityProbe(context: Context) : NetworkProbe {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun isUp(): Boolean = try {
        val net = cm?.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true || caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true || caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
    } catch (e: Exception) {
        true
    }

    @Suppress("DEPRECATION")
    private fun lanNetworks(): List<Network> = try {
        cm?.allNetworks.orEmpty().filter { n ->
            val caps = cm?.getNetworkCapabilities(n)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true || caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        }
    } catch (e: Exception) {
        emptyList()
    }

    override fun hasLan(): Boolean = lanNetworks().isNotEmpty()

    override fun lanInterfaces(): Set<String> = try {
        lanNetworks().mapNotNull { cm?.getLinkProperties(it)?.interfaceName }.toSet()
    } catch (e: Exception) {
        emptySet()
    }

    override fun isMetered(): Boolean = try {
        cm?.isActiveNetworkMetered == true
    } catch (e: Exception) {
        false
    }

    override fun onChange(listener: () -> Unit): () -> Unit {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { listener() }
            override fun onLost(network: Network) { listener() }
            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) { listener() }
        }
        try {
            cm?.registerDefaultNetworkCallback(callback)
        } catch (e: Exception) {
            return {}
        }
        return { runCatching { cm?.unregisterNetworkCallback(callback) } }
    }
}
