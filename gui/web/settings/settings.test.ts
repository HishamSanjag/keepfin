import assert from 'node:assert/strict'
import {test} from 'node:test'
import {deriveAttention} from './attention'
import {categories, resolveSection} from './sections'
import {entryPath, searchSettings, settingsIndex} from './searchIndex'

test('the rail has the nine categories in the planned order, with the same icons as the phone', () => {
  assert.deepEqual(
    categories.map((item) => item.label),
    ['Downloads', 'Library & playback', 'Storage & copies', 'Devices', 'Appearance', 'Notifications', 'Help', 'Support Keepfin', 'About Keepfin'],
  )
  assert.deepEqual(
    categories.map((item) => item.icon),
    ['download', 'video-library', 'folder', 'devices', 'palette', 'bell', 'help', 'heart', 'info'],
  )
  assert.equal(new Set(categories.map((item) => item.icon)).size, categories.length, 'an icon each')
})

test('old section ids redirect to their new category and group', () => {
  assert.deepEqual(resolveSection('general'), {category: 'appearance', anchor: undefined})
  assert.deepEqual(resolveSection('performance'), {category: 'downloads', anchor: 'speed'})
  assert.deepEqual(resolveSection('accounts'), {category: 'downloads', anchor: 'signin'})
  assert.deepEqual(resolveSection('network'), {category: 'downloads', anchor: 'proxy'})
  assert.deepEqual(resolveSection('updates'), {category: 'about', anchor: 'updates'})
  assert.deepEqual(resolveSection('guide'), {category: 'help', anchor: 'guide'})
  assert.deepEqual(resolveSection('support'), {category: 'support', anchor: undefined})
  for (const id of ['downloads', 'library', 'storage', 'devices', 'notifications', 'help', 'support', 'about']) assert.equal(resolveSection(id).category, id)
  assert.equal(resolveSection('nonsense').category, 'downloads')
  assert.equal(resolveSection('constructor').category, 'downloads')
})

test('an explicit anchor wins over the redirect anchor', () => {
  assert.deepEqual(resolveSection('performance', 'turbo'), {category: 'downloads', anchor: 'turbo'})
})

test('every index entry belongs to a category and has a unique anchor', () => {
  const ids = new Set(categories.map((item) => item.id))
  for (const entry of settingsIndex) assert.ok(ids.has(entry.category), entry.anchor)
  assert.equal(new Set(settingsIndex.map((entry) => entry.anchor)).size, settingsIndex.length)
})

test('searching Proxy finds Downloads > Speed and connections', () => {
  const [first] = searchSettings('Proxy')
  assert.equal(first.anchor, 'proxy')
  assert.equal(first.category, 'downloads')
  assert.equal(entryPath(first), 'Downloads › Speed and connections')
})

test('search matches keywords, ignores case and apostrophes, and needs every word', () => {
  assert.equal(searchSettings('cookies')[0].anchor, 'signin')
  assert.equal(searchSettings('whats new')[0].anchor, 'whats-new')
  assert.equal(searchSettings('DARK')[0].anchor, 'theme')
  assert.equal(searchSettings('zzzz').length, 0)
  assert.equal(searchSettings('   ').length, 0)
  assert.equal(searchSettings('proxy zzzz').length, 0)
})

test('Start over is found by its keywords on About Keepfin and on Storage & copies', () => {
  for (const word of ['reset', 'delete everything', 'erase', 'wipe', 'clean']) {
    const found = searchSettings(word, 20).filter((entry) => entry.title === 'Start over')
    assert.deepEqual(found.map((entry) => entry.category).sort(), ['about', 'storage'], word)
  }
  assert.equal(settingsIndex.find((entry) => entry.anchor === 'shortcuts')?.category, 'help')
  assert.equal(settingsIndex.find((entry) => entry.anchor === 'support')?.category, 'support')
})

test('a title match ranks above a keyword match', () => {
  assert.equal(searchSettings('crossfade')[0].anchor, 'crossfade')
  assert.equal(searchSettings('sound')[0].anchor, 'notify-sound')
})

test('no search keyword names an external tool', () => {
  const text = JSON.stringify(settingsIndex).toLowerCase()
  for (const name of ['yt-dlp', 'ffmpeg', 'gallery-dl', 'aria2c']) assert.ok(!text.includes(name), name)
})

test('the attention list is empty when nothing is wrong', () => {
  assert.deepEqual(deriveAttention({}), [])
})

test('a blocking firewall, missing files, a failed copy and a ready update each add one line with a place to fix it', () => {
  const items = deriveAttention({
    devices: {available: true, enabled: true, firewall: {blocked: true, category: 'Public'}} as never,
    overview: {health: {missing: 3, checking: false}} as never,
    copies: {available: true, places: [{id: 'p', problem: {count: 2}}], review: [{id: 'b', kind: 'conflict'}], reviewMore: 0} as never,
    update: {phase: 'ready', version: '1.0.1'} as never,
  })
  const places = items.map((item) => [item.category, item.anchor])
  assert.deepEqual(places, [
    ['devices', 'status'],
    ['storage', 'files'],
    ['storage', 'copies'],
    ['storage', 'review'],
    ['about', 'updates'],
  ])
})

test('devices that are off or not blocked show nothing', () => {
  assert.deepEqual(deriveAttention({devices: {available: true, enabled: false, firewall: {blocked: true, category: 'Public'}} as never}), [])
  assert.deepEqual(deriveAttention({devices: {available: true, enabled: true, firewall: {blocked: false, category: 'Private'}} as never}), [])
  assert.deepEqual(deriveAttention({devices: {available: true, enabled: true} as never}), [])
  const [item] = deriveAttention({devices: {available: true, enabled: true, firewall: {blocked: true, category: 'Public'}} as never})
  assert.match(item.text, /Windows Firewall/)
  assert.ok(!/private/i.test(item.text), 'no private-network advice any more')
})
