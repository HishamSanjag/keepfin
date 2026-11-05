package app.keepfin.core.after

/**
 * What is known about a finished job when its follow-up steps are planned (fixtures/engine/after/SCHEMA.md). [video] is `none`, or the fate of a
 * music video download: `attach` (its song is in the library), `wait` (its song is still on its way) or `ordinary` (no song to take it).
 * [library] is `folder` (the Keepfin folder library) or `legacy` (the older library, which has no song to hold a video).
 */
data class Facts(
    val audio: Boolean = false,
    val hasFile: Boolean = true,
    val existing: Boolean = false,
    val video: String = "none",
    val playlist: Boolean = false,
    val lyrics: Boolean = false,
    val library: String = "folder",
)

/**
 * One follow-up step. [kind]: lyrics, save, attachVideo, waitForSong, joinPlaylist, cover, notify, notifyExisting, settleVideos.
 * [onFailure]: `continue` (the job still completes), `notSaved` (the job completes as "Not saved" with Save again) or `saveOrdinary`
 * (the video is saved as an ordinary download instead). [fallback]: runs only when the step before it declined or failed.
 */
data class Step(val kind: String, val onFailure: String = "continue", val fallback: Boolean = false)

/** The one order and the one meaning of failure for what happens when a download finishes, on both apps (dev-docs/plans/AFTER-DOWNLOAD.md). */
object AfterDownload {
    fun plan(facts: Facts): List<Step> {
        val steps = ArrayList<Step>()
        if (facts.existing || !facts.hasFile) {
            if (facts.playlist) steps.add(Step("joinPlaylist"))
            steps.add(Step(if (facts.existing) "notifyExisting" else "notify"))
            steps.add(Step("settleVideos"))
            return steps
        }
        // the older library has no song to hold a video: it is an ordinary download there
        val video = if (facts.library == "folder") facts.video else "none"
        if (video == "wait") steps.add(Step("waitForSong"))
        else {
            // lyrics come before saving, so the saved copy has them
            if (facts.audio && facts.lyrics) steps.add(Step("lyrics"))
            if (video == "attach") {
                steps.add(Step("attachVideo", "saveOrdinary"))
                steps.add(Step("save", "notSaved", fallback = true))
            } else steps.add(Step("save", "notSaved"))
            if (facts.playlist) steps.add(Step("joinPlaylist"))
        }
        steps.add(Step("cover"))
        steps.add(Step("notify"))
        steps.add(Step("settleVideos"))
        return steps
    }
}
