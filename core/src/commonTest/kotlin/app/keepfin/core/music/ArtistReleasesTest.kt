package app.keepfin.core.music

import app.keepfin.core.readSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Captures are trimmed real YouTube Music artist-page answers (fixtures/engine/music/samples/ytmusic-artist-*.json). */
class ArtistReleasesTest {
    private fun sample(name: String) = readSample("music", "ytmusic-artist-$name.json")

    @Test
    fun anArtistPageGivesItsAlbumsThenSinglesWithTheirKinds() {
        val releases = ArtistReleases.parse(sample("albums-and-singles"))
        assertEquals(6, releases.size)
        assertEquals(
            listOf("petal", "Wicked: For Good – The Soundtrack (Commentary)", "Wicked: For Good – The Soundtrack", "petal", "hate that i made you love me", "hate that i made you love me"),
            releases.map { it.title },
        )
        assertEquals(listOf("Album", "Album", "Album", "Single", "Single", "Single"), releases.map { it.kind })
        assertEquals("https://music.youtube.com/playlist?list=OLAK5uy_mtyCSl5kALK2CkurHfhbSbHRAeY4WPmew", releases.first().link)
        assertEquals("https://music.youtube.com/playlist?list=OLAK5uy_n3alMU9vPJpZfP47FiCbcrjS5kUMdrOsQ", releases[3].link)
        assertEquals(releases.size, releases.map { it.link }.toSet().size)
        assertEquals(true, releases.first().thumbnail!!.startsWith("https://"))
    }

    @Test
    fun theVideosShelfIsNotReadWhenThereAreAlbums() {
        val releases = ArtistReleases.parse(sample("one-album"))
        assertEquals(listOf("Spirit of Ashes", "Cursed Land", "Necro", "Typical Story Il"), releases.map { it.title })
        assertEquals(listOf("Album", "Single", "Single", "Single"), releases.map { it.kind })
    }

    @Test
    fun aChannelWithoutAlbumsGivesItsLatestUploads() {
        val releases = ArtistReleases.parse(sample("channel-videos-only"))
        assertEquals(3, releases.size)
        assertEquals("https://www.youtube.com/watch?v=69sKYwsxUM8", releases.first().link)
        assertEquals("VOCÊ É BRABA // VALORANT 2026 Game Changers Championship Hype Film", releases.first().title)
        assertEquals(setOf("Video"), releases.map { it.kind }.toSet())
    }

    @Test
    fun aPageWithNothingToReportIsEmptyNotAnError() {
        assertEquals(emptyList(), ArtistReleases.parse("""{"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[]}}}"""))
        assertEquals(emptyList(), ArtistReleases.parse("{}"))
    }

    @Test
    fun textThatIsNotJsonThrows() {
        assertFailsWith<Exception> { ArtistReleases.parse("<html>") }
    }

    @Test
    fun anAlbumWithoutAPlaylistLinksItsPageAddress() {
        val json = """{"a":{"musicCarouselShelfRenderer":{"contents":[{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Solo"}]},"subtitle":{"runs":[{"text":"EP"},{"text":" • "},{"text":"2026"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_abc-DEF_1","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}}}}]}}}"""
        val release = ArtistReleases.parse(json).single()
        assertEquals("https://music.youtube.com/browse/MPREb_abc-DEF_1", release.link)
        assertEquals("EP", release.kind)
        assertNull(release.thumbnail)
    }

    @Test
    fun onlyAChannelIdMakesARequestAndOldAddressesGiveTheirId() {
        assertEquals(null, ArtistReleases.browseBody("MPREb_x"))
        assertEquals(null, ArtistReleases.browseBody("UC\"x"))
        assertEquals(true, ArtistReleases.browseBody("UC0076UMUgEng8HORUw_MYHA")!!.contains("\"browseId\":\"UC0076UMUgEng8HORUw_MYHA\""))
        assertEquals("UC0076UMUgEng8HORUw_MYHA", ArtistReleases.channelId("https://www.youtube.com/channel/UC0076UMUgEng8HORUw_MYHA/releases"))
        assertEquals("UC0076UMUgEng8HORUw_MYHA", ArtistReleases.channelId(ArtistReleases.channelUrl("UC0076UMUgEng8HORUw_MYHA")))
        assertNull(ArtistReleases.channelId("https://www.youtube.com/@someone"))
        assertEquals(true, ArtistReleases.isLegacyAddress("https://www.youtube.com/channel/UCabcdefgh/releases"))
        assertEquals(false, ArtistReleases.isLegacyAddress(ArtistReleases.channelUrl("UCabcdefgh")))
    }
}
