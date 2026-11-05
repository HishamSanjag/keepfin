package app.keepfin.net

import app.keepfin.core.net.DOMAIN_PAIR
import app.keepfin.core.net.PAIR_NONCE_BYTES
import app.keepfin.core.net.PairCode
import app.keepfin.core.net.base64Decode
import app.keepfin.core.net.deviceIdOf
import app.keepfin.core.net.pairPinOf
import app.keepfin.core.net.pairSas
import app.keepfin.core.net.pairTranscript
import app.keepfin.core.net.pairVerifyReveal
import app.keepfin.core.net.signDomain
import app.keepfin.core.net.verifyDomain
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CompletableFuture
import org.json.JSONArray
import org.json.JSONObject

// Pairing, the asking side (spec 3 and 9.1 #3): connect, run the commitment exchange, show the six digits, wait for the person, and
// trade signatures. Port of gui/desktop/net/initiator.ts; the protocol is documented in PairingHost. Nothing is saved here: the caller
// pins the peer on success.

class AskError(message: String, val code: String) : Exception(message)

class AskOptions(
    val identity: DeviceIdentity,
    val host: String,
    val port: Int,
    /** Device id the peer must have (a QR carries it), or null to take the key it presents. */
    val pin: String?,
    /** The QR's one-use token, or null for the number comparison. */
    val token: ByteArray?,
    /** A typed code: its 20-bit token and the 30 pin bits. The key must match the bits (checked in the handshake, before a request is written) and announce that a code is showing. */
    val code: PairCode? = null,
    val libraries: List<PairLibraryRef>,
    /** Both nonces are known. Shows the digits; the future resolves true at once on the asking side (only the answering device's person confirms) and false when the person here cancels. */
    val decide: (DecideInfo) -> CompletableFuture<Boolean>,
    /** Reached the peer: its name and key, before anything is compared. */
    val connected: ((peerName: String, peerFingerprint: String) -> Unit)? = null,
    /** Hands the caller a way to drop the connection from another thread (the person cancelled while the other device was deciding). */
    val abortHook: ((() -> Unit) -> Unit)? = null,
    val connectTimeoutMs: Long = 10_000,
    val random: SecureRandom = SecureRandom(),
)

class DecideInfo(val peerName: String, val peerFingerprint: String, val sas: String, val method: String, val expiresAt: Long)

