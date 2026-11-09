import {test} from 'node:test'
import assert from 'node:assert/strict'
import {galleryArgs, galleryDownloadArgs, galleryInfo} from './gallery.js'
import {chosenRange, galleryContentsLabel, validateGalleryRequest} from '../../shared/core/gallery.js'
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

test('a gallery request names the chosen photos: checked against the post, an all-chosen list is dropped', () => {
  assert.deepEqual(validateGalleryRequest({type: 'gallery'}, 5), {type: 'gallery'})
  assert.deepEqual(validateGalleryRequest({type: 'gallery', items: [4, 1, 2]}, 5), {type: 'gallery', items: [4, 1, 2]})
  assert.deepEqual(validateGalleryRequest({type: 'gallery', items: [3, 1, 2]}, 3), {type: 'gallery'})
  for (const items of [[], [0], [6], [1, 1], [1.5], ['1'], 'all'])
    assert.throws(() => validateGalleryRequest({type: 'gallery', items}, 5), /at least one photo/)
  assert.throws(() => validateGalleryRequest({type: 'gallery', items: Array.from({length: 501}, (_, i) => i + 1)}), /at least one photo/)
})

test('the engine range is compact and sorted', () => {
  assert.equal(chosenRange([5, 1, 3, 4, 2, 7]), '1-5,7')
  assert.equal(chosenRange([2]), '2')
})

test('a download asks the engine to print the saved paths in UTF-8 (a non-Latin title otherwise prints as ???? and the file is "not found")', () => {
  const base = {binary: 'gallery-dl', env: {}, network: {fragments: 1, rateLimit: 0, proxy: ''}}
  const args = galleryDownloadArgs(base, POST, 'C:\\Keepfin\\Downloads\\مجلس', [1, 3])
  const option = args.indexOf('output.stdout=utf-8')
  assert.equal(args[option - 1], '-o')
  assert.deepEqual(args.slice(-6), ['-D', 'C:\\Keepfin\\Downloads\\مجلس', '--range', '1,3', '--', POST])
})

test('a carousel of videos and photos (captured from the engine, issue #49): videos keep their cover, the label names both', () => {
  const file = (num: number, video: boolean) => [
    3,
    video ? `ytdl:${POST}${num}.mp4` : `https://scontent.cdninstagram.com/p${num}.jpg`,
    {category: 'instagram', count: 4, num, extension: video ? 'mp4' : 'jpg', display_url: `https://scontent.cdninstagram.com/cover${num}.jpg`},
  ]
  const media = galleryInfo([[2, {category: 'instagram'}], file(1, true), file(2, true), file(3, false), file(4, true)], POST)
  const gallery = media.gallery!
  assert.deepEqual(gallery.items.map((i) => [i.photo, i.cover]), [
    [false, 'https://scontent.cdninstagram.com/cover1.jpg'],
    [false, 'https://scontent.cdninstagram.com/cover2.jpg'],
    [true, undefined],
    [false, 'https://scontent.cdninstagram.com/cover4.jpg'],
  ])
  assert.deepEqual(gallery.previews, ['https://scontent.cdninstagram.com/p3.jpg'])
  assert.equal(galleryContentsLabel(gallery), '1 photo, 3 videos')
  assert.equal(galleryContentsLabel(gallery, [3]), '1 photo')
  assert.equal(galleryContentsLabel(gallery, [1, 2]), '2 videos')
})

test('a Firefox profile reaches the gallery engine as firefox:<folder>; a browser alone stays a bare name', () => {
  const network = {fragments: 1, rateLimit: 0, proxy: ''}
  const base = {binary: 'gallery-dl', env: {}, network}
  const profile = String.raw`C:\Users\a\AppData\Roaming\Mozilla\Firefox\Profiles\x.default`
  assert.deepEqual(galleryArgs({...base, cookies: {browser: 'firefox', profile}}).slice(-2), ['--cookies-from-browser', `firefox:${profile}`])
  assert.deepEqual(galleryArgs({...base, cookies: {browser: 'firefox'}}).slice(-2), ['--cookies-from-browser', 'firefox'])
  assert.throws(() => galleryArgs({...base, cookies: {browser: 'chrome', profile}}))
})
