package app.keepfin.setup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import app.keepfin.LibraryCodec
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.LibrarySettings
import app.keepfin.MediaRows
import app.keepfin.Space
import app.keepfin.defaultSpaces
import java.io.File

/** One file an earlier Keepfin saved in Music, Movies or Download (a MediaStore row without Android types, so that tests can build them). */
internal data class EarlierRow(
    val uri: String,
    /** MediaStore RELATIVE_PATH, for example `Music/Keepfin/`. */
    val relativePath: String,
    val name: String,
    val kind: LibraryItemKind,
    val size: Long,
    val modifiedAt: Long = 0,
    val addedAt: Long = 0,
    /** Created by this install; false for files that survived an uninstall (removing them needs the user's yes). */
    val owned: Boolean = true,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val track: Int? = null,
    val year: Int? = null,
)

/**
 * Files an earlier Keepfin saved are still in Music/Keepfin, Movies/Keepfin and Download/Keepfin after an uninstall. This turns them
 * into the same inventory the setup of an earlier library uses, so they go through the existing move-or-leave, review, consent, run
 * and report steps (dev-docs/plans/KEEPFIN-FOLDER-ANDROID-SPEC.md section 5). Nothing here touches a file.
 */
internal object EarlierFiles {
    /** The folders an earlier Keepfin used below Music, Movies and Download. */
    val FOLDERS = listOf("Keepfin", "Keepfin Music", "Keepfin Videos")

    /** The space an earlier file belongs to by the root it sits in; null for anything outside Music, Movies and Download. */
    fun spaceFor(relativePath: String): String? = when (relativePath.trim('/').substringBefore('/').lowercase()) {
        "music" -> "music"
        "movies" -> "videos"
        "download" -> "downloads"
        else -> null
    }

    /** The folder below the Keepfin folder: `Music/Keepfin Music/Album/` is `Album`; `Music/Keepfin/` is empty. */
    fun subFolder(relativePath: String): String {
        val below = relativePath.trim('/').split('/').filter { it.isNotEmpty() }.drop(1)
        val rest = if (below.isNotEmpty() && FOLDERS.any { it.equals(below[0], ignoreCase = true) }) below.drop(1) else below
        return rest.joinToString("/")
    }

    private fun isSidecar(name: String) = name.substringAfterLast('.', "").lowercase() in app.keepfin.ALL_SIDECARS

    /** The inventory of [rows]; subtitle and lyrics files are left out (they travel with their media). The settings are the defaults. */
    fun inventory(rows: List<EarlierRow>): LegacyInventory {
        val settings = LibrarySettings()
        val recs = rows.filter { !isSidecar(it.name) }.mapNotNull { r ->
            val space = spaceFor(r.relativePath) ?: return@mapNotNull null
            val stem = r.name.substringBeforeLast('.', r.name)
            val item = LibraryItem(
                id = r.uri, space = space, uri = r.uri, folder = subFolder(r.relativePath), kind = r.kind,
                title = r.title?.takeIf { r.kind == LibraryItemKind.AUDIO } ?: stem, artist = r.artist, album = r.album, track = r.track, year = r.year,
                duration = r.durationMs?.let { it / 1000.0 }, size = r.size, modifiedAt = r.modifiedAt, addedAt = r.addedAt, width = r.width, height = r.height,
            )
            LibraryCodec.Rec(item, owned = r.owned, name = r.name)
        }
        return LegacyInventories.read(LibraryCodec.encodeFile(recs, emptyList()), settings)
    }

    /** The files of [rows] that the earlier library [base] does not hold (the library names a file by its MediaStore address). */
    fun untracked(base: LegacyInventory, rows: List<EarlierRow>): List<EarlierRow> {
        val ids = base.items.mapTo(HashSet()) { it.item.id }
        return rows.filter { r -> r.uri !in ids && !isSidecar(r.name) && spaceFor(r.relativePath) != null }
    }

    /** [base] with [extra] added to the spaces they belong to (a space the library no longer has: the first one). */
    fun merge(base: LegacyInventory, extra: LegacyInventory): LegacyInventory {
        val first = base.spaces.firstOrNull()?.space?.id
        val add = extra.spaces.flatMap { s -> s.items.map { (if (base.spaces.any { it.space.id == s.space.id }) s.space.id else first) to it } }
        val grouped = add.groupBy({ it.first }, { it.second })
        val spaces = base.spaces.map { s -> InvSpace(s.space, s.items + grouped[s.space.id].orEmpty().map { it.copy(item = it.item.copy(space = s.space.id)) }) }
        return LegacyInventory(spaces, base.collections, base.settings)
    }

    /** What the screen shows: the number of files and bytes, and per place the number of files (Music, Videos, Downloads). */
    fun summary(inv: LegacyInventory): EarlierUi = EarlierUi(
        needsPermission = false, files = inv.itemCount, bytes = inv.bytes,
        places = inv.spaces.filter { it.count > 0 }.map { it.space.name to it.count },
    )

    /** The media-read permissions that are still missing (Android 13 splits them by kind). */
    fun missingPermissions(context: Context): List<String> {
        val needed = if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO) else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        return needed.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
    }

    /**
     * A cheap hint that an earlier Keepfin saved files here: one of its folders exists. Without it a new install is not asked to read
     * the music and videos. Settings > Storage always offers the search.
     */
    @Suppress("DEPRECATION")
    fun hint(): Boolean = try {
        fun dir(type: String, name: String) = File(Environment.getExternalStoragePublicDirectory(type), name).exists()
        dir(Environment.DIRECTORY_MUSIC, "Keepfin") || dir(Environment.DIRECTORY_MUSIC, "Keepfin Music") ||
            dir(Environment.DIRECTORY_MOVIES, "Keepfin") || dir(Environment.DIRECTORY_MOVIES, "Keepfin Videos") || dir(Environment.DIRECTORY_DOWNLOADS, "Keepfin")
    } catch (e: Exception) {
        false
    }

    /** Looks the files up in MediaStore. Null when a query failed (do not conclude that there is nothing). Blocking. */
    fun find(context: Context): List<EarlierRow>? {
        val spaces: List<Space> = FOLDERS.mapIndexed { i, folder -> defaultSpaces().first().copy(id = "earlier-$i", name = folder, folder = folder) }
        val result = MediaRows(context).query(spaces)
        if (!result.complete) return null
        return result.rows.map { r ->
            EarlierRow(
                r.uri.toString(), r.relativePath, r.name, r.kind, r.size, r.modifiedAt, r.addedAt, r.owned, r.title, r.artist, r.album,
                r.durationMs, r.width, r.height, r.track, r.year,
            )
        }
    }
}
