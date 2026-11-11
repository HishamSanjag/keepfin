import assert from 'node:assert/strict'
import test from 'node:test'
import {pagesFor} from './whatsNewPages'

const manifests = {
  '../../content/whats-new/1.1.0/pages.json': {
    version: '1.1.0',
    pages: [
      {id: 'photos', windows: {title: 'Photos', text: 'Save photos.'}, android: {title: 'A', text: 'B'}},
      {id: 'phone-only', android: {title: 'A', text: 'B'}},
      {id: 'mixes', windows: {title: 'Mixes', text: 'Home mixes.'}},
      {windows: {title: 'No id', text: 'x'}},
    ],
  },
  '../../content/whats-new/1.0.2/pages.json': {pages: [{id: 'old', windows: {title: 'Old', text: 'Old.'}}]},
}
const images = {
  '../../content/whats-new/1.1.0/photos-windows.png': '/photos.png',
  '../../content/whats-new/1.1.0/photos-android.png': '/photos-a.png',
  '../../content/whats-new/1.0.2/mixes-windows.png': '/other.png',
}

test('keeps pages with a Windows entry, in order, with their picture', () => {
  assert.deepEqual(pagesFor('1.1.0', manifests, images), [
    {id: 'photos', title: 'Photos', text: 'Save photos.', image: '/photos.png'},
    {id: 'mixes', title: 'Mixes', text: 'Home mixes.', image: undefined},
  ])
})

test('a leading v is ignored and other versions are separate', () => {
  assert.equal(pagesFor('v1.0.2', manifests, images)[0].id, 'old')
})

test('a version without content has no pages', () => {
  assert.deepEqual(pagesFor('0.4.0', manifests, images), [])
  assert.deepEqual(pagesFor('1.1.0', {'x/whats-new/1.1.0/pages.json': {pages: 'bad'}}, {}), [])
})
