package app.keepfin.library.repo

import app.keepfin.ImportResult
import app.keepfin.IsrcCapture
import app.keepfin.LibrarySettings
import app.keepfin.Providers
import app.keepfin.Space
import app.keepfin.SpaceKind
import app.keepfin.SpaceLogic
import app.keepfin.SpaceType
import app.keepfin.core.library.LibItem
import app.keepfin.core.library.LibRoutes
import app.keepfin.core.library.LibSpace
import app.keepfin.core.library.LibraryMapping
import app.keepfin.folder.FolderError
import java.io.InputStream

/** Imports of picked documents, new files and the spaces of the library. Inner state is reached through [RepoCore]. */
internal class RepoImports(private val core: RepoCore) {
    private fun settingsOf(s: Small): LibrarySettings {
        val spaces = s.spaces.map { it.toSpace() }
        return core.host.settings.value.library.copy(spaces = spaces, musicRoute = s.routes.music, otherRoute = s.routes.other)
    }

    /**
     * Adds a file to a space: the bytes are copied into the folder (staged, fsynced, read back and hashed on the way), then the
     * item, its placement and its first revision are written as operations. A copy of content the library already holds is
     * removed again and reported through [AddedFile.duplicateOf]. [template] carries the metadata known before the copy.
     */
    fun addFile(spaceId: String, subfolder: String, fileName: String, source: InputStream, template: LibItem): AddedFile {
        val b = core.backend()
        val space = core.small.spaces.firstOrNull { it.id == spaceId && it.type == "folder" }
            ?: throw IllegalArgumentException("Choose a folder space. That space no longer exists or only gathers items by rules.")
        val dir = join(space.folder, if (space.layout == "flat") "" else subfolder)
        b.folder.mkdirs(dir)
        val name = core.uniqueIn(b, dir, fileName)
        val path = join(dir, name)
        // a song's recording code is read from its tags on the way in (this device only; it is never written to the shared data)
        val capture = if (template.kind == "audio") IsrcCapture(source) else null
        val result = (capture ?: source).use { b.folder.publishFrom(path, it) }
        val contentId = "sha256:${result.sha256}"
        val existing = core.rd().rows("SELECT id FROM lib_index WHERE content = ? LIMIT 1", listOf(contentId)).firstOrNull()?.get(0) as String?
        if (existing != null) {
            b.folder.remove(path)
            return AddedFile(existing, existing)
        }
        val item = template.copy(id = core.newId(), space = spaceId, place = path, addedAt = template.addedAt ?: core.now().toDouble(), kind = template.kind)
        core.addItems(listOf(item), listOf(LibraryMapping.reviseDraft(item.id, contentId, result.size, emptyList())))
        capture?.isrc()?.let { runCatching { core.setIsrc(item.id, it) }.onFailure { e -> core.log("The recording code of a file could not be kept.", e) } }
        return AddedFile(item.id, null)
    }

    fun importFiles(uris: List<String>, spaceId: String): ImportResult {
        val source = core.imports ?: throw IllegalStateException("Importing needs the document picker.")
        var added = 0
        var duplicates = 0
        var failed = 0
        val errors = ArrayList<String>()
        for (uri in uris) {
            try {
                val name = Providers.safeName(source.displayName(uri).ifBlank { "file" }, 120)
                val stem = name.substringBeforeLast('.', name)
                val template = LibItem(id = "", space = spaceId, kind = kindOf(name, source.mimeType(uri)), title = stem, addedAt = core.now().toDouble())
                val r = addFile(spaceId, "", name, source.open(uri), template)
                if (r.duplicateOf != null) duplicates++ else added++
            } catch (e: Exception) {
                failed++
                e.message?.let { errors += it }
            }
        }
        return ImportResult(added, duplicates, failed, errors)
    }

    fun earlierFilesPermissions(): List<String> = emptyList()

    fun adoptEarlierFiles(): Int = 0

    // ------------------------------------------------------------------------------------------------ spaces

