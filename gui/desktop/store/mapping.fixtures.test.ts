// Proves the library mapping of the desktop (mapping.ts) and the shared core (app.keepfin.core.library, used by Android)
// are the same: fixtures/library-mapping/*.json hold neutral inputs and the expected operations and models, and both
// implementations must produce exactly them. The core runs the same files on JVM and JS (LibraryMappingTest).
//
// The files are generated from mapping.ts: `KEEPFIN_WRITE_FIXTURES=1 npx tsx --test gui/desktop/store/mapping.fixtures.test.ts`
// rewrites them (review the diff; a change in expected output is a change of the wire format). Otherwise the test only reads them.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import type {Collection, Space} from '../../shared/library.js'
import {SyncLog, hlcTick, makeOp, type SyncJson, type SyncViews} from '../../shared/core/sync.js'
import type {LibraryEntry} from '../library/index.js'
import {placementOf, resolvePlacement, relativeBelow, type DeviceData} from './device.js'
import {diff, diffSnaps, emptySnapshot, project, snapshot, spaceRels, spaceRoots, type Model, type ObservedLookup, type OpDraft} from './mapping.js'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const FIXTURES = path.resolve(HERE, '../../../fixtures/library-mapping')
const ROOT = path.join(os.tmpdir(), 'keepfin-mapping-fixture-root')
const OUTSIDE = `${ROOT}-linked`
const ACTOR = '11111111-1111-4111-8111-111111111111'
const REPLICA = '22222222-2222-4222-8222-222222222222'

type Rec = Record<string, unknown>
type Neutral = {items: Rec[]; collections: Rec[]; spaces: Rec[]; routes: {music: string; other: string}}

// ---------------------------------------------------------------------------------------------------------------------
// Neutral model <-> mapping.ts model
// ---------------------------------------------------------------------------------------------------------------------

const OPTIONAL = ['artist', 'album', 'albumArtist', 'track', 'disc', 'year', 'genre', 'duration', 'width', 'height', 'chapters', 'gain', 'subtitles', 'hasLyrics', 'sourceUrl', 'sourceId', 'tags', 'lastPlayedAt', 'position', 'watched'] as const

function toSpace(n: Rec): Space {
  const smart = n.type === 'smart'
  const rel = String(n.folder ?? '')
  const folder = smart ? '' : n.linked ? path.join(OUTSIDE, ...rel.split('/')) : rel ? path.join(ROOT, ...rel.split('/')) : ROOT
  const space: Rec = {id: n.id, name: n.name ?? n.id, kind: n.kind ?? 'mixed', folder, layout: n.layout ?? 'flat', naming: n.naming ?? 'title', skipExisting: n.skipExisting === true, type: n.type ?? 'folder'}
  for (const key of ['ignore', 'scanFolder', 'color', 'icon', 'pinned', 'rules']) if (n[key] !== undefined) space[key] = n[key]
  return space as unknown as Space
}

function relsOf(...models: (Neutral | null)[]): Map<string, string> {
  const out = new Map<string, string>()
  for (const model of models) for (const s of model?.spaces ?? []) if (s.linked === true && s.type !== 'smart') out.set(String(s.id), String(s.folder))
  return out
}

function toModel(n: Neutral): Model {
  const spaces = n.spaces.map(toSpace)
  const roots = spaceRoots(spaces, ROOT, relsOf(n))
  const items = n.items.map((i) => {
    const abs = typeof i.place === 'string' ? resolvePlacement(i.place, ROOT, roots) : undefined
    const entry: Rec = {id: i.id, space: i.space, path: abs ?? path.join(OUTSIDE, 'nowhere', String(i.id)), folder: '', kind: i.kind, title: i.title, size: 0, modifiedAt: 0, hasArt: false, managed: true}
    if (i.addedAt !== undefined) entry.addedAt = i.addedAt
    for (const key of OPTIONAL) if (i[key] !== undefined) entry[key] = i[key]
    if (i.favorite === true) entry.favorite = true
    if (Array.isArray(i.userTags) && i.userTags.length) entry.userTags = i.userTags
    if (typeof i.plays === 'number' && i.plays) entry.plays = i.plays
    return entry as unknown as LibraryEntry
  })
  const collections = n.collections.map((c) => {
    const out: Rec = {id: c.id, name: c.name, kind: c.kind, space: c.space, itemIds: c.itemIds ?? [], seen: c.seen ?? []}
    for (const key of ['createdAt', 'updatedAt', 'coverItemId', 'source']) if (c[key] !== undefined) out[key] = c[key]
    const f = c.follow as Rec | undefined
    if (f) {
      out.follow = {mode: f.mode, intervalHours: f.intervalHours, wifiOnly: f.wifiOnly, notify: f.notify, confirmAbove: f.confirmAbove, ...(f.pendingCount !== undefined ? {pending: {count: f.pendingCount, at: f.pendingAt}} : {})}
    }
    if (Array.isArray(c.newItemIds) && c.newItemIds.length) out.newItemIds = c.newItemIds
    if (Array.isArray(c.removedItemIds) && c.removedItemIds.length) out.removedItemIds = c.removedItemIds
    return out as unknown as Collection
  })
  return {items, collections, spaces, routes: n.routes}
}

