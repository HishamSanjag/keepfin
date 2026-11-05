package app.keepfin.folder.sql

import app.keepfin.AlbumGroup
import app.keepfin.ItemOrder
import app.keepfin.ItemQuery
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.MemoryBrowse
import app.keepfin.PageDirection
import app.keepfin.SmartField
import app.keepfin.SmartMatch
import app.keepfin.SmartRule
import app.keepfin.SmartRules
import app.keepfin.all
import app.keepfin.core.library.DiffContext
import app.keepfin.core.library.LibItem
import app.keepfin.core.library.LibModel
import app.keepfin.core.library.LibRoutes
import app.keepfin.core.library.LibSpace
import app.keepfin.core.library.LibraryMapping
import app.keepfin.core.library.ObservedAnswer
import app.keepfin.folder.FakeFolderAccess
import app.keepfin.folder.KeepfinStore
import app.keepfin.folder.OpDraft
import app.keepfin.folder.StoreOptions
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The paged index must answer exactly what the in-memory views answer: the same rows in the same order, for every order, filter,
 * search and group. The memory browse is the reference (it runs the old whole-list logic); this builds one library, loads it
 * into the SQL index through the real store, and compares the two query by query.
 */
class IndexBrowseParityTest {
    private val library = "11111111-1111-4111-8111-111111111111"
    private val actor = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val replica = "a1a1a1a1-a1a1-4a1a-8a1a-a1a1a1a1a1a1"
    private lateinit var root: File
    private var ids = 0
    private lateinit var state: SqlMergeState
    private lateinit var store: KeepfinStore
    private lateinit var index: IndexBrowse
    private lateinit var memory: MemoryBrowse
    private lateinit var items: List<LibraryItem>

    private fun nextId() = "00000000-0000-4000-8000-%012d".format(++ids)

    private val spaces = listOf(
        LibSpace("music", "Music", "music", "Music", layout = "collection"),
        LibSpace("videos", "Videos", "video", "Videos", layout = "collection"),
        LibSpace("downloads", "Downloads", "mixed", "Downloads", layout = "collection"),
    )

    @Before fun setUp() {
        root = Files.createTempDirectory("kf-parity").toFile()
        state = SqlMergeState(File(root, "state.db").path, library, replica, null, JdbcHost())
        val dir = File(root, "dev")
        store = KeepfinStore.open(
            StoreOptions(
                folder = FakeFolderAccess(), libraryId = library, actor = actor, deviceDir = dir, outboxDir = File(dir, "outbox"), newId = ::nextId,
                now = { 1_700_000_000_000L + ++ids }, flushIdleMs = 3_600_000, state = state,
            ),
        ).get(10, TimeUnit.SECONDS)
        val artists = listOf("Ludwig Göransson", "Blender Studio", "Esther Wolf", "The Uplift", "Glum Aleks", "beyoncé", "Beyonce")
        val tags = listOf("lofi", "workout", "chill", "rock", "pop", "road")
        val libItems = (0 until 420).map { i ->
            val kind = when (i % 7) { 0, 1 -> "video"; 2 -> "image"; else -> "audio" }
            val space = when (kind) { "audio" -> if (i % 11 == 0) "downloads" else "music"; "video" -> if (i % 5 == 0) "downloads" else if (i % 14 == 0) "music" else "videos"; else -> "downloads" }
            val folder = space.replaceFirstChar { it.uppercase() }
            val sub = when (i % 9) { 0 -> ""; 1, 2 -> "Open Movies"; 3 -> "Open Movies/Extras"; 4 -> "Road Trip"; else -> "Agent 327" }
            val title = "Item ${"%03d".format(i)} ${listOf("alpha", "bravo", "Éclair", "delta")[i % 4]}"
            LibItem(
                id = "%08x-0000-4000-8000-%012d".format((i * 2654435761L) and 0xffffffffL, i), space = space, kind = kind, title = title,
                artist = if (kind == "audio") artists[i % artists.size] else null, album = if (kind == "audio" && i % 3 != 0) "Album ${i % 6}" else null,
                albumArtist = if (kind == "audio" && i % 6 == 0) "Various Artists" else null,
                track = if (kind == "audio") (i % 13 + 1).toDouble() else null, disc = if (i % 17 == 0) 2.0 else null,
                duration = 30.0 + i, addedAt = 1_600_000_000_000.0 + (i % 50) * 1000, favorite = i % 8 == 0, plays = if (i % 3 == 0) (i % 10).toDouble() else null,
                lastPlayedAt = if (i % 3 == 0) 1_650_000_000_000.0 + i * 1000 else null, position = if (kind == "video" && i % 4 == 0) 60.0 + i else null,
                watched = if (kind == "video" && i % 6 == 0) true else null,
                tags = if (i % 2 == 0) listOf(tags[i % tags.size], tags[(i + 2) % tags.size]) else listOf(tags[i % tags.size]),
                userTags = if (i % 10 == 0) listOf("mine") else emptyList(), sourceUrl = if (i % 4 == 0) "https://www.youtube.com/watch?v=abc$i" else null,
                place = (if (sub.isEmpty()) "$folder/file$i.bin" else "$folder/$sub/file$i.bin"),
            )
        }
        val model = LibModel(libItems, emptyList(), spaces, LibRoutes("music", "downloads"))
        val ctx = DiffContext(actor, replica, ::nextId, { 0L }, { emptyList() }, ObservedAnswer(emptyMap(), emptyMap(), emptyMap(), emptyMap()))
        store.apply(LibraryMapping.diff(null, model, ctx).map { OpDraft(it.kind, it.body) }).get(60, TimeUnit.SECONDS)
        val byWire = spaces.associate { LibraryMapping.spaceWireId(it.id) to it.id }
        val folderOf = spaces.associate { it.id to it.folder }
        val env = object : BrowseEnv {
            override fun modelSpace(wire: String) = byWire[wire] ?: wire
            override fun spaceFolder(modelId: String) = folderOf[modelId].orEmpty()
            override fun uriFor(place: String) = "keepfin-folder:$place"
            override fun linked(id: String, place: String): LinkedView? = null
            override fun jobIdFor(id: String): String? = null
            override fun itemForJob(jobId: String): String? = null
            override fun musicSpaces() = listOf("music")
        }
        val reader = IndexReader(state.statePath, { state.epoch }) { JdbcHost().open(it) }
        index = IndexBrowse(reader, env) { LibraryMapping.spaceWireId(it) }
        items = index.all(ItemQuery(order = ItemOrder.ADDED), 100)
        memory = MemoryBrowse({ items }, MutableStateFlow(0L), { emptyList() }, { setOf("music") })
    }

