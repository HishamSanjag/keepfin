package app.keepfin

import android.util.Log
import java.io.File

/**
 * Runs one photo-post job: the gallery engine saves the chosen files into a folder private to the job, the job's progress is the files done
 * of the files asked for, and the shared follow-up steps ([EngineAfterSteps]) then publish them. A cancelled job removes its folder.
 */
internal class PhotoDownload(private val engine: MediaEngine) {
    fun run(record: JobRecord, request: FormatRequest.Gallery) {
        val id = record.id
        // "Already in your library": the same photos of this post were saved before and every copy is still there (Download anyway skips this)
        if (!record.job.force) existing(record, request)?.let { EngineAfterSteps(engine, id, it).finish(); return }
        val expected = PhotoPosts.expected(request)
        val folder = folder(id).apply { mkdirs() }
        val seen = LinkedHashSet<String>()
        fun report(phase: String) = engine.progress(id, ProgressParser.Snapshot(seen.size * 100f / expected, null, null, null, null, phase, false))
        report("Starting")
        val cookies = if (record.job.signedIn != null) engine.cookieSnapshot() else null
        val output = try {
            engine.galleryEngine.run(
                GalleryLogic.downloadArgs(record.job.url, folder.absolutePath, PhotoPosts.range(request), cookies?.absolutePath), TIMEOUT_SECONDS,
                onLine = { line -> GalleryLogic.savedPath(line)?.let { if (seen.add(it)) report("${seen.size} of $expected") } },
                cancelled = { engine.stopped(id) },
            )
        } finally { cookies?.delete() }
        if (engine.stopped(id)) { if (engine.record(id)?.status == JobStatus.CANCELLED) folder.deleteRecursively(); return }
        val files = seen.map(::File).filter { it.isFile && it.canonicalPath.startsWith(folder.canonicalPath + File.separator) }
        Log.i(TAG, "${id.take(8)} ${files.size} of $expected files, exit ${output.exit}")
        if (files.isEmpty()) {
            folder.deleteRecursively()
            val failure = GalleryLogic.errorText(output.stderr, output.timedOut)
            // a sign-in wall at download time gets the same single retry (the first try went without it)
            if (engine.useSignIn(id, failure)) { run(engine.record(id) ?: return, request); return }
            engine.failOrRetry(id, failure)
            return
        }
        val kept = engine.mutate(id, persist = true) {
            if (!it.status.active) null
            else it.copy(job = it.job.copy(
                status = JobStatus.PROCESSING, phase = "Saving to your library", file = files.first().absolutePath, fileSize = files.sumOf(File::length), percent = 99f,
                speed = null, eta = null, label = PhotoPosts.savedLabel(files.size, expected, it.job.label),
            ))
        }
        if (kept != null) EngineAfterSteps(engine, id).finish()
    }

    private fun existing(record: JobRecord, request: FormatRequest.Gallery): ExistingMatch? {
        val earlier = PhotoPosts.earlier(engine.allRecords(), record.id, engine.publicDownloads::exists)
        val hit = PhotoPosts.alreadySaved(record.job.url, request, earlier) ?: return null
        val first = hit.photoSaves.values.firstOrNull() ?: return null
        return ExistingMatch(engine.folderLibrary?.itemOfJob(hit.id), first, hit.job.space.orEmpty(), "source")
    }

    companion object {
        private const val TAG = "KeepfinPhotos"
        private const val TIMEOUT_SECONDS = 20 * 60L
    }

    private fun folder(id: String) = File(engine.output, "photos-$id")
}