function fromModel(model: Model, views: SyncViews, device: DeviceData): Neutral {
  const rels = spaceRels(views)
  const roots = spaceRoots(model.spaces, device.root, rels)
  const items = model.items.map((e) => {
    const out: Rec = {id: e.id, space: e.space, kind: e.kind, title: e.title}
    for (const key of OPTIONAL) if ((e as unknown as Rec)[key] !== undefined) out[key] = (e as unknown as Rec)[key]
    out.addedAt = e.addedAt
    out.favorite = e.favorite === true
    out.userTags = e.userTags ?? []
    out.plays = e.plays ?? 0
    out.place = e.missing ? null : placementOf(e.path, device.root, roots)
    return out
  })
  const collections = model.collections.map((c) => {
    const out: Rec = {id: c.id, name: c.name, kind: c.kind, space: c.space, itemIds: c.itemIds, seen: c.seen, createdAt: c.createdAt, updatedAt: c.updatedAt}
    if (c.coverItemId !== undefined) out.coverItemId = c.coverItemId
    if (c.source) out.source = c.source
    if (c.follow) {
      const f = c.follow
      out.follow = {mode: f.mode, intervalHours: f.intervalHours, wifiOnly: f.wifiOnly, notify: f.notify, confirmAbove: f.confirmAbove, ...(f.pending ? {pendingCount: f.pending.count, pendingAt: f.pending.at} : {})}
    }
    out.newItemIds = c.newItemIds ?? []
    out.removedItemIds = c.removedItemIds ?? []
    return out
  })
  const spaces = model.spaces.map((s) => {
    const linked = rels.has(s.id) && s.type !== 'smart'
    const out: Rec = {id: s.id, name: s.name, kind: s.kind, folder: s.type === 'smart' ? '' : linked ? rels.get(s.id) : (relativeBelow(device.root, s.folder) ?? ''), linked, layout: s.layout, naming: s.naming, skipExisting: s.skipExisting, type: s.type}
    for (const key of ['ignore', 'scanFolder', 'color', 'icon', 'pinned', 'rules'] as const) if (s[key] !== undefined) out[key] = s[key]
    return out
  })
  return {items, collections, spaces, routes: model.routes}
}

// ---------------------------------------------------------------------------------------------------------------------
// Running a fixture input through mapping.ts
// ---------------------------------------------------------------------------------------------------------------------

type Observed = {items?: Record<string, {tags: string[]; heads: string[]}>; playlists?: Record<string, string[]>; spaces?: Record<string, string[]>; members?: Record<string, Record<string, string[]>>}
type DiffFns = {newId(): string; ownPlays(id: string): number; songs(playlist: string): {entry: string; item: string; key: string}[]; observed: ObservedLookup}

function tableFns(input: Rec): DiffFns {
  const ids = [...((input.ids as string[] | undefined) ?? [])]
  const plays = (input.ownPlays as Record<string, number> | undefined) ?? {}
  const songs = (input.songs as Record<string, {entry: string; item: string; key: string}[]> | undefined) ?? {}
  const obs = (input.observed as Observed | undefined) ?? {}
  return {
    newId: () => {
      const id = ids.shift()
      if (!id) throw new Error('the case needs more ids than it lists')
      return id
    },
    ownPlays: (id) => plays[id] ?? 0,
    songs: (id) => songs[id] ?? [],
    observed: {
      item: (id) => obs.items?.[id] ?? {tags: [], heads: []},
      playlist: (id) => obs.playlists?.[id] ?? [],
      space: (id) => obs.spaces?.[id] ?? [],
      member: (set, element) => obs.members?.[set]?.[element] ?? [],
    },
  }
}

