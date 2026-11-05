package app.keepfin.core.net

import org.khronos.webgl.Int8Array

// Node's crypto, found the way NativeSha does (no import, so a runtime without it only loses signatures).

private fun nodeCrypto(): dynamic {
    val crypto: dynamic = js("(typeof process !== 'undefined' && typeof process.getBuiltinModule === 'function') ? process.getBuiltinModule('node:crypto') : null")
    if (crypto == null || crypto == undefined) throw IllegalStateException("ECDSA needs node:crypto")
    return crypto
}

private fun toBuffer(bytes: ByteArray): dynamic {
    val view = bytes.unsafeCast<Int8Array>()
    return js("Buffer.from(view.buffer, view.byteOffset, view.length)")
}

private fun fromBuffer(buf: dynamic): ByteArray {
    val copy: dynamic = js("new Int8Array(buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.length))")
    return copy.unsafeCast<ByteArray>()
}

actual object Ecdsa {
    actual fun generate(): EcKeyPair {
        val crypto = nodeCrypto()
        val opts: dynamic = js("({namedCurve: 'P-256', publicKeyEncoding: {type: 'spki', format: 'der'}, privateKeyEncoding: {type: 'pkcs8', format: 'der'}})")
        val pair = crypto.generateKeyPairSync("ec", opts)
        return EcKeyPair(fromBuffer(pair.publicKey), fromBuffer(pair.privateKey))
    }

    actual fun sign(pkcs8: ByteArray, message: ByteArray): ByteArray {
        val crypto = nodeCrypto()
        val key = toBuffer(pkcs8)
        val k: dynamic = js("({key: key, format: 'der', type: 'pkcs8'})")
        return fromBuffer(crypto.sign("sha256", toBuffer(message), k))
    }

    actual fun verify(spki: ByteArray, message: ByteArray, signature: ByteArray): Boolean = try {
        val crypto = nodeCrypto()
        val key = toBuffer(spki)
        val k: dynamic = js("({key: key, format: 'der', type: 'spki'})")
        crypto.verify("sha256", toBuffer(message), k, toBuffer(signature)) == true
    } catch (e: Throwable) {
        false
    }
}
