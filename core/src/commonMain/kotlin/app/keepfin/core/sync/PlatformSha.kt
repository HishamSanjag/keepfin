package app.keepfin.core.sync

/** The platform's own SHA-256 of `data[from, to)` as lower-case hex, or null when the platform offers none (then the portable code runs). */
internal expect fun platformSha256Hex(data: ByteArray, from: Int, to: Int): String?
