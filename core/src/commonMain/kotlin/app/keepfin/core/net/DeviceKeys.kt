package app.keepfin.core.net

import app.keepfin.core.sync.sha256Hex

// Device keys: ECDSA P-256 over SHA-256. A public key travels as SPKI DER, a signature as ASN.1 DER (what Java's
// SHA256withECDSA and Node's crypto both produce and accept). The device id is the SHA-256 of the SPKI.
// Every signature is domain separated: the signed message is `domain 0x00 data`.

const val DOMAIN_SEGMENT = "keepfin-seg-v2"
const val DOMAIN_PAIR = "keepfin-pair-v1"

class EcKeyPair(val spki: ByteArray, val pkcs8: ByteArray)

/** The platform's ECDSA P-256 (java.security on the JVM and Android, node:crypto on Node). */
expect object Ecdsa {
    /** Makes a fresh software key pair (tests, and desktop keys that are then wrapped by the host). */
    fun generate(): EcKeyPair

    /** Signs [message] (SHA-256) with a PKCS#8 private key; a DER signature. */
    fun sign(pkcs8: ByteArray, message: ByteArray): ByteArray

    /** True only for a valid DER signature of [message] under the P-256 key [spki]. Never throws. */
    fun verify(spki: ByteArray, message: ByteArray, signature: ByteArray): Boolean
}

/** Something that can sign with the device key without exposing it (a software key, or an AndroidKeyStore key). */
interface DeviceSigner {
    val spki: ByteArray

    /** A DER signature of [message] (ECDSA P-256, SHA-256). */
    fun sign(message: ByteArray): ByteArray
}

/** A [DeviceSigner] over a software key pair. */
class SoftwareSigner(private val pair: EcKeyPair) : DeviceSigner {
    override val spki: ByteArray get() = pair.spki
    override fun sign(message: ByteArray): ByteArray = Ecdsa.sign(pair.pkcs8, message)

    companion object {
        fun generate(): SoftwareSigner = SoftwareSigner(Ecdsa.generate())
    }
}

/** The device id: SHA-256 of the SPKI as lower-case hex. */
fun deviceIdOf(spki: ByteArray): String = sha256Hex(spki)

private val SPKI_P256_PREFIX = bytesOfHex("3059301306072a8648ce3d020106082a8648ce3d030107034200")!!

/** True for a 91-byte SPKI of an uncompressed P-256 point: the only key shape Keepfin accepts. */
fun isP256Spki(spki: ByteArray): Boolean {
    if (spki.size != 91) return false
    for (i in SPKI_P256_PREFIX.indices) if (spki[i] != SPKI_P256_PREFIX[i]) return false
    return spki[SPKI_P256_PREFIX.size] == 4.toByte()
}

/** The bytes a signature covers: `domain`, a zero byte, then [data]. */
fun domainMessage(domain: String, data: ByteArray): ByteArray = concatBytes(domain.encodeToByteArray(), byteArrayOf(0), data)

fun signDomain(signer: DeviceSigner, domain: String, data: ByteArray): ByteArray = signer.sign(domainMessage(domain, data))

fun verifyDomain(spki: ByteArray, domain: String, data: ByteArray, signature: ByteArray): Boolean =
    isP256Spki(spki) && Ecdsa.verify(spki, domainMessage(domain, data), signature)