object Initiator {
    private fun friendly(error: Throwable): AskError {
        if (error is AskError) return error
        if (error is PinMismatch) return AskError(error.message ?: "This is not the device you meant.", "pin")
        if (error is PeerHttpError) {
            val messages = mapOf(
                "not-pairing" to "That device is not accepting new devices. Open Add a device on it first.",
                "rate" to "That device has seen too many tries. Wait a few minutes and try again.",
                "busy" to "That device is pairing with someone else right now.",
                "token" to "This code was already used or has expired. Show a new one.",
                "version" to "One of the devices needs to update Keepfin before they can pair.",
                "declined" to "The other device did not confirm.",
                "expired" to "The pairing took too long. Start again.",
            )
            return AskError(messages[error.code] ?: error.message ?: "Pairing failed.", error.code)
        }
        return when (error) {
            is ConnectException -> AskError("Nothing is listening at that address. Is Keepfin open on the other device?", "refused")
            is SocketTimeoutException -> AskError("The device did not answer. Check that both are on the same Wi-Fi and Keepfin is open there. If it is a Windows PC, Windows Firewall must allow Keepfin.", "timeout")
            is UnknownHostException -> AskError("That name could not be found.", "dns")
            is IOException ->
                if (error.message == "The device did not answer.") AskError("The device did not answer. Check that both are on the same Wi-Fi and Keepfin is open there. If it is a Windows PC, Windows Firewall must allow Keepfin.", "timeout")
                else AskError(error.message ?: "Pairing failed.", "error")
            else -> AskError(error.message ?: "Pairing failed.", "error")
        }
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    /** Runs the whole exchange. Returns the peer's identity once both people confirmed and both signatures verified. */
    fun askToPair(options: AskOptions): PairPeerInfo {
        val identity = options.identity
        val client = PeerClient(ClientOptions(identity, options.host, options.port, options.pin, options.connectTimeoutMs, pinBits = options.code?.pinBits))
        options.abortHook?.invoke { client.close() }
        try {
            if (options.code != null) {
                // one announce on the checked connection: only a device that says a code is showing gets /start (and so costs the attempt)
                val announced = parseAnnounce(client.json("GET", "/v1/announce", timeoutMs = options.connectTimeoutMs))
                if (announced?.code != true) throw AskError("That device is not showing a code.", "nocode")
            }
            val libs = JSONArray()
            for (l in options.libraries) libs.put(JSONObject().put("libraryId", l.libraryId).put("actor", l.actor))
            val startBody = JSONObject().put("versions", JSONArray(PROTOCOL_VERSIONS)).put("name", identity.name).put("platform", "android").put("libraries", libs)
            options.token?.let { startBody.put("token", b64(it)) }
            options.code?.let { startBody.put("code", it.token) }
            val started = client.json("POST", "/v1/pair/start", startBody)
            val spkiB = client.peerSpki ?: throw AskError("The device did not present a key.", "pin")
            val peerId = deviceIdOf(spkiB)
            val peerName = started.optString("name", "Another device").trim().take(40).ifEmpty { "Another device" }
            val fingerprint = fingerprintOf(peerId)
            options.connected?.invoke(peerName, fingerprint)
            // a QR already named the key it expects; the client enforced it on the socket. Belt and braces:
            if (options.token != null && options.pin != null && hex(pairPinOf(spkiB)) != options.pin) throw PinMismatch()
            val na = ByteArray(PAIR_NONCE_BYTES).also { options.random.nextBytes(it) }
            val revealed = client.json("POST", "/v1/pair/nonce", JSONObject().put("id", started.optString("id")).put("na", b64(na)))
            val nb = base64Decode(revealed.optString("nb")) ?: ByteArray(0)
            val commitment = base64Decode(started.optString("commitment")) ?: ByteArray(0)
            if (nb.size != PAIR_NONCE_BYTES || !pairVerifyReveal(commitment, nb, spkiB, identity.spki)) {
                throw AskError("The other device did not follow the pairing steps, so nothing was paired.", "commitment")
            }
            val sas = pairSas(na, nb, identity.spki, spkiB)
            val agreed = options.decide(DecideInfo(peerName, fingerprint, sas, if (options.token != null || options.code != null) "qr" else "sas", started.optLong("expiresAt"))).get()
            if (!agreed) {
                runCatching { client.json("POST", "/v1/pair/confirm", JSONObject().put("id", started.optString("id")).put("decline", true)) }
                throw AskError("You said the numbers do not match, so nothing was paired.", "declined")
            }
            val transcript = pairTranscript(na, nb, identity.spki, spkiB)
            val sig = signDomain(identity.signer, DOMAIN_PAIR, transcript)
            val theirs = client.json("POST", "/v1/pair/confirm", JSONObject().put("id", started.optString("id")).put("sig", b64(sig)), timeoutMs = 3 * 60_000L)
            val theirSig = base64Decode(theirs.optString("sig")) ?: ByteArray(0)
            if (!verifyDomain(spkiB, DOMAIN_PAIR, transcript, theirSig)) throw AskError("The other device could not prove it was the one you meant.", "signature")
            val peerLibs = ArrayList<PairLibraryRef>()
            started.optJSONArray("libraries")?.let { a ->
                for (i in 0 until minOf(a.length(), 16)) {
                    val o = a.optJSONObject(i) ?: continue
                    val id = o.optString("libraryId")
                    val actor = o.optString("actor")
                    if (id.isNotEmpty() && actor.isNotEmpty()) peerLibs += PairLibraryRef(id, actor)
                }
            }
            return PairPeerInfo(spkiB, peerId, peerName, started.optString("platform", "unknown").take(20), peerLibs)
        } catch (e: Throwable) {
            throw friendly(e)
        } finally {
            client.close()
        }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
