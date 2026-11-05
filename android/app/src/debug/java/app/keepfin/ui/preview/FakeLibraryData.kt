package app.keepfin.ui.preview

import app.keepfin.CollectionKind
import app.keepfin.CollectionSource
import app.keepfin.Follow
import app.keepfin.FollowMode
import app.keepfin.FolderLayout
import app.keepfin.LibraryCollection
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.ui.preview.FakeLibrary.FakeSnapshot
import app.keepfin.PendingCheck
import app.keepfin.SmartField
import app.keepfin.SmartMatch
import app.keepfin.SmartRule
import app.keepfin.SmartRules
import app.keepfin.Space
import app.keepfin.SpaceColor
import app.keepfin.SpaceIcon
import app.keepfin.SpaceKind
import app.keepfin.SpaceNaming
import app.keepfin.SpaceType

/**
 * Spaces v2 fixtures for Compose tests and screenshots: a Music space with tagged songs, a Workout space whose "Gym Mix" playlist is
 * followed (three new items, one removed upstream), a playlist with a held-back check, an album, one of your own lists, more spaces
 * than the hub shows, and three smart spaces. Everything is illustrative; no file exists behind any item.
 */
object FakeLibraryData {
    private fun audio(id: Int, title: String, artist: String, album: String?, space: String, minutesAgo: Long, now: Long, tags: List<String>, user: List<String> = emptyList(), plays: Int = 0, favorite: Boolean = false, duration: Double = 200.0) =
        LibraryItem(
            id = "content://media/external/audio/media/$id", space = space, uri = "content://media/external/audio/media/$id", folder = "", kind = LibraryItemKind.AUDIO,
            title = title, artist = artist, album = album, albumArtist = artist, year = 2024, duration = duration, size = 7_000_000, modifiedAt = now, addedAt = now - minutesAgo * 60_000,
            plays = plays, lastPlayedAt = if (plays > 0) now - minutesAgo * 30_000 else null, favorite = favorite, gain = -3.5, genre = tags.firstOrNull(),
            tags = tags, userTags = user, sourceUrl = "https://music.youtube.com/watch?v=$id",
        )

    private fun video(id: Int, title: String, space: String, minutesAgo: Long, now: Long, tags: List<String>, duration: Double = 400.0) =
        LibraryItem(
            id = "content://media/external/video/media/$id", space = space, uri = "content://media/external/video/media/$id", folder = "", kind = LibraryItemKind.VIDEO,
            title = title, duration = duration, size = 180_000_000, modifiedAt = now, addedAt = now - minutesAgo * 60_000, width = 1920, height = 1080, tags = tags,
            sourceUrl = "https://www.youtube.com/watch?v=$id",
        )

    fun spaces(): List<Space> = listOf(
        Space("music", "Music", SpaceKind.MUSIC, "Keepfin Music", skipExisting = true, color = SpaceColor.COBALT, icon = SpaceIcon.MUSIC, pinned = true),
        Space("videos", "Videos", SpaceKind.VIDEO, "Keepfin Videos", color = SpaceColor.VIOLET, icon = SpaceIcon.VIDEO, pinned = true),
        Space("lofi", "Lo-fi study", SpaceKind.MIXED, "", FolderLayout.FLAT, SpaceNaming.TITLE, type = SpaceType.SMART, color = SpaceColor.TEAL, icon = SpaceIcon.MOON, pinned = true,
            rules = SmartRules(SmartMatch.ANY, listOf(SmartRule.Values(SmartField.TAG, listOf("lofi", "chill", "study"))), LibraryItemKind.AUDIO)),
        Space("workout", "Workout", SpaceKind.MUSIC, "Workout", color = SpaceColor.RUST, icon = SpaceIcon.DUMBBELL, pinned = true),
        Space("downloads", "Downloads", SpaceKind.MIXED, "Keepfin", color = SpaceColor.SLATE, icon = SpaceIcon.FOLDER, pinned = true),
        Space("kids", "Kids", SpaceKind.VIDEO, "Kids", color = SpaceColor.BERRY, icon = SpaceIcon.CHILD, pinned = false),
        Space("podcasts", "Podcasts", SpaceKind.MIXED, "Podcasts", color = SpaceColor.OLIVE, icon = SpaceIcon.MIC, pinned = false),
        Space("cooking", "Cooking", SpaceKind.MIXED, "", FolderLayout.FLAT, SpaceNaming.TITLE, type = SpaceType.SMART, color = SpaceColor.AMBER, icon = SpaceIcon.BOOK, pinned = false,
            rules = SmartRules(SmartMatch.ANY, listOf(SmartRule.Values(SmartField.TAG, listOf("recipe"))))),
        Space("arabic", "Arabic", SpaceKind.MIXED, "", FolderLayout.FLAT, SpaceNaming.TITLE, type = SpaceType.SMART, color = SpaceColor.VIOLET, icon = SpaceIcon.MIC, pinned = false,
            rules = SmartRules(SmartMatch.ANY, listOf(SmartRule.Values(SmartField.TAG, listOf("arabic", "khaleeji"))))),
    )

