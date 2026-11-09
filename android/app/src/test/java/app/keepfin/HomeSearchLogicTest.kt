package app.keepfin

import app.keepfin.ui.home.HomeKind
import app.keepfin.ui.home.HomeLogic
import app.keepfin.core.home.Mixes
import app.keepfin.ui.home.QuickTile
import app.keepfin.ui.search.SearchLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's rows and Search's grouping are pure: what the screens show is decided here and checked on the JVM. */
class HomeSearchLogicTest {
    private val now = 1_800_000_000_000L
    private val day = HomeLogic.DAY

    private fun song(id: Int, title: String = "Song $id", artist: String? = "Artist", album: String? = null, plays: Int = 0, played: Long? = null, favorite: Boolean = false, added: Long = now - id * day) =
        LibraryItem(
            id = "a$id", space = "music", uri = "content://a/$id", folder = "", kind = LibraryItemKind.AUDIO, title = title, artist = artist, album = album, size = 1, modifiedAt = 0,
            addedAt = added, plays = plays, lastPlayedAt = played, favorite = favorite,
        )

    private fun video(id: Int, title: String = "Video $id", position: Double? = null, watched: Boolean = false, added: Long = now - id * day) =
        LibraryItem(
            id = "v$id", space = "videos", uri = "content://v/$id", folder = "", kind = LibraryItemKind.VIDEO, title = title, duration = 600.0, size = 1, modifiedAt = 0, addedAt = added,
            position = position, watched = watched, lastPlayedAt = if (position != null) now - id * 1000 else null,
        )

    @Test fun aVideoKeptInMusicIsUnderMusicNotVideos() {
        val clip = video(9).copy(space = "music")
        val music = setOf("music")
        assertTrue(HomeLogic.matches(clip, HomeKind.MUSIC, music))
        assertFalse(HomeLogic.matches(clip, HomeKind.VIDEOS, music))
        assertTrue(HomeLogic.matches(video(8), HomeKind.VIDEOS, music))
        assertFalse(HomeLogic.matches(video(8), HomeKind.MUSIC, music))
        // the listening rows hold it with the songs
        val rows = HomeLogic.listening(listOf(clip) + (1..5).map { song(it) }, HomeKind.MUSIC, now, music).associateBy { it.mix.id }
        assertTrue(rows.getValue("recent").songs.any { it.id == clip.id })
    }

    @Test fun theMixesComeFromTheCoreWithItemsInTheMixOrderAndNoneForVideosOrPhotos() {
        val library = (1..6).map { song(it, artist = "Pale Cassette", plays = 3, played = now - 2 * day) } + (7..12).map { song(it, artist = "Oren Vale") }
        val mixes = HomeLogic.listening(library, HomeKind.ALL, now)
        val byId = mixes.associateBy { it.mix.id }
        assertEquals(listOf("on-repeat", "recent", "never", "because"), mixes.map { it.mix.id })
        assertEquals("Because you play Pale Cassette", byId.getValue("because").mix.title)
        assertEquals(library.filter { it.artist == "Pale Cassette" }.map { it.id }.toSet(), byId.getValue("because").songs.map { it.id }.toSet())
        assertEquals((7..12).map { "a$it" }.toSet(), byId.getValue("never").songs.map { it.id }.toSet())
        assertTrue(HomeLogic.listening(library, HomeKind.VIDEOS, now).isEmpty())
        assertTrue(HomeLogic.listening(library, HomeKind.PHOTOS, now).isEmpty())
    }

    @Test fun lateNightReadsTheLocalClockOffset() {
        val tenThirtyUtc = (now / day) * day + (22 * 60 + 30) * 60_000L
        val library = (1..5).map { song(it, plays = 2, played = tenThirtyUtc - 2 * day) }
        assertTrue(HomeLogic.listening(library, HomeKind.MUSIC, now, utcOffsetMinutes = 0).any { it.mix.id == "late-night" })
        assertTrue(HomeLogic.listening(library, HomeKind.MUSIC, now, utcOffsetMinutes = -5 * 60).none { it.mix.id == "late-night" })
    }

    private fun photo(id: Int) = LibraryItem(id = "p$id", space = "downloads", uri = "content://p/$id", folder = "", kind = LibraryItemKind.IMAGE, title = "Photo $id", size = 1, modifiedAt = 0, addedAt = now - id * day)

