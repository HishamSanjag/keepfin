package app.keepfin.net

// Devices on the network, Android side (dev-docs/plans/KEEPFIN-NETWORK-SPEC.md, sections 4, 9.1, 9.3 and 9.4). This file holds the
// constants and the denial-of-service budgets of 9.1 #7. They mirror gui/desktop/net/limits.ts number for number, so both
// apps refuse the same things.

const val DEFAULT_PORT = 47215

/** The protocol versions this build speaks; the highest common one is chosen in `hello` (spec 4). */
val PROTOCOL_VERSIONS: List<Int> = listOf(1)
const val PROTOCOL_MIN = 1

/** The DNS-SD service type (spec 2). */
const val SERVICE_TYPE = "_keepfin._tcp."

/** Pairing mode and a pairing session last two minutes (spec 3). */
const val PAIRING_WINDOW_MS = 2 * 60 * 1000L

/** A download request kept for 30 days (spec 9.1 #6). */
const val JOB_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

/** Above this the person on the receiving phone is asked before a job from another device starts (1 GiB). */
const val REMOTE_CONSENT_BYTES = 1024L * 1024 * 1024

data class Limits(
    /** Open connections, all peers and strangers together. */
    val maxConnections: Int = 64,
    val perPeerConnections: Int = 8,
    /** Open connections per source address for keys that are not paired. */
    val perStrangerAddress: Int = 3,
    val handshakeMs: Int = 10_000,
    val headersMs: Int = 10_000,
    /** A whole request (headers and body) for the small endpoints. */
    val requestMs: Long = 5 * 60_000L,
    val idleMs: Int = 60_000,
    val maxHeaderBytes: Int = 16 * 1024,
    val maxHeaders: Int = 64,
    val maxTargetChars: Int = 8 * 1024,
    /** JSON bodies (pairing, jobs, mutations). */
    val maxJsonBytes: Int = 256 * 1024,
    /** One pushed segment file. */
    val maxSegmentBytes: Int = 16 * 1024 * 1024,
    val maxPushSegments: Int = 256,
    val maxPushBytes: Long = 256L * 1024 * 1024,
    val maxVectorChars: Int = 32 * 1024,
    /** The slowest body a peer may send: below this for a few seconds it is cut off. */
    val minBodyBytesPerSecond: Long = 1024,
    val transfersPerPeer: Int = 4,
    val requestsPerMinute: Int = 1200,
    val strangerRequestsPerMinute: Int = 30,
    /** GET /v1/announce is open to strangers (the scan of D2): its own budget per source address. */
    val announcePerMinute: Int = 120,
    val verifiesPerMinute: Int = 4000,
    val pairAttempts: Int = 5,
    val pairWindowMs: Long = 10 * 60_000L,
    val eventSocketsPerPeer: Int = 2,
    val eventBackpressureMessages: Int = 256,
    val maxRangeBytes: Long = 1024L * 1024 * 1024,
)

/** At most [max] events inside [windowMs]; each key has its own window. Memory is bounded by [maxKeys]. */
class AttemptLimiter(
    private val max: Int,
    private val windowMs: Long,
    private val now: () -> Long,
    private val maxKeys: Int = 4096,
) {
    private val hits = LinkedHashMap<String, ArrayList<Long>>()

    /** Records an attempt and answers whether it is allowed. */
    @Synchronized fun take(key: String): Boolean {
        val at = now()
        val list = hits.getOrPut(key) { ArrayList() }
        list.removeAll { at - it >= windowMs }
        if (list.size >= max) return false
        list.add(at)
        if (hits.size > maxKeys) sweep(at)
        return true
    }

    /** How many are left for [key] without recording one. */
    @Synchronized fun left(key: String): Int {
        val at = now()
        return maxOf(0, max - (hits[key]?.count { at - it < windowMs } ?: 0))
    }

    private fun sweep(at: Long) {
        val it = hits.entries.iterator()
        while (it.hasNext()) if (!it.next().value.any { t -> at - t < windowMs }) it.remove()
        // still over: drop the oldest keys, a window that long is not worth the memory
        while (hits.size > maxKeys) {
            val first = hits.keys.iterator()
            first.next()
            first.remove()
        }
    }
}

/** A failure with a status and a reason, answered as `{error, message}` (the desktop's HttpError). */
class HttpError(val status: Int, val code: String, message: String) : Exception(message)

/** The words people read for a pairing or protocol problem; the codes are what the peers agree on. */
class PairError(val code: String, message: String, val status: Int = 403) : Exception(message)

fun interface NetLog {
    fun log(message: String)
}
