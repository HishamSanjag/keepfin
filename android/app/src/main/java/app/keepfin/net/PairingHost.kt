package app.keepfin.net

import app.keepfin.core.net.DOMAIN_PAIR
import app.keepfin.core.net.PAIR_NONCE_BYTES
import app.keepfin.core.net.base64Decode
import app.keepfin.core.net.buildPairQr
import app.keepfin.core.net.deviceIdOf
import app.keepfin.core.net.pairCodeOf
import app.keepfin.core.net.pairCommitment
import app.keepfin.core.net.pairPinOf
import app.keepfin.core.net.pairSas
import app.keepfin.core.net.pairTranscript
import app.keepfin.core.net.signDomain
import app.keepfin.core.net.verifyDomain
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// Pairing, the answering side (spec 3 and 9.1 #3 and #4): the state machine behind the `/v1/pair/*` endpoints. Port of
// gui/desktop/net/pairing.ts; the wire protocol is documented in its header and is the same here:
//   POST /v1/pair/start    asking -> {versions, name, platform, libraries:[{libraryId, actor}], token?}
//                          answering -> {id, commitment, name, platform, libraries, expiresAt}   (Cb = HMAC(Nb, SPKIb || SPKIa))
//                          code (the 20-bit number of a typed code) may stand in for 	oken: it is treated exactly like a valid QR token.
//   POST /v1/pair/nonce    asking -> {id, na}          answering -> {nb}   (the asking side checks Nb against Cb)
//   POST /v1/pair/confirm  asking -> {id, sig}         answering -> {sig}  (answered only after the answering person decided)
// Limits: unknown keys reach these endpoints only inside the 2-minute pairing mode; 5 attempts per 10 minutes per peer key and per
// source address; a session expires 2 minutes after /start; tokens carry 128 random bits, are consumed on first sight, never logged.

const val PAIR_SESSION_MS = 2 * 60 * 1000L

/** A request nobody opened a window for (an unrequested comparison) waits only this long for the person. */
const val UNREQUESTED_SESSION_MS = 60 * 1000L

/** At most this many unrequested starts in [UNREQUESTED_WINDOW_MS], whoever sends them. */
const val UNREQUESTED_MAX = 10
const val UNREQUESTED_WINDOW_MS = 10 * 60 * 1000L

/** This many unrequested sessions ending declined or expired within [UNREQUESTED_WINDOW_MS] mute unrequested starts for [MUTE_MS]. */
const val MUTE_AFTER = 3
const val MUTE_MS = 5 * 60 * 1000L

class PairLibraryRef(val libraryId: String, val actor: String)

class PairPeerInfo(
    val spki: ByteArray,
    val deviceId: String,
    val name: String,
    val platform: String,
    val libraries: List<PairLibraryRef>,
    /** The address the asking device connected from (a hint for the first reconnect). */
    val address: String? = null,
)

enum class PairStage { Comparing, Approving, Done, Failed }

class PairSessionView(
    val id: String,
    val method: String,
    val stage: PairStage,
    val peerName: String,
    val peerFingerprint: String,
    val sas: String?,
    val error: String?,
    val expiresAt: Long,
    /** Nobody opened Add a device here: the other device asked on its own ("<name> wants to connect"). */
    val unrequested: Boolean = false,
    /** A scanned code authorized it, so no person is asked here; the page shows "<name> connected" with Undo. */
    val autoApproved: Boolean = false,
    val peerDeviceId: String = "",
)

class PairingHostOptions(
    val identity: () -> DeviceIdentity,
    val limits: Limits,
    val now: () -> Long,
    val libraries: () -> List<PairLibraryRef>,
    /** Called when both sides confirmed and the asking device proved it. The caller saves the pin. */
    val onPaired: (PairPeerInfo, String) -> Unit,
    /** Something about the pairing state changed (the page shows it). */
    val changed: () -> Unit,
    val random: SecureRandom = SecureRandom(),
)

class PairingHost(private val options: PairingHostOptions) {
    private class Session(
        val id: String,
        val method: String,
        val peer: PairPeerInfo,
        val address: String,
        val nb: ByteArray,
        val commitment: ByteArray,
        @Volatile var na: ByteArray? = null,
        @Volatile var sas: String? = null,
        @Volatile var stage: PairStage,
        @Volatile var error: String? = null,
        val expiresAt: Long,
        val unrequested: Boolean = false,
        val auto: Boolean = false,
        val decided: CompletableFuture<Boolean> = CompletableFuture(),
        var timer: java.util.concurrent.ScheduledFuture<*>? = null,
    )

