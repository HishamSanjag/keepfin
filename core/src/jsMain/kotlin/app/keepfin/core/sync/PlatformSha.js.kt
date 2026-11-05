package app.keepfin.core.sync

// JavaScript has no standard synchronous SHA-256; on Node the facade installs one (see NativeSha.kt).
internal actual fun platformSha256Hex(data: ByteArray, from: Int, to: Int): String? = null