    @After fun tearDown() {
        store.abandon()
        root.deleteRecursively()
    }

    private fun idsOf(l: List<LibraryItem>) = l.map { it.id }

    private fun sameRows(query: ItemQuery, label: String) {
        assertEquals("$label: count", memory.count(query), index.count(query))
        assertEquals("$label: ids", memory.ids(query, 100_000), index.ids(query, 100_000))
        // the pages: walk forward by 37, then back from the end by 29, then reopen in the middle
        val forward = ArrayList<String>()
        var key: List<Any>? = null
        var dir = PageDirection.FIRST
        while (true) {
            val p = index.page(query, dir, key, 37)
            forward += idsOf(p.items)
            key = p.next ?: break
            dir = PageDirection.AFTER
        }
        assertEquals("$label: forward pages", memory.ids(query, 100_000), forward)
        if (forward.size > 40) {
            val middle = index.item(forward[forward.size / 2])!!
            val k = index.keyOf(middle, query.order)
            val back = index.page(query, PageDirection.BEFORE, k, 29)
            val expectedBack = forward.subList(maxOf(0, forward.size / 2 - 29), forward.size / 2)
            assertEquals("$label: before", expectedBack, idsOf(back.items))
            val at = index.page(query, PageDirection.AT, k, 25)
            assertEquals("$label: at", forward.subList(forward.size / 2, minOf(forward.size, forward.size / 2 + 25)), idsOf(at.items))
        }
    }