    private fun collection(id: String, ids: List<String>, updated: Long = now) =
        LibraryCollection(id, "List $id", CollectionKind.PLAYLIST, "music", ids, createdAt = updated, updatedAt = updated)

    @Test fun chipsFilterByKindAndAllKeepsEverythingButOther() {
        val items = listOf(song(1), video(1), photo(1))
        assertEquals(3, items.count { HomeLogic.matches(it, HomeKind.ALL) })
        assertEquals(listOf("v1"), items.filter { HomeLogic.matches(it, HomeKind.VIDEOS) }.map { it.id })
        assertEquals(listOf("a1"), items.filter { HomeLogic.matches(it, HomeKind.MUSIC) }.map { it.id })
        assertEquals(listOf("p1"), items.filter { HomeLogic.matches(it, HomeKind.PHOTOS) }.map { it.id })
        assertEquals(HomeKind.ALL, HomeKind.of("nonsense"))
        assertEquals(HomeKind.PHOTOS, HomeKind.of("photos"))
    }

    @Test fun everyRowIsEmptyForAnEmptyLibrary() {
        assertTrue(HomeLogic.quickGrid(emptyList(), emptyList(), HomeKind.ALL).isEmpty())
        assertTrue(HomeLogic.continueWatching(emptyList(), HomeKind.ALL).isEmpty())
        assertTrue(HomeLogic.recentlyAdded(emptyList(), HomeKind.ALL).isEmpty())
        assertTrue(HomeLogic.listening(emptyList(), HomeKind.ALL, now).isEmpty())
    }

    @Test fun quickGridMixesKindsNewestFirstAndStopsAtEight() {
        val items = (1..6).map { song(it, played = now - it * 60_000) } + (1..6).map { video(it, added = now - it * 1000 * 60 * 60) } + photo(1)
        val grid = HomeLogic.quickGrid(items, emptyList(), HomeKind.ALL)
        assertEquals(HomeLogic.QUICK_MAX, grid.size)
        assertTrue("videos and songs share the grid", grid.any { it is QuickTile.Item && it.item.kind == LibraryItemKind.VIDEO } && grid.any { it is QuickTile.Item && it.item.kind == LibraryItemKind.AUDIO })
        assertEquals(listOf("a1", "a2", "a3", "a4"), grid.take(4).map { (it as QuickTile.Item).item.id })
        assertTrue(HomeLogic.quickGrid(items, emptyList(), HomeKind.MUSIC).all { it is QuickTile.Item && it.item.kind == LibraryItemKind.AUDIO })
    }

    @Test fun aCollectionIsOneTileAndItsSongsStayOutOfTheGrid() {
        val items = listOf(song(1), song(2), song(3, played = now - 1000))
        val grid = HomeLogic.quickGrid(items, listOf(collection("c", listOf("a1", "a2"), updated = now - 5000)), HomeKind.ALL)
        assertEquals(listOf("i-a3", "c-c"), grid.map { it.key })
        // A collection with nothing of the chosen kind does not show for that chip.
        assertTrue(HomeLogic.quickGrid(items, listOf(collection("c", listOf("a1"))), HomeKind.VIDEOS).isEmpty())
    }

    @Test fun continueWatchingIsVideosWithAProgressAndHidesForMusicAndPhotos() {
        val items = listOf(video(1, position = 120.0), video(2), video(3, position = 590.0, watched = true), song(1))
        assertEquals(listOf("v1"), HomeLogic.continueWatching(items, HomeKind.ALL).map { it.id })
        assertEquals(listOf("v1"), HomeLogic.continueWatching(items, HomeKind.VIDEOS).map { it.id })
        assertTrue(HomeLogic.continueWatching(items, HomeKind.MUSIC).isEmpty())
        assertTrue(HomeLogic.continueWatching(items, HomeKind.PHOTOS).isEmpty())
    }

