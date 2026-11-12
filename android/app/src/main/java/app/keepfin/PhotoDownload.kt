package app.keepfin

import android.util.Log
import app.keepfin.core.gallery.PublicPost
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

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
        val publicLinks = if (record.job.signedIn == null) publicLinks(id, record.job.url, request) else null
        fun attempt(args: List<String>): GalleryEngine.Output {
            seen.clear()
            return engine.galleryEngine.run(
                args, TIMEOUT_SECONDS,
                onLine = { line -> GalleryLogic.savedPath(line)?.let { if (seen.add(it)) report("${seen.size} of $expected") } },
                cancelled = { engine.stopped(id) },
            )
        }
        val cookies = if (record.job.signedIn != null) engine.cookieSnapshot() else null
        val output = try {
            publicLinks?.let { attempt(GalleryLogic.directArgs(folder.absolutePath, it)) }?.takeIf { seen.isNotEmpty() || engine.stopped(id) }
                ?: attempt(GalleryLogic.downloadArgs(record.job.url, folder.absolutePath, PhotoPosts.range(request), cookies?.absolutePath))
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

    /** The direct links of the chosen photos of a post the site shows signed out (read again: the links expire); null means use the usual route. */
    private fun publicLinks(id: String, url: String, request: FormatRequest.Gallery): List<String>? {
        if (!PublicPost.supports(url)) return null
        val session = "public-$id"
        val done = AtomicBoolean()
        // the read has no cancel hook of its own: watch the job while it runs
        Thread { while (!done.get()) { if (engine.stopped(id)) { engine.metadata.cancel(session); break }; Thread.sleep(200) } }.apply { isDaemon = true; start() }
        return try { engine.inspector.readPublic(url, session)?.let { PublicPhotos.links(it, request.count, request.items) } }
            catch (_: Exception) { null } finally { done.set(true) }
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
