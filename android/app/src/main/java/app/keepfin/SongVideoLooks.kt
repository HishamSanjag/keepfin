package app.keepfin

import java.util.concurrent.Executor

/**
 * "Also keep music videos where there is one" for a collection (dev-docs/plans/MUSIC-VIDEOS.md, phase 5). When a song job that carries
 * [JobRecord.findVideo] completes, its real artist and title are known: look once for its official video and queue it as its own job,
 * linked to the song's job like Capture does. [executor] must run one task at a time, so a 200-song playlist never fires 200 requests at once.
 * A song that already has a video, one that has a linked video job, no official video and a failed look all leave the song untouched.
 */
internal class SongVideoLooks(
    private val executor: Executor,
    private val song: (JobRecord) -> Song?,
    private val hasVideo: (itemId: String) -> Boolean,
    private val linked: (songJob: String) -> Boolean,
    private val find: (artists: List<String>, title: String) -> MusicVideoLookup,
    private val queue: (songJob: JobRecord, saved: Song, video: MusicVideoMatch) -> Unit,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
    /** The look is over (whatever it found): the job stops asking, so a restart resumes only the looks that never ran. */
    private val done: (songJob: String) -> Unit = {},
    /** Keeps the app working (the download service) while a look waits or runs; null when it cannot. */
    private val hold: () -> AutoCloseable? = { null },
) {
    /** The library item a finished song job became, with the artist and title it was saved with. */
    class Song(val itemId: String, val artists: List<String>, val title: String)

    fun completed(record: JobRecord) {
        if (!record.findVideo || record.status != JobStatus.COMPLETED) return
        val lease = try { hold() } catch (error: Exception) { null }
        executor.execute {
            try {
                if (linked(record.id)) return@execute
                val saved = song(record) ?: return@execute
                if (hasVideo(saved.itemId)) return@execute
                when (val look = find(saved.artists, saved.title)) {
                    is MusicVideoLookup.Found -> queue(record, saved, look.video)
                    is MusicVideoLookup.Failed -> log("music video look failed for ${record.id}", null)
                    MusicVideoLookup.None -> Unit
                }
            } catch (error: Exception) { log("music video not queued for ${record.id}", error) }
            finally {
                runCatching { done(record.id) }
                runCatching { lease?.close() }
            }
        }
    }
}