    @Test fun everyOrderAndFilterGivesTheMemoryAnswer() {
        for (order in ItemOrder.entries) {
            sameRows(ItemQuery(order = order), "all/$order")
            sameRows(ItemQuery(spaces = listOf("music"), kinds = setOf(LibraryItemKind.AUDIO), order = order), "music/$order")
        }
        sameRows(ItemQuery(favorite = true, order = ItemOrder.ADDED), "favourites")
        sameRows(ItemQuery(unplayed = true, order = ItemOrder.ADDED), "unplayed")
        sameRows(ItemQuery(played = true, order = ItemOrder.MOST_PLAYED), "most played")
        sameRows(ItemQuery(everPlayed = true, order = ItemOrder.RECENT), "recent")
        sameRows(ItemQuery(resumable = true, order = ItemOrder.RECENT), "resumable")
        sameRows(ItemQuery(spaces = listOf("videos"), folder = "Open Movies", order = ItemOrder.TITLE), "folder")
        sameRows(ItemQuery(spaces = listOf("videos"), subtree = "Open Movies", order = ItemOrder.TITLE), "subtree")
        sameRows(ItemQuery(excludeSpaces = listOf("music"), order = ItemOrder.TITLE), "exclude")
        sameRows(ItemQuery(tagsAny = listOf("lofi", "road"), order = ItemOrder.ADDED), "tags any")
        sameRows(ItemQuery(tagsAll = listOf("rock", "chill"), order = ItemOrder.ADDED), "tags all")
        sameRows(ItemQuery(tagsAny = listOf("mine"), order = ItemOrder.ADDED), "own tag")
        sameRows(ItemQuery(minPlays = 5, order = ItemOrder.MOST_PLAYED), "min plays")
        sameRows(ItemQuery(playedBefore = 1_650_100_000_000L, order = ItemOrder.STALE), "stale")
    }

    @Test fun songsAreAudioAndVideoKeptInMusicSpaces() {
        // Songs.isSong: audio anywhere, a video only in a music space
        val songs = ItemQuery(songs = true, order = ItemOrder.TITLE)
        val listed = index.all(songs, 100)
        val listedIds = listed.map { it.id }.toSet()
        val videoSongs = listed.filter { it.kind == LibraryItemKind.VIDEO }
        assertTrue("the fixture keeps videos in the music space", videoSongs.isNotEmpty())
        assertTrue(videoSongs.all { it.space == "music" })
        assertEquals(items.count { it.kind == LibraryItemKind.AUDIO } + videoSongs.size, listed.size)
        assertTrue(items.none { it.kind == LibraryItemKind.VIDEO && it.space != "music" && it.id in listedIds })
        // a video space holds no songs, the music space lists its audio and its video
        assertEquals(0, index.count(ItemQuery(spaces = listOf("videos"), songs = true)))
        assertEquals(videoSongs.size, index.count(ItemQuery(spaces = listOf("music"), songs = true, kinds = setOf(LibraryItemKind.VIDEO))))
        sameRows(songs, "songs")
        sameRows(ItemQuery(spaces = listOf("music"), songs = true, order = ItemOrder.ARTIST), "music songs")
        sameRows(ItemQuery(songs = true, favorite = true, order = ItemOrder.ADDED), "favourite songs")
        assertEquals(memory.artistCount(songs), index.artistCount(songs))
        assertEquals(memory.albumCount(songs), index.albumCount(songs))
    }

    @Test fun aRebuiltIndexIsRowForRowTheIndexTheOpsMade() {
        val cols = "id, space, media, title, tkey, artist, akey, rkey, album, bkey, albumArtist, albumKey, track, disc, dsort, tsort, year, genre, duration, durs, width, height, gain, added, played, plays, position, watched, fav, hasLyrics, srcUrl, srcId, tags, utags, place, dir, size, content, prov, musicVideo"
        val sql = "SELECT $cols FROM lib_index ORDER BY id"
        val before = state.query(sql)
        val tagsBefore = state.query("SELECT tag, id, own FROM lib_tags ORDER BY tag, id")
        val sourcesBefore = state.query("SELECT sid, id FROM lib_sources ORDER BY sid, id")
        val ftsBefore = state.query("SELECT i.id, f.title, f.artist, f.album, f.extra FROM lib_fts f JOIN lib_index i ON i.n = f.rowid ORDER BY i.id")
        assertTrue(before.size > 300)
        state.rebuildIndex()
        assertEquals(before, state.query(sql))
        assertEquals(tagsBefore, state.query("SELECT tag, id, own FROM lib_tags ORDER BY tag, id"))
        assertEquals(sourcesBefore, state.query("SELECT sid, id FROM lib_sources ORDER BY sid, id"))
        assertEquals(ftsBefore, state.query("SELECT i.id, f.title, f.artist, f.album, f.extra FROM lib_fts f JOIN lib_index i ON i.n = f.rowid ORDER BY i.id"))
    }

