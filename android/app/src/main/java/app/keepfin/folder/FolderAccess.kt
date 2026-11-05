package app.keepfin.folder

import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Typed failures of the Keepfin folder (dev-docs/plans/KEEPFIN-FOLDER-ANDROID-SPEC.md section 11.1). The mapping is fixed by the
 * phase 2 probes: none of these ever means "the files are gone".
 */
sealed class FolderError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The grant was revoked or is missing (`SecurityException`): the user must pick the folder again. */
    class NeedsAccess(message: String, cause: Throwable? = null) : FolderError(message, cause)

    /** The volume is gone (`No root for ...`, a removed card): wait for it to return; nothing is missing. */
    class Unavailable(message: String, cause: Throwable? = null) : FolderError(message, cause)

    /** A rename or publish would have landed on a taken name (the provider returned a different name); it was undone. */
    class NameConflict(val path: String, message: String = "The name $path is already taken.") : FolderError(message)

    /** The disk is full (`SyncFailedException`, `ENOSPC`). A partial staged file is removed, never published. */
    class DiskFull(message: String, cause: Throwable? = null) : FolderError(message, cause)

    /** This one file or folder does not exist (the volume itself is fine). */
    class NotFound(val path: String, cause: Throwable? = null) : FolderError("$path was not found.", cause)

    /** A provider other than the local external-storage one (phase 2 supports only that). */
    class UnsupportedProvider(val authority: String) : FolderError("This place ($authority) is not supported yet. Pick a folder on this phone or its memory card.")

    class Io(message: String, cause: Throwable? = null) : FolderError(message, cause)
}

data class FolderEntry(val name: String, val isDirectory: Boolean, val size: Long, val modifiedMs: Long)

/**
 * The children of a directory. [possiblyIncomplete] is true when the listing is empty or smaller than the previous
 * one for the same directory: callers must not read missing names as deletions, and re-list later.
 */
class FolderListing(val entries: List<FolderEntry>, val possiblyIncomplete: Boolean)

class PublishResult(val size: Long, val sha256: String)

/**
 * File access to one Keepfin folder. Paths are relative to the folder, separated by `/`, never start with `/`
 * (the empty string is the folder itself). Paths are the identity of a file; any document ids behind them are a cache.
 *
 * Nothing here is atomic or exclusive at the provider, so [publishFrom] writes under a temporary name, fsyncs, reads back
 * and renames, and checks the name the provider returned.
 */
interface FolderAccess {
    /** Lists a directory. Throws [FolderError.NotFound] when it does not exist. */
    fun list(dir: String): FolderListing

    /** The entry at [path], or null when there is none. */
    fun stat(path: String): FolderEntry?

    /** Reads a whole file ([FolderError.NotFound] when missing). */
    fun read(path: String): ByteArray

    fun openRead(path: String): InputStream

    /** Creates [dir] and its parents. */
    fun mkdirs(dir: String)

    /**
     * Publishes a new file without replacing anything: [FolderError.NameConflict] when [path] exists. The bytes are
     * staged, fsynced and read back (size and SHA-256) before the rename. A failure leaves nothing at [path].
     */
    fun publishFrom(path: String, source: InputStream, onProgress: ((Long) -> Unit)? = null): PublishResult

    fun publish(path: String, bytes: ByteArray): PublishResult = publishFrom(path, ByteArrayInputStream(bytes))

    /** Replaces (or creates) a small mutable file: staged under a new name, then switched. */
    fun replace(path: String, bytes: ByteArray)

    /** Removes a file or directory tree; a missing path is fine. */
    fun remove(path: String)

    /** Renames inside the same directory. A case-only change goes through a temporary name. [FolderError.NameConflict] when taken. */
    fun rename(path: String, newName: String)

    /**
     * Moves the file [path] into the existing directory [toDir] under the same name without copying its bytes, when the storage can.
     * False when it cannot (or that name is taken there): the caller copies and removes instead. Nothing changes on false.
     */
    fun moveInto(path: String, toDir: String): Boolean = false

    /** Free bytes on the volume, or null when it cannot be mapped to a path. */
    fun freeBytes(): Long?
}

internal fun parentOf(path: String): String = path.substringBeforeLast('/', "")
internal fun nameOf(path: String): String = path.substringAfterLast('/')
internal fun join(dir: String, name: String): String = if (dir.isEmpty()) name else "$dir/$name"
