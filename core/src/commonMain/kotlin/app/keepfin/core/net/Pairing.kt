package app.keepfin.core.net

import app.keepfin.core.music.isJsWhitespace

// The pairing math (network spec 9.1 #3 and #4): a commitment-based numeric comparison, the reviewed Bluetooth LE
// Secure Connections design with HMAC-SHA-256 in place of AES-CMAC.
//
//   1. B (responder) sends Cb = HMAC(key = Nb, SPKIb || SPKIa) before it has seen Na.
//   2. A (initiator) sends Na.
//   3. B reveals Nb; A checks it against Cb.
//   4. Both show SAS = HMAC-SHA256(key = Na || Nb, "keepfin-sas" || SPKIa || SPKIb) as six digits.
//
// A machine in the middle shows each side a different key, and cannot grind nonces to make the numbers match: the
// responder is bound to Nb before it learns Na. Pure functions only; nonces come from the caller's random source.

const val PAIR_NONCE_BYTES = 16
const val PAIR_TOKEN_BYTES = 16
const val SAS_DIGITS = 6
private const val SAS_LABEL = "keepfin-sas"

/** Cb = HMAC(Nb, SPKIb || SPKIa): what the responder sends first. */
fun pairCommitment(nb: ByteArray, spkiB: ByteArray, spkiA: ByteArray): ByteArray {
    require(nb.size == PAIR_NONCE_BYTES) { "a pairing nonce is $PAIR_NONCE_BYTES bytes" }
    return hmacSha256(nb, concatBytes(spkiB, spkiA))
}

/** True when the revealed [nb] is the one [commitment] was made with (constant time). */
fun pairVerifyReveal(commitment: ByteArray, nb: ByteArray, spkiB: ByteArray, spkiA: ByteArray): Boolean =
    nb.size == PAIR_NONCE_BYTES && constantTimeEquals(commitment, pairCommitment(nb, spkiB, spkiA))

/**
 * The six-digit string both users compare. The SPKIs are in role order (A is the initiator). Unbiased: 32-bit
 * words of the HMAC output are taken in turn and a word at or above the largest multiple of 10^6 is rejected; the
 * (astronomically rare) case where all eight words are rejected moves to the next HMAC block (message plus a counter byte).
 */
fun pairSas(na: ByteArray, nb: ByteArray, spkiA: ByteArray, spkiB: ByteArray): String {
    require(na.size == PAIR_NONCE_BYTES && nb.size == PAIR_NONCE_BYTES) { "a pairing nonce is $PAIR_NONCE_BYTES bytes" }
    val key = concatBytes(na, nb)
    val message = concatBytes(SAS_LABEL.encodeToByteArray(), spkiA, spkiB)
    val limit = 4_294_000_000L // 4294 * 10^6, the largest multiple of 10^6 that fits in 32 bits
    var counter = 0
    while (true) {
        val block = if (counter == 0) hmacSha256(key, message) else hmacSha256(key, concatBytes(message, byteArrayOf(counter.toByte())))
        for (w in 0 until 8) {
            val v = ((block[4 * w].toLong() and 0xff) shl 24) or ((block[4 * w + 1].toLong() and 0xff) shl 16) or
                ((block[4 * w + 2].toLong() and 0xff) shl 8) or (block[4 * w + 3].toLong() and 0xff)
            if (v < limit) return (v % 1_000_000L).toString().padStart(SAS_DIGITS, '0')
        }
        counter++
        check(counter < 256) { "SAS sampling did not terminate" }
    }
}

/** The signature the paired devices exchange over the confirmed session: it binds both keys and both nonces (domain keepfin-pair-v1). */
fun pairTranscript(na: ByteArray, nb: ByteArray, spkiA: ByteArray, spkiB: ByteArray): ByteArray =
    concatBytes(na, nb, spkiA, spkiB)

// ---- the QR form ----------------------------------------------------------------------------------------------

/** keepfin://pair?h=<host>&p=<port>&k=<spki pin>&t=<one-time token>. The pin is SHA-256(SPKI), the token 128 random bits. */
class PairQr(val host: String, val port: Int, val pin: ByteArray, val token: ByteArray) {
    fun build(): String = buildPairQr(host, port, pin, token)
}

private const val QR_PREFIX = "keepfin://pair?"