    @Test fun aSongThatKeepsAMusicVideoIsFlaggedByBothBrowses() {
        val song = items.first { it.kind == LibraryItemKind.AUDIO && it.space == "music" }
        assertTrue(items.none { it.musicVideo })
        val record = app.keepfin.core.sync.JObj(mapOf("content" to app.keepfin.core.sync.JStr("sha256:" + "d".repeat(64)), "size" to app.keepfin.core.sync.JNum(9L)))
        store.apply(listOf(OpDraft("item.set", mapOf("item" to app.keepfin.core.sync.JStr(song.id), "field" to app.keepfin.core.sync.JStr("companion:.video.mp4"), "value" to record)))).get(10, TimeUnit.SECONDS)
        val flagged = index.item(song.id)!!
        assertTrue(flagged.musicVideo)
        assertEquals(1, index.all(ItemQuery(order = ItemOrder.ADDED), 100).count { it.musicVideo })
        // the in-memory views carry the same flag, and the same rows come back flagged
        val memory2 = MemoryBrowse({ items.map { if (it.id == song.id) flagged else it } }, MutableStateFlow(0L), { emptyList() }, { setOf("music") })
        assertTrue(memory2.item(song.id)!!.musicVideo)
        assertEquals(1, memory2.top(ItemQuery(order = ItemOrder.ADDED), 1000).count { it.musicVideo })
        // a lyrics record is no music video
        val other = items.first { it.id != song.id && it.kind == LibraryItemKind.AUDIO }
        store.apply(listOf(OpDraft("item.set", mapOf("item" to app.keepfin.core.sync.JStr(other.id), "field" to app.keepfin.core.sync.JStr("companion:.lrc"), "value" to record)))).get(10, TimeUnit.SECONDS)
        assertEquals(false, index.item(other.id)!!.musicVideo)
    }

    @Test fun searchFindsWordStartsAndIgnoresAccents() {
        sameRows(ItemQuery(text = "eclair", order = ItemOrder.ADDED), "accents")
        sameRows(ItemQuery(text = "alph", order = ItemOrder.TITLE), "prefix")
        sameRows(ItemQuery(text = "open movies", order = ItemOrder.TITLE), "folder words")
        sameRows(ItemQuery(text = "goransson", kinds = setOf(LibraryItemKind.AUDIO), order = ItemOrder.ADDED), "artist")
        sameRows(ItemQuery(text = "beyonce", order = ItemOrder.ADDED), "two spellings")
        sameRows(ItemQuery(text = "lofi", order = ItemOrder.ADDED), "tag word")
    }

    @Test fun smartRulesGatherTheSameItems() {
        fun smart(match: SmartMatch, vararg rules: SmartRule, only: LibraryItemKind? = null) = ItemQuery(smart = SmartRules(match, rules.toList(), only), order = ItemOrder.ADDED)
        sameRows(smart(SmartMatch.ANY, SmartRule.Values(SmartField.TAG, listOf("lofi", "road"))), "smart tag")
        sameRows(smart(SmartMatch.ALL, SmartRule.Values(SmartField.TAG, listOf("rock")), SmartRule.Favorite), "smart tag and favourite")
        sameRows(smart(SmartMatch.ANY, SmartRule.Unplayed, SmartRule.Values(SmartField.KIND, listOf("video")), only = LibraryItemKind.AUDIO), "smart kind or unplayed")
        sameRows(smart(SmartMatch.ANY, SmartRule.Values(SmartField.SPACE, listOf("downloads"))), "smart space")
        sameRows(smart(SmartMatch.ALL, SmartRule.Values(SmartField.ARTIST, listOf("Esther Wolf")), SmartRule.Unplayed), "smart artist (checked row by row)")
        sameRows(smart(SmartMatch.ANY, SmartRule.Values(SmartField.ALBUM, listOf("album 2"))), "smart album")
        sameRows(smart(SmartMatch.ANY, SmartRule.Values(SmartField.SOURCE, listOf("youtube"))), "smart source")
        sameRows(ItemQuery(smart = SmartRules(), order = ItemOrder.ADDED), "no rules gather nothing")
    }

    private fun summary(a: AlbumGroup) = listOf(a.key, a.title, a.artist, a.year, a.tracks, a.discs, a.compilation, a.routeKey)

