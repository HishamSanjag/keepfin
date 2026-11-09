// A copy that holds a smart space holds what its rules gather (#31): the desired set follows the core's own rules over the SQL state.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {makeOp, type SyncJson} from '../../shared/core/sync.js'
import {prng, uuid} from './history.js'
import {desiredEntries, type Holds} from './desired.js'
import {heldSmart, readSmartSpaces, smartMembers} from './smart.js'
import {SqlState} from './sqlstate.js'

function world() {
  const rnd = prng(42)
  const actor = uuid(rnd)
  const main = uuid(rnd)
  let seq = 0
  const make = (kind: string, body: Record<string, SyncJson>) => makeOp(actor, ++seq, `${String(1_700_000_000_000 + seq).padStart(13, '0')}.000000.${actor}`, kind, body)
  const db = new SqlState(':memory:')
  const ops: string[] = []
  const musicWire = uuid(rnd) // a space this library made itself
  const song = (title: string, fields: Record<string, SyncJson> = {}) => {
    const item = uuid(rnd)
    ops.push(make('item.add', {item, fields: {media: 'audio', space: musicWire, title, ...fields}}))
    ops.push(make('item.place', {item, place: main, path: `Music/${title}.mp3`}))
    ops.push(make('item.revise', {item, content: 'sha256:' + ops.length.toString(16).padStart(2, '0').repeat(32), size: 100, parents: []}))
    return item
  }
  const smart = (name: string, rules: SyncJson) => {
    const space = uuid(rnd)
    ops.push(make('space.add', {space, fields: {name, type: 'smart', rules}}))
    return space
  }
  const apply = () => db.admit(ops.splice(0))
  return {db, main, ops, make, song, smart, apply, musicWire}
}

const holdsOf = (smartIds: string[]): Holds => ({all: false, spaces: [], playlists: [], favourites: false, smart: smartIds})

test('a smart space by tag, kind and artist gathers the items its rules match, and a copy holds exactly those', () => {
  const w = world()
  const lofi = w.song('Rain', {tags: ['lofi']})
  const other = w.song('Loud', {tags: ['metal']})
  const byArtist = w.song('Waves', {artist: 'Nina Simone', tags: []})
  const chill = w.smart('Chill', {match: 'any', rules: [{field: 'tag', values: ['lofi']}, {field: 'artist', values: ['nina simone']}]})
  const songsOnly = w.smart('Songs', {match: 'all', rules: [{field: 'tag', values: ['metal']}], onlyKind: 'audio'})
  const nothing = w.smart('Nothing', {match: 'any', rules: []})
  w.apply()
  assert.deepEqual(readSmartSpaces(w.db.db).map((s) => s.name).sort(), ['Chill', 'Nothing', 'Songs'])
  assert.deepEqual([...smartMembers(w.db.db, [chill])].sort(), [lofi, byArtist].sort())
  assert.deepEqual([...smartMembers(w.db.db, [songsOnly])], [other])
  assert.equal(smartMembers(w.db.db, [nothing]).size, 0, 'a space without rules gathers nothing')
  assert.equal(smartMembers(w.db.db, ['gone']).size, 0, 'a smart space that no longer exists gathers nothing')
  const held = holdsOf([chill])
  const paths = [...desiredEntries(w.db.db, w.main, held, true, false, heldSmart(w.db.db, held))].map((e) => e.path).sort()
  assert.deepEqual(paths, ['Music/Rain.mp3', 'Music/Waves.mp3'])
  // without the set the smart hold selects nothing (the caller reads it once per run)
  assert.deepEqual([...desiredEntries(w.db.db, w.main, held, true, false)], [])
  // two held smart spaces are a union, each item once
  const both = holdsOf([chill, songsOnly])
  assert.equal([...desiredEntries(w.db.db, w.main, both, true, false, heldSmart(w.db.db, both))].length, 3)
  w.db.close()
})

test('favourites, collections and the library\'s own space ids are understood by the rules', () => {
  const w = world()
  const a = w.song('A')
  const b = w.song('B')
  w.song('C')
  const playlist = uuid(prng(9))
  w.ops.push(w.make('set.add', {set: 'favourites', element: a}))
  w.ops.push(w.make('set.add', {set: `tags:${b}`, element: 'road trip'}))
  w.ops.push(w.make('playlist.add', {playlist, fields: {name: 'Mix'}}))
  w.ops.push(w.make('entry.insert', {playlist, entry: uuid(prng(3)), item: b, key: 'a0'}))
  const hearts = w.smart('Hearts', {match: 'any', rules: [{field: 'favorite'}]})
  const trip = w.smart('Trip', {match: 'any', rules: [{field: 'tag', values: ['road trip']}]})
  const inMix = w.smart('InMix', {match: 'any', rules: [{field: 'collection', values: [playlist]}]})
  const inSpace = w.smart('InSpace', {match: 'any', rules: [{field: 'space', values: ['music']}]})
  w.apply()
  assert.deepEqual([...smartMembers(w.db.db, [hearts])], [a])
  assert.deepEqual([...smartMembers(w.db.db, [trip])], [b], 'a tag the person added')
  assert.deepEqual([...smartMembers(w.db.db, [inMix])], [b], 'a collection by its id')
  assert.equal(smartMembers(w.db.db, [inSpace]).size, 0, 'the rule names the library\'s space id, which is not this space')
  w.db.close()
})
