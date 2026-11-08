import {test} from 'node:test'
import assert from 'node:assert/strict'
import path from 'node:path'
import os from 'node:os'
import {defaultSettings, loadSettings, mergeSettings, validateSpaces} from './settings.js'
import {favoritesSpace, keptSpaceIds, withKeptSpaces} from '../shared/library.js'

const root = path.join(os.tmpdir(), 'keepfin-settings')
const base = () => defaultSettings(path.join(root, 'Downloads'), {music: path.join(root, 'Music'), videos: path.join(root, 'Videos')})
const downloads = (s: ReturnType<typeof base>) => s.library.spaces.find((space) => space.id === 'downloads')!

test('0.4.x settings migrate losslessly into the Downloads space', () => {
  const out = path.join(root, 'Custom')
  const loaded = loadSettings(base(), {outDir: out, organization: 'flat', filenameStyle: 'uploader-title', concurrency: 5})
  assert.equal(loaded.outDir, out)
  assert.equal(loaded.concurrency, 5)
  assert.deepEqual([downloads(loaded).folder, downloads(loaded).layout, downloads(loaded).naming], [out, 'flat', 'uploader-title'])
  const platform = loadSettings(base(), {organization: 'platform'})
  assert.equal(downloads(platform).layout, 'platform')
  assert.equal(loaded.library.enabled, true)
})

test('legacy fields and the Downloads space stay in sync both ways', () => {
  let s = mergeSettings(base(), {outDir: path.join(root, 'Elsewhere'), organization: 'flat', filenameStyle: 'date-title'})
  assert.deepEqual([downloads(s).folder, downloads(s).layout, downloads(s).naming], [path.join(root, 'Elsewhere'), 'flat', 'date-title'])
  const spaces = s.library.spaces.map((space) => (space.id === 'downloads' ? {...space, folder: path.join(root, 'New'), layout: 'platform', naming: 'title'} : space))
  s = mergeSettings(s, {library: {spaces}})
  assert.deepEqual([s.outDir, s.organization, s.filenameStyle], [path.join(root, 'New'), 'platform', 'title'])
})

test('library validation', () => {
  const s = base()
  const withSpaces = (spaces: unknown) => () => mergeSettings(s, {library: {spaces}})
  const good = s.library.spaces
  const custom = (patch: object) => ({id: 'x', name: 'x', kind: 'mixed', folder: path.join(root, 'x'), layout: 'flat', naming: 'title', ...patch})
  assert.throws(withSpaces(good.filter((x) => x.id !== 'music')), /not removed/)
  assert.throws(withSpaces([...good, {...good[0], id: 'dup'}]), /same folder/)
  assert.throws(withSpaces(good.map((x) => ({...x, folder: 'relative'}))), /Choose a folder/)
  assert.throws(withSpaces([...good, custom({name: 'n'.repeat(61)})]), /60/)
  assert.throws(withSpaces([...good, custom({kind: 'video', layout: 'artist-album'})]), /music spaces/)
  assert.throws(withSpaces([...good, custom({kind: 'weird'})]), /music, video or mixed/)
  assert.throws(() => mergeSettings(s, {library: {routes: {music: 'nope'}}}), /one of your spaces/)
  assert.equal(mergeSettings(s, {library: {crossfade: 99}}).library.crossfade, 12)
  assert.equal(mergeSettings(s, {library: {crossfade: -3}}).library.crossfade, 0)
  const renamed = mergeSettings(s, {library: {spaces: good.map((x) => (x.id === 'music' ? {...x, name: 'Songs', folder: path.join(root, 'Elsewhere')} : x))}})
  assert.equal(renamed.library.spaces.find((x) => x.id === 'music')!.name, 'Songs')
  const added = mergeSettings(s, {library: {spaces: [...good, custom({id: 'watch', name: 'Watch later', kind: 'video', skipExisting: false})]}})
  assert.equal(added.library.spaces.length, 5)
  const routed = mergeSettings(added, {library: {routes: {other: 'watch'}}})
  const removed = mergeSettings(routed, {library: {spaces: good}})
  assert.equal(removed.library.routes.other, 'downloads')
})

test('update channel defaults to stable, validates, and migrates a missing value', () => {
  assert.equal(base().updateChannel, 'stable')
  assert.equal(loadSettings(base(), {concurrency: 2}).updateChannel, 'stable')
  assert.equal(mergeSettings(base(), {updateChannel: 'beta'}).updateChannel, 'beta')
  assert.equal(mergeSettings(mergeSettings(base(), {updateChannel: 'beta'}), {updateChannel: 'nightly'}).updateChannel, 'beta')
  assert.equal(loadSettings(base(), {updateChannel: 'beta'}).updateChannel, 'beta')
})

