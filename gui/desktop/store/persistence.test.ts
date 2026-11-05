import {test} from 'node:test'
import assert from 'node:assert/strict'
import {createHash, randomUUID} from 'node:crypto'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {createKeepfinFolder, dataDir} from './keepfinFolder.js'
import {BUILTIN_SPACE_IDS, spaceWireId} from './mapping.js'
import {withKeptSpaces} from '../../shared/library.js'
import {importPlan, modelFromPlan, StoreLibrary} from './persistence.js'
import {createFirstProfile, loadProfiles} from './profiles.js'
import {takeInventory} from './migration/inventory.js'
import {buildPlan} from './migration/plan.js'
import {makeWorld} from './migration/fixtures.js'

const A = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'

async function temp() {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-persist-'))
  const folder = await createKeepfinFolder(path.join(dir, 'Keepfin'), {libraryId: randomUUID(), replicaId: randomUUID(), now: new Date().toISOString(), hide: () => undefined})
  const library = (name = 'AppData') => new StoreLibrary({deviceDir: path.join(dir, name), newId: randomUUID, now: Date.now, store: {watch: false, flushIdleMs: 20}})
  return {dir, folder, library, cleanup: () => fs.rm(dir, {recursive: true, force: true})}
}

test('create writes the default spaces and routes (relative folders); open finds them without retiring the actor', async () => {
  const t = await temp()
  try {
    const created = t.library()
    await created.create(t.folder, A)
    const segmentDir = path.join(dataDir(t.folder.root), 'ops', A)
    assert.equal((await fs.readdir(segmentDir)).filter((name) => name.endsWith('.seg')).length, 1)
    const library = t.library()
    await library.open(t.folder, A)
    const store = library.persistence.store
    assert.equal(store.stats.rotated, false)
    assert.equal(store.actorId, A)
    const views = await store.views()
    assert.deepEqual(
      views.spaces.map((space) => [space.id, space.fields.name, space.fields.folder, space.present]).sort(),
      [
        [BUILTIN_SPACE_IDS.downloads, 'Downloads', 'Downloads', true],
        [BUILTIN_SPACE_IDS.music, 'Music', 'Music', true],
        [BUILTIN_SPACE_IDS.videos, 'Videos', 'Videos', true],
        // a new library has Favorites, a kept smart space (no folder)
        ['b11c64ea-bcff-5c05-80b4-ab6ed166cc38', 'Favorites', '', true],
      ].sort(),
    )
    assert.deepEqual(views.profile.routes, {music: BUILTIN_SPACE_IDS.music, other: BUILTIN_SPACE_IDS.downloads})
    const model = await library.persistence.reload()
    assert.deepEqual(model.spaces?.map((space) => [space.id, space.folder]), [
      ['downloads', path.join(t.folder.root, 'Downloads')],
      ['music', path.join(t.folder.root, 'Music')],
      ['videos', path.join(t.folder.root, 'Videos')],
      ['favorites', ''],
    ])
    assert.deepEqual(model.routes, {music: 'music', other: 'downloads'})
    await library.close()
  } finally {
    await t.cleanup()
  }
})

test('a library that lost Favorites gets it back through the store, once, under the same id on every device', async () => {
  const t = await temp()
  try {
    const library = t.library()
    await library.create(t.folder, A)
    await library.open(t.folder, A)
    const session = library.persistence
    const loaded = await session.reload()
    // an older version removed it
    const without = loaded.spaces!.filter((space) => space.id !== 'favorites')
    await session.save({items: [], collections: [], spaces: without, routes: loaded.routes})
    assert.deepEqual((await session.reload()).spaces?.map((space) => space.id), ['downloads', 'music', 'videos'])
    // the library opens: the missing kept space is added by id and saved like any settings change
    const ensured = withKeptSpaces((await session.reload()).spaces!)
    assert.deepEqual(ensured.map((space) => space.id), ['downloads', 'music', 'videos', 'favorites'])
    await session.save({items: [], collections: [], spaces: ensured, routes: loaded.routes})
    const back = (await session.reload()).spaces!
    assert.deepEqual(back.map((space) => space.id), ['downloads', 'music', 'videos', 'favorites'])
    assert.equal((await session.store.views()).spaces.filter((space) => space.id === spaceWireId('favorites') && space.present).length, 1, 'one space with the wire id every device derives')
    // a second pass (another device, or the next open) finds nothing to add
    assert.equal(withKeptSpaces(back).length, back.length)
    await library.close()
  } finally {
    await t.cleanup()
  }
})