    private fun validateSpace(space: Space, others: List<Space>): Space {
        val name = space.name.trim().take(40)
        require(name.isNotEmpty()) { "Give the space a name." }
        require(others.none { it.id != space.id && it.name.equals(name, ignoreCase = true) }) { "Another space already has this name." }
        if (space.type == SpaceType.SMART) return SpaceLogic.validate(space, others)
        val parts = space.folder.replace('\\', '/').split('/').map { it.trim() }.filter { it.isNotEmpty() && it != "." && it != ".." }
        require(parts.isNotEmpty()) { "Choose a folder name." }
        require(parts.size <= 3) { "Use a folder name with at most three levels." }
        val folder = parts.joinToString("/") { SpaceLogic.segment(it, 60) }
        require(others.none { it.id != space.id && it.type == SpaceType.FOLDER && it.folder.equals(folder, ignoreCase = true) }) { "Another space already uses this folder." }
        val layout = if (space.layout == app.keepfin.FolderLayout.ARTIST_ALBUM && space.kind != SpaceKind.MUSIC) app.keepfin.FolderLayout.COLLECTION else space.layout
        return space.copy(name = name, folder = folder, layout = layout, ignore = space.ignore.map { it.trim() }.filter { it.isNotEmpty() && '/' !in it }.distinct().take(50), rules = null)
    }

    fun saveSpace(space: Space, moveExisting: Boolean) {
        val id = space.id.ifBlank { core.newId() }
        var previous: LibSpace? = null
        var saved: Space? = null
        core.commitSpaces { current, routes ->
            val spaces = current.map { it.toSpace() }
            previous = current.firstOrNull { it.id == id }
            val validated = validateSpace(space.copy(id = id), spaces).let { v -> if (previous == null && v.color == null) v.copy(color = SpaceLogic.nextColor(spaces)) else v }
            saved = validated
            val next = if (previous == null) current + validated.toLib() else current.map { if (it.id == id) validated.toLib().keepLinked(current) else it }
            next to routes
        }
        val old = previous
        val new = saved
        if (moveExisting && old != null && new != null && !old.folder.equals(new.folder, ignoreCase = true) && new.type == SpaceType.FOLDER) relocateSpace(old, new)
    }

    /** Moves the files of a re-pointed space to its new folder (prefix replace, collision-safe names). */
    private fun relocateSpace(from: LibSpace, to: Space) {
        val b = core.backend()
        val wire = LibraryMapping.spaceWireId(from.id)
        var after = ""
        // one page of the space at a time: the files move, then their placements are written, then the next page
        while (true) {
            val page = core.rd().rows("SELECT id, place, title FROM lib_index WHERE space = ?1 AND place IS NOT NULL AND id > ?2 ORDER BY id LIMIT $SLICE", listOf(wire, after))
            if (page.isEmpty()) break
            after = page.last()[0] as String
            val moved = HashMap<String, String>()
            for (r in page) {
                val itemId = r[0] as String
                val place = r[1] as String
                if (isLinkedPlace(place)) continue // linked files stay where they are
                val rest = folderWithin(from.folder, place)
                val dir = join(to.folder, rest)
                try {
                    b.folder.mkdirs(dir)
                    val name = core.uniqueIn(b, dir, place.substringAfterLast('/'))
                    val sidecars = core.sidecarsOf(b, place)
                    val oldStem = place.substringAfterLast('/').substringBeforeLast('.')
                    core.moveFile(b, place, join(dir, name))
                    val newStem = name.substringBeforeLast('.', name)
                    for (side in sidecars) try { core.moveFile(b, side, join(dir, newStem + side.substringAfterLast('/').removePrefix(oldStem))) } catch (e: FolderError) { core.log("sidecar relocate failed", e) }
                    moved[itemId] = join(dir, name)
                } catch (e: FolderError) {
                    core.log("relocate failed for ${r[2]}", e)
                }
            }
            if (moved.isNotEmpty()) core.updateItems(moved.keys) { it.copy(place = moved.getValue(it.id)) }
        }
    }
    fun deleteSpace(id: String) {
        require(!app.keepfin.core.spaces.Spaces.isKept(id)) { "Downloads, Music, Videos and Favorites can be renamed but not removed." }
        core.commitSpaces { current, routes ->
            val kept = current.filter { it.id != id }
            fun route(chosen: String, fallback: String) = if (kept.any { it.id == chosen && it.type == "folder" }) chosen else if (kept.any { it.id == fallback && it.type == "folder" }) fallback else "downloads"
            kept to LibRoutes(route(routes.music, "music"), route(routes.other, "downloads"))
        }
    }

    fun spaceReorder(ids: List<String>) {
        try {
            core.commitSpaces { current, routes ->
                val ordered = SpaceLogic.reorder(current.map { it.toSpace() }, ids)
                val byId = current.associateBy { it.id }
                ordered.map { byId.getValue(it.id) } to routes
            }
        } catch (_: IllegalArgumentException) {
            // a stale list is rejected and the order stays
        }
    }

    fun defaultSpaceFor(url: String, uploader: String?): String? = SpaceLogic.route(settingsOf(core.small), url, uploader)
}
