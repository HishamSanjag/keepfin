import {test} from 'node:test'
import assert from 'node:assert/strict'
import {defaultLayout, toggleSection} from '../../shared/core/layout.js'
import {getCardPrefs, getPageLayout, resetPageLayout, setCardScale, setCardText, stepCardScale, updatePageLayout} from './layoutStore.js'

test('card size stays between 80% and 125% and steps land on whole hundredths', () => {
  setCardScale(1)
  stepCardScale(0.05)
  stepCardScale(0.05)
  stepCardScale(0.05)
  assert.equal(getCardPrefs().scale, 1.15)
  setCardScale(9)
  assert.equal(getCardPrefs().scale, 1.25)
  for (let i = 0; i < 20; i++) stepCardScale(-0.05)
  assert.equal(getCardPrefs().scale, 0.8)
})

test('text under cards is kept with the size', () => {
  setCardScale(1.1)
  setCardText('title')
  assert.deepEqual(getCardPrefs(), {scale: 1.1, text: 'title'})
})

test('a page layout edit is kept, and reset brings the defaults back', () => {
  updatePageLayout('home', (layout) => toggleSection(layout, 'mixes'))
  assert.equal(getPageLayout('home').sections.find((section) => section.id === 'mixes')?.visible, false)
  resetPageLayout('home')
  assert.deepEqual(getPageLayout('home'), defaultLayout('home'))
})
