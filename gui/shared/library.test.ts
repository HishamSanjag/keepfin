import {test} from 'node:test'
import assert from 'node:assert/strict'
import {
  buildM3u8, defaultLibrarySettings, defaultSpaceFor, mediaKindOf, normalizeTitle, outputStem, outputSubfolders,
  parseLrc, sameRecording, sourceIdFromUrl, matchExisting, jobFileState, removedSummary,
  canonicalSourceUrl, collectionFromPlaylist, collectionItems, collectionMembership, collectSourceTags, defaultFollow, entryKey,
  folderSpaces, gateNew, homeSpaces, isInfiniteSource, isPinned, matchesSmartRules, newEntries, normalizeTags, removedFromSource,
  reorderSpaces, smartItems, sortBySourceOrder, sourceIdOfCollection, spaceColorOf, tagsInScope,
  type LibraryItem, type SmartRules, type Space,
} from './library.js'

const job = {url: 'https://www.youtube.com/watch?v=abc123DEF45', title: 'Song: Live/Remix?', uploader: 'Daft Punk - Topic', createdAt: Date.UTC(2026, 0, 2), group: {title: 'My Album'}}

test('layouts pick subfolders', () => {
  assert.deepEqual(outputSubfolders('flat', job), [])
  assert.deepEqual(outputSubfolders('collection', job), ['My Album'])
  assert.deepEqual(outputSubfolders('collection', {...job, group: undefined}), [])
  assert.deepEqual(outputSubfolders('platform', job), ['YouTube'])
  assert.deepEqual(outputSubfolders('artist-album', {...job, music: {album: 'Discovery'}}), ['Daft Punk', 'Discovery'])
  assert.deepEqual(outputSubfolders('artist-album', {...job, uploader: undefined}), [])
})

test('naming styles', () => {
  assert.equal(outputStem('title', job), 'Song_ Live_Remix_')
  assert.equal(outputStem('date-title', job), '2026-01-02 - Song_ Live_Remix_')
  assert.equal(outputStem('artist-title', {...job, music: {artist: 'Daft Punk', track: 'One More Time'}}), 'Daft Punk - One More Time')
  assert.equal(outputStem('artist-title', {...job, title: 'Daft Punk - Aerodynamic'}), 'Daft Punk - Aerodynamic')
  assert.equal(outputStem('artist-title', {...job, uploader: undefined, title: 'Plain'}), 'Plain')
})

test('source ids', () => {
  assert.equal(sourceIdFromUrl('https://music.youtube.com/watch?v=abc123DEF45&list=x'), 'youtube:abc123DEF45')
  assert.equal(sourceIdFromUrl('https://youtu.be/abc123DEF45?t=3'), 'youtube:abc123DEF45')
  assert.equal(sourceIdFromUrl('https://www.youtube.com/shorts/abc123DEF45'), 'youtube:abc123DEF45')
  assert.equal(sourceIdFromUrl('https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC'), 'spotify:4uLU6hMCjMI75M1A2tKUQC')
  assert.equal(sourceIdFromUrl('https://www.deezer.com/en/track/123456'), 'deezer:123456')
  assert.equal(sourceIdFromUrl('https://open.spotify.com/album/4uLU6hMCjMI75M1A2tKUQC'), undefined)
  assert.equal(sourceIdFromUrl('https://vimeo.com/1'), undefined)
  assert.equal(sourceIdFromUrl('nope'), undefined)
})

test('title noise is stripped but version words stay', () => {
  assert.equal(normalizeTitle('Daft Punk - One More Time (Official Video) [HD]', 'Daft Punk'), 'one more time')
  assert.equal(normalizeTitle('Café del Mar (feat. Someone)'), 'cafe del mar')
  assert.equal(normalizeTitle('Song (Live)'), 'song live')
  assert.notEqual(normalizeTitle('Song (Remix)'), normalizeTitle('Song'))
  assert.equal(normalizeTitle('(Official Video)'), '')
})

test('same recording: tiers and exclusions', () => {
  const base = {artist: 'Daft Punk', title: 'One More Time', album: 'Discovery', duration: 320}
  assert.ok(sameRecording(base, {artist: 'Daft Punk - Topic', title: 'One More Time (Official Audio)', duration: 321.5}))
  assert.ok(!sameRecording(base, {...base, duration: 323}))
  assert.ok(!sameRecording(base, {...base, album: 'Other'}))
  assert.ok(!sameRecording(base, {...base, title: 'One More Time (Live)'}))
  assert.ok(!sameRecording(base, {...base, title: 'One More Time (Slowed)'}))
  assert.ok(!sameRecording(base, {...base, artist: 'Someone Else'}))
  assert.ok(!sameRecording({title: '(Official Video)', artist: 'A', duration: 1}, {title: '[HD]', artist: 'A', duration: 1}))
  assert.ok(!sameRecording({title: 'x', duration: 1}, {title: 'x', duration: 1}))
})

