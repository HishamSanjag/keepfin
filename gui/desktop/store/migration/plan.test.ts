import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import {takeInventory} from './inventory.js'
import {buildPlan, readPlan, writePlan} from './plan.js'
import type {Choice, Inventory, InvFile} from './types.js'
import {makeWorld, put} from './fixtures.js'

const counter = () => {
  let n = 0
  return () => `uuid-${++n}`
}

async function planFor(world: Awaited<ReturnType<typeof makeWorld>>, choices: Record<string, Choice> = {}, extra: {ignore?: string[]} = {}) {
  const inventory = await takeInventory(world.deviceDir)
  return buildPlan(inventory, {target: world.target, choices, newId: counter(), runId: 'run1', now: () => 1000, ...extra})
}

test('the plan maps spaces to Music, Videos and Downloads and keeps sidecars with their media', async () => {
  const world = await makeWorld()
  try {
    const plan = await planFor(world)
    const rels = plan.files.map((file) => file.rel)
    assert.ok(rels.includes('Music/Artist A/Song 1.mp3'))
    assert.ok(rels.includes('Music/Artist A/Song 1.lrc'))
    assert.ok(rels.includes('Music/Song 3.jpg'))
    assert.ok(rels.includes('Downloads/YouTube/Clip.mp4'))
    assert.ok(rels.includes('Downloads/YouTube/Clip.en.srt'))
    assert.ok(rels.includes('Downloads/YouTube/Clip.info.json'))
    assert.ok(rels.includes('Music/Loose Folder/Loose 2.m4a'))
    assert.equal(new Set(plan.files.map((file) => file.dest)).size, plan.files.length)

    const art = plan.files.filter((file) => file.kind === 'art')
    assert.equal(art.length, 1)
    assert.match(art[0].rel, /^\.keepfin\/art\/uuid-\d+\.jpg$/)
    assert.equal(art[0].action, 'copy')

    // Identity mapping.
    assert.equal(Object.keys(plan.mapping.items).length, 5)
    assert.equal(Object.keys(plan.mapping.collections).length, 2)
    assert.equal(Object.keys(plan.mapping.unindexed).length, 3)
    assert.equal(plan.mapping.paths[path.join(world.music, 'Artist A', 'Song 1.mp3')], 'Music/Artist A/Song 1.mp3')
    const sidecar = plan.files.find((file) => file.rel === 'Music/Artist A/Song 1.lrc')
    const media = plan.files.find((file) => file.rel === 'Music/Artist A/Song 1.mp3')
    assert.equal(sidecar?.owner, media?.id)

    const ignoring = await planFor(world, {}, {ignore: [path.join(world.music, 'Loose 1.mp3')]})
    assert.equal(Object.keys(ignoring.mapping.unindexed).length, 2)
    assert.ok(ignoring.files.some((file) => file.rel === 'Music/Loose 1.mp3'), 'an ignored file still travels')
  } finally {
    await world.cleanup()
  }
})

test('Copy keeps originals as copies, Leave keeps files in place as a linked folder', async () => {
  const world = await makeWorld()
  try {
    const plan = await planFor(world, {music: 'leave', downloads: 'copy'})
    assert.ok(plan.files.filter((file) => file.kind !== 'art' && file.space === 'downloads').every((file) => file.action === 'copy'))
    assert.equal(plan.files.some((file) => file.space === 'music'), false)
    assert.equal(plan.mapping.linkedSpaces.music, world.music)
    assert.ok(plan.left.length >= 7)
    assert.equal(plan.spaces.find((space) => space.id === 'music')?.destRel, null)
    assert.equal(plan.files.filter((file) => file.kind === 'art').length, 1, 'art is copied even for a linked space')
  } finally {
    await world.cleanup()
  }
})

