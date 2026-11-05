package app.keepfin.setup

/** Pure rules about the places a Keepfin folder can be (dev-docs/plans/KEEPFIN-FOLDER-ANDROID-SPEC.md section 1). */
internal object FolderPlaces {
    /**
     * True for places Android 11 and later refuse to apps: the top of a volume, the Download folder itself, Android/data and Android/obb.
     * [treeDocumentId] is the tree's document id (for example `primary:Keepfin`). Older versions let a few of them through, but the
     * folder is not safe there either, so they are refused on every version.
     */
    fun forbidden(treeDocumentId: String): Boolean {
        val path = pathOf(treeDocumentId).lowercase()
        // Documents itself is allowed by Android but too wide: the recommended place is the Keepfin folder inside it
        return path.isEmpty() || path == "download" || path == "downloads" || path == "documents" || path == "android" || path.startsWith("android/data") || path.startsWith("android/obb")
    }

    fun pathOf(treeDocumentId: String): String = treeDocumentId.substringAfter(':', "").trim('/')

    fun volumeOf(treeDocumentId: String): String = treeDocumentId.substringBefore(':')

    /** A readable place: `Internal storage > Keepfin`, or `Memory card > Keepfin`. */
    fun label(treeDocumentId: String): String {
        val volume = if (volumeOf(treeDocumentId) == "primary") "Internal storage" else "Memory card"
        val path = pathOf(treeDocumentId)
        return if (path.isEmpty()) volume else "$volume > ${path.split('/').filter { it.isNotEmpty() }.joinToString(" > ")}"
    }

    /**
     * "I already have a Keepfin folder": when the chosen folder is not one itself, the child directory called Keepfin (any case) is the
     * one to use. [children] are the names of the directories directly inside the chosen folder.
     */
    fun innerName(children: List<String>): String? = children.firstOrNull { it.equals("Keepfin", ignoreCase = true) }

    /** Only the local external-storage provider is supported (phase 4 handles clouds through their own APIs). */
    fun supportedAuthority(authority: String?): Boolean = authority == "com.android.externalstorage.documents"
}
