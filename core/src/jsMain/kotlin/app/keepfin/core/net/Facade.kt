@file:OptIn(ExperimentalJsExport::class)

package app.keepfin.core.net

// The JavaScript surface of the network core (gui/shared/core/net.ts is the only importer). Bytes cross as Int8Array,
// structured values as JSON text. Data problems come back as null or a decision; programmer errors throw.

import app.keepfin.core.sync.JArr
import app.keepfin.core.sync.JBool
import app.keepfin.core.sync.JNull
import app.keepfin.core.sync.JNum
import app.keepfin.core.sync.JObj
import app.keepfin.core.sync.JStr
import app.keepfin.core.sync.Jv
import app.keepfin.core.sync.encodeCanonical
import app.keepfin.core.sync.jObj
import app.keepfin.core.sync.parseLoose

private fun json(v: Jv) = encodeCanonical(v)

/** `{spki, pkcs8}` as Base64: a fresh software key pair (the desktop wraps the private half with safeStorage). */
@JsExport
fun netGenerateKey(): String {
    val p = Ecdsa.generate()
    return json(jObj("pkcs8" to JStr(base64Encode(p.pkcs8)), "spki" to JStr(base64Encode(p.spki))))
}

@JsExport
fun netDeviceId(spki: ByteArray): String = deviceIdOf(spki)

@JsExport
fun netIsP256Spki(spki: ByteArray): Boolean = isP256Spki(spki)

@JsExport
fun netSign(pkcs8: ByteArray, domain: String, data: ByteArray): ByteArray = Ecdsa.sign(pkcs8, domainMessage(domain, data))

@JsExport
fun netVerify(spki: ByteArray, domain: String, data: ByteArray, signature: ByteArray): Boolean = verifyDomain(spki, domain, data, signature)

@JsExport
fun netHmacSha256(key: ByteArray, message: ByteArray): ByteArray = hmacSha256(key, message)

@JsExport
fun netPairCommitment(nb: ByteArray, spkiB: ByteArray, spkiA: ByteArray): ByteArray = pairCommitment(nb, spkiB, spkiA)

@JsExport
fun netPairVerifyReveal(commitment: ByteArray, nb: ByteArray, spkiB: ByteArray, spkiA: ByteArray): Boolean = pairVerifyReveal(commitment, nb, spkiB, spkiA)

@JsExport
fun netPairSas(na: ByteArray, nb: ByteArray, spkiA: ByteArray, spkiB: ByteArray): String = pairSas(na, nb, spkiA, spkiB)

@JsExport
fun netPairTranscript(na: ByteArray, nb: ByteArray, spkiA: ByteArray, spkiB: ByteArray): ByteArray = pairTranscript(na, nb, spkiA, spkiB)

@JsExport
fun netPairPin(spki: ByteArray): ByteArray = pairPinOf(spki)

@JsExport
fun netBuildPairQr(host: String, port: Int, pin: ByteArray, token: ByteArray): String = buildPairQr(host, port, pin, token)

/** `{host, port, pin, token}` (Base64 for the bytes) or null when the text is not a valid pairing QR. */
@JsExport
fun netParsePairQr(text: String): String? {
    val q = parsePairQr(text) ?: return null
    return json(jObj("host" to JStr(q.host), "pin" to JStr(base64Encode(q.pin)), "port" to JNum(q.port.toLong()), "token" to JStr(base64Encode(q.token))))
}

@JsExport
fun netPairCodeOf(codeToken: Int, pin: ByteArray): String = pairCodeOf(codeToken, pin)

/** `{token, pinBits}` or null when the typed text is not a pairing code. */
@JsExport
fun netParsePairCode(text: String): String? {
    val c = parsePairCode(text) ?: return null
    return json(jObj("pinBits" to JNum(c.pinBits.toLong()), "token" to JNum(c.token.toLong())))
}

@JsExport
fun netPinMatches(pin: ByteArray, pinBits: Int): Boolean = pinMatches(pin, pinBits)
/**
 * [ctxJson] is `{"pairingMode": bool, "peer": null | {"deviceId", "revoked", "libraries": {"<id>": ["metadata.read", ...]}}}`.
 * Returns `{"allow": bool, "reason": string}`.
 */
@JsExport
fun netAuthorize(ctxJson: String, action: String, library: String?): String {
    val ctx = (parseLoose(ctxJson) as? JObj)?.members ?: throw IllegalArgumentException("context is not an object")
    val peerV = ctx["peer"]
    val peer = if (peerV is JObj) {
        val p = peerV.members
        val libs = LinkedHashMap<String, Set<NetAction>>()
        for ((id, v) in (p["libraries"] as? JObj)?.members ?: emptyMap()) {
            libs[id] = ((v as? JArr)?.items ?: emptyList()).mapNotNull { a -> NetAction.values().firstOrNull { it.wire == (a as? JStr)?.value } }.toSet()
        }
        PeerGrants((p["deviceId"] as? JStr)?.value ?: "", (p["revoked"] as? JBool)?.value ?: false, libs)
    } else null
    val act = NetAction.values().firstOrNull { it.wire == action } ?: return json(jObj("allow" to JBool(false), "reason" to JStr("action")))
    val d = authorize(AuthzContext((ctx["pairingMode"] as? JBool)?.value ?: false, peer), act, library)
    return json(jObj("allow" to JBool(d.allow), "reason" to JStr(d.reason)))
}

private fun decision(d: UrlDecision) =
    json(jObj("allow" to JBool(d.allowed), "href" to (d.href?.let { JStr(it) } ?: JNull), "host" to (d.host?.let { JStr(it) } ?: JNull), "reason" to JStr(d.reason)))

@JsExport
fun netCheckDownloadUrl(text: String): String = decision(checkDownloadUrl(text))

@JsExport
fun netCheckResolved(addressesJson: String): String =
    decision(checkResolved(((parseLoose(addressesJson) as? JArr)?.items ?: emptyList()).map { (it as JStr).value }))

@JsExport
fun netCheckRedirect(current: String, location: String, addressesJson: String, hops: Int): String =
    decision(checkRedirect(current, location, ((parseLoose(addressesJson) as? JArr)?.items ?: emptyList()).map { (it as JStr).value }, hops))

/** Why the address is not a public destination (loopback, private, ...), or null when it is. */
@JsExport
fun netDeniedAddressClass(address: String): String? = deniedAddressClass(address)