test('matchExisting prefers source id and ignores missing items', () => {
  const items = [
    {id: '1', path: '/a', space: 'music', title: 'Song', artist: 'A', duration: 100, missing: true, sourceId: 'youtube:x'},
    {id: '2', path: '/b', space: 'music', title: 'Song', artist: 'A', duration: 100},
    {id: '3', path: '/c', space: 'music', title: 'Other', sourceId: 'youtube:y'},
  ]
  assert.equal(matchExisting(items, {sourceId: 'youtube:x'}), undefined)
  assert.equal(matchExisting(items, {sourceId: 'youtube:y'})?.by, 'source')
  assert.equal(matchExisting(items, {recording: {artist: 'A', title: 'Song', duration: 101}})?.item.id, '2')
})

test('routing and media kinds', () => {
  const library = defaultLibrarySettings({downloads: '/d', music: '/m', videos: '/v'})
  assert.equal(defaultSpaceFor('https://music.youtube.com/watch?v=abc123DEF45', library), 'music')
  assert.equal(defaultSpaceFor('https://www.youtube.com/watch?v=abc123DEF45', library), 'downloads')
  library.routes.other = 'gone'
  assert.equal(defaultSpaceFor('https://vimeo.com/1', library), 'downloads')
  assert.equal(mediaKindOf('C:\\x\\a.MKV'), 'video')
  assert.equal(mediaKindOf('a.flac'), 'audio')
  assert.equal(mediaKindOf('a.txt'), undefined)
})

test('LRC parsing', () => {
  const synced = parseLrc('[ti:T]\n[offset:500]\n[00:01.50]Hello\n[00:00.00][00:10.2]Again\n')
  assert.equal(synced.synced, true)
  assert.deepEqual(synced.lines.map((l) => [l.time, l.text]), [[0.5, 'Again'], [2, 'Hello'], [10.7, 'Again']])
  const plain = parseLrc('one\ntwo\n')
  assert.equal(plain.synced, false)
  assert.equal(plain.lines.length, 2)
})

test('M3U8 writer', () => {
  const text = buildM3u8('Mix', [{path: 'a\\b.mp3', title: 'B', artist: 'A', duration: 10.4}, {path: 'c.mp3', title: 'C'}])
  assert.equal(text, '#EXTM3U\n#PLAYLIST:Mix\n#EXTINF:10,A - B\na/b.mp3\n#EXTINF:-1,C\nc.mp3\n')
})

test('file state follows the library', () => {
  assert.equal(jobFileState({status: 'completed', file: 'C:/a.mp3'}, [{missing: true}]), 'deleted')
  assert.equal(jobFileState({status: 'completed', file: 'C:/a.mp3'}, [{missing: true}, {}]), 'present')
  assert.equal(jobFileState({status: 'completed'}, []), 'deleted')
  assert.equal(jobFileState({status: 'completed', file: 'C:/a.mp3'}, []), 'present')
  assert.equal(jobFileState({status: 'completed', existing: {}}, []), 'present')
  assert.equal(jobFileState({status: 'failed'}, []), 'present')
  assert.equal(removedSummary(0, 5), '')
  assert.equal(removedSummary(2, 5), '2 of 5 files removed')
  assert.equal(removedSummary(5, 5), 'Deleted')
})

// ---------------------------------------------------------------------------
// Collections, tags, smart spaces, spaces
// ---------------------------------------------------------------------------

const item = (patch: Partial<LibraryItem> & {id: string}): LibraryItem =>
  ({title: patch.id, space: 'music', path: `C:/${patch.id}`, folder: '', kind: 'audio', size: 1, modifiedAt: 1, addedAt: 1, hasArt: false, ...patch}) as LibraryItem

