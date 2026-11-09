package app.keepfin.core.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeTest {
    private val now = 1_800_000_000_000L
    private val d = Mixes.DAY
    private var n = 0
    private fun song(artist: String? = "Someone", plays: Int = 0, last: Long? = null, added: Long? = null, fav: Boolean = false): MixSong {
        n++
        return MixSong("s$n", artist, plays, last, added ?: (now - n * d), fav)
    }
    private fun many(count: Int, make: () -> MixSong) = List(count) { make() }
    private fun ids(mixes: List<Mix>, id: String) = mixes.firstOrNull { it.id == id }?.ids

    @Test
    fun aMixWithFewerThanFiveSongsIsHidden() {
        assertTrue(Mixes.build(many(4) { song(plays = 3, last = now - d) }, now).none { it.id == Mixes.ON_REPEAT })
    }

    @Test
    fun onRepeatIsTheMostPlayedOfTheLastThirtyDays() {
        val recent = many(5) { song(plays = 2, last = now - 2 * d) } + song(plays = 9, last = now - d)
        val old = many(5) { song(plays = 50, last = now - 40 * d) }
        val ids = ids(Mixes.build(recent + old, now), Mixes.ON_REPEAT)!!
        assertEquals(6, ids.size)
        assertEquals(recent.last().id, ids.first())
        assertTrue(old.none { it.id in ids })
    }

    @Test
    fun forgottenFavouritesNeedAPlayAndSixtyQuietDays() {
        val loved = many(3) { song(fav = true, plays = 1, last = now - 70 * d) }
        val lots = many(2) { song(plays = 6, last = now - 90 * d) }
        val neverPlayedFavourite = song(fav = true)
        val playedLately = song(fav = true, plays = 4, last = now - 10 * d)
        val ids = ids(Mixes.build(loved + lots + neverPlayedFavourite + playedLately, now), Mixes.FORGOTTEN)!!
        assertEquals(5, ids.size)
        assertFalse(neverPlayedFavourite.id in ids)
        assertFalse(playedLately.id in ids)
        assertEquals(lots.map { it.id }.toSet(), ids.take(2).toSet(), "the longest-forgotten first")
    }

    @Test
    fun recentIsNewestFirstAndNeverPlayedLeavesOutAnythingEverPlayed() {
        val unheard = many(5) { song() }
        val played = song(plays = 1, last = now - d, added = now)
        val mixes = Mixes.build(unheard + played, now)
        assertEquals(played.id, ids(mixes, Mixes.RECENT)!!.first())
        assertFalse(played.id in ids(mixes, Mixes.NEVER)!!)
        assertEquals(unheard.first().id, ids(mixes, Mixes.NEVER)!!.first())
    }

    @Test
    fun becauseYouPlayNamesTheMostPlayedArtistAndGathersTheirSongs() {
        val pale = many(6) { song("Pale Cassette", plays = 4, last = now - 3 * d) }
        val other = many(5) { song("Oren Vale", plays = 1, last = now - 3 * d) }
        val unplayedToo = song("Pale Cassette feat. Someone")
        val mix = Mixes.build(pale + other + unplayedToo, now).first { it.id == Mixes.BECAUSE }
        assertEquals("Because you play Pale Cassette", Mixes.build(pale + other + unplayedToo, now).first { it.id == Mixes.BECAUSE }.title)
        assertEquals(7, mix.ids.size)
        assertEquals(unplayedToo.id, mix.ids.first(), "songs you have not played lately come first")
    }

    @Test
    fun becauseYouPlayHasNoArtistWhenNothingWasPlayedLately() {
        val songs = many(6) { song("Pale Cassette", plays = 4, last = now - 45 * d) }
        assertTrue(Mixes.build(songs, now).none { it.id == Mixes.BECAUSE })
    }

    @Test
    fun lateNightReadsTheHourOnThePersonsOwnClock() {
        val tenThirtyUtc = (now / d) * d + (22 * 60 + 30) * 60_000L
        assertTrue(Mixes.isLateNight(tenThirtyUtc, 0))
        assertTrue(Mixes.isLateNight(tenThirtyUtc, 3 * 60), "01:30")
        assertFalse(Mixes.isLateNight(tenThirtyUtc, 6 * 60), "04:30")
        assertFalse(Mixes.isLateNight(tenThirtyUtc - 6 * 3_600_000L, 0), "4:30 pm")
        assertTrue(Mixes.isLateNight(tenThirtyUtc, -5 * 60).not(), "5:30 pm in New York")
        val songs = many(5) { song(plays = 2, last = tenThirtyUtc - 2 * d) } + many(3) { song(plays = 2, last = tenThirtyUtc - 2 * d - 8 * 3_600_000L) }
        assertEquals(5, ids(Mixes.build(songs, now), Mixes.LATE_NIGHT)!!.size)
    }

    @Test
    fun mixesComeInTheOrderHomeShowsThem() {
        val songs = many(6) { song("Pale Cassette", plays = 5, last = now - 70 * d, fav = true) } +
            many(6) { song("Pale Cassette", plays = 5, last = now - 22 * 60 * 60_000L, fav = true) }
        val wanted = listOf(Mixes.ON_REPEAT, Mixes.FORGOTTEN, Mixes.RECENT, Mixes.NEVER, Mixes.BECAUSE, Mixes.LATE_NIGHT)
        val order = Mixes.build(songs, now).map { it.id }
        assertEquals(order, order.sortedBy { wanted.indexOf(it) })
    }

    private fun rel(id: String, ageDays: Double, kept: Boolean = false, artist: String = "a") =
        FeedRelease(id, artist, id, now - (ageDays * d).toLong(), kept)

    @Test
    fun theFeedGroupsThisWeekAndEarlierThisMonthNewestFirst() {
        val groups = ReleaseFeed.group(listOf(rel("old", 40.0), rel("b", 14.0), rel("a", 3.0), rel("c", 5.0), rel("future", -1.0)), now)
        assertEquals(listOf("This week", "Earlier this month"), groups.map { it.label })
        assertEquals(listOf("a", "c"), groups[0].ids)
        assertEquals(listOf("b"), groups[1].ids)
    }

    @Test
    fun aKeptReleaseLeavesTheFeedAndAnEmptyFeedHasNoGroups() {
        assertTrue(ReleaseFeed.group(listOf(rel("a", 1.0, kept = true)), now).isEmpty())
        assertTrue(ReleaseFeed.group(emptyList(), now).isEmpty())
    }

    @Test
    fun theDotShowsOnlyForArtistsWithSomethingInTheFeed() {
        val list = listOf(rel("a", 1.0, artist = "pale cassette"), rel("b", 2.0, kept = true, artist = "oren vale"), rel("c", 90.0, artist = "sable ash"))
        assertEquals(setOf("pale cassette"), ReleaseFeed.artistsWithNew(list, now))
    }

    @Test
    fun theFirstCheckOfAnArtistRecordsTheCatalogueAndFeedsNothing() {
        assertTrue(ReleaseFeed.fresh(emptySet(), listOf("x", "y"), firstCheck = true).isEmpty())
        assertEquals(listOf("z"), ReleaseFeed.fresh(setOf("x", "y"), listOf("x", "z", "z"), firstCheck = false))
    }

    @Test
    fun followKeysIgnoreCaseAccentsAndFeaturedArtists() {
        assertEquals(ReleaseFeed.artistKey("Pale Cassette"), ReleaseFeed.artistKey("pale  cassette feat. Oren Vale"))
        assertEquals(ReleaseFeed.artistKey("Beyoncé"), ReleaseFeed.artistKey("Beyonce - Topic"))
        assertNull(ReleaseFeed.artistKey(""))
    }

    @Test
    fun channelsAreNeverGuessedAndKindsAreSplitFromTitles() {
        val key = ReleaseFeed.artistKey("Pale Cassette")!!
        assertEquals("UC1", ReleaseFeed.pickChannel(listOf("Oren Vale" to "UC9", "Pale Cassette" to "UC1"), key))
        assertNull(ReleaseFeed.pickChannel(listOf("Pale Cassette" to "MPRE1", "Other" to "UC2"), key))
        assertEquals("Album" to "Salt Season", ReleaseFeed.splitKind("Album - Salt Season"))
        assertEquals(null to "Night Ferry", ReleaseFeed.splitKind("Night Ferry"))
    }

    @Test
    fun ageWords() {
        assertEquals("today", ReleaseFeed.ageWords(now - 3_600_000L, now))
        assertEquals("yesterday", ReleaseFeed.ageWords(now - d, now))
        assertEquals("3 days ago", ReleaseFeed.ageWords(now - 3 * d, now))
        assertEquals("1 week ago", ReleaseFeed.ageWords(now - 8 * d, now))
        assertEquals("2 weeks ago", ReleaseFeed.ageWords(now - 15 * d, now))
    }
}
