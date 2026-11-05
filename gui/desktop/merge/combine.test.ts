// Combining two libraries on Windows, end to end: two devices on loopback with different libraries, B joins A's library T into a
// records root of its own Keepfin folder, describes its files in receipted batches, switches the pointer, and the device-local data
// follows at the next start. Media files are hashed before and after.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import {readJson} from '../store/atomic.js'
import {folderJsonPath, recordsPath} from '../store/keepfinFolder.js'
import {loadProfiles} from '../store/profiles.js'
import {mapFileOf} from './engine.js'
import {readJsonLines} from './lines.js'
import {mediaTree, mergeRig, uuidN} from './testkit.js'

const song = (n: number) => Buffer.from(`audio bytes of song ${n} `.repeat(40))

async function seed(rig: Awaited<ReturnType<typeof mergeRig>>) {
  const {a, b} = rig
  // A (the main library): one song of its own, one that B also has
  await a.put(1, 'Music/a-own.mp3', song(1))
  await a.put(2, 'Music/shared.mp3', song(2))
  // B: its own song, the same file as A's second song under another name, and a copy of its own song
  await b.put(11, 'Music/b-own.mp3', song(11))
  await b.put(12, 'Music/b-copy-of-shared.mp3', song(2))
  await b.put(13, 'Music/b-own-twice.mp3', song(11))
  await b.store.apply([{kind: 'set.add', body: {set: 'favourites', element: uuidN(11)}}] as never)
  await b.store.flush()
}

test('combine end to end: A and B hold different libraries; B joins T, describes S, switches; media bytes and paths are unchanged', async () => {
  const rig = await mergeRig()
  try {
    await seed(rig)
    const before = await mediaTree(rig.b.root)
    const sOpsBefore = await fs.readdir(path.join(rig.b.root, '.keepfin', 'ops'))

    // the review: a dry run, S stays in charge
    const reviewed = await rig.engine.preview(rig.a.identity.deviceId)
    assert.equal(reviewed.phase, 'ready')
    assert.deepEqual(
      [reviewed.preview!.items, reviewed.preview!.added, reviewed.preview!.linked, reviewed.preview!.keptDuplicates, reviewed.preview!.missingFiles],
      [3, 1, 1, 1, 0],
      'three items: one new, one already in the other library, one the same song twice',
    )
    assert.equal(JSON.parse(await fs.readFile(folderJsonPath(rig.b.root), 'utf8')).format, 1, 'the review changes nothing about the folder')

    const state = await rig.engine.start(rig.a.identity.deviceId, 'theirs')
    assert.equal(state.phase, 'done')
    assert.equal(rig.relaunches, 1, 'the app restarts onto the combined library')

    // the pointer
    const folder = JSON.parse(await fs.readFile(folderJsonPath(rig.b.root), 'utf8'))
    assert.equal(folder.format, 2)
    assert.equal(folder.libraryId, rig.tId)
    assert.equal(folder.records, `libraries/${rig.tId}`)
    assert.deepEqual(folder.libraries.map((l: {role: string}) => l.role).sort(), ['active', 'previous'])
    // S's records are frozen where they were
    assert.deepEqual(await fs.readdir(path.join(rig.b.root, '.keepfin', 'ops')), sOpsBefore)

    // the map is lasting and travels with the folder
    const j = (await rig.engine.current())!
    const map = await readJsonLines<{s: string; t: string; how: string}>(mapFileOf(rig.b.root, j.t.records, j.mergeId))
    assert.deepEqual(map.map((m) => m.how).sort(), ['added', 'kept-duplicate', 'linked'])
    assert.equal(map.find((m) => m.s === uuidN(12))!.t, uuidN(2), 'the second copy of A\'s song is linked to A\'s item')

    // the next start finishes the device-local part before the library opens
    const restarted = rig.restart()
    await restarted.recover()
    const profile = (await loadProfiles(rig.b.deviceDir))!.profiles[0]
    assert.equal(profile.libraryId, rig.tId)
    const opened = await rig.openActive()
    assert.equal(opened.libraryId, rig.tId)
    const titles = opened.store.views().items.map((i) => String(i.fields.title)).sort()
    assert.equal(titles.length, 4, 'A\'s two items, B\'s new song and its twin: the shared song is counted once')
    // B's file is a placement of A's item, in B's replica
    const placed = opened.store.views().placements[uuidN(2)]
    assert.ok(Object.values(placed).includes('Music/b-copy-of-shared.mp3'), 'B\'s copy is a copy of A\'s item')

    assert.deepEqual(await mediaTree(rig.b.root), before, 'media paths and bytes are exactly as before')
    assert.ok((await readJson(path.join(rig.b.root, '.keepfin', 'folder.json'))) !== undefined)
    assert.ok(await fs.stat(recordsPath(rig.b.root, `libraries/${rig.tId}`)))
  } finally {
    await rig.cleanup()
  }
})

test('combine from a connected device that offers nothing says why: no Keepfin folder, the same library already, or no permission to read', async () => {
  const rig = await mergeRig()
  try {
    const {SeparateEngine} = await import('./separate.js')
    const gaps = ['none', 'held', 'denied', undefined, 'unshared'] as const
    const said: string[] = []
    for (const gap of gaps) {
      const net = {...rig.deps.net!, offer: () => undefined, deviceName: () => 'Pixel', online: () => true, gap: () => gap}
      const engine = new SeparateEngine({...rig.deps, net})
      said.push(await engine.preview('pixel').then(() => 'no error', (error: Error) => error.message))
    }
    assert.match(said[0], /^Pixel has no Keepfin folder yet/)
    assert.match(said[1], /^Pixel already shares this library/)
    assert.match(said[2], /^Pixel does not let this device read its library/)
    assert.match(said[3], /not offering its library right now/, 'before its hello nothing is known: the general message')
    assert.match(said[4], /^Pixel has not shared its library with this device yet\. On Pixel, open Settings › Devices/)
    // a device with a Keepfin folder it has not shared is not "no Keepfin folder" (the PC said that about a phone that had one)
    const {offerGapOf} = await import('../net/service.js')
    assert.equal(offerGapOf({libraries: [], unshared: 1}, new Set()), 'unshared')
    assert.equal(offerGapOf({libraries: [], unshared: 0}, new Set()), 'none')
    assert.equal(offerGapOf({libraries: []}, new Set()), 'unshared', 'an older device does not say: never claim it has no folder')
  } finally {
    await rig.cleanup()
  }
})