test('tags are normalised: lower case, deduplicated, no empties, links or long values, at most 15', () => {
  assert.deepEqual(normalizeTags([' Lo-Fi ', 'lo-fi', '', '  ', '#Chill', 'https://example.com/x', 'www.x.com', 'a'.repeat(31), 'Hip  Hop']), ['lo-fi', 'chill', 'hip hop'])
  assert.deepEqual(normalizeTags('Pop, Rock; ROCK'), ['pop', 'rock'])
  assert.equal(normalizeTags(Array.from({length: 40}, (_, n) => `tag${n}`)).length, 15)
  assert.deepEqual(normalizeTags(undefined), [])
  assert.deepEqual(normalizeTags([1, null, {}, 'ok']), ['ok'])
  assert.equal(normalizeTags('a'.repeat(30)).length, 1)
  // genre as a list and as a single value, tags and categories are gathered together
  assert.deepEqual(collectSourceTags({genres: ['Electronic', 'Ambient'], genre: 'Ambient', tags: ['chill', 'Study'], categories: ['Music']}), ['electronic', 'ambient', 'chill', 'study', 'music'])
})

test('scope tags: used at least twice, plus every user tag', () => {
  const items = [
    item({id: 'a', tags: ['lofi', 'solo']}),
    item({id: 'b', tags: ['lofi', 'jazz'], userTags: ['mine']}),
    item({id: 'c', tags: ['jazz', 'lofi', 'lofi']}),
  ]
  assert.deepEqual(tagsInScope(items), [
    {tag: 'lofi', count: 3, user: false},
    {tag: 'jazz', count: 2, user: false},
    {tag: 'mine', count: 1, user: true},
  ])
  assert.deepEqual(tagsInScope(items, 3).map((entry) => entry.tag), ['lofi', 'mine'])
  assert.deepEqual(tagsInScope([]), [])
})

test('smart rules: any or all, every rule type, exclusions', () => {
  const now = Date.UTC(2026, 9, 9)
  const items = [
    item({id: 'a', tags: ['lofi'], artist: 'Daft Punk - Topic', album: 'Discovery', sourceUrl: 'https://www.youtube.com/watch?v=aaaaaaaaaaa', sourceId: 'youtube:aaaaaaaaaaa', addedAt: now - 2 * 86_400_000, plays: 3}),
    item({id: 'b', userTags: ['study'], kind: 'video', space: 'videos', favorite: true, addedAt: now - 40 * 86_400_000}),
    item({id: 'c', space: 'downloads', sourceUrl: 'https://open.spotify.com/track/abcdefghij', sourceId: 'spotify:abcdefghij', album: 'Discovery', addedAt: now - 30 * 86_400_000}),
  ]
  const inCollection: Record<string, string[]> = {a: ['col-1'], c: ['col-2']}
  const context = {now, collectionsOf: (id: string) => inCollection[id] ?? []}
  const rules = (match: 'any' | 'all', ...list: SmartRules['rules']): SmartRules => ({match, rules: list})
  const ids = (spec: SmartRules) => smartItems(spec, items, context).map((entry) => entry.id)
  assert.deepEqual(ids(rules('any', {field: 'tag', values: ['LoFi', 'study']})), ['a', 'b'], 'tag values are ORed and match user tags')
  assert.deepEqual(ids(rules('all', {field: 'tag', values: ['lofi']}, {field: 'kind', values: ['video']})), [])
  assert.deepEqual(ids(rules('all', {field: 'tag', values: ['lofi']}, {field: 'artist', values: ['Daft Punk']})), ['a'], 'artist ignores the Topic suffix')
  assert.deepEqual(ids(rules('any', {field: 'album', values: ['discovery']})), ['a', 'c'])
  assert.deepEqual(ids(rules('any', {field: 'collection', values: ['col-2']})), ['c'])
  assert.deepEqual(ids(rules('any', {field: 'space', values: ['videos', 'downloads']})), ['b', 'c'])
  assert.deepEqual(ids(rules('any', {field: 'source', values: ['spotify']})), ['c'])
  assert.deepEqual(ids(rules('any', {field: 'source', values: ['youtube']})), ['a'])
  assert.deepEqual(ids(rules('any', {field: 'addedWithin', days: 7})), ['a'])
  assert.deepEqual(ids(rules('any', {field: 'favorite'})), ['b'])
  assert.deepEqual(ids(rules('any', {field: 'unplayed'})), ['b', 'c'])
  assert.deepEqual(ids({...rules('any', {field: 'unplayed'}, {field: 'favorite'}), onlyKind: 'video'}), ['b'], 'onlyKind applies after the rules')
  assert.deepEqual(ids({...rules('any', {field: 'unplayed'}), excludeSpaces: ['videos']}), ['c'], 'excluded spaces apply after the rules')
  assert.deepEqual(ids({match: 'any', rules: []}), [], 'no rules gather nothing')
  assert.deepEqual(ids(rules('any', {field: 'tag', values: []})), [], 'a rule without values never matches')
  assert.equal(matchesSmartRules(rules('all'), items[0], context), false)
  const membership = collectionMembership([{id: 'col-1', itemIds: ['a', 'b']}, {id: 'col-2', itemIds: ['b']}])
  assert.deepEqual(membership('b'), ['col-1', 'col-2'])
  assert.deepEqual(membership('zzz'), [])
})