    private val timers = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "keepfin-pair-timer").also { it.isDaemon = true } }.apply { removeOnCancelPolicy = true }
    @Volatile private var until = 0L
    @Volatile private var session: Session? = null
    private val tokens = HashMap<String, Long>()

    /** The typed code showing now: its 20-bit value, when it expires and the wrong guesses made against it. */
    private class CodeEntry(val value: Int, val expiresAt: Long, var misses: Int = 0)
    private var codeEntry: CodeEntry? = null
    private val byKey = AttemptLimiter(options.limits.pairAttempts, options.limits.pairWindowMs, options.now)
    private val byAddress = AttemptLimiter(options.limits.pairAttempts, options.limits.pairWindowMs, options.now)
    private val unrequestedStarts = AttemptLimiter(UNREQUESTED_MAX, UNREQUESTED_WINDOW_MS, options.now)
    private val guard = Any()
    private val failures = ArrayList<Long>() // when unrequested sessions ended declined or expired
    private var mutedUntil = 0L

    // ---- the window ----------------------------------------------------------------------------------------------------

    /** Opens the explicit pairing mode (this device answers pairing requests) for 2 minutes. */
    fun open(): Long {
        until = options.now() + PAIRING_WINDOW_MS
        options.changed()
        return until
    }

    fun close() {
        until = 0
        synchronized(tokens) { tokens.clear(); codeEntry = null }
        endSession("Pairing was closed.")
        options.changed()
    }

    val active: Boolean get() = options.now() < until
    val windowEnd: Long? get() = if (active) until else null

    // ---- QR ------------------------------------------------------------------------------------------------------------

    /** A one-use token and the link that carries it, and the typed code for the same two minutes (link, expiresAt, code). Returned only here; never logged. */
    fun makeQr(host: String, port: Int): Triple<String, Long, String> {
        // the token is the authorization: showing a code does not open the window
        val token = ByteArray(16).also { options.random.nextBytes(it) }
        val expiresAt = options.now() + PAIR_SESSION_MS
        synchronized(tokens) {
            tokens.clear() // one QR at a time: a new one replaces the last
            tokens[hex(token)] = expiresAt
        }
        val pin = pairPinOf(options.identity().spki)
        val value = options.random.nextInt(1 shl 20)
        synchronized(tokens) { codeEntry = CodeEntry(value, expiresAt) }
        return Triple(buildPairQr(host, port, pin, token), expiresAt, pairCodeOf(value, pin))
    }

    /** True while a typed code is showing (the announcement says so; it never says what the code is). */
    val codeShowing: Boolean get() = synchronized(tokens) { codeEntry?.let { it.expiresAt > options.now() } == true }


    /** Consumes a token. True only for a token that exists, is unexpired and has not been used: and it is gone either way. */
    private fun consume(token: ByteArray): Boolean {
        val expiresAt = synchronized(tokens) { tokens.remove(hex(token)) }
        return expiresAt != null && expiresAt > options.now()
    }

    /** Consumes a typed code. A right one is gone after this either way; five wrong guesses retire the showing code (the QR stays). */
    private fun consumeCode(value: Int): Boolean = synchronized(tokens) {
        val c = codeEntry
        if (c == null || c.expiresAt <= options.now()) {
            codeEntry = null
            return false
        }
        if (c.value == value) {
            codeEntry = null
            return true
        }
        if (++c.misses >= 5) codeEntry = null
        false
    }

    // ---- the view for the page -----------------------------------------------------------------------------------------

    fun view(): PairSessionView? {
        val s = session ?: return null
        return PairSessionView(s.id, s.method, s.stage, s.peer.name, fingerprintOf(s.peer.deviceId), if (s.method == "sas") s.sas else null, s.error, s.expiresAt, s.unrequested, s.auto, s.peer.deviceId)
    }

    /** The person closed a finished pairing (paired, or why not): the page stops showing it. An open one is left alone. */
    fun dismiss() {
        val s = session ?: return
        if (s.stage != PairStage.Done && s.stage != PairStage.Failed) return
        session = null
        options.changed()
    }

    /** The person on this device answered: the numbers match / approved (true), or they do not / declined (false). */
    fun decide(id: String, ok: Boolean) {
        val s = session
        if (s == null || s.id != id) throw PairError("gone", "This pairing is no longer open.", 404)
        if (s.stage == PairStage.Done || s.stage == PairStage.Failed) return
        s.decided.complete(ok)
        if (!ok) endSession("You said the numbers do not match, so nothing was paired.")
    }

    // ---- the endpoints -------------------------------------------------------------------------------------------------

    fun start(peer: PairPeerInfo, address: String, token: ByteArray?, code: Int? = null): StartedPairing {
        expireStale()
        // no window and no code: only a device on this network or Tailscale may ask unprompted, and only while not muted;
        // anything else is answered like a closed window and costs no attempt
        val unrequested = !active && token == null && code == null
        if (unrequested && (!PairSource.isLocalOrTailscale(address) || muted())) throw PairError("not-pairing", "This device is not accepting new devices right now.")
        // every try costs an attempt, whoever the peer is and whatever it sent
        val okKey = byKey.take(peer.deviceId)
        val okAddress = byAddress.take(address)
        if (!okKey || !okAddress) throw PairError("rate", "Too many pairing attempts. Try again in a few minutes.", 429)
        if (unrequested && !unrequestedStarts.take("unrequested")) throw PairError("rate", "Too many pairing attempts. Try again in a few minutes.", 429)
        var method = "sas"
        if (token != null) {
            if (!consume(token)) throw PairError("token", "This code was already used or has expired.")
            method = "qr"
        } else if (code != null) {
            // a typed code is a separate secret: it never matches a token and a token never matches it
            if (!consumeCode(code)) throw PairError("token", "This code was already used or has expired.")
            method = "qr"
        }
        synchronized(this) {
            val existing = session
            if (existing != null && existing.stage != PairStage.Done && existing.stage != PairStage.Failed) throw PairError("busy", "Another device is pairing right now.", 409)
            val identity = options.identity()
            val nb = ByteArray(PAIR_NONCE_BYTES).also { options.random.nextBytes(it) }
            val commitment = pairCommitment(nb, identity.spki, peer.spki)
            val lifetime = if (unrequested) UNREQUESTED_SESSION_MS else PAIR_SESSION_MS
            val expiresAt = options.now() + lifetime
            val s = Session(UUID.randomUUID().toString(), method, PairPeerInfo(peer.spki, peer.deviceId, peer.name, peer.platform, peer.libraries, address), address, nb, commitment,
                stage = if (method == "qr") PairStage.Approving else PairStage.Comparing, expiresAt = expiresAt, unrequested = unrequested, auto = method == "qr")
            if (s.auto) s.decided.complete(true) // the code in the QR already authorized it: no person is asked on this side
            s.timer = timers.schedule({ expire(s.id) }, lifetime, TimeUnit.MILLISECONDS)
            session = s
            options.changed()
            return StartedPairing(s.id, commitment, expiresAt, identity.name, "android", options.libraries())
        }
    }

    fun nonce(peerId: String, id: String, na: ByteArray): ByteArray {
        val s = live(peerId, id)
        synchronized(s) {
            if (s.na != null) throw PairError("state", "The first number was already sent.", 409)
            if (na.size != PAIR_NONCE_BYTES) throw PairError("nonce", "The nonce has the wrong size.", 400)
            s.na = na
            s.sas = pairSas(na, s.nb, s.peer.spki, options.identity().spki)
        }
        options.changed()
        return s.nb
    }

    /** The asking device's person said the numbers differ: this session ends at once. */
    fun cancel(peerId: String, id: String) {
        val s = session
        if (s == null || s.id != id || s.peer.deviceId != peerId) return
        endSession("The other device said the numbers do not match, so nothing was paired.")
    }

    /** Answers only after the person here decided. Returns our signature when both sides confirmed. */
    fun confirm(peerId: String, id: String, sig: ByteArray): ByteArray {
        val s = live(peerId, id)
        val na = s.na
        if (na == null || s.sas == null) throw PairError("state", "The numbers were not exchanged yet.", 409)
        val identity = options.identity()
        val transcript = pairTranscript(na, s.nb, s.peer.spki, identity.spki)
        // the asking device signs the transcript with its own key, which is the key of this TLS session
        if (!verifyDomain(s.peer.spki, DOMAIN_PAIR, transcript, sig)) {
            endSession("The other device could not prove it was the one you meant.")
            throw PairError("signature", "The confirmation does not match.")
        }
        val wait = s.expiresAt - options.now()
        val ok = try {
            s.decided.get(maxOf(1L, wait), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            false
        }
        if (!ok || session !== s || s.stage == PairStage.Failed) throw PairError("declined", "The pairing was declined.")
        if (options.now() >= s.expiresAt) throw PairError("expired", "The pairing took too long.", 410)
        s.stage = PairStage.Done
        s.timer?.cancel(false)
        try {
            options.onPaired(s.peer, s.method)
        } catch (e: Exception) {
            endSession("Pairing could not be saved.")
            throw PairError("save", e.message ?: "Pairing could not be saved.", 500)
        }
        options.changed()
        return signDomain(identity.signer, DOMAIN_PAIR, transcript)
    }

    // ---- internals -----------------------------------------------------------------------------------------------------

    private fun live(peerId: String, id: String): Session {
        val s = session
        if (s == null || s.id != id || s.peer.deviceId != peerId) throw PairError("gone", "This pairing is no longer open.", 404)
        if (s.stage == PairStage.Failed) throw PairError("declined", s.error ?: "The pairing was declined.")
        if (options.now() >= s.expiresAt) {
            endSession("The pairing took too long.")
            throw PairError("expired", "The pairing took too long.", 410)
        }
        return s
    }

    /** A session whose time ran out by the clock ends now (the timer does the same on a real clock). */
    private fun expireStale() {
        val s = session ?: return
        if (s.stage != PairStage.Done && s.stage != PairStage.Failed && options.now() >= s.expiresAt) endSession("The pairing took too long.")
    }

    private fun muted(): Boolean = synchronized(guard) { options.now() < mutedUntil }

    /** An unrequested session ended without pairing: three of these within ten minutes mute unrequested starts for five. */
    private fun recordFailure() = synchronized(guard) {
        val at = options.now()
        failures.removeAll { at - it >= UNREQUESTED_WINDOW_MS }
        failures += at
        if (failures.size >= MUTE_AFTER) {
            mutedUntil = at + MUTE_MS
            failures.clear()
        }
    }

    private fun expire(id: String) {
        val s = session
        if (s != null && s.id == id && s.stage != PairStage.Done) endSession("The pairing took too long.")
    }

    private fun endSession(error: String) {
        val s = session ?: return
        if (s.stage == PairStage.Done || s.stage == PairStage.Failed) return
        s.stage = PairStage.Failed
        s.error = error
        s.timer?.cancel(false)
        s.decided.complete(false)
        if (s.unrequested) recordFailure()
        options.changed()
    }

    fun shutdown() {
        endSession("Pairing was closed.")
        timers.shutdownNow()
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    companion object {
        /** The peer's description from a `/v1/pair/start` body (names are cut to 40 characters, at most 16 libraries). */
        fun peerInfo(spki: ByteArray, body: org.json.JSONObject): PairPeerInfo {
            val libs = ArrayList<PairLibraryRef>()
            val arr = body.optJSONArray("libraries")
            if (arr != null) {
                for (i in 0 until minOf(arr.length(), 16)) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("libraryId")
                    val actor = o.optString("actor")
                    if (id.isNotEmpty() && actor.isNotEmpty() && id.length <= 64 && actor.length <= 64) libs += PairLibraryRef(id, actor)
                }
            }
            fun text(key: String, fallback: String): String = body.optString(key).trim().take(40).ifEmpty { fallback }
            return PairPeerInfo(spki, deviceIdOf(spki), text("name", "Another device"), text("platform", "unknown"), libs)
        }

        fun decode64(value: Any?, maxBytes: Int): ByteArray {
            val text = value as? String ?: throw HttpError(400, "bad-field", "A field is missing.")
            if (text.length > maxBytes * 2) throw HttpError(400, "bad-field", "A field is missing.")
            return base64Decode(text) ?: throw HttpError(400, "bad-field", "A field is missing.")
        }
    }
}

class StartedPairing(val id: String, val commitment: ByteArray, val expiresAt: Long, val name: String, val platform: String, val libraries: List<PairLibraryRef>)
