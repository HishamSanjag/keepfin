package app.keepfin

/**
 * Where a finished music video download goes (dev-docs/plans/MUSIC-VIDEOS.md): beside a song, or on its own as an ordinary download. Pure, so the
 * orders (video first, song first, song failed) are tested without an engine.
 */
internal object MusicVideoLink {
    /** [Phase] of a finished video that waits for its song; the row says it and the engine knows which videos to settle. */
    const val WAITING = "Waiting for the song"

    sealed interface Fate {
        /** The song is still downloading (or paused): keep the video and decide when the song is done. */
        data object Wait : Fate
        data class Attach(val itemId: String) : Fate
        /** Not a song's video any more (no song, it failed or was cancelled, it is not in the library): the video is kept as an ordinary download. */
        data object Ordinary : Fate
    }

    /** [song] is the record of [JobRecord.attachToJob] (null when it is gone); [itemOf] finds the library item a finished job saved (or already was). */
    fun fate(video: JobRecord, song: JobRecord?, itemOf: (String) -> String?): Fate {
        video.attachTo?.let { return Fate.Attach(it) }
        val songId = video.attachToJob ?: return Fate.Ordinary
        if (song != null && (song.status.active || song.status == JobStatus.PAUSED)) return Fate.Wait
        if (song != null && (song.status == JobStatus.FAILED || song.status == JobStatus.CANCELLED)) return Fate.Ordinary
        return itemOf(songId)?.let(Fate::Attach) ?: Fate.Ordinary
    }

    /** A finished video that was left waiting for its song. */
    fun waiting(record: JobRecord) = record.attachToJob != null && record.status == JobStatus.COMPLETED && record.job.phase == WAITING && record.job.publicUri == null && record.job.saveError == null
}