function runDiff(input: Rec, fns: DiffFns = tableFns(input)): OpDraft[] {
  const before = input.before as Neutral | null
  const after = input.after as Neutral
  const rels = relsOf(before, after)
  const ctx = {root: ROOT, rels, actor: input.actor as string, replicaId: input.replicaId as string, ...fns}
  const drafts = before ? diff(toModel(before), toModel(after), ctx) : diffSnaps(emptySnapshot(), snapshot(toModel(after), {root: ROOT, rels}), ctx)
  return JSON.parse(JSON.stringify(drafts)) as OpDraft[]
}

function runProject(input: Rec): Neutral {
  const views = input.views as SyncViews
  const rels = spaceRels(views)
  const linked: Record<string, string> = {}
  for (const [id, rel] of rels) linked[id] = path.join(OUTSIDE, ...rel.split('/'))
  const device: DeviceData = {replicaId: input.replicaId as string, root: ROOT, linked, items: {}, collections: {}, art: new Set()}
  const model = project(views, device)
  return JSON.parse(JSON.stringify(fromModel(model, views, device))) as Neutral
}

// ---------------------------------------------------------------------------------------------------------------------
// Generating the fixtures
// ---------------------------------------------------------------------------------------------------------------------

type Case = {name: string; input: Rec; expected: unknown}
type FixtureFile = {file: string; note: string; cases: Case[]}

const uid = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
const eid = (n: number) => `00000000-0000-4000-9000-${String(n).padStart(12, '0')}`
const clone = <T,>(value: T): T => JSON.parse(JSON.stringify(value)) as T

class Mini {
  readonly log = SyncLog.create()
  private seq = 0
  private hlc: string | null = null
  private clock = 1_700_000_000_000
  apply(drafts: OpDraft[]) {
    for (const draft of drafts) {
      this.seq++
      this.hlc = hlcTick(this.hlc, this.clock++, ACTOR)
      const result = this.log.admit(makeOp(ACTOR, this.seq, this.hlc, draft.kind, draft.body as Record<string, SyncJson>))
      assert.equal(result.kind, 'admitted', `${draft.kind} ${JSON.stringify(draft.body)} -> ${JSON.stringify(result)}`)
    }
  }
  views(): SyncViews {
    return this.log.views()
  }
}

/** Runs a diff against a real log, recording every answer the log gave so the fixture can replay them. */
function recordCase(name: string, mini: Mini, before: Neutral | null, after: Neutral, firstId: number, extra: Rec = {}): Case {
  const rec = {ids: [] as string[], ownPlays: {} as Record<string, number>, songs: {} as Record<string, unknown>, observed: {items: {} as Record<string, unknown>, playlists: {} as Record<string, unknown>, spaces: {} as Record<string, unknown>, members: {} as Record<string, Record<string, unknown>>}}
  let counter = firstId
  const log = mini.log
  const fns: DiffFns = {
    newId: () => {
      const id = eid(counter++)
      rec.ids.push(id)
      return id
    },
    ownPlays: (id) => {
      const own = mini.views().items.find((item) => item.id === id)?.fields[`plays@${ACTOR}`]
      const value = typeof own === 'number' ? own : 0
      rec.ownPlays[id] = value
      return value
    },
    songs: (id) => {
      const songs = log.viewsFor({all: false, items: [], playlists: [id], spaces: [], sets: [], places: [], profile: false, findings: false}).playlists[0]?.songs ?? []
      rec.songs[id] = songs
      return songs
    },
    observed: {
      item: (id) => (rec.observed.items[id] = log.observed({items: [id]}).items[id] ?? {tags: [], heads: []}) as {tags: string[]; heads: string[]},
      playlist: (id) => (rec.observed.playlists[id] = log.observed({playlists: [id]}).playlists[id] ?? []) as string[],
      space: (id) => (rec.observed.spaces[id] = log.observed({spaces: [id]}).spaces[id] ?? []) as string[],
      member: (set, element) => {
        const tags = log.observed({setElements: [[set, element]]}).setElements[set]?.[element] ?? []
        ;(rec.observed.members[set] ??= {})[element] = tags
        return tags
      },
    },
  }
  const input: Rec = {op: 'diff', actor: ACTOR, replicaId: REPLICA, before, after, ...extra}
  const drafts = runDiff(input, fns)
  mini.apply(drafts)
  const recorded: Rec = {}
  if (rec.ids.length) recorded.ids = rec.ids
  if (Object.keys(rec.ownPlays).length) recorded.ownPlays = rec.ownPlays
  if (Object.keys(rec.songs).length) recorded.songs = rec.songs
  const o = rec.observed
  if (Object.keys(o.items).length || Object.keys(o.playlists).length || Object.keys(o.spaces).length || Object.keys(o.members).length) recorded.observed = o
  return {name, input: {...input, ...recorded}, expected: drafts}
}

