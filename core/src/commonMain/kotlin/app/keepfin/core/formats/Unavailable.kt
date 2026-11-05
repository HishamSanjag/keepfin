package app.keepfin.core.formats

// Playlist entries that cannot be downloaded, told apart from the flat discovery data both apps read: YouTube marks
// private and deleted videos by title, and lists a video whose channel was removed with nothing but its id. Such an
// entry stays in the list, unselected, with the reason, so a person sees why it is left out.

object Unavailable {
    /** The label in place of the title. */
    const val LABEL = "Unavailable"
    const val PRIVATE = "Private video: only its owner can watch it."
    const val DELETED = "Deleted from YouTube."
    const val GONE = "No longer on YouTube: deleted, made private, or its channel was removed."

    /**
     * Why the entry cannot be downloaded, or null when it looks fine. [youtube]: the entry is a YouTube video; other sites
     * may list entries without a title or length, so only YouTube's empty entries count as gone.
     */
    fun reason(title: String?, uploader: String?, duration: Double?, youtube: Boolean): String? {
        val name = title?.trim().orEmpty()
        return when {
            name == "[Private video]" -> PRIVATE
            name == "[Deleted video]" -> DELETED
            youtube && name.isEmpty() && uploader.isNullOrBlank() && (duration == null || duration <= 0.0) -> GONE
            else -> null
        }
    }
}
