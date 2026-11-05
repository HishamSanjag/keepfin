import {test} from 'node:test'
import assert from 'node:assert/strict'
import {galleryInfo} from './gallery.js'
import {EngineError} from './ytdlp.js'

const POST = 'https://www.instagram.com/p/DfW56Yoim48/'

test('a post the site shows only to signed-in visitors asks for a sign-in instead of saying there are no images', () => {
  // The gallery engine exits 0 and reports the failure as a [-1, {error, message}] line in its JSON output.
  const output = [[-1, {error: 'AbortExtraction', message: 'HTTP redirect to login page (https://www.instagram.com/accounts/login/)'}]]
  assert.throws(
    () => galleryInfo(output, POST),
    (error: unknown) => error instanceof EngineError && error.action === 'signIn' && /sign-in/i.test(error.message),
  )
})

test('output with neither images nor an error still says no images were found', () => {
  assert.throws(() => galleryInfo([[2, {category: 'instagram'}]], POST), /No images were found/)
})

test('images are read as before', () => {
  const output = [
    [2, {category: 'instagram', description: 'Post by royanews'}],
    [3, 'https://scontent.cdninstagram.com/a.jpg', {category: 'instagram', count: 2, extension: 'jpg', num: 1}],
    [3, 'https://scontent.cdninstagram.com/b.jpg', {category: 'instagram', count: 2, extension: 'jpg', num: 2}],
  ]
  const media = galleryInfo(output, POST)
  assert.equal(media.gallery?.count, 2)
})
