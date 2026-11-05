package app.keepfin.copies

import app.keepfin.folder.FolderAccess
import app.keepfin.folder.FolderError
import java.io.InputStream
import java.security.MessageDigest

// Shared pieces of the copies engine (dev-docs/plans/KEEPFIN-COPIES-SPEC.md section 5, ported from gui/desktop/copies over FolderAccess).

/** Stands in for a killed process in fault-injection tests: nothing after it may touch the disk. */
internal class CrashError(val step: String) : RuntimeException("crash injected at $step")

/** An action that must not run (and nothing is touched): the reason is recorded and the next plan decides again. */
internal class SkipError(val reason: String, message: String? = null) : RuntimeException(message ?: reason)

/** Why the copy cannot be reached: it is gone (card out, drive unplugged) or Android withdrew access. */
internal enum class Away { UNAVAILABLE, ACCESS }

/** The copy root could not be reached: the run pauses as "disconnected" (spec 5.3). A missing file is never this. */
internal class DisconnectedError(val away: Away, message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** The copy is full: the run stops, everything done is journaled. */
internal class DiskFullStop(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

internal const val CONTENT_PREFIX = "sha256:"

internal fun hexOf(bytes: ByteArray): String {
    val chars = CharArray(bytes.size * 2)
    for ((i, b) in bytes.withIndex()) {
        val v = b.toInt() and 0xff
        chars[i * 2] = "0123456789abcdef"[v ushr 4]
        chars[i * 2 + 1] = "0123456789abcdef"[v and 15]
    }
    return String(chars)
}

internal fun sha256Text(text: String): String = CONTENT_PREFIX + hexOf(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

/** Splits `dir/name.ext` into stem and extension (the extension includes the dot). */
internal fun splitExt(rel: String): Pair<String, String> {
    val slash = rel.lastIndexOf('/')
    val dot = rel.lastIndexOf('.')
    return if (dot > slash + 1) rel.substring(0, dot) to rel.substring(dot) else rel to ""
}

/** `name (2).ext`, `name (3).ext`...: the counter added to a name that is already taken (spec 5.4). */
internal fun withCounter(rel: String, n: Int): String {
    val (stem, ext) = splitExt(rel)
    return "$stem ($n)$ext"
}

internal fun uniqueName(wanted: String, taken: (String) -> Boolean): String {
    if (!taken(wanted)) return wanted
    for (n in 2 until 10_000) {
        val candidate = withCounter(wanted, n)
        if (!taken(candidate)) return candidate
    }
    throw IllegalStateException("No free name for $wanted")
}

internal fun parentPath(path: String): String = path.substringBeforeLast('/', "")
internal fun leafName(path: String): String = path.substringAfterLast('/')
internal fun joinPath(dir: String, name: String): String = if (dir.isEmpty()) name else "$dir/$name"

/** The content id and size of a stream, read once. [abort] is polled between chunks. */
internal class Hashed(val contentId: String, val size: Long)

internal fun hashStream(input: InputStream, abort: () -> Boolean = { false }): Hashed {
    val digest = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(256 * 1024)
    var size = 0L
    input.use { ins ->
        while (true) {
            if (abort()) throw SkipError("paused")
            val n = ins.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
            size += n
        }
    }
    return Hashed(CONTENT_PREFIX + hexOf(digest.digest()), size)
}

internal fun hashPath(access: FolderAccess, path: String, abort: () -> Boolean = { false }): Hashed = hashStream(access.openRead(path), abort)

/** A source whose size (and, when known, the content id it must hash to) is known before it is read: an upload to a cloud account needs both. */
internal interface SizedStream {
    val knownSize: Long?
    val pinnedContentId: String?
}

/** A cloud copy moves objects on the provider's side instead of reading and sending them again (used for versions and the trash). */
internal interface ServerSideMove {
    /** Moves [from] to [to] without replacing anything. [expected] (a content id) is checked against the bytes that are kept; returns the content id. */
    fun moveVerified(from: String, to: String, expected: String?, abort: () -> Boolean): String
}

/** An input stream that stops with [SkipError] ("paused") when [abort] says so, and counts bytes. [knownSize] and [pinnedContentId] ride along for uploads. */
internal class GuardedInput(
    private val inner: InputStream,
    private val abort: () -> Boolean,
    private val onBytes: (Long) -> Unit = {},
    override val knownSize: Long? = null,
    override val pinnedContentId: String? = null,
) : InputStream(), SizedStream {
    override fun read(): Int {
        val b = inner.read()
        if (b >= 0) onBytes(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (abort()) throw SkipError("paused")
        val n = inner.read(b, off, len)
        if (n > 0) onBytes(n.toLong())
        return n
    }

    override fun close() = inner.close()
}

/** True when a provider failure says the copy volume is gone or access was withdrawn: a disconnect, never "the files are missing". */
internal fun awayOf(error: Throwable): Away? = when (error) {
    is FolderError.Unavailable -> Away.UNAVAILABLE
    is FolderError.NeedsAccess -> Away.ACCESS
    else -> null
}

/** Android's own scratch names (`name.ab12cd34.tmp`, `.old`) and ours: never part of a library. */
private val PROVIDER_TEMP = Regex("\\.[0-9a-f]{8}\\.(tmp|old)$")
private val SKIPPED_FILES = setOf("desktop.ini", "thumbs.db", ".ds_store")

/** Temporary files, partial downloads and system files are not part of a library (spec 3). */
internal fun skippedFile(name: String): Boolean {
    val lower = name.lowercase()
    return lower.contains(".kftmp") || lower.endsWith(".part") || name.startsWith("~$") || lower in SKIPPED_FILES ||
        lower.startsWith(".trashed-") || lower.startsWith(".keepfin-tmp-") || PROVIDER_TEMP.containsMatchIn(lower)
}