private fun hostCharOk(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '-' || c == ':' || c == '_' || c == '%'

fun buildPairQr(host: String, port: Int, pin: ByteArray, token: ByteArray): String {
    require(host.isNotEmpty() && host.length <= 253 && host.all { hostCharOk(it) }) { "bad host" }
    require(port in 1..65535) { "bad port" }
    require(pin.size == 32) { "the pin is a SHA-256" }
    require(token.size == PAIR_TOKEN_BYTES) { "the token is $PAIR_TOKEN_BYTES bytes" }
    return QR_PREFIX + "h=" + host + "&p=" + port + "&k=" + base64UrlEncode(pin) + "&t=" + base64UrlEncode(token)
}

/** Reads a QR text strictly: exactly the four parameters once each, a valid port, and exact-length pin and token. Null otherwise. */
fun parsePairQr(text: String): PairQr? {
    if (!text.startsWith(QR_PREFIX)) return null
    val seen = HashMap<String, String>()
    for (piece in text.substring(QR_PREFIX.length).split('&')) {
        val eq = piece.indexOf('=')
        if (eq <= 0) return null
        val key = piece.substring(0, eq)
        if (key != "h" && key != "p" && key != "k" && key != "t") return null
        if (seen.put(key, piece.substring(eq + 1)) != null) return null
    }
    if (seen.size != 4) return null
    val host = seen.getValue("h")
    if (host.isEmpty() || host.length > 253 || !host.all { hostCharOk(it) }) return null
    val portText = seen.getValue("p")
    if (portText.isEmpty() || portText.length > 5 || !portText.all { it in '0'..'9' } || (portText.length > 1 && portText[0] == '0')) return null
    val port = portText.toInt()
    if (port !in 1..65535) return null
    val pin = base64UrlDecode(seen.getValue("k")) ?: return null
    val token = base64UrlDecode(seen.getValue("t")) ?: return null
    if (pin.size != 32 || token.size != PAIR_TOKEN_BYTES) return null
    return PairQr(host, port, pin, token)
}

/** The pin B checks A's key against: SHA-256 of the SPKI. */
fun pairPinOf(spki: ByteArray): ByteArray = app.keepfin.core.sync.sha256(spki)

// ---- the typed code -------------------------------------------------------------------------------------------------

/** What a typed pairing code holds: a 20-bit one-use token and the first 30 bits of the answering device's pin. */
class PairCode(val token: Int, val pinBits: Int)

const val PAIR_CODE_TOKEN_BITS = 20
const val PAIR_CODE_PIN_BITS = 30
private const val CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
private const val CODE_SYMBOLS = 10

/** The first 30 bits of SHA-256(SPKI), as a number below 2^30. */
fun pinBitsOf(pin: ByteArray): Int {
    require(pin.size == 32) { "the pin is a SHA-256" }
    return ((pin[0].toInt() and 0xff) shl 22) or ((pin[1].toInt() and 0xff) shl 14) or ((pin[2].toInt() and 0xff) shl 6) or ((pin[3].toInt() and 0xff) shr 2)
}

/** True when [pin] starts with the 30 bits a code carried. */
fun pinMatches(pin: ByteArray, pinBits: Int): Boolean = pin.size == 32 && pinBitsOf(pin) == pinBits

/** `XXXXX-XXXXX`: 4 symbols of the token, then 6 of the pin bits, 5 bits each, Crockford base32. */
fun pairCodeOf(codeToken: Int, pin: ByteArray): String {
    require(codeToken in 0 until (1 shl PAIR_CODE_TOKEN_BITS)) { "a code token has $PAIR_CODE_TOKEN_BITS bits" }
    val bits = pinBitsOf(pin)
    val out = StringBuilder()
    for (i in 0 until CODE_SYMBOLS) {
        val v = if (i < 4) (codeToken shr (5 * (3 - i))) and 31 else (bits shr (5 * (9 - i))) and 31
        out.append(CODE_ALPHABET[v])
        if (i == 4) out.append('-')
    }
    return out.toString()
}

/**
 * Reads what a person typed: upper or lower case, spaces and dashes anywhere, O as 0, I and L as 1. Exactly ten symbols
 * from the alphabet remain, otherwise null (U and every other character are rejected).
 */
fun parsePairCode(text: String): PairCode? {
    var token = 0
    var pinBits = 0
    var n = 0
    for (raw in text) {
        if (raw == '-' || isJsWhitespace(raw)) continue
        var c = if (raw in 'a'..'z') raw - 32 else raw
        if (c == 'O') c = '0' else if (c == 'I' || c == 'L') c = '1'
        val v = CODE_ALPHABET.indexOf(c)
        if (v < 0) return null
        if (n >= CODE_SYMBOLS) return null
        if (n < 4) token = (token shl 5) or v else pinBits = (pinBits shl 5) or v
        n++
    }
    if (n != CODE_SYMBOLS) return null
    return PairCode(token, pinBits)
}

/**
 * What is wrong with a typed code, in words for the person, or null when [parsePairCode] reads it. Checked before anything is sent,
 * so a typo never costs one of the five tries a device allows.
 */
fun pairCodeProblem(text: String): String? {
    var symbols = 0
    var bad = false
    for (raw in text) {
        if (raw == '-' || isJsWhitespace(raw)) continue
        var c = if (raw in 'a'..'z') raw - 32 else raw
        if (c == 'O') c = '0' else if (c == 'I' || c == 'L') c = '1'
        if (CODE_ALPHABET.indexOf(c) < 0) bad = true
        symbols++
    }
    return when {
        symbols == 0 -> "Type the code shown on the other device."
        bad -> "That is not a Keepfin code. Codes use the numbers 0 to 9 and the letters A to Z, but never U, like 7Q4MX-2XKDP."
        symbols < CODE_SYMBOLS -> "That code is too short. It has 10 characters, like 7Q4MX-2XKDP."
        symbols > CODE_SYMBOLS -> "That code is too long. It has 10 characters, like 7Q4MX-2XKDP."
        else -> null
    }
}
