import {test} from 'node:test'
import assert from 'node:assert/strict'
import {cardSize, defaultLayout, moveSection, readCardPrefs, readLayout, stepSection, styleSection, toggleSection} from './layout.js'

test('layout adapter: nothing saved gives the defaults, with which sections can switch style', () => {
  const home = readLayout('home', undefined)
  assert.deepEqual(home, defaultLayout('home'))
  assert.equal(home.sections[0].id, 'downloading')
  assert.equal(home.sections.find((s) => s.id === 'jump-back-in')?.canStyle, false)
  assert.equal(home.sections.find((s) => s.id === 'continue')?.canStyle, true)
})

test('layout adapter: edits come back as whole layouts and survive a save', () => {
  let library = readLayout('library', undefined)
  library = toggleSection(library, 'photos')
  library = styleSection(library, 'albums', 'grid')
  library = moveSection(library, 'artists', 'playlists', false)
  library = stepSection(library, 'collections', -1)
  assert.equal(library.sections.find((s) => s.id === 'photos')?.visible, false)
  assert.equal(library.sections.find((s) => s.id === 'albums')?.style, 'grid')
  assert.deepEqual(library.sections.slice(0, 3).map((s) => s.id), ['spaces', 'artists', 'playlists'])
  assert.deepEqual(readLayout('library', JSON.parse(JSON.stringify(library))), library)
})

test('layout adapter: card sizes and prefs', () => {
  assert.equal(cardSize('video', 360, 1), 200)
  assert.ok(cardSize('square', 1400, 1) > cardSize('square', 900, 1))
  assert.deepEqual(readCardPrefs(undefined), {scale: 1, text: 'full'})
  assert.deepEqual(readCardPrefs({scale: 0.2, text: 'title'}), {scale: 0.8, text: 'title'})
})