    fun snapshot(now: Long): FakeSnapshot {
        val items = listOf(
            audio(101, "Big Buck Bunny Theme", "Blender Studio", "Open Movies OST", "music", 30, now, listOf("soundtrack", "pop"), plays = 12, favorite = true),
            audio(102, "Sintel Theme", "Blender Studio", "Open Movies OST", "music", 29, now, listOf("soundtrack", "chill"), plays = 4),
            audio(103, "Tears of Steel Suite", "Blender Studio", "Open Movies OST", "music", 28, now, listOf("soundtrack", "chill", "lofi"), duration = 301.0),
            audio(104, "Cosmos Laundromat Waltz", "Blender Studio", "Open Movies OST", "music", 27, now, listOf("soundtrack", "lofi"), plays = 1),
            audio(105, "Agent 327 Main Title", "Ludwig Göransson", "Agent 327", "music", 300, now, listOf("soundtrack", "pop", "workout"), plays = 7, favorite = true, duration = 236.0),
            audio(106, "Barbershop Chase", "Ludwig Göransson", "Agent 327", "music", 299, now, listOf("soundtrack", "workout"), duration = 184.0),
            audio(107, "Layali Khaleeji", "Esther Wolf", null, "music", 900, now, listOf("chill", "lofi", "arabic", "khaleeji"), user = listOf("morning"), plays = 2),
            audio(108, "Llamigos Fanfare", "Esther Wolf", null, "music", 1_200, now, listOf("pop", "arabic")),
            audio(501, "Higher Ground", "Neon Hall", "Gym Mix", "workout", 20, now, listOf("workout", "pop")),
            audio(502, "Push Through", "Kira Vale", "Gym Mix", "workout", 19, now, listOf("workout", "pop")),
            audio(503, "Last Rep", "The Uplift", "Gym Mix", "workout", 18, now, listOf("workout", "rock")),
            audio(504, "Gone Tomorrow", "Glum Aleks", "Gym Mix", "workout", 600, now, listOf("workout")),
            audio(505, "First Flame", "Glum Aleks", "Gym Mix", "workout", 610, now, listOf("workout", "rock"), duration = 290.0),
            audio(506, "Cold Front", "The Overcast Choir", "Gym Mix", "workout", 620, now, listOf("workout", "chill"), duration = 227.0),
            video(201, "Spring - Blender Open Movie", "videos", 60, now, listOf("short film", "blender")),
            video(202, "Sintel - Open Movie", "videos", 120, now, listOf("short film", "blender")),
            video(203, "Tears of Steel", "videos", 180, now, listOf("short film", "blender")),
            video(204, "Smoky Lentil Soup", "downloads", 240, now, listOf("recipe", "soup")),
            video(205, "Weeknight Dumplings", "downloads", 250, now, listOf("recipe")),
            video(601, "Caminandes: Llama Drama", "kids", 90, now, listOf("kids", "cartoon")),
            video(602, "Caminandes: Gran Dillama", "kids", 95, now, listOf("kids", "cartoon")),
            audio(701, "Episode 12: Open movies", "Studio Notes", null, "podcasts", 1_000, now, listOf("podcast"), duration = 1_800.0),
        )
        fun id(n: Int) = (if (n >= 200 && n < 700 && n != 501 && n != 502 && n != 503 && n != 504 && n != 505 && n != 506) "content://media/external/video/media/$n" else "content://media/external/audio/media/$n")
        val minutes = 60_000L
        return FakeSnapshot(
            items = items.sortedByDescending { it.addedAt },
            collections = listOf(
                LibraryCollection(
                    "gym", "Gym Mix", CollectionKind.PLAYLIST, "workout", listOf(505, 506, 501, 502, 503, 504).map(::id), source = CollectionSource("https://music.youtube.com/playlist?list=PLgym", "youtube-music", "PLgym"),
                    follow = Follow(FollowMode.AUTO, 6, wifiOnly = true, notify = true, confirmAbove = 20, lastCheckedAt = now - 12 * minutes),
                    newItemIds = listOf(501, 502, 503).map(::id), removedItemIds = listOf(id(504)), createdAt = now - 86_400_000, updatedAt = now - 12 * minutes,
                ),
                LibraryCollection(
                    "daily-run", "Daily run", CollectionKind.PLAYLIST, "workout", listOf(id(505), id(506)), source = CollectionSource("https://music.youtube.com/playlist?list=PLrun", "youtube-music", "PLrun"),
                    follow = Follow(FollowMode.AUTO, 6, lastCheckedAt = now - 40 * minutes, pending = PendingCheck(28, now - 40 * minutes)), createdAt = now - 172_800_000, updatedAt = now - 40 * minutes,
                ),
                LibraryCollection(
                    "khaleeji", "Khaleeji Hits 2026", CollectionKind.PLAYLIST, "music", listOf(107, 108).map(::id), source = CollectionSource("https://music.youtube.com/playlist?list=PLkhal", "youtube-music", "PLkhal"),
                    follow = Follow(FollowMode.AUTO, 6, lastCheckedAt = now - 3 * 60 * minutes), createdAt = now - 259_200_000, updatedAt = now - 3 * 60 * minutes,
                ),
                LibraryCollection(
                    "rumours", "Agent 327", CollectionKind.ALBUM, "music", listOf(105, 106).map(::id), source = CollectionSource("https://music.youtube.com/playlist?list=OLAK5uy", "youtube-music", "OLAK5uy"),
                    follow = Follow(FollowMode.OFF), createdAt = now - 345_600_000, updatedAt = now - 345_600_000,
                ),
                LibraryCollection("roadtrip", "Road trip", CollectionKind.OWN, "music", listOf(101, 102, 103).map(::id), createdAt = now - 432_000_000, updatedAt = now - 432_000_000),
                LibraryCollection(
                    "old-arabic", "Old Arabic classics", CollectionKind.PLAYLIST, "music", listOf(id(104)), source = CollectionSource("https://music.youtube.com/playlist?list=PLold", "youtube-music", "PLold"),
                    follow = Follow(FollowMode.OFF), createdAt = now - 518_400_000, updatedAt = now - 518_400_000,
                ),
                LibraryCollection("mixtape", "Weekend mix", CollectionKind.OWN, "downloads", listOf(id(201), id(101)), createdAt = now - 600_000_000, updatedAt = now - 600_000_000),
            ),
            scannedAt = now,
        )
    }
}