const SP = {
  downloads: {id: 'downloads', name: 'Downloads', kind: 'mixed', folder: 'Downloads', layout: 'collection', naming: 'title', skipExisting: false, type: 'folder', color: 'slate', icon: 'folder', pinned: true},
  music: {id: 'music', name: 'Music', kind: 'music', folder: 'Music', layout: 'artist-album', naming: 'artist-title', skipExisting: true, type: 'folder', color: 'cobalt', icon: 'music', pinned: true, ignore: ['Podcasts'], scanFolder: true},
  videos: {id: 'videos', name: 'Videos', kind: 'video', folder: 'Videos', layout: 'platform', naming: 'title-uploader', skipExisting: false, type: 'folder', color: 'violet', icon: 'video', pinned: true},
  custom: {id: 'my-space', name: 'My space', kind: 'music', folder: 'My space', layout: 'flat', naming: 'date-title', skipExisting: false, type: 'folder'},
  external: {id: 'ext', name: 'External', kind: 'mixed', folder: 'External', linked: true, layout: 'flat', naming: 'title', skipExisting: false, type: 'folder'},
  smart: {id: 'smart-1', name: 'Rock', kind: 'mixed', folder: '', layout: 'flat', naming: 'title', skipExisting: false, type: 'smart', icon: 'star', rules: {match: 'any', rules: [{field: 'tag', values: ['rock']}], onlyKind: 'audio'}},
}
const ALL_SPACES = [SP.downloads, SP.music, SP.videos, SP.custom, SP.external, SP.smart]
const ROUTES = {music: 'music', other: 'downloads'}
const T0 = 1_700_000_000_000

const song = (n: number, extra: Rec = {}): Rec => ({id: uid(n), space: 'music', kind: 'audio', title: `Song ${n}`, addedAt: T0 + n, favorite: false, userTags: [], plays: 0, place: `Music/Artist/Album/song-${n}.mp3`, ...extra})

function base(): Neutral {
  const i1 = song(1, {
    artist: 'Artist', album: 'Album', albumArtist: 'Various', track: 3, disc: 1, year: 2019, genre: 'Rock', duration: 215.457,
    chapters: [{start: 0, title: 'Intro'}, {start: 61.5, title: 'Verse'}], gain: -7.25, subtitles: ['en'], hasLyrics: true,
    sourceUrl: 'https://music.example/watch?v=abc', sourceId: 'youtube:abc', tags: ['rock', 'live'], lastPlayedAt: T0 + 5000, position: 12.5,
    watched: false, favorite: true, userTags: ['alpha', 'zeta'], plays: 5,
  })
  const i2: Rec = {id: uid(2), space: 'videos', kind: 'video', title: 'Clip', addedAt: T0 + 2, width: 1920, height: 1080, duration: 61, watched: true, position: 30, favorite: false, userTags: [], plays: 0, place: 'Videos/clip.mp4'}
  const i3: Rec = {id: uid(3), space: 'downloads', kind: 'image', title: 'Picture', addedAt: T0 + 3, favorite: false, userTags: [], plays: 0, place: 'Downloads/pic.jpg'}
  const i4 = song(4, {place: null})
  const i5 = song(5, {space: 'ext', place: 'External/x/y.mp3'})
  const i6 = song(6, {space: 'my-space', place: 'My space/z.mp3', userTags: ['road']})
  const own = {id: uid(101), name: 'Road trip', kind: 'own', space: 'music', itemIds: [uid(1), uid(2), uid(6)], seen: ['u1', 'u2'], createdAt: T0, updatedAt: T0 + 10, coverItemId: uid(1), newItemIds: [], removedItemIds: []}
  const downloaded = {
    id: uid(102), name: 'Mix', kind: 'playlist', space: 'videos', itemIds: [uid(2), uid(1)], seen: ['x'], createdAt: T0 + 1, updatedAt: T0 + 11,
    source: {url: 'https://video.example/playlist?list=PL1', provider: 'video', id: 'PL1'},
    follow: {mode: 'auto', intervalHours: 6, wifiOnly: true, notify: false, confirmAbove: 20, pendingCount: 3, pendingAt: T0 + 12},
    newItemIds: [uid(2)], removedItemIds: [uid(1)],
  }
  const album = {id: uid(103), name: 'An album', kind: 'album', space: 'music', itemIds: [uid(1)], seen: [], createdAt: T0 + 2, updatedAt: T0 + 2, newItemIds: [], removedItemIds: []}
  return {items: [i1, i2, i3, i4, i5, i6], collections: [own, downloaded, album], spaces: ALL_SPACES, routes: ROUTES}
}