test('start page defaults to downloads, validates, and migrates a missing value', () => {
  assert.equal(base().startPage, 'last')
  assert.equal(loadSettings(base(), {concurrency: 2}).startPage, 'last')
  assert.equal(mergeSettings(base(), {startPage: 'library'}).startPage, 'library')
  assert.equal(mergeSettings(base(), {startPage: 'last'}).startPage, 'last')
  assert.equal(mergeSettings(mergeSettings(base(), {startPage: 'library'}), {startPage: 'nowhere'}).startPage, 'library')
  assert.equal(loadSettings(base(), {startPage: 'library'}).startPage, 'library')
})

test('"Keep music videos automatically" is off by default, validates, and migrates a missing value', () => {
  assert.equal(base().musicVideos, false)
  assert.equal(loadSettings(base(), {concurrency: 2}).musicVideos, false)
  assert.equal(mergeSettings(base(), {musicVideos: true}).musicVideos, true)
  assert.equal(mergeSettings(mergeSettings(base(), {musicVideos: true}), {musicVideos: 'yes' as never}).musicVideos, true)
  assert.equal(loadSettings(base(), {musicVideos: true}).musicVideos, true)
})

test('spaces from before spaces v2 get their defaults and round-trip losslessly', () => {
  // A library saved by an earlier build: no type, color, icon, pinned or importTags.
  const old = base()
  const stored = {
    library: {
      ...old.library,
      importTags: undefined,
      spaces: old.library.spaces.filter((space) => space.id !== 'favorites').map(({type: _t, color: _c, icon: _i, pinned: _p, ...rest }) => rest),
    },
  }
  const loaded = loadSettings(base(), JSON.parse(JSON.stringify(stored)))
  assert.equal(loaded.library.importTags, true)
  assert.deepEqual(
    loaded.library.spaces.map((space) => [space.id, space.type, space.color, space.icon, space.pinned]),
    [['downloads', 'folder', 'slate', 'folder', true], ['music', 'folder', 'cobalt', 'music', true], ['videos', 'folder', 'violet', 'video', true], ['favorites', 'smart', 'berry', 'heart', true]],
  )
  // Favorites is a kept space: this library lacked it and now has it, right after Videos
  assert.deepEqual(loaded.library.spaces.at(-1)?.rules, {match: 'any', rules: [{field: 'favorite'}]})
  // saved without it (removed by an older version): it comes back on load, and cannot be removed by an update
  const removed = {...JSON.parse(JSON.stringify(loaded)), library: {...loaded.library, spaces: loaded.library.spaces.filter((space) => space.id !== 'favorites')}}
  assert.equal(loadSettings(base(), removed).library.spaces.some((space) => space.id === 'favorites'), true)
  assert.throws(() => mergeSettings(loaded, {library: {spaces: loaded.library.spaces.filter((space) => space.id !== 'favorites')}}), /Favorites can be renamed but not removed/)
  // renamed: it keeps its name
  const renamed = JSON.parse(JSON.stringify({...loaded, library: {...loaded.library, spaces: loaded.library.spaces.map((space) => (space.id === 'favorites' ? {...space, name: 'Hearts'} : space))}}))
  assert.deepEqual(loadSettings(base(), renamed).library.spaces.filter((space) => space.id === 'favorites').map((space) => space.name), ['Hearts'])
  // a space of the person's own named Favorites is not the kept one: the kept one is still added by its id
  const own = {library: {...stored.library, spaces: [...stored.library.spaces, {id: 'mine', name: 'favorites', type: 'smart', rules: {match: 'any', rules: [{field: 'favorite'}]}}]}}
  assert.deepEqual(loadSettings(base(), JSON.parse(JSON.stringify(own))).library.spaces.map((space) => space.id), ['downloads', 'music', 'videos', 'favorites', 'mine'])
  // A custom space takes the next palette color once; saving and loading again changes nothing.
  const withCustom = mergeSettings(loaded, {library: {spaces: [...loaded.library.spaces, {id: 'kids', name: 'Kids', kind: 'mixed', folder: path.join(root, 'kids'), layout: 'flat', naming: 'title'}]}})
  const kids = withCustom.library.spaces.find((space) => space.id === 'kids')!
  assert.deepEqual([kids.type, kids.color, kids.pinned], ['folder', 'teal', false])
  assert.deepEqual(loadSettings(base(), JSON.parse(JSON.stringify(withCustom))), withCustom)
})