test('new-item detection uses seen: deselected and deleted items are never new', () => {
  const entry = (id: string) => ({url: `https://www.youtube.com/watch?v=${id}`, id})
  const seen = ['youtube:aaaaaaaaaaa', 'youtube:bbbbbbbbbbb']
  const listing = [entry('aaaaaaaaaaa'), entry('bbbbbbbbbbb'), entry('ccccccccccc'), entry('ccccccccccc'), entry('ddddddddddd')]
  // a was downloaded, b was deselected or deleted: both are in `seen`; c repeats in the listing
  assert.deepEqual(newEntries(listing, seen).map((value) => value.id), ['ccccccccccc', 'ddddddddddd'])
  assert.deepEqual(newEntries([], seen), [])
  assert.deepEqual(newEntries(listing, listing.map((value) => entryKey(value.url))), [])
  assert.equal(entryKey('https://example.com/post/1'), 'https://example.com/post/1')
})

test('the confirm gate: more than confirmAbove waits for review', () => {
  assert.equal(gateNew(0, 20), 'none')
  assert.equal(gateNew(1, 20), 'enqueue')
  assert.equal(gateNew(20, 20), 'enqueue')
  assert.equal(gateNew(21, 20), 'confirm')
  assert.equal(gateNew(28, 20, true), 'enqueue', 'review approves the held items')
  assert.equal(gateNew(0, 20, true), 'none')
})

test('items that left the source are noticed only when they carry a source id', () => {
  const members = [{id: '1', sourceId: 'youtube:aaaaaaaaaaa'}, {id: '2', sourceId: 'youtube:bbbbbbbbbbb'}, {id: '3'}]
  assert.deepEqual(removedFromSource(members, new Set(['youtube:aaaaaaaaaaa'])), ['2'])
  assert.deepEqual(removedFromSource(members, new Set(['youtube:aaaaaaaaaaa', 'youtube:bbbbbbbbbbb'])), [])
})

test('collections order by collectionIndex; items without one follow, in their own order', () => {
  const index: Record<string, number> = {c: 2, a: 0, b: 1}
  assert.deepEqual(sortBySourceOrder(['c', 'x', 'a', 'y', 'b'], (id) => index[id]), ['a', 'b', 'c', 'x', 'y'])
  assert.deepEqual(sortBySourceOrder([], () => 0), [])
  const byId = new Map([['a', item({id: 'a'})], ['c', item({id: 'c'})]])
  assert.deepEqual(collectionItems({itemIds: ['c', 'gone', 'a']}, byId).map((value) => value.id), ['c', 'a'])
})

test('playlists migrate to collections of kind own, keeping name, order and times', () => {
  const collection = collectionFromPlaylist({id: 'pl-1', name: 'Gym', itemIds: ['b', 'a'], createdAt: 5, updatedAt: 9}, 'music')
  assert.deepEqual(collection, {id: 'pl-1', name: 'Gym', kind: 'own', space: 'music', itemIds: ['b', 'a'], seen: [], createdAt: 5, updatedAt: 9})
})

test('source links are canonical, mixes are recognised, defaults follow playlists and channels only', () => {
  const list = 'https://www.youtube.com/playlist?list=PLabc123'
  assert.equal(canonicalSourceUrl('https://music.youtube.com/playlist?list=PLabc123&si=zzz'), list)
  assert.equal(canonicalSourceUrl('https://www.youtube.com/watch?v=aaaaaaaaaaa&list=PLabc123&index=4'), list)
  assert.equal(canonicalSourceUrl('https://youtube.com/@Some_Channel/videos?utm_source=x'), 'https://www.youtube.com/@Some_Channel')
  assert.equal(canonicalSourceUrl('https://open.spotify.com/intl-de/playlist/37i9dQZF1DX4sWSpwq3LiO?si=1'), 'https://open.spotify.com/playlist/37i9dQZF1DX4sWSpwq3LiO')
  assert.equal(canonicalSourceUrl('https://soundcloud.com/a/sets/b/?utm_campaign=1#x'), 'https://soundcloud.com/a/sets/b')
  assert.equal(sourceIdOfCollection(list), 'PLabc123')
  assert.equal(sourceIdOfCollection('https://open.spotify.com/album/1A2B3C4D5E6F'), '1A2B3C4D5E6F')
  assert.equal(isInfiniteSource('https://www.youtube.com/watch?v=aaaaaaaaaaa&list=RDaaaaaaaaaaa'), true)
  assert.equal(isInfiniteSource('https://music.youtube.com/playlist?list=RDCLAK5uy_abc'), false, 'curated mixes are finite')
  assert.equal(isInfiniteSource(list), false)
  // Following is opt-in: off unless Keep in sync was chosen, and then only for playlists and channels.
  assert.equal(defaultFollow('playlist', list).mode, 'off')
  assert.equal(defaultFollow('channel').mode, 'off')
  assert.equal(defaultFollow('album').mode, 'off')
  assert.deepEqual(defaultFollow('playlist', list), {mode: 'off', intervalHours: 6, wifiOnly: true, notify: true, confirmAbove: 20})
  assert.deepEqual(defaultFollow('playlist', list, 12), {mode: 'auto', intervalHours: 12, wifiOnly: true, notify: true, confirmAbove: 20})
  assert.equal(defaultFollow('channel', undefined, 168).mode, 'auto')
  assert.equal(defaultFollow('album', undefined, 6).mode, 'off')
  assert.equal(defaultFollow('own', undefined, 6).mode, 'off')
  assert.equal(defaultFollow('playlist', 'https://www.youtube.com/watch?v=aaaaaaaaaaa&list=RDaaaaaaaaaaa', 6).mode, 'off')
})