function withBase(): {mini: Mini; model: Neutral; first: Case} {
  const mini = new Mini()
  const model = base()
  const first = recordCase('a new library with every kind of data (round trip)', mini, null, model, 1, {roundtrip: true})
  return {mini, model, first}
}

function edit(model: Neutral, change: (m: Neutral) => void): Neutral {
  const next = clone(model)
  change(next)
  return next
}

const item = (m: Neutral, n: number) => m.items.find((i) => i.id === uid(n)) as Rec
const coll = (m: Neutral, n: number) => m.collections.find((c) => c.id === uid(n)) as Rec

function newLibraryFile(): FixtureFile {
  const {first} = withBase()
  const mini = new Mini()
  // normalisation: messy but legal input; ids that are not UUIDs are left out; nothing here round-trips
  const messy: Neutral = {
    items: [
      {id: uid(20), space: 'music', kind: 'weird', title: 'Rounded', addedAt: T0.valueOf() + 20.4, duration: 12.3456, position: 0.0004, gain: -0.0004, track: 2.5, year: 1999.49, width: 640.5, favorite: false, userTags: ['b', 'a', 'a', ''], plays: 2.6, place: 'Music/a b/c.mp3'},
      {id: 'content://media/external/audio/media/77', space: 'music', kind: 'audio', title: 'Not a uuid', place: 'Music/n.mp3'},
      {id: uid(21), space: 'music', kind: 'audio', title: 'Bad placement', chapters: [{start: 1, title: 'x'}], subtitles: [], tags: [], place: 'Music/../escape.mp3'},
      {id: uid(22), space: 'music', kind: 'audio', title: 'Odd chapters', chapters: [{start: 1e300, title: 'huge'}], place: 'Music/odd.mp3'},
    ],
    collections: [
      {id: uid(110), name: 'Messy', kind: 'channel', space: 'music', itemIds: [uid(20), uid(20), uid(99), 'content://media/external/audio/media/77', uid(21)], seen: ['b', 'a', 'a', ''], createdAt: T0 + 0.6, newItemIds: [uid(99), uid(21), uid(20)], removedItemIds: [uid(21)]},
      {id: 'not-a-uuid', name: 'Skipped', kind: 'own', space: 'music', itemIds: [], seen: []},
    ],
    spaces: [SP.music, {...SP.custom, id: '6b8e1c10-2d3a-4f55-8a01-0000000000ff'}, {id: 'plain', name: 'Plain', kind: 'weird', folder: '', layout: 'collection', naming: 'title', skipExisting: false, type: 'folder'}],
    routes: {music: 'plain', other: 'unknown-space'},
  }
  return {
    file: 'new-library.json',
    note: 'A library written from nothing: spaces (built-in, custom, linked, smart), routes, items with every field, favourites, tags, plays, placements, playlists and collections.',
    cases: [first, recordCase('messy input is normalised and unusable ids are left out', mini, null, messy, 1)],
  }
}

