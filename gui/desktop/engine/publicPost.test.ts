import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import {publicPostArgs, publicPostFromOutput, publicPostSupported} from './publicPost.js'
import {galleryLinksArgs} from './gallery.js'
import {readPublicPost} from '../../shared/core/gallery.js'
import type {YtDlpContext} from './ytdlp.js'

const cases = JSON.parse(fs.readFileSync(new URL('../../../fixtures/engine/gallery/public.json', import.meta.url), 'utf8')).cases as {
  name: string
  input: {url: string; answer: string}
}[]
const sample = cases.find((c) => c.name === 'carousel-of-photos')!.input

function output(answer: string) {
  const encoded = Buffer.from(answer, 'utf8').toString('base64')
  return `[Instagram] Extracting URL\n[Instagram] Dumping request to https://www.instagram.com/api/graphql\n${encoded}\n[Instagram] done`
}

const ctx: YtDlpContext = {
  binary: 'yt-dlp',
  env: {},
  network: {proxy: '', rateLimit: 0, fragments: 4} as YtDlpContext['network'],
  cookies: {file: 'C:\\cookies.txt'} as YtDlpContext['cookies'],
}

test('a post printed by the engine is read into its photos', () => {
  const media = publicPostFromOutput(output(sample.answer), sample.url)
  assert.ok(media?.gallery)
  assert.ok(media.gallery.count > 1)
  assert.ok(media.gallery.items.every((item) => item.url.startsWith('https://')))
})

test('output without the answer, or with an unusable one, reads as nothing', () => {
  assert.equal(readPublicPost('[Instagram] nothing here', sample.url, 200).kind, 'empty')
  assert.equal(readPublicPost(output('not json'), sample.url, 200).kind, 'empty')
})

test('only Instagram posts are tried signed out', () => {
  assert.equal(publicPostSupported('https://www.instagram.com/p/Dfe36o0ihqC/'), true)
  assert.equal(publicPostSupported('https://www.youtube.com/watch?v=abc'), false)
})

test('the signed-out look never carries cookies', () => {
  const args = publicPostArgs(ctx, 'https://www.instagram.com/p/Dfe36o0ihqC/')
  assert.ok(args.includes('--dump-pages') && args.includes('--skip-download'))
  assert.ok(!args.includes('--cookies') && !args.some((a) => a.includes('cookies')))
  assert.equal(args.at(-1), 'https://www.instagram.com/p/Dfe36o0ihqC/')
})

test('direct links are saved by their own names, without cookies', () => {
  const args = galleryLinksArgs({binary: 'gallery-dl', env: {}, network: ctx.network, cookies: ctx.cookies}, ['https://a.example/1.jpg', 'https://a.example/2.jpg'], 'C:\\out')
  assert.deepEqual(args.slice(-3), ['--', 'https://a.example/1.jpg', 'https://a.example/2.jpg'])
  assert.ok(args.includes('{filename}.{extension}') && args.includes('-D'))
  assert.ok(!args.includes('--cookies') && !args.includes('--cookies-from-browser'))
})
