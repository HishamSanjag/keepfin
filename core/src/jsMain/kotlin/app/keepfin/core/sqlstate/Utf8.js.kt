package app.keepfin.core.sqlstate

private val encoder: dynamic = js("new TextEncoder()")

@Suppress("UNUSED_PARAMETER")
private fun asSigned(bytes: dynamic): ByteArray = js("new Int8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength)")

internal actual fun utf8Bytes(text: String): ByteArray = asSigned(encoder.encode(text))