test('spaces: order, pins on home, smart spaces are never targets', () => {
  const defaults = defaultLibrarySettings({downloads: 'C:/d', music: 'C:/m', videos: 'C:/v'}).spaces
  // Favorites is a kept smart space right after Videos, pinned like them
  assert.deepEqual(defaults.map((space) => space.id), ['downloads', 'music', 'videos', 'favorites'])
  assert.ok(isPinned(defaults[3]) && defaults[3].type === 'smart')
  const base = defaults.filter((space) => space.type === 'folder')
  const custom = (id: string, patch: Partial<Space> = {}): Space => ({id, name: id, kind: 'mixed', folder: `C:/${id}`, layout: 'flat', naming: 'title', skipExisting: false, type: 'folder', ...patch})
  const spaces = [...base, custom('kids'), custom('lofi', {type: 'smart', folder: '', rules: {match: 'any', rules: []}}), custom('gym', {pinned: true})]
  assert.ok(base.every((space) => isPinned(space) && space.type === 'folder'), 'built-ins are pinned')
  assert.deepEqual(homeSpaces(spaces).shown.map((space) => space.id), ['downloads', 'music', 'videos', 'gym'])
  assert.equal(homeSpaces(spaces).more, 2)
  // no pins at all: the first five show
  const unpinned = spaces.map((space) => ({...space, pinned: false}))
  assert.deepEqual(homeSpaces(unpinned).shown.map((space) => space.id), ['downloads', 'music', 'videos', 'kids', 'lofi'])
  assert.equal(homeSpaces(unpinned).more, 1)
  // never more than five cards
  const many = Array.from({length: 9}, (_, n) => custom(`s${n}`, {pinned: true}))
  assert.equal(homeSpaces(many).shown.length, 5)
  // reorder covers built-ins; an unknown or repeated id is rejected; omitted spaces keep their order at the end
  assert.deepEqual(reorderSpaces(spaces, ['gym', 'videos', 'music', 'downloads', 'kids', 'lofi']).map((space) => space.id), ['gym', 'videos', 'music', 'downloads', 'kids', 'lofi'])
  assert.deepEqual(reorderSpaces(spaces, ['kids']).map((space) => space.id), ['kids', 'downloads', 'music', 'videos', 'lofi', 'gym'])
  assert.throws(() => reorderSpaces(spaces, ['nope']), /out of date/)
  assert.throws(() => reorderSpaces(spaces, ['kids', 'kids']), /out of date/)
  // smart spaces are not download targets: not in Save to, and routes skip them
  assert.deepEqual(folderSpaces(spaces).map((space) => space.id), ['downloads', 'music', 'videos', 'kids', 'gym'])
  const library = {...defaultLibrarySettings({downloads: 'C:/d', music: 'C:/m', videos: 'C:/v'}), spaces, routes: {music: 'lofi', other: 'downloads'}}
  assert.equal(defaultSpaceFor('https://open.spotify.com/track/abcdefghij', library), 'downloads', 'a route to a smart space falls back to a folder space')
  assert.equal(spaceColorOf({id: 'music'}), 'cobalt')
  assert.equal(spaceColorOf({id: 'x', color: 'berry'}), 'berry')
  assert.equal(spaceColorOf({id: 'x'}, 1), 'olive')
})
