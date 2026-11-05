import assert from 'node:assert/strict'
import test from 'node:test'
import {discoveryEntry} from './ytdlp'

test('discovery keeps the largest provided thumbnail', () => {
  const entry = discoveryEntry(
    {
      ie_key: 'Youtube',
      id: '78wrful9cVU',
      url: 'https://music.youtube.com/watch?v=78wrful9cVU',
      thumbnails: [{url: 'https://i.ytimg.com/vi/78wrful9cVU/small.jpg'}, {url: 'https://i.ytimg.com/vi/78wrful9cVU/large.jpg'}],
    },
    1,
  )
  assert.equal(entry?.thumbnail, 'https://i.ytimg.com/vi/78wrful9cVU/large.jpg')
})

test('YouTube Music tracks without artwork get the video thumbnail', () => {
  const byId = discoveryEntry({ie_key: 'Youtube', id: 'nUsrYVxrDwI', url: 'https://music.youtube.com/watch?v=nUsrYVxrDwI', title: 'Song'}, 3)
  assert.equal(byId?.thumbnail, 'https://i.ytimg.com/vi/nUsrYVxrDwI/mqdefault.jpg')
  const byUrl = discoveryEntry({url: 'https://www.youtube.com/watch?v=nUsrYVxrDwI&list=PL1'}, 4)
  assert.equal(byUrl?.thumbnail, 'https://i.ytimg.com/vi/nUsrYVxrDwI/mqdefault.jpg')
  const short = discoveryEntry({url: 'https://youtu.be/nUsrYVxrDwI'}, 5)
  assert.equal(short?.thumbnail, 'https://i.ytimg.com/vi/nUsrYVxrDwI/mqdefault.jpg')
})

test('other sites never get a made-up YouTube thumbnail', () => {
  const entry = discoveryEntry({id: 'abcdefghijk', url: 'https://soundcloud.com/a/b'}, 1)
  assert.equal(entry?.thumbnail, undefined)
})