function editsFile(): FixtureFile {
  const cases: Case[] = []
  const step = (name: string, change: (m: Neutral) => void, extra: Rec = {}) => {
    const {mini, model} = withBase()
    cases.push(recordCase(name, mini, model, edit(model, change), 1000, extra))
  }
  step('nothing changed writes nothing', () => undefined)
  step('field edits: set, change and clear fields', (m) => {
    const i = item(m, 1)
    i.title = 'Renamed'
    delete i.artist
    i.year = 2020
    i.duration = 216
    i.watched = true
    i.tags = ['rock']
    delete i.chapters
    const j = item(m, 2)
    j.genre = 'Documentary'
    j.sourceUrl = 'https://video.example/watch?v=1'
  })
  step('favourite and tags: add and remove', (m) => {
    item(m, 1).favorite = false
    item(m, 2).favorite = true
    item(m, 1).userTags = ['alpha', 'new']
    item(m, 6).userTags = []
  })
  step('plays grow by this device', (m) => {
    item(m, 1).plays = 8
    item(m, 2).plays = 1
  })
  step('plays that do not grow write nothing', (m) => {
    item(m, 1).plays = 2
  })
  step('placement moves and clears', (m) => {
    item(m, 1).place = 'Music/Artist/Album/renamed.mp3'
    item(m, 2).place = null
    item(m, 4).place = 'Music/found.mp3'
    item(m, 5).place = 'External/other/y.mp3'
  })
  step('deleting an item observes its tags and heads', (m) => {
    m.items = m.items.filter((i) => i.id !== uid(3) && i.id !== uid(1))
  })
  step('space edits, a new space and a removed space', (m) => {
    const music = m.spaces.find((s) => s.id === 'music') as Rec
    music.name = 'Songs'
    music.layout = 'flat'
    delete music.ignore
    music.pinned = false
    m.spaces = m.spaces.filter((s) => s.id !== 'my-space' && s.id !== 'smart-1')
    m.spaces.push({id: uid(300), name: 'Podcasts', kind: 'music', folder: 'Podcasts', layout: 'flat', naming: 'title', skipExisting: true, type: 'folder'})
  })
  step('routes change', (m) => {
    m.routes = {music: 'videos', other: 'music'}
  })
  step('a new item with a placement, plays, favourite and tags', (m) => {
    m.items.push(song(7, {favorite: true, userTags: ['fresh'], plays: 3, artist: 'New'}))
  })
  return {file: 'edits.json', note: 'Changes to a library that already exists: only what changed is written; removals carry what the store observed.', cases}
}

function playlistsFile(): FixtureFile {
  const cases: Case[] = []
  const more = (m: Neutral) => {
    for (let n = 30; n < 36; n++) m.items.push(song(n))
    m.collections.push({id: uid(120), name: 'Long list', kind: 'own', space: 'music', itemIds: [uid(30), uid(31), uid(32), uid(33), uid(34)], seen: [], createdAt: T0, updatedAt: T0, newItemIds: [], removedItemIds: []})
  }
  const step = (name: string, change: (m: Neutral) => void) => {
    const mini = new Mini()
    const model = base()
    more(model)
    mini.apply(runDiff({op: 'diff', actor: ACTOR, replicaId: REPLICA, before: null, after: model, ids: Array.from({length: 40}, (_, n) => eid(500 + n))}))
    cases.push(recordCase(name, mini, model, edit(model, change), 2000))
  }
  const list = (m: Neutral) => coll(m, 120)
  step('reordering moves the fewest entries', (m) => {
    list(m).itemIds = [uid(34), uid(30), uid(32), uid(31), uid(33)]
  })
  step('inserting and removing items', (m) => {
    list(m).itemIds = [uid(30), uid(35), uid(32), uid(34)]
  })
  step('items appended at the end and at the start', (m) => {
    list(m).itemIds = [uid(35), uid(30), uid(31), uid(32), uid(33), uid(34), uid(1)]
  })
  step('a playlist is emptied', (m) => {
    list(m).itemIds = []
  })
  step('a collection is deleted and a playlist is added', (m) => {
    m.collections = m.collections.filter((c) => c.id !== uid(102))
    m.collections.push({id: uid(121), name: 'Fresh', kind: 'own', space: 'music', itemIds: [uid(3), uid(1), uid(2)], seen: ['k'], createdAt: T0 + 5, updatedAt: T0 + 5, newItemIds: [], removedItemIds: []})
  })
  step('follow settings and the seen, new and removed sets change', (m) => {
    const c = coll(m, 102)
    c.follow = {mode: 'manual', intervalHours: 24, wifiOnly: false, notify: true, confirmAbove: 50}
    c.seen = ['x', 'y']
    c.newItemIds = []
    c.removedItemIds = [uid(2)]
    c.name = 'Mix (renamed)'
    c.updatedAt = T0 + 99
  })
  step('an item deleted from the library also leaves its collections', (m) => {
    m.items = m.items.filter((i) => i.id !== uid(31))
    list(m).itemIds = (list(m).itemIds as string[]).filter((id) => id !== uid(31))
  })
  return {file: 'playlists.json', note: 'Playlist and collection changes: entry inserts, moves (fewest) and removals with fractional keys, follow, seen, new and removed sets.', cases}
}