test('overview counts files and bytes per space and the missing ones; checkFiles finds changed content', async () => {
  const t = await temp()
  try {
    const library = t.library()
    await library.create(t.folder, A)
    await library.open(t.folder, A)
    const session = library.persistence
    const loaded = await session.reload()
    const music = path.join(t.folder.root, 'Music')
    const make = async (name: string, content: string, space: string) => {
      const file = path.join(t.folder.root, space === 'music' ? 'Music' : 'Downloads', name)
      await fs.mkdir(path.dirname(file), {recursive: true})
      await fs.writeFile(file, content)
      return {
        id: randomUUID(), space, path: file, folder: '', kind: 'audio', title: name, size: content.length, modifiedAt: 1, addedAt: 1, hasArt: false, managed: true,
      } as never
    }
    const a = await make('a.mp3', 'AAAA', 'music')
    const b = await make('b.mp3', 'BBBBBB', 'music')
    const c = await make('c.mp3', 'CC', 'downloads')
    ;(c as {missing?: boolean}).missing = true
    await session.save({items: [a, b, c], collections: [], spaces: loaded.spaces, routes: loaded.routes})
    const overview = await library.overview()
    assert.deepEqual(overview.spaces.map((space) => [space.id, space.files, space.bytes]), [['downloads', 1, 2], ['music', 2, 10], ['videos', 0, 0]])
    assert.equal(overview.bytes, 12)
    assert.equal(overview.missing, 1)
    assert.equal(overview.checking, false)
    assert.equal(overview.lastCheckedAt, undefined)

    // Revisions record content; checkFiles notices a file whose bytes changed.
    const sha = (text: string) => `sha256:${createHash('sha256').update(text).digest('hex')}`
    await session.store.apply([
      {kind: 'item.revise', body: {item: (a as {id: string}).id, content: sha('AAAA'), size: 4, parents: []}},
      {kind: 'item.revise', body: {item: (b as {id: string}).id, content: sha('BBBBBB'), size: 6, parents: []}},
    ])
    await fs.writeFile(path.join(music, 'b.mp3'), 'tampered')
    await library.checkFiles()
    const checked = await library.overview()
    assert.equal(checked.mismatched, 1)
    assert.ok(checked.lastCheckedAt && checked.lastCheckedAt > 0)
    await library.close()
    const again = t.library()
    await again.open(t.folder, A)
    assert.equal((await again.overview()).mismatched, 1, 'the result is kept on this device')
    await again.close()
  } finally {
    await t.cleanup()
  }
})

test('a restored install retires its actor and the profile follows', async () => {
  const t = await temp()
  try {
    const deviceDir = path.join(t.dir, 'AppData')
    await createFirstProfile(deviceDir, {id: 'p1', name: 'Ana', color: '#fff', folder: t.folder.root, libraryId: t.folder.libraryId, actorId: A, now: new Date().toISOString()})
    const first = t.library()
    await first.create(t.folder, A)
    await first.close()
    // The device data is lost (a restored backup that predates the library).
    await fs.rm(path.join(deviceDir, 'seq'), {recursive: true, force: true})
    const library = t.library()
    await library.open(t.folder, A)
    const rotated = library.persistence.store.actorId
    assert.notEqual(rotated, A)
    assert.equal((await loadProfiles(deviceDir))!.profiles[0].actorId, rotated)
    await library.close()
  } finally {
    await t.cleanup()
  }
})