    @Test fun recentlyAddedIsVideosAndPhotosNotMusic() {
        val items = listOf(song(1, added = now), video(1, added = now - 1000), photo(1))
        assertEquals(listOf("v1", "p1"), HomeLogic.recentlyAdded(items, HomeKind.ALL).map { it.id })
        assertEquals(listOf("p1"), HomeLogic.recentlyAdded(items, HomeKind.PHOTOS).map { it.id })
        assertTrue(HomeLogic.recentlyAdded(items, HomeKind.MUSIC).isEmpty())
    }

    @Test fun listeningRowsNeedFiveSongsAndSayWhichData() {
        // Four songs played lately: not enough for "On repeat".
        val few = (1..4).map { song(it, plays = it, played = now - it * day) }
        assertTrue(HomeLogic.listening(few, HomeKind.ALL, now).none { it.mix.id == Mixes.ON_REPEAT })
        // Five: on repeat orders by plays, ignoring songs not played in 30 days.
        val five = (1..5).map { song(it, plays = it, played = now - it * day) } + song(9, plays = 99, played = now - 40 * day)
        val onRepeat = HomeLogic.listening(five, HomeKind.ALL, now).first { it.mix.id == Mixes.ON_REPEAT }.songs
        assertEquals(listOf("a5", "a4", "a3", "a2", "a1"), onRepeat.map { it.id })
    }

    @Test fun forgottenFavouritesAreFavouritesOrFivePlayersNotHeardForSixtyDays() {
        val forgotten = (1..5).map { song(it, plays = 6, played = now - (70 + it) * day) } + song(7, favorite = true, plays = 1, played = now - 90 * day) +
            song(8, plays = 9, played = now - 10 * day) + song(10, favorite = true, plays = 0, played = null)
        val row = HomeLogic.listening(forgotten, HomeKind.ALL, now).first { it.mix.id == Mixes.FORGOTTEN }.songs.map { it.id }
        assertTrue("a8 was heard lately", "a8" !in row)
        assertTrue("a7 is a favourite", "a7" in row)
        assertEquals("oldest first", "a7", row.first())
    }

    @Test fun neverPlayedAndRecentlyAddedMusicShowFromFiveSongs() {
        val songs = (1..5).map { song(it) }
        val rows = HomeLogic.listening(songs, HomeKind.ALL, now).map { it.mix.id }
        assertTrue(Mixes.RECENT in rows && Mixes.NEVER in rows)
        assertTrue(Mixes.ON_REPEAT !in rows && Mixes.FORGOTTEN !in rows)
        assertTrue("videos and photos chips hide the music rows", HomeLogic.listening(songs, HomeKind.VIDEOS, now).isEmpty() && HomeLogic.listening(songs, HomeKind.PHOTOS, now).isEmpty())
        assertEquals("Music chip keeps them", rows, HomeLogic.listening(songs, HomeKind.MUSIC, now).map { it.mix.id })
    }

    // ---- Search ----

    @Test fun searchGroupsByKindAndIgnoresDiacritics() {
        val items = listOf(
            song(1, "Main Title", artist = "Ludwig Göransson", album = "Agent 327"), song(2, "Chase", artist = "Ludwig Göransson", album = "Agent 327"),
            video(1, "Goransson interview"), photo(1),
        )
        val results = SearchLogic.search(items, listOf(collection("c", listOf("a1"))), "goransson")
        assertEquals(listOf("a1", "a2"), results.songs.map { it.id }.sorted())
        assertEquals(listOf("v1"), results.videos.map { it.id })
        assertEquals(1, results.artists.size)
        assertEquals("the album matches by its artist", 1, results.albums.size)
        assertTrue(results.photos.isEmpty())
        val album = SearchLogic.search(items, emptyList(), "agent")
        assertEquals(1, album.albums.size)
        assertEquals(2, album.songs.size)
        assertEquals(listOf("List c"), SearchLogic.search(items, listOf(collection("c", emptyList())), "list").playlists.map { it.name })
    }

    @Test fun anEmptyQueryFindsNothingAndTheYouTubeLinkIsEncoded() {
        assertTrue(SearchLogic.search(listOf(song(1)), emptyList(), "   ").isEmpty)
        assertEquals("https://www.youtube.com/results?search_query=lofi+beats+%26+rain", SearchLogic.youtubeSearchLink("  lofi beats & rain "))
        assertFalse(SearchLogic.search(listOf(song(1, "Rain")), emptyList(), "rain").isEmpty)
    }
}
