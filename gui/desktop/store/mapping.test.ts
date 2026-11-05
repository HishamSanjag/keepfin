import {test} from 'node:test'
import assert from 'node:assert/strict'
import os from 'node:os'
import path from 'node:path'
import type {Collection, CollectionFollow, LibraryItemKind, Space} from '../../shared/library.js'
import {SyncLog, hlcTick, makeOp, type SyncViews} from '../../shared/core/sync.js'
import type {LibraryEntry} from '../library/index.js'
import {DEVICE_ITEM_KEYS, type DeviceData} from './device.js'
import {BUILTIN_SPACE_IDS, deviceCollectionOf, diff, emptySnapshot, isUuid, project, snapshot, spaceRels, spaceRoots, spaceWireId, type Model, type OpDraft} from './mapping.js'

// ---------------------------------------------------------------------------------------------------------------------
// A tiny deterministic writer: the mapping's output goes straight into a SyncLog.
// ---------------------------------------------------------------------------------------------------------------------

function prng(seed: number) {
  let a = seed >>> 0
  return () => {
    a = (a + 0x6d2b79f5) >>> 0
    let t = a
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}
type Rng = () => number
const pick = <T,>(rng: Rng, list: readonly T[]): T => list[Math.floor(rng() * list.length)]
const chance = (rng: Rng, p: number) => rng() < p
const between = (rng: Rng, lo: number, hi: number) => lo + Math.floor(rng() * (hi - lo + 1))

function uuid(rng: Rng): string {
  const hex = Array.from({length: 32}, () => Math.floor(rng() * 16).toString(16)).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-4${hex.slice(13, 16)}-8${hex.slice(17, 20)}-${hex.slice(20)}`
}

const ACTOR = '11111111-1111-4111-8111-111111111111'
const REPLICA = '22222222-2222-4222-8222-222222222222'

class Mini {
  readonly log = SyncLog.create()
  private seq = 0
  private hlc: string | null = null
  private clock = 1_700_000_000_000
  ops = 0
  constructor(readonly newId: () => string) {}
  apply(drafts: OpDraft[]) {
    for (const draft of drafts) {
      this.seq++
      this.hlc = hlcTick(this.hlc, this.clock++, ACTOR)
      const text = makeOp(ACTOR, this.seq, this.hlc, draft.kind, draft.body as never)
      const result = this.log.admit(text)
      assert.equal(result.kind, 'admitted', `${draft.kind} ${JSON.stringify(draft.body)} -> ${JSON.stringify(result)}`)
      this.ops++
    }
  }
  views(): SyncViews {
    return this.log.views()
  }
  ctx(root: string) {
    return {
      root,
      actor: ACTOR,
      replicaId: REPLICA,
      newId: this.newId,
      ownPlays: (id: string) => {
        const own = this.views().items.find((item) => item.id === id)?.fields[`plays@${ACTOR}`]
        return typeof own === 'number' ? own : 0
      },
      songs: (id: string) => this.log.viewsFor({all: false, items: [], playlists: [id], spaces: [], sets: [], places: [], profile: false, findings: false}).playlists[0]?.songs ?? [],
      observed: {
        item: (id: string) => this.log.observed({items: [id]}).items[id] ?? {tags: [], heads: []},
        playlist: (id: string) => this.log.observed({playlists: [id]}).playlists[id] ?? [],
        space: (id: string) => this.log.observed({spaces: [id]}).spaces[id] ?? [],
        member: (set: string, element: string) => this.log.observed({setElements: [[set, element]]}).setElements[set]?.[element] ?? [],
      },
    }
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// Random models
// ---------------------------------------------------------------------------------------------------------------------

const ROOT = path.join(os.tmpdir(), 'keepfin-mapping-root')
const OUTSIDE = path.join(os.tmpdir(), 'keepfin-mapping-outside', 'Podcasts')
const WORDS = ['Alpha', 'Beyoncé', '日本語', 'Ünïcode', 'plain', 'a "quote"', 'back\\slash', 'emoji 🎵', 'tab\there', 'Long title '.repeat(5)]

function spacesFor(): Space[] {
  const base = {skipExisting: false, type: 'folder' as const}
  return [
    {...base, id: 'downloads', name: 'Downloads', kind: 'mixed', folder: path.join(ROOT, 'Downloads'), layout: 'platform', naming: 'title', color: 'slate', icon: 'folder', pinned: true},
    {...base, id: 'music', name: 'Music', kind: 'music', folder: path.join(ROOT, 'Music'), layout: 'collection', naming: 'title', skipExisting: true, color: 'cobalt', icon: 'music', pinned: true},
    {...base, id: 'videos', name: 'Videos', kind: 'video', folder: path.join(ROOT, 'Videos'), layout: 'collection', naming: 'title', scanFolder: true},
    {...base, id: 'space-1a2b3c4d', name: 'Podcasts', kind: 'mixed', folder: OUTSIDE, layout: 'flat', naming: 'artist-title', ignore: ['skip/me']},
    {id: 'space-feedbeef', name: 'Loved', kind: 'mixed', folder: '', layout: 'flat', naming: 'title', skipExisting: false, type: 'smart', rules: {match: 'any', rules: [{field: 'favorite'}, {field: 'addedWithin', days: 7}]}},
  ]
}

function folderOf(space: Space | undefined, file: string) {
  if (!space) return ''
  const relative = path.relative(space.folder, path.dirname(file))
  return relative.startsWith('..') || path.isAbsolute(relative) ? '' : relative.split(path.sep).join('/')
}

function genEntry(rng: Rng, spaces: Space[]): LibraryEntry {
  const folderSpaces = spaces.filter((space) => space.type === 'folder')
  const space = pick(rng, folderSpaces)
  const sub = chance(rng, 0.5) ? [pick(rng, ['Album', 'Rock', 'a b', 'Ünï'])] : []
  const file = path.join(space.folder, ...sub, `${pick(rng, WORDS).replace(/[\\/:*?"<>|\t]/g, '_')}-${between(rng, 0, 99999)}.${pick(rng, ['mp3', 'mp4', 'flac'])}`)
  const entry: Record<string, unknown> = {
    id: uuid(rng),
    space: space.id,
    path: file,
    folder: folderOf(space, file),
    kind: pick(rng, ['audio', 'video', 'image', 'other'] as LibraryItemKind[]),
    title: pick(rng, WORDS),
    size: 0,
    modifiedAt: 0,
    addedAt: 1_700_000_000_000 + between(rng, 0, 1e9),
    hasArt: false,
    managed: true,
  }
  for (const key of ['artist', 'album', 'albumArtist', 'genre', 'sourceUrl', 'sourceId'] as const) if (chance(rng, 0.4)) entry[key] = pick(rng, WORDS)
  for (const key of ['track', 'disc', 'year', 'width', 'height', 'lastPlayedAt'] as const) if (chance(rng, 0.4)) entry[key] = between(rng, 0, 4000)
  for (const key of ['duration', 'position'] as const) if (chance(rng, 0.5)) entry[key] = between(rng, 0, 600_000) / 1000
  if (chance(rng, 0.3)) entry.gain = -between(rng, 0, 2000) / 100
  if (chance(rng, 0.3)) entry.chapters = [{start: 0, title: 'Intro'}, {start: between(rng, 1, 5000) / 10, title: pick(rng, WORDS)}]
  if (chance(rng, 0.3)) entry.subtitles = ['English', pick(rng, WORDS)]
  if (chance(rng, 0.3)) entry.hasLyrics = true
  if (chance(rng, 0.3)) entry.watched = chance(rng, 0.5)
  if (chance(rng, 0.4)) entry.tags = [...new Set([pick(rng, WORDS), pick(rng, WORDS)])]
  if (chance(rng, 0.4)) entry.userTags = [...new Set([pick(rng, ['rock', 'chill', 'x y', 'ünï']), pick(rng, ['rock', 'chill', 'gym'])])].sort()
  if (chance(rng, 0.3)) entry.favorite = true
  if (chance(rng, 0.3)) entry.plays = between(rng, 1, 50)
  return entry as LibraryEntry
}

function genCollection(rng: Rng, items: LibraryEntry[], spaces: Space[]): Collection {
  const own = chance(rng, 0.5)
  const kind = own ? 'own' : pick(rng, ['playlist', 'album', 'channel'] as const)
  const ids = items.map((item) => item.id).filter(() => chance(rng, 0.5))
  for (let i = ids.length - 1; i > 0; i--) {
    const j = Math.floor(rng() * (i + 1))
    ;[ids[i], ids[j]] = [ids[j], ids[i]]
  }
  const collection: Collection = {
    id: uuid(rng),
    name: pick(rng, WORDS),
    kind,
    space: pick(rng, spaces.filter((space) => space.type === 'folder')).id,
    itemIds: ids,
    seen: [...new Set(Array.from({length: between(rng, 0, 4)}, () => `youtube:${between(rng, 0, 30)}`))].sort(),
    createdAt: 1_700_000_000_000 + between(rng, 0, 1e6),
    updatedAt: 1_700_000_000_000 + between(rng, 0, 1e6),
  }
  if (chance(rng, 0.3) && ids.length) collection.coverItemId = ids[0]
  if (!own) {
    collection.source = {url: `https://example.com/list/${between(rng, 0, 99)}`, provider: 'Example', ...(chance(rng, 0.5) ? {id: 'abc'} : {})}
    const follow: CollectionFollow = {mode: pick(rng, ['auto', 'manual', 'off'] as const), intervalHours: pick(rng, [1, 6, 12, 24, 168] as const), wifiOnly: chance(rng, 0.5), notify: chance(rng, 0.5), confirmAbove: between(rng, 1, 500)}
    if (chance(rng, 0.3)) follow.pending = {count: between(rng, 1, 90), at: 1_700_000_000_000}
    if (chance(rng, 0.5)) follow.lastCheckedAt = 1_700_000_000_000 + between(rng, 0, 1e6)
    if (chance(rng, 0.3)) follow.lastError = 'Could not reach the site.'
    collection.follow = follow
    const news = ids.filter(() => chance(rng, 0.3)).sort()
    if (news.length) collection.newItemIds = news
    const removed = ids.filter(() => chance(rng, 0.2)).sort()
    if (removed.length) collection.removedItemIds = removed
  }
  return collection
}

function genModel(rng: Rng): Model {
  const spaces = spacesFor()
  const items = Array.from({length: between(rng, 0, 40)}, () => genEntry(rng, spaces))
  const collections = Array.from({length: between(rng, 0, 6)}, () => genCollection(rng, items, spaces))
  return {items, collections, spaces, routes: {music: 'music', other: pick(rng, ['downloads', 'space-1a2b3c4d'])}}
}

function deviceFor(model: Model): DeviceData {
  const linked: Record<string, string> = {}
  for (const space of spaceRoots(model.spaces, ROOT)) if (space.linked) linked[space.id] = space.abs
  const collections: DeviceData['collections'] = {}
  for (const collection of model.collections) {
    const local = deviceCollectionOf(collection)
    if (local) collections[collection.id] = local
  }
  return {replicaId: REPLICA, root: ROOT, linked, items: {}, collections, art: new Set()}
}

const sorted = <T extends {id: string}>(list: T[]) => [...list].sort((a, b) => (a.id < b.id ? -1 : 1))

function assertSame(actual: Model, expected: Model, label: string) {
  const strip = (entry: LibraryEntry) => {
    const copy: Record<string, unknown> = {...entry}
    for (const key of DEVICE_ITEM_KEYS) delete copy[key]
    return copy
  }
  assert.deepStrictEqual(sorted(actual.items).map(strip), sorted(expected.items).map(strip), `${label}: items`)
  assert.deepStrictEqual(sorted(actual.collections), sorted(expected.collections), `${label}: collections`)
  assert.deepStrictEqual(actual.spaces, expected.spaces, `${label}: spaces`)
  assert.deepStrictEqual(actual.routes, expected.routes, `${label}: routes`)
}

const empty: Model = {items: [], collections: [], spaces: [], routes: {music: 'music', other: 'downloads'}}

// ---------------------------------------------------------------------------------------------------------------------
// Edits
// ---------------------------------------------------------------------------------------------------------------------

function edit(rng: Rng, model: Model): Model {
  const next: Model = structuredClone(model)
  const touch = (collection: Collection) => (collection.updatedAt += 1)
  const choice = between(rng, 0, 15)
  const item = next.items.length ? pick(rng, next.items) : undefined
  const collection = next.collections.length ? pick(rng, next.collections) : undefined
  switch (choice) {
    case 0:
      if (item) item.title = pick(rng, WORDS)
      break
    case 1:
      if (item) {
        if (item.artist) delete item.artist
        else item.artist = pick(rng, WORDS)
      }
      break
    case 2:
      if (item) {
        item.duration = between(rng, 0, 600_000) / 1000
        item.position = between(rng, 0, 100_000) / 1000
      }
      break
    case 3:
      if (item) {
        if (item.favorite) delete item.favorite
        else item.favorite = true
      }
      break
    case 4:
      if (item) {
        const tag = pick(rng, ['rock', 'chill', 'gym', 'x y'])
        const current = new Set(item.userTags ?? [])
        if (current.has(tag)) current.delete(tag)
        else current.add(tag)
        if (current.size) item.userTags = [...current].sort()
        else delete item.userTags
      }
      break
    case 5:
      if (item) item.plays = (item.plays ?? 0) + between(rng, 1, 3)
      break
    case 6:
      if (item) {
        next.items = next.items.filter((other) => other !== item)
        for (const entry of next.collections) {
          entry.itemIds = entry.itemIds.filter((id) => id !== item.id)
          if (entry.newItemIds) entry.newItemIds = entry.newItemIds.filter((id) => id !== item.id)
          if (entry.removedItemIds) entry.removedItemIds = entry.removedItemIds.filter((id) => id !== item.id)
          if (!entry.newItemIds?.length) delete entry.newItemIds
          if (!entry.removedItemIds?.length) delete entry.removedItemIds
          if (entry.coverItemId === item.id) delete entry.coverItemId
        }
      }
      break
    case 7:
      next.items.push(genEntry(rng, next.spaces))
      break
    case 8:
      next.collections.push(genCollection(rng, next.items, next.spaces))
      break
    case 9:
      if (collection) {
        next.collections = next.collections.filter((other) => other !== collection)
      }
      break
    case 10:
      if (collection) {
        collection.name = pick(rng, WORDS)
        touch(collection)
      }
      break
    case 11:
      if (collection) {
        for (let i = collection.itemIds.length - 1; i > 0; i--) {
          const j = Math.floor(rng() * (i + 1))
          ;[collection.itemIds[i], collection.itemIds[j]] = [collection.itemIds[j], collection.itemIds[i]]
        }
        touch(collection)
      }
      break
    case 12:
      if (collection) {
        const free = next.items.filter((candidate) => !collection.itemIds.includes(candidate.id))
        if (free.length) {
          const at = between(rng, 0, collection.itemIds.length)
          collection.itemIds.splice(at, 0, pick(rng, free).id)
          touch(collection)
        }
      }
      break
    case 13:
      if (collection && collection.itemIds.length) {
        collection.itemIds.splice(between(rng, 0, collection.itemIds.length - 1), 1)
        touch(collection)
      }
      break
    case 14:
      if (collection) {
        collection.seen = [...new Set([...collection.seen, `youtube:${between(rng, 0, 60)}`])].sort()
        if (collection.kind !== 'own' && collection.itemIds.length) {
          collection.newItemIds = [pick(rng, collection.itemIds)]
          delete collection.removedItemIds
        }
      }
      break
    case 15: {
      const space = next.spaces.find((candidate) => candidate.id === 'music')
      if (space) space.naming = pick(rng, ['title', 'artist-title'] as const)
      next.routes = {...next.routes, other: pick(rng, ['downloads', 'videos'])}
      break
    }
  }
  return next
}

// ---------------------------------------------------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------------------------------------------------

test('ids: built-in spaces have fixed UUIDs, other ids are derived and stable', () => {
  assert.equal(spaceWireId('music'), BUILTIN_SPACE_IDS.music)
  // Favorites is not a fixed built-in id: its wire id is derived from the name, the same value the phone derives (core KeptSpacesTest)
  assert.ok(!('favorites' in BUILTIN_SPACE_IDS))
  assert.equal(spaceWireId('favorites'), 'b11c64ea-bcff-5c05-80b4-ab6ed166cc38')
  assert.ok(isUuid(spaceWireId('space-1a2b3c4d')))
  assert.equal(spaceWireId('space-1a2b3c4d'), spaceWireId('space-1a2b3c4d'))
  assert.notEqual(spaceWireId('space-1a2b3c4d'), spaceWireId('space-1a2b3c4e'))
  assert.equal(spaceWireId(REPLICA), REPLICA)
})

test('round trip: diff(empty, M) then project gives M back for 200 random models', () => {
  let ops = 0
  for (let seed = 1; seed <= 200; seed++) {
    const rng = prng(seed)
    const model = genModel(rng)
    const mini = new Mini(() => uuid(rng))
    const drafts = diff(empty, model, mini.ctx(ROOT))
    mini.apply(drafts)
    ops += drafts.length
    const views = mini.views()
    assert.deepStrictEqual(views.findings, [], `seed ${seed}`)
    assertSame(project(views, deviceFor(model)), model, `seed ${seed}`)
    // Nothing changed: nothing to write.
    assert.deepStrictEqual(diff(model, model, mini.ctx(ROOT)), [], `seed ${seed}: no-op`)
  }
  assert.ok(ops > 2000)
})

test('random edit sequences: after every step the projection equals the edited model, and unchanged data writes nothing', () => {
  for (let seed = 1; seed <= 40; seed++) {
    const rng = prng(1000 + seed)
    let model = genModel(rng)
    const mini = new Mini(() => uuid(rng))
    mini.apply(diff(empty, model, mini.ctx(ROOT)))
    for (let step = 0; step < 40; step++) {
      const next = edit(rng, model)
      const drafts = diff(model, next, mini.ctx(ROOT))
      mini.apply(drafts)
      const label = `seed ${seed} step ${step}`
      assertSame(project(mini.views(), deviceFor(next)), next, label)
      assert.deepStrictEqual(mini.views().findings, [], label)
      model = next
    }
  }
})

test('a single field edit is exactly one operation', () => {
  const rng = prng(7)
  const model = genModel(rng)
  while (model.items.length < 3) model.items.push(genEntry(rng, model.spaces))
  const mini = new Mini(() => uuid(rng))
  mini.apply(diff(empty, model, mini.ctx(ROOT)))
  const next = structuredClone(model)
  next.items[1].title = 'Something new'
  const drafts = diff(model, next, mini.ctx(ROOT))
  assert.equal(drafts.length, 1)
  assert.deepStrictEqual(drafts[0], {kind: 'item.set', body: {item: model.items[1].id, field: 'title', value: 'Something new'}})
  // Clearing a field writes null, and the clear round-trips.
  const cleared = structuredClone(next)
  cleared.items[1].artist = 'x'
  mini.apply(diff(next, cleared, mini.ctx(ROOT)))
  const removed = structuredClone(cleared)
  delete removed.items[1].artist
  const clear = diff(cleared, removed, mini.ctx(ROOT))
  assert.deepStrictEqual(clear, [{kind: 'item.set', body: {item: model.items[1].id, field: 'artist', value: null}}])
})

test('plays are written as this actor’s own counter plus the increase', () => {
  const rng = prng(9)
  const model = genModel(rng)
  while (!model.items.length) model.items.push(genEntry(rng, model.spaces))
  model.items[0].plays = 4
  const mini = new Mini(() => uuid(rng))
  mini.apply(diff(empty, model, mini.ctx(ROOT)))
  const next = structuredClone(model)
  next.items[0].plays = 7
  const drafts = diff(model, next, mini.ctx(ROOT))
  assert.deepStrictEqual(drafts, [{kind: 'item.set', body: {item: model.items[0].id, field: `plays@${ACTOR}`, value: 7}}])
  mini.apply(drafts)
  assert.equal(mini.views().items.find((item) => item.id === model.items[0].id)?.plays, 7)
})

/** Longest strictly increasing subsequence length, the slow way. */
function lisLength(values: number[]): number {
  const best = values.map(() => 1)
  for (let i = 0; i < values.length; i++) for (let j = 0; j < i; j++) if (values[j] < values[i]) best[i] = Math.max(best[i], best[j] + 1)
  return Math.max(0, ...best)
}

function withPlaylist(rng: Rng, count: number) {
  const spaces = spacesFor()
  const items = Array.from({length: count}, () => genEntry(rng, spaces))
  const collection: Collection = {id: uuid(rng), name: 'List', kind: 'own', space: 'music', itemIds: items.map((item) => item.id), seen: [], createdAt: 1, updatedAt: 1}
  const model: Model = {items, collections: [collection], spaces, routes: {music: 'music', other: 'downloads'}}
  const mini = new Mini(() => uuid(rng))
  mini.apply(diff(empty, model, mini.ctx(ROOT)))
  return {model, mini, collection}
}

const entryOps = (drafts: OpDraft[]) => drafts.filter((draft) => draft.kind.startsWith('entry.'))
const kindCount = (drafts: OpDraft[], kind: string) => drafts.filter((draft) => draft.kind === kind).length

test('reorders write the minimum: moving one item is one move, insert one, remove one', () => {
  const rng = prng(11)
  const {model, mini} = withPlaylist(rng, 12)
  const ids = model.collections[0].itemIds
  const moveOne = (from: number, to: number) => {
    const next = structuredClone(model)
    const list = next.collections[0].itemIds
    const [item] = list.splice(from, 1)
    list.splice(to, 0, item)
    return next
  }
  for (const [from, to] of [[0, 11], [11, 0], [5, 6], [3, 9], [8, 2]]) {
    const next = moveOne(from, to)
    const drafts = diff(model, next, mini.ctx(ROOT))
    assert.equal(entryOps(drafts).length, 1, `${from}->${to}`)
    assert.equal(drafts.find((draft) => draft.kind === 'entry.move') !== undefined || from === to, true)
  }
  // Apply one and check the projection, then insert and remove.
  const moved = moveOne(0, 11)
  mini.apply(diff(model, moved, mini.ctx(ROOT)))
  assert.deepStrictEqual(project(mini.views(), deviceFor(moved)).collections[0].itemIds, moved.collections[0].itemIds)

  const extra = genEntry(rng, model.spaces)
  const inserted = structuredClone(moved)
  inserted.items.push(extra)
  inserted.collections[0].itemIds.splice(4, 0, extra.id)
  const insertDrafts = diff(moved, inserted, mini.ctx(ROOT))
  assert.equal(kindCount(insertDrafts, 'entry.insert'), 1)
  assert.equal(entryOps(insertDrafts).length, 1)
  mini.apply(insertDrafts)
  assertSame(project(mini.views(), deviceFor(inserted)), inserted, 'insert')

  const removed = structuredClone(inserted)
  removed.collections[0].itemIds.splice(2, 1)
  const removeDrafts = diff(inserted, removed, mini.ctx(ROOT))
  assert.deepStrictEqual(entryOps(removeDrafts).map((draft) => draft.kind), ['entry.remove'])
  mini.apply(removeDrafts)
  assertSame(project(mini.views(), deviceFor(removed)), removed, 'remove')
  assert.equal(ids.length, 12)
})

test('random reorders use exactly (n - longest increasing subsequence) moves', () => {
  for (let seed = 0; seed < 25; seed++) {
    const rng = prng(500 + seed)
    const {model, mini} = withPlaylist(rng, between(rng, 2, 25))
    const next = structuredClone(model)
    const list = next.collections[0].itemIds
    for (let i = list.length - 1; i > 0; i--) {
      const j = Math.floor(rng() * (i + 1))
      ;[list[i], list[j]] = [list[j], list[i]]
    }
    const was = model.collections[0].itemIds
    const expected = list.length - lisLength(was.map((id) => list.indexOf(id)))
    const drafts = diff(model, next, mini.ctx(ROOT))
    assert.equal(kindCount(drafts, 'entry.move'), expected, `seed ${seed}`)
    assert.equal(entryOps(drafts).length, expected)
    mini.apply(drafts)
    assertSame(project(mini.views(), deviceFor(next)), next, `seed ${seed}`)
  }
})

test('a playlist can be reordered again and again without keys growing out of hand', () => {
  const rng = prng(31)
  let {model, mini} = withPlaylist(rng, 20)
  for (let round = 0; round < 60; round++) {
    const next = structuredClone(model)
    const list = next.collections[0].itemIds
    const [item] = list.splice(between(rng, 0, list.length - 1), 1)
    list.splice(between(rng, 0, list.length), 0, item)
    mini.apply(diff(model, next, mini.ctx(ROOT)))
    model = next
  }
  assertSame(project(mini.views(), deviceFor(model)), model, 'after 60 moves')
  const songs = mini.views().playlists[0].songs
  assert.ok(Math.max(...songs.map((song) => song.key.length)) < 40)
})

test('deletes carry the observed tags and heads, and everything deleted disappears from the projection', () => {
  const rng = prng(21)
  const model = genModel(rng)
  while (model.items.length < 4) model.items.push(genEntry(rng, model.spaces))
  model.items[0].favorite = true
  model.items[0].userTags = ['rock', 'chill']
  model.collections.push(genCollection(rng, model.items, model.spaces))
  const mini = new Mini(() => uuid(rng))
  mini.apply(diff(empty, model, mini.ctx(ROOT)))
  const next = structuredClone(model)
  const gone = next.items[0].id
  next.items.splice(0, 1)
  next.collections.forEach((collection) => (collection.itemIds = collection.itemIds.filter((id) => id !== gone)))
  const removedCollection = next.collections.pop() as Collection
  next.spaces = next.spaces.filter((space) => space.id !== 'space-1a2b3c4d')
  next.items = next.items.filter((item) => item.space !== 'space-1a2b3c4d')
  next.collections.forEach((collection) => (collection.itemIds = collection.itemIds.filter((id) => next.items.some((item) => item.id === id))))
  const drafts = diff(model, next, mini.ctx(ROOT))
  const del = drafts.find((draft) => draft.kind === 'item.delete' && draft.body.item === gone)
  assert.ok(del)
  assert.equal((del!.body.observedTags as string[]).length, 1)
  assert.ok(drafts.some((draft) => draft.kind === 'playlist.delete' && draft.body.playlist === removedCollection.id))
  assert.ok(drafts.some((draft) => draft.kind === 'space.delete'))
  mini.apply(drafts)
  const projected = project(mini.views(), deviceFor(next))
  assert.deepStrictEqual(sorted(projected.items).map((item) => item.id), sorted(next.items).map((item) => item.id))
  assert.deepStrictEqual(projected.spaces.map((space) => space.id), next.spaces.map((space) => space.id))
  assert.ok(!projected.collections.some((collection) => collection.id === removedCollection.id))
})

test('items outside the Keepfin folder and every linked space are unplaced but still sync', () => {
  const rng = prng(5)
  const model = genModel(rng)
  const stray = genEntry(rng, model.spaces)
  stray.path = path.join(os.tmpdir(), 'somewhere-else', 'a.mp3')
  stray.folder = ''
  model.items = [stray]
  model.collections = []
  const mini = new Mini(() => uuid(rng))
  const drafts = diff(empty, model, mini.ctx(ROOT))
  assert.ok(!drafts.some((draft) => draft.kind === 'item.place'))
  mini.apply(drafts)
  const projected = project(mini.views(), deviceFor(model))
  assert.equal(projected.items.length, 1)
  assert.equal(projected.items[0].missing, true)
  assert.ok(projected.items[0].path.endsWith(path.join('.keepfin', 'unplaced', stray.id)))
})

test('linked spaces keep their virtual folder name and placements resolve through linked.json', () => {
  const rng = prng(6)
  const model = genModel(rng)
  const linkedSpace = model.spaces.find((space) => space.id === 'space-1a2b3c4d') as Space
  const item = genEntry(rng, model.spaces)
  item.space = linkedSpace.id
  item.path = path.join(OUTSIDE, 'Shows', 'ep1.mp3')
  item.folder = 'Shows'
  model.items = [item]
  model.collections = []
  const mini = new Mini(() => uuid(rng))
  mini.apply(diff(empty, model, mini.ctx(ROOT)))
  const views = mini.views()
  assert.equal(views.placements[item.id][REPLICA], 'Podcasts/Shows/ep1.mp3')
  assert.equal(views.spaces.find((space) => space.fields.legacyId === 'space-1a2b3c4d')?.fields.linked, true)
  assert.equal(spaceRels(views).get('space-1a2b3c4d'), 'Podcasts')
  // Another device resolves the same placement through its own linked.json.
  const other = deviceFor(model)
  other.linked['space-1a2b3c4d'] = path.join(os.tmpdir(), 'other-device', 'Pods')
  const projected = project(views, other)
  assert.equal(projected.items[0].path, path.join(os.tmpdir(), 'other-device', 'Pods', 'Shows', 'ep1.mp3'))
  assert.equal(projected.items[0].folder, 'Shows')
  // A device that does not know the linked folder shows the item as missing.
  const unknown = deviceFor(model)
  delete unknown.linked['space-1a2b3c4d']
  assert.equal(project(views, unknown).items[0].missing, true)
})

test('unmanaged entries and entries with non-UUID ids are not synced', () => {
  const rng = prng(8)
  const model = genModel(rng)
  model.items = [genEntry(rng, model.spaces), {...genEntry(rng, model.spaces), managed: false}, {...genEntry(rng, model.spaces), id: 'abcdef0123456789abcd'}]
  model.collections = []
  const snap = snapshot(model, {root: ROOT})
  assert.equal(snap.items.size, 1)
  assert.deepStrictEqual(snap.skipped, ['abcdef0123456789abcd'])
  assert.equal(emptySnapshot().items.size, 0)
})

test('extra fields written by a newer app are ignored on projection', () => {
  const rng = prng(12)
  const model = genModel(rng)
  while (!model.items.length) model.items.push(genEntry(rng, model.spaces))
  const mini = new Mini(() => uuid(rng))
  mini.apply(diff(empty, model, mini.ctx(ROOT)))
  mini.apply([{kind: 'item.set', body: {item: model.items[0].id, field: 'futureField', value: {a: 1}}}])
  assertSame(project(mini.views(), deviceFor(model)), model, 'future field')
})
