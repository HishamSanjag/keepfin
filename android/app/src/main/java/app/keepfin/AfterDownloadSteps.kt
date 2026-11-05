package app.keepfin

import app.keepfin.core.after.AfterDownload
import app.keepfin.core.after.Facts

/**
 * One function per follow-up step of a finished download (the steps are the shared core's, `app.keepfin.core.after.AfterDownload`). The phone's
 * adapter is [EngineAfterSteps]; the unit tests use an in-memory one. A step that cannot do its job throws; [AfterDownloadRunner] reads what the
 * plan says a failure means.
 */
internal interface AfterDownloadSteps {
    /** Lyrics into the file, before it is saved. */
    fun lyrics()
    /** Saves the file into the library; returns the library item it became (null when there is none to join a playlist). */
    fun save(): String?
    /** Keeps a music video beside its song; returns the song's item, or null when the song cannot take it (the plan then saves it as an ordinary download). */
    fun attachVideo(): String?
    /** A music video that finished before its song stays where it is until the song is done. */
    fun waitForSong()
    fun joinPlaylist(itemId: String?)
    fun cover()
    fun notifyDone()
    /** "Already in ...". */
    fun notifyExisting()
    /** Videos that waited for this song are kept beside it; a song that asked for its video looks for it. */
    fun settleVideos()
    /** The job completes as "Not saved" (with Save again). */
    fun markNotSaved(error: Throwable)
}

internal object AfterDownloadRunner {
    /** The steps that put the file in the library: what "Save again" and a settled music video run. */
    val SAVING = setOf("attachVideo", "save", "joinPlaylist")

    /**
     * Runs the plan for [facts] on [steps]. [only] limits it to those step kinds; [item] is the library item the job already is (an existing
     * recording). A failing step does what the plan says: `notSaved` marks the job, `saveOrdinary` runs the fallback save, `continue` goes on.
     */
    fun run(facts: Facts, steps: AfterDownloadSteps, only: Set<String>? = null, item: String? = null) {
        var itemId = item
        var fellBack = false
        for (step in AfterDownload.plan(facts)) {
            if ((only != null && step.kind !in only) || (step.fallback && !fellBack)) continue
            try {
                when (step.kind) {
                    "lyrics" -> steps.lyrics()
                    "save" -> itemId = steps.save()
                    "attachVideo" -> { itemId = steps.attachVideo(); fellBack = itemId == null }
                    "waitForSong" -> steps.waitForSong()
                    "joinPlaylist" -> steps.joinPlaylist(itemId)
                    "cover" -> steps.cover()
                    "notify" -> steps.notifyDone()
                    "notifyExisting" -> steps.notifyExisting()
                    "settleVideos" -> steps.settleVideos()
                }
            } catch (error: Exception) {
                when (step.onFailure) {
                    "notSaved" -> { itemId = null; steps.markNotSaved(error) }
                    "saveOrdinary" -> fellBack = true
                }
            }
        }
    }
}