test('smart spaces: validated rules, no folder, never a route or a duplicate-folder clash', () => {
  const s = base()
  const good = s.library.spaces
  const smart = (patch: object = {}) => ({id: 'lofi', name: 'Lo-fi study', type: 'smart', color: 'teal', icon: 'moon', pinned: true, rules: {match: 'all', rules: [{field: 'tag', values: [' Lo-Fi ', 'lo-fi', '']}, {field: 'addedWithin', days: 7.4}, {field: 'favorite'}], onlyKind: 'audio', excludeSpaces: ['videos']}, ...patch})
  const withSmart = mergeSettings(s, {library: {spaces: [...good, smart(), smart({id: 'two', name: 'Two'})]}})
  const lofi = withSmart.library.spaces.find((space) => space.id === 'lofi')!
  assert.deepEqual([lofi.type, lofi.folder, lofi.layout, lofi.skipExisting, lofi.color, lofi.icon, lofi.pinned], ['smart', '', 'flat', false, 'teal', 'moon', true])
  assert.deepEqual(lofi.rules, {match: 'all', rules: [{field: 'tag', values: ['Lo-Fi', 'lo-fi']}, {field: 'addedWithin', days: 7}, {field: 'favorite'}], onlyKind: 'audio', excludeSpaces: ['videos']})
  assert.equal(withSmart.library.spaces.length, 6, 'two smart spaces share the empty folder without clashing')
  // everything round-trips (the old offered list is not relied on)
  const reloaded = loadSettings(base(), JSON.parse(JSON.stringify(withSmart)))
  assert.deepEqual({...reloaded.library, offered: undefined}, {...withSmart.library, offered: undefined})
  // not a download target
  assert.throws(() => mergeSettings(withSmart, {library: {routes: {other: 'lofi'}}}), /one of your spaces/)
  // invalid rules and values
  const bad = (rules: unknown) => () => mergeSettings(s, {library: {spaces: [...good, smart({rules})]}})
  assert.throws(bad({match: 'some', rules: []}), /any or all/)
  assert.throws(bad({match: 'any', rules: [{field: 'nonsense', values: []}]}), /Unknown rule/)
  assert.throws(bad({match: 'any', rules: [{field: 'tag', values: ['x'.repeat(101)]}]}), /100 characters/)
  assert.throws(bad({match: 'any', rules: Array.from({length: 31}, () => ({field: 'favorite'}))}), /30 rules/)
  assert.throws(() => mergeSettings(s, {library: {spaces: [...good.filter((x) => x.id !== 'music'), {...smart(), id: 'music'}]}}), /cannot be smart/)
  assert.throws(() => mergeSettings(s, {library: {spaces: [...good, smart({color: 'neon'})]}}), /colors/)
  assert.throws(() => mergeSettings(s, {library: {spaces: [...good, smart({icon: 'rocket'})]}}), /icons/)
})

test('import tags is a boolean setting', () => {
  assert.equal(mergeSettings(base(), {library: {importTags: false}}).library.importTags, false)
  assert.throws(() => mergeSettings(base(), {library: {importTags: 'no'}}), /Invalid library settings/)
})

test('appIcon: default is empty (the main icon), older files keep working, bad ids reset', () => {
  assert.equal(base().appIcon, '')
  assert.equal(loadSettings(base(), {concurrency: 2}).appIcon, '')
  assert.equal(loadSettings(base(), {appIcon: 'k-fin'}).appIcon, 'k-fin')
  assert.equal(mergeSettings(base(), {appIcon: '../evil'}).appIcon, '')
  assert.equal(mergeSettings(base(), {appIcon: 42}).appIcon, '')
  assert.equal(mergeSettings(mergeSettings(base(), {appIcon: 'drop'}), {appIcon: ''}).appIcon, '')
})

test('a fresh library has Favorites; a library without it gets it once; a list without it is refused', () => {
  const fresh = base().library
  assert.deepEqual(fresh.spaces.map((space) => [space.id, space.type]), [['downloads', 'folder'], ['music', 'folder'], ['videos', 'folder'], ['favorites', 'smart']])
  assert.deepEqual(fresh.spaces.at(-1), favoritesSpace())
  assert.deepEqual(keptSpaceIds(), ['downloads', 'music', 'videos', 'favorites'])
  // a library opened without it (the Keepfin folder path): the list gets it right after Videos, by id, and a second pass changes nothing
  const without = fresh.spaces.filter((space) => space.id !== 'favorites')
  const once = withKeptSpaces([...without, {...without[0], id: 'kids', name: 'Kids', folder: path.join(root, 'kids')}])
  assert.deepEqual(once.map((space) => space.id), ['downloads', 'music', 'videos', 'favorites', 'kids'])
  assert.deepEqual(withKeptSpaces(once), once)
  // validateSpaces (every settings update) refuses a list without it, and the message says what is allowed
  assert.throws(() => validateSpaces(without), /Downloads, Music, Videos and Favorites can be renamed but not removed/)
  assert.equal(validateSpaces(fresh.spaces).length, 4)
})
test('"Use a sign-in when a site asks for one" is on by default, validates, and old settings files load with it on', () => {
  assert.equal(base().autoSignIn, true)
  assert.equal(loadSettings(base(), {concurrency: 2}).autoSignIn, true)
  assert.equal(mergeSettings(base(), {autoSignIn: false}).autoSignIn, false)
  assert.equal(mergeSettings(mergeSettings(base(), {autoSignIn: false}), {autoSignIn: 'no' as never}).autoSignIn, false)
  assert.equal(loadSettings(base(), {autoSignIn: false}).autoSignIn, false)
})
