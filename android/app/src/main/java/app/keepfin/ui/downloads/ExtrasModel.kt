package app.keepfin.ui.downloads

import app.keepfin.DownloadOptions
import app.keepfin.core.spaces.PreviewWords

/** How many groups on the Extras tab differ from the person's defaults. The counting rule is the core's, the same as on Windows. */
internal object ExtrasModel {
    /**
     * One for each group that was changed. [queueChanged] is the collection-only choice that lives outside the options. The phone has no
     * "one file per chapter" choice, so that group never counts.
     */
    fun changed(options: DownloadOptions, base: DownloadOptions, queueChanged: Boolean = false): Int = PreviewWords.extrasCount(
        subtitles = options.subtitles != base.subtitles,
        cut = options.sectionStart != base.sectionStart || options.sectionEnd != base.sectionEnd,
        sponsor = options.sponsorBlock != base.sponsorBlock,
        chapters = false,
        inFile = options.embedThumbnail != base.embedThumbnail || options.embedMetadata != base.embedMetadata || options.embedChapters != base.embedChapters,
        beside = options.writeThumbnail != base.writeThumbnail,
        queue = queueChanged,
    )

    /** "Cover, tags and chapters are on." / "Nothing extra goes in the file." (the Windows words). */
    fun inTheFile(options: DownloadOptions): String {
        val on = listOfNotNull("Cover".takeIf { options.embedThumbnail }, "tags".takeIf { options.embedMetadata }, "chapters".takeIf { options.embedChapters })
        if (on.isEmpty()) return "Nothing extra goes in the file."
        val list = if (on.size == 1) on.first() else on.dropLast(1).joinToString(", ") + " and " + on.last()
        return "$list ${if (on.size > 1) "are" else "is"} on.".replaceFirstChar { it.uppercase() }
    }
}