test('importPlan turns a migration plan into the library: ids, placements, favourites, tags, plays, collections, linked spaces', async () => {
  const world = await makeWorld()
  try {
    const inventory = await takeInventory(world.deviceDir)
    const plan = await buildPlan(inventory, {target: world.target, choices: {music: 'copy', downloads: 'leave'}, newId: randomUUID, runId: 'run1', now: () => 1000})
    const folder = await createKeepfinFolder(world.target, {libraryId: randomUUID(), replicaId: randomUUID(), now: new Date().toISOString(), hide: () => undefined})
    const library = new StoreLibrary({deviceDir: path.join(world.root, 'Device2'), newId: randomUUID, now: Date.now, store: {watch: false}})
    await library.open(folder, A)
    const session = library.persistence
    const first = Object.entries(plan.mapping.items).find(([old]) => old === 'id1')![1]
    assert.equal(await importPlan(session, plan, {hashes: {id1: {content: `sha256:${'a'.repeat(64)}`, size: 3}}}), true)
    assert.equal(await importPlan(session, plan), false, 'running again writes nothing')

    const loaded = await session.reload()
    assert.equal(loaded.items!.length, 5)
    const song = loaded.items.find((item) => item.id === first)!
    assert.equal(song.title, 'Title 1')
    assert.equal(song.favorite, true)
    assert.deepEqual(song.userTags, ['live', 'rock'])
    assert.equal(song.plays, 3)
    assert.equal(song.path, path.join(world.target, 'Music', 'Artist A', 'Song 1.mp3'))
    assert.equal(song.folder, 'Artist A')
    assert.equal((await session.store.views()).items.find((item) => item.id === first)?.heads[0].size, 3)
    // The missing entry comes over, marked missing.
    assert.equal(loaded.items.filter((item) => item.missing).length, 1)
    // Collections keep their order under the new ids, and the follow source survives.
    assert.deepEqual(loaded.collections.map((collection) => [collection.name, collection.kind, collection.itemIds.length]).sort(), [['Followed', 'playlist', 1], ['Mine', 'own', 2]])
    const mine = loaded.collections.find((collection) => collection.name === 'Mine')!
    assert.deepEqual(mine.itemIds, [plan.mapping.items.id1, plan.mapping.items.id3])
    assert.equal(loaded.collections.find((collection) => collection.name === 'Followed')!.source?.provider, 'youtube')
    // Downloads were left: a linked space with its folder on this device.
    const downloads = loaded.spaces!.find((space) => space.id === 'downloads')!
    assert.equal(downloads.folder, world.downloads)
    assert.equal((await session.store.views()).spaces.find((space) => space.fields.legacyId === undefined && space.fields.name === 'Downloads')?.fields.linked, true)
    const clip = loaded.items.find((item) => item.title === 'Title 4')!
    assert.equal(clip.path, path.join(world.downloads, 'YouTube', 'Clip.mp4'))
    await library.close()
    // A restart sees the same.
    const again = new StoreLibrary({deviceDir: path.join(world.root, 'Device2'), newId: randomUUID, now: Date.now, store: {watch: false}})
    await again.open(folder, A)
    assert.equal((await again.persistence.model()).items.length, 5)
    assert.equal((await again.persistence.model()).items.find((item) => item.title === 'Title 4')!.path, clip.path)
    await again.close()
  } finally {
    await world.cleanup()
  }
})

test('modelFromPlan uses the old settings for space layout and naming', async () => {
  const world = await makeWorld()
  try {
    const inventory = await takeInventory(world.deviceDir)
    const plan = await buildPlan(inventory, {target: world.target, choices: {}, newId: randomUUID, runId: 'run1', now: () => 1000})
    const settings = {
      enabled: true, openWith: 'keepfin' as const, normalize: true, crossfade: 0, importTags: true,
      routes: {music: 'music', other: 'downloads'},
      spaces: [
        {id: 'music', name: 'Music', kind: 'music' as const, folder: world.music, layout: 'artist-album' as const, naming: 'artist-title' as const, skipExisting: true, type: 'folder' as const, color: 'teal' as const},
        {id: 'smart1', name: 'Smart', kind: 'mixed' as const, folder: '', layout: 'flat' as const, naming: 'title' as const, skipExisting: false, type: 'smart' as const},
      ],
    }
    const {model} = modelFromPlan(plan, {settings})
    assert.ok(model.spaces.some((space) => space.id === 'smart1'), 'smart spaces carry over from the settings')
    const music = model.spaces.find((space) => space.id === 'music')!
    assert.equal(music.layout, 'artist-album')
    assert.equal(music.color, 'teal')
    assert.equal(music.folder, path.join(world.target, 'Music'))
  } finally {
    await world.cleanup()
  }
})
