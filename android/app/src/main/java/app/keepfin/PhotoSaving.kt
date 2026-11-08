package app.keepfin

import app.keepfin.core.gallery.Gallery
import java.io.File

/**
 * Saves the files of a finished photo-post job: into the Keepfin folder library (each file an item of the chosen space), else to
 * Pictures under the job's space like a video goes to Movies (Pictures/Keepfin when it has none; a video of a carousel to Movies).
 * Each file is saved on its own, so one failure never loses the others and the job says honestly how many arrived; the private originals stay
 * in the app either way. "Save again" runs this once more and saves only the files that did not reach storage ([JobRecord.photoSaves]).
 */
internal class PhotoSaving(private val engine: MediaEngine, private val id: String, private val cancelled: () -> Boolean) {
    private class Placed(val name: String, val uri: String, val path: String, val itemId: String?, val store: PublicDownloads.Saved?)

    /** Returns the library item of the first file saved now (null without a folder library); throws the first error when nothing could be saved. */
    fun save(
        record: JobRecord, directory: File, saveContext: (JobRecord) -> SaveContext, destination: (JobRecord) -> Pair<PublicDownloads.Placement, String>?,
    ): String? {
        val files = directory.listFiles { file -> file.isFile }.orEmpty().sortedWith(compareBy({ it.lastModified() }, { it.name }))
        require(files.isNotEmpty()) { "The downloaded files are unavailable." }
        val folder = engine.folderLibrary?.takeIf { engine.settings.value.library.enabled }
        val placed = ArrayList<Placed>()
        var firstError: Exception? = null
        for ((index, file) in files.withIndex()) {
            if (file.name in record.photoSaves) continue
            if (cancelled()) break
            try {
                placed += if (folder != null) toFolder(folder, record, file, index, files.size, saveContext) else toPictures(record, file, index, files.size, destination(record))
            } catch (error: Exception) { if (firstError == null) firstError = error }
        }
        if (placed.isEmpty() && firstError != null) throw firstError
        if (placed.isEmpty() && cancelled()) throw IllegalStateException("Download cancelled.")
        val saves = record.photoSaves + placed.associate { it.name to it.uri }
        val note = firstError?.let { "Saved ${saves.size} of ${files.size} files. The others are safe in the app: ${(it.message ?: "storage unavailable").trimEnd('.')}." }
        val expected = (record.format as? FormatRequest.Gallery)?.let(PhotoPosts::expected) ?: files.size
        val first = placed.firstOrNull()
        val kept = engine.mutate(id, persist = true) {
            if (it.status == JobStatus.CANCELLED) null
            else it.copy(
                job = it.job.copy(
                    publicUri = it.job.publicUri ?: first?.uri, publicPath = if (it.job.publicUri != null) it.job.publicPath else first?.path, saveError = note, filesRemoved = false, phase = null,
                    label = Gallery.savedLabel(saves.size, expected, it.job.label),
                ),
                publicFiles = (it.publicFiles + placed.map(Placed::uri)).distinct(), photoSaves = saves,
            )
        }
        if (kept == null) engine.publicDownloads.discard(placed.mapNotNull(Placed::store))
        return first?.itemId.takeIf { kept != null }
    }

    private fun name(stem: String, index: Int, count: Int) = if (count == 1) stem else "$stem (${index + 1})"

    private fun toFolder(folder: app.keepfin.library.repo.LibraryRepository, record: JobRecord, file: File, index: Int, count: Int, saveContext: (JobRecord) -> SaveContext): Placed {
        val library = engine.settings.value.library
        val space = library.folderSpaces.firstOrNull { it.id == record.job.space } ?: library.folderSpaces.firstOrNull { it.id == library.otherRoute } ?: library.folderSpaces.first()
        val context = saveContext(record)
        val stem = SpaceLogic.fileStem(space, context).ifBlank { Providers.safeName(record.job.title) }
        val title = name(record.job.title, index, count)
        val tags = if (count == 1) null else SourceTags(title, null, null)
        val saved = folder.saveDownload(record.job, tags, space.id, SpaceLogic.subfolder(space, context), name(stem, index, count), file, emptyList())
        return Placed(file.name, saved.uri, saved.path, saved.itemId, null)
    }

    private fun toPictures(record: JobRecord, file: File, index: Int, count: Int, destination: Pair<PublicDownloads.Placement, String>?): Placed {
        val stem = name(destination?.second?.takeIf { it.isNotBlank() } ?: Providers.safeName(record.job.title), index, count)
        val saved = engine.publicDownloads.publish(listOf(file), stem, destination?.first, file.nameWithoutExtension, photos = true) { cancelled() }.getValue(file)
        return Placed(file.name, saved.uri.toString(), saved.path, null, saved)
    }
}