function projectFile(): FixtureFile {
  const cases: Case[] = []
  const grown = withBase()
  cases.push({name: 'the views of a new library project back to the model', input: {op: 'project', replicaId: REPLICA, views: grown.mini.views()}, expected: undefined})
  const edited = withBase()
  const after = edit(edited.model, (m) => {
    item(m, 1).favorite = false
    item(m, 1).userTags = ['alpha', 'new']
    item(m, 2).plays = 2
    item(m, 4).place = 'Music/found.mp3'
    m.items = m.items.filter((i) => i.id !== uid(3))
    coll(m, 101).itemIds = [uid(6), uid(1)]
  })
  recordCase('scratch', edited.mini, edited.model, after, 3000)
  cases.push({name: 'views after edits, a deletion and a reorder', input: {op: 'project', replicaId: REPLICA, views: edited.mini.views()}, expected: undefined})

  const fields = (value: Rec) => value as Record<string, SyncJson>
  const head = {op: `${ACTOR}:1`, content: `sha256:${'a'.repeat(64)}`, size: 10}
  const views = {
    items: [
      {id: uid(1), status: 'present', fields: fields({media: 'audio', space: '6b8e1c10-2d3a-4f55-8a01-000000000002', title: 'Song', duration: 1500, position: 2500, gain: -3125, track: 4, subtitles: ['en', 'fr'], tags: ['a'], chapters: [{start: 500, title: 'c'}], addedAt: 77}), heads: [head], contentConflict: false, deleteConflictHeads: [], plays: 4},
      {id: uid(2), status: 'deleted', fields: fields({media: 'audio', space: 'music', title: 'Gone'}), heads: [], contentConflict: false, deleteConflictHeads: [], plays: 0},
      {id: uid(3), status: 'deleteConflict', fields: fields({media: 'hologram', space: 'nowhere', title: 7, track: 'x', subtitles: [], tags: [1], chapters: [{start: 'a'}]}), heads: [head], contentConflict: false, deleteConflictHeads: [head.op], plays: 0},
      {id: uid(4), status: 'present', fields: fields({media: 'video', space: '6b8e1c10-2d3a-4f55-8a01-000000000003', title: 'Placed elsewhere'}), heads: [], contentConflict: false, deleteConflictHeads: [], plays: 0},
      {id: uid(5), status: 'present', fields: fields({media: 'audio', space: '00000000-0000-4000-8000-0000000000a1', title: 'In the linked space'}), heads: [], contentConflict: false, deleteConflictHeads: [], plays: 1},
    ],
    playlists: [
      {id: uid(101), present: true, fields: fields({kind: 'playlist', name: 'Mine', space: '6b8e1c10-2d3a-4f55-8a01-000000000002', createdAt: 5}), songs: [{entry: eid(1), item: uid(1), key: 'a0'}, {entry: eid(2), item: uid(2), key: 'a1'}, {entry: eid(3), item: uid(1), key: 'a2'}, {entry: eid(4), item: uid(3), key: 'a3'}]},
      {id: uid(102), present: true, fields: fields({kind: 'collection', collectionKind: 'weird', name: 'Coll', space: 'music', source: {url: 'https://x.example/l', provider: 'x'}, follow: {mode: 'sometimes', intervalHours: 5, wifiOnly: 1, notify: true, confirmAbove: 'many', pending: {count: 2, at: 9}}}), songs: []},
      {id: uid(103), present: true, fields: fields({kind: 'collection', collectionKind: 'channel', name: 'Chan', space: 'music', follow: {mode: 'auto', intervalHours: 168, wifiOnly: true, notify: false, confirmAbove: 7}, coverItemId: uid(1), updatedAt: 8}), songs: [{entry: eid(5), item: uid(1), key: 'a0'}]},
      {id: uid(104), present: false, fields: fields({kind: 'playlist', name: 'Deleted'}), songs: []},
      {id: uid(105), present: true, fields: fields({kind: 'mystery', name: 'Unknown kind'}), songs: []},
    ],
    spaces: [
      {id: '6b8e1c10-2d3a-4f55-8a01-000000000002', present: true, fields: fields({name: 'Music', kind: 'music', folder: 'Music', layout: 'artist-album', naming: 'artist-title', skipExisting: true, type: 'folder', order: 2, ignore: [], color: 'cobalt'})},
      {id: '6b8e1c10-2d3a-4f55-8a01-000000000003', present: true, fields: fields({name: 'Videos', kind: 'video', folder: 'Videos', layout: 'bogus', naming: 'title', skipExisting: false, type: 'folder', order: 0})},
      {id: '00000000-0000-4000-8000-0000000000a1', present: true, fields: fields({name: 'Linked', kind: 'weird', folder: 'Elsewhere', linked: true, layout: 'flat', naming: 'title', skipExisting: false, type: 'folder', order: 1, legacyId: 'ext-legacy', pinned: true, scanFolder: false})},
      {id: '00000000-0000-4000-8000-0000000000a2', present: true, fields: fields({name: 'Smart', kind: 'mixed', folder: '', layout: 'flat', naming: 'title', skipExisting: false, type: 'smart', order: 3, rules: {match: 'all', rules: []}})},
      {id: '00000000-0000-4000-8000-0000000000a3', present: false, fields: fields({name: 'Removed', kind: 'music', folder: 'R', type: 'folder', order: 4})},
    ],
    sets: {favourites: [uid(1)], [`tags:${uid(1)}`]: ['zed', 'abc'], [`seen:${uid(101)}`]: ['s2', 's1'], [`new:${uid(103)}`]: [uid(1), uid(2)], [`removed:${uid(103)}`]: [uid(3), uid(2)]},
    placements: {[uid(1)]: {[REPLICA]: 'Music/a.mp3'}, [uid(3)]: {[REPLICA]: '../bad'}, [uid(4)]: {'other-replica': 'Videos/v.mp4'}, [uid(5)]: {[REPLICA]: 'Elsewhere/deep/f.mp3'}},
    places: {},
    profile: {routes: {music: '00000000-0000-4000-8000-0000000000a1', other: 'unknown'}},
    findings: [],
  }
  cases.push({name: 'unusual views: defaults, unknown values, deleted and conflicting items, other replicas, linked spaces', input: {op: 'project', replicaId: REPLICA, views}, expected: undefined})
  cases.push({name: 'empty views', input: {op: 'project', replicaId: REPLICA, views: {items: [], playlists: [], spaces: [], sets: {}, placements: {}, places: {}, profile: {}, findings: []}}, expected: undefined})
  for (const c of cases) c.expected = runProject(c.input)
  return {file: 'project.json', note: 'Views (as the store reports them) projected to the neutral library model.', cases}
}