test('names that already exist in the Keepfin folder get (2), and a media file keeps its sidecars together', async () => {
  const world = await makeWorld()
  try {
    await put(path.join(world.target, 'Music', 'Artist A', 'Song 1.lrc'), 'someone else')
    await put(path.join(world.target, 'Music', 'Song 2.mp3'), 'someone else')
    const plan = await planFor(world)
    const rels = plan.files.map((file) => file.rel)
    assert.ok(rels.includes('Music/Artist A/Song 1 (2).mp3'))
    assert.ok(rels.includes('Music/Artist A/Song 1 (2).lrc'))
    assert.ok(rels.includes('Music/Song 2 (2).mp3'))
    assert.equal(rels.includes('Music/Artist A/Song 1.mp3'), false)
    assert.equal(plan.mapping.paths[path.join(world.music, 'Song 2.mp3')], 'Music/Song 2 (2).mp3')
  } finally {
    await world.cleanup()
  }
})

function synthetic(files: {path: string; rel: string; space: string}[], spaces = [{id: 'music', name: 'Music', folder: '/src', type: 'folder' as const}]): Inventory {
  const media: InvFile[] = files.map((file) => ({...file, size: 1, mtimeMs: 1, kind: 'media', indexed: false}))
  return {
    version: 1, deviceDir: '/dev', spaces, library: {present: true, extra: {}, items: [], collections: []}, queuePresent: true, media, sidecars: [], art: [],
    unindexed: [], missing: [], jobs: [], orphanArt: [], other: [], skipped: [],
    counts: {media: files.length, sidecars: 0, history: 0, playlists: 0, collections: 0, favourites: 0, userTags: 0, art: 0},
  }
}

test('collisions between sources are compared case-insensitively when asked, and spaces with the same name get their own folders', async () => {
  const base = {target: path.resolve('/keepfin-target'), choices: {}, newId: counter(), runId: 'r', now: () => 1, exists: async () => false}
  const files = [
    {path: '/src/Song.mp3', rel: 'Song.mp3', space: 'music'},
    {path: '/src/song.mp3', rel: 'song.mp3', space: 'music'},
  ]
  const folded = await buildPlan(synthetic(files), {...base, caseInsensitive: true})
  assert.deepEqual(folded.files.map((file) => file.rel).sort(), ['Music/Song.mp3', 'Music/song (2).mp3'])
  const exact = await buildPlan(synthetic(files), {...base, caseInsensitive: false})
  assert.deepEqual(exact.files.map((file) => file.rel).sort(), ['Music/Song.mp3', 'Music/song.mp3'])

  // Three same-name sources: (2), (3). A custom space named like a built-in does not merge with it.
  const spaces = [
    {id: 'music', name: 'Music', folder: '/a', type: 'folder' as const},
    {id: 'x1', name: 'music', folder: '/b', type: 'folder' as const},
    {id: 'x2', name: 'Music', folder: '/c', type: 'folder' as const},
  ]
  const merged = await buildPlan(
    synthetic(
      [
        {path: '/a/one.mp3', rel: 'one.mp3', space: 'music'},
        {path: '/b/one.mp3', rel: 'one.mp3', space: 'x1'},
        {path: '/c/one.mp3', rel: 'one.mp3', space: 'x2'},
      ],
      spaces,
    ),
    {...base, caseInsensitive: true},
  )
  assert.deepEqual(merged.files.map((file) => file.rel).sort(), ['Music (3)/one.mp3', 'Music/one.mp3', 'music (2)/one.mp3'])
})

test('a file that is already exactly where the plan puts it stays put', async () => {
  const target = path.resolve('/same-place')
  const here = path.join(target, 'Music', 'a.mp3')
  const plan = await buildPlan(synthetic([{path: here, rel: 'a.mp3', space: 'music'}], [{id: 'music', name: 'Music', folder: path.join(target, 'Music'), type: 'folder'}]), {
    target, choices: {}, newId: counter(), runId: 'r', now: () => 1, caseInsensitive: false, exists: async () => true,
  })
  assert.equal(plan.files[0].rel, 'Music/a.mp3')
  assert.equal(plan.files[0].dest, here)
})

test('the plan is written atomically and reads back unchanged', async () => {
  const world = await makeWorld({loose: false})
  try {
    const plan = await planFor(world)
    await writePlan(world.runDir, plan)
    assert.deepEqual(await readPlan(world.runDir), JSON.parse(JSON.stringify(plan)))
    const names = await fs.readdir(world.runDir)
    assert.deepEqual(names, ['plan.json'])
  } finally {
    await world.cleanup()
  }
})