    @Test fun duplicatesAreItemsWithTheSameContentWithinTheirScope() {
        val music = items.filter { it.space == "music" }.take(5)
        val a = "sha256:" + "a".repeat(64)
        val drafts = listOf(
            LibraryMapping.reviseDraft(music[0].id, a, 100, emptyList()),
            LibraryMapping.reviseDraft(music[1].id, a, 100, emptyList()),
            LibraryMapping.reviseDraft(music[2].id, a, 100, emptyList()),
            LibraryMapping.reviseDraft(music[3].id, "sha256:" + "b".repeat(64), 200, emptyList()),
            LibraryMapping.reviseDraft(music[4].id, "sha256:" + "c".repeat(64), 300, emptyList()),
        )
        store.apply(drafts.map { OpDraft(it.kind, it.body) }).get(10, TimeUnit.SECONDS)
        // the whole library: one group of three (the other two fingerprints are held once)
        val all = index.duplicates(ItemQuery())
        assertEquals(1, all.size)
        assertEquals(music.take(3).map { it.id }.toSet(), all.single().map { it.id }.toSet())
        // a collection that holds two of the three finds a pair; one that holds one of them finds nothing
        assertEquals(2, index.duplicates(ItemQuery(), listOf(music[0].id, music[1].id, music[3].id)).single().size)
        assertTrue(index.duplicates(ItemQuery(), listOf(music[0].id, music[3].id)).isEmpty())
        // a list's query is the scope too: the videos space holds none of them
        assertTrue(index.duplicates(ItemQuery(spaces = listOf("videos"))).isEmpty())
        assertEquals(1, index.duplicates(ItemQuery(spaces = listOf("music"))).size)
        // the in-memory library (no Keepfin folder) has no fingerprints
        assertTrue(memory.duplicates(ItemQuery()).isEmpty())
    }

    @Test fun groupsAndCountsAgree() {
        val q = ItemQuery(spaces = listOf("music"))
        val memAlbums = memory.albums(q, null, 10_000).map(::summary)
        val sqlAlbums = index.albums(q, null, 10_000).map(::summary)
        assertEquals(memAlbums.map { it[0] }.toSet(), sqlAlbums.map { it[0] }.toSet())
        val sa = sqlAlbums.associateBy { it[0] as String }
        // two spellings of one artist ("beyoncé", "Beyonce") are one album key: which spelling is shown is not a promise
        fun folded(row: List<Any?>?) = row?.mapIndexed { i, v -> if (i == 2) app.keepfin.LibraryViewsKeys.fold(v as String) else v }
        for (m in memAlbums) assertEquals("album ${m[0]}", folded(m), folded(sa[m[0] as String]))
        // paged by cursor, in the order the index gives
        val paged = ArrayList<String>()
        var after: String? = null
        while (true) {
            val page = index.albums(q, after, 7)
            paged += page.map { it.key }
            if (page.size < 7) break
            after = page.last().cursor
        }
        assertEquals(sqlAlbums.map { it[0] }, paged)
        assertEquals(sqlAlbums.size, index.albumCount(q))
        val memArtists = memory.artists(q, null, 10_000)
        val sqlArtists = index.artists(q, null, 10_000)
        assertEquals(memArtists.map { Triple(it.key, it.songs, it.albums) }.sortedBy { it.first }, sqlArtists.map { Triple(it.key, it.songs, it.albums) }.sortedBy { it.first })
        assertEquals(memArtists.size, index.artistCount(q))
        assertEquals(memory.counts(ItemQuery()).let { listOf(it.total, it.audio, it.video, it.image, it.other, it.bytes) }, index.counts(ItemQuery()).let { listOf(it.total, it.audio, it.video, it.image, it.other, it.bytes) })
        assertEquals(memory.spaceCounts().mapValues { it.value.total }, index.spaceCounts().mapValues { it.value.total })
        assertEquals(memory.folders("videos", "").map { it.path to it.count }, index.folders("videos", "").map { it.path to it.count })
        assertEquals(memory.folders("videos", "Open Movies").map { it.path to it.count }, index.folders("videos", "Open Movies").map { it.path to it.count })
        assertEquals(memory.folderList("music", emptySet()).map { it.path to it.count }, index.folderList("music", emptySet()).map { it.path to it.count })
        assertEquals(memory.tags(ItemQuery(), 2).map { Triple(it.tag, it.count, it.user) }, index.tags(ItemQuery(), 2).map { Triple(it.tag, it.count, it.user) })
        assertEquals(memory.facets(SmartField.TAG, 50).map { it.value to it.count }.toSet(), index.facets(SmartField.TAG, 50).map { it.value to it.count }.toSet())
        assertTrue(index.byIds(items.take(5).map { it.id }.reversed()).map { it.id } == items.take(5).map { it.id }.reversed())
    }
}