function generate(): FixtureFile[] {
  return [newLibraryFile(), editsFile(), playlistsFile(), projectFile()]
}

if (process.env.KEEPFIN_WRITE_FIXTURES === '1') {
  fs.mkdirSync(FIXTURES, {recursive: true})
  for (const file of generate()) fs.writeFileSync(path.join(FIXTURES, file.file), `${JSON.stringify({note: file.note, cases: file.cases}, null, 1)}\n`)
}

// ---------------------------------------------------------------------------------------------------------------------
// The check: every fixture through mapping.ts
// ---------------------------------------------------------------------------------------------------------------------

const files = fs.existsSync(FIXTURES) ? fs.readdirSync(FIXTURES).filter((name) => name.endsWith('.json')).sort() : []

test('the fixture files exist', () => {
  assert.ok(files.length >= 4, `fixtures/library-mapping has ${files.length} files`)
})

let total = 0
for (const name of files) {
  const doc = JSON.parse(fs.readFileSync(path.join(FIXTURES, name), 'utf8')) as {cases: Case[]}
  for (const c of doc.cases) {
    total++
    test(`${name}: ${c.name}`, () => {
      const input = c.input
      if (input.op === 'diff') assert.deepStrictEqual(runDiff(input), c.expected)
      else if (input.op === 'project') assert.deepStrictEqual(runProject(input), c.expected)
      else assert.fail(`unknown op ${String(input.op)}`)
    })
  }
}

test('the fixtures have enough cases to mean something', () => {
  assert.ok(total >= 20, `${total} cases`)
})
