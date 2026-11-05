// Real Electron playback smoke for the library (run after `npm run gui:build`):
//   node scripts/test/library-smoke.mjs
// Generates an MP4, an MKV and an MP3 with the bundled ffmpeg, imports them into spaces through the real
// IPC, then plays them with <video>/<audio> elements over keepfin-media:// and checks readiness and seeking.
import {_electron as electron} from '@playwright/test'
import assert from 'node:assert/strict'
import {spawnSync} from 'node:child_process'
import fs from 'node:fs/promises'
import {createRequire} from 'node:module'
import os from 'node:os'
import path from 'node:path'

const require = createRequire(import.meta.url)
const ffmpeg = require('ffmpeg-static')
const root = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-library-smoke-'))
const userData = path.join(root, 'profile')
const media = path.join(root, 'media')
await fs.mkdir(media, {recursive: true})

function generate(name, args) {
  const file = path.join(media, name)
  const result = spawnSync(ffmpeg, ['-hide_banner', '-loglevel', 'error', '-y', ...args, file], {encoding: 'utf8'})
  assert.equal(result.status, 0, `ffmpeg failed for ${name}: ${result.stderr}`)
  return file
}
const video = ['-f', 'lavfi', '-i', 'testsrc=duration=6:size=320x240:rate=25', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=6']
const mp4 = generate('clip.mp4', [...video, '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-g', '25', '-c:a', 'aac', '-shortest'])
const mkv = generate('clip-mkv.mkv', [...video, '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-g', '25', '-c:a', 'aac', '-shortest'])
const mp3 = generate('tone.mp3', ['-f', 'lavfi', '-i', 'sine=frequency=330:duration=8', '-metadata', 'title=Tone', '-metadata', 'artist=Smoke', '-c:a', 'libmp3lame'])

const env = {...process.env}
delete env.ELECTRON_RUN_AS_NODE
// the library without a Keepfin folder (KEEPFIN_FOLDER=0); the folder library is covered by smoke-desktop.mjs and the store tests
const app = await electron.launch({args: ['.', `--user-data-dir=${userData}`], env: {...env, KEEPFIN_FOLDER: '0'}, timeout: 60_000})
try {
  const page = await app.firstWindow()
  const errors = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.getByRole('navigation', {name: 'Main navigation'}).waitFor()
  const request = (body) => page.evaluate((payload) => window.keepfin.request(payload), body)

  // Point the built-in spaces at the temp directory.
  const state = await request({op: 'state'})
  const spaces = state.settings.library.spaces.map((space) => ({...space, folder: path.join(root, 'spaces', space.id)}))
  await request({op: 'updateSettings', settings: {library: {spaces}}})

  const imported = await request({op: 'libraryImport', paths: [mp4, mkv], space: 'videos', importMode: 'copy'})
  assert.equal(imported.added.length, 2, JSON.stringify(imported))
  const music = await request({op: 'libraryImport', paths: [mp3], space: 'music', importMode: 'copy'})
  assert.equal(music.added.length, 1, JSON.stringify(music))
  const duplicate = await request({op: 'libraryImport', paths: [mp3], space: 'music', importMode: 'copy'})
  assert.equal(duplicate.duplicates.length, 1)

  // Metadata and posters arrive asynchronously through the library push.
  let items = []
  for (let i = 0; i < 100; i++) {
    items = (await request({op: 'libraryState'})).items
    if (items.length === 3 && items.every((item) => item.duration) && items.filter((item) => item.kind === 'video').every((item) => item.hasArt)) break
    await new Promise((resolve) => setTimeout(resolve, 100))
  }
  const byName = (name) => items.find((item) => item.path.endsWith(name))
  const [mp4Item, mkvItem, mp3Item] = [byName('clip.mp4'), byName('clip-mkv.mkv'), byName('tone.mp3')]
  assert.ok(mp4Item && mkvItem && mp3Item, JSON.stringify(items.map((item) => item.path)))
  assert.equal(mp3Item.title, 'Tone')
  assert.equal(mp3Item.artist, 'Smoke')
  assert.ok(mp4Item.duration > 5 && mp4Item.hasArt, 'video duration and poster')

  const prepareMp4 = await request({op: 'libraryPrepare', id: mp4Item.id})
  assert.equal(prepareMp4.remuxed, false)
  const prepareMkv = await request({op: 'libraryPrepare', id: mkvItem.id})
  assert.equal(prepareMkv.remuxed, true, JSON.stringify(prepareMkv))
  assert.match(prepareMkv.url, /^keepfin-media:\/\/prepared\//)

  const check = (tag, url) =>
    page.evaluate(
      ([tagName, source]) =>
        new Promise((resolve, reject) => {
          const element = document.createElement(tagName)
          element.preload = 'auto'
          element.muted = true
          document.body.append(element)
          const timer = setTimeout(() => reject(new Error(`timeout for ${source}: ${element.error?.message ?? 'no error'} readyState=${element.readyState}`)), 20000)
          const seek = () => {
            element.addEventListener('seeked', () => {
              clearTimeout(timer)
              const result = {readyState: element.readyState, time: element.currentTime, duration: element.duration}
              element.remove()
              resolve(result)
            }, {once: true})
            element.currentTime = element.duration / 2
          }
          element.addEventListener('error', () => reject(new Error(`media error for ${source}: ${element.error?.message}`)), {once: true})
          element.addEventListener('loadeddata', () => (element.readyState >= 2 ? seek() : undefined), {once: true})
          element.src = source
        }),
      [tag, url],
    )
  const cases = [
    ['video', `keepfin-media://item/${mp4Item.id}`, 'mp4 over the protocol'],
    ['video', prepareMkv.url, 'remuxed mkv'],
    ['audio', `keepfin-media://item/${mp3Item.id}`, 'mp3 over the protocol'],
  ]
  for (const [tag, url, label] of cases) {
    const result = await check(tag, url)
    assert.ok(result.readyState >= 2, `${label}: readyState ${result.readyState}`)
    assert.ok(Math.abs(result.time - result.duration / 2) < 1, `${label}: seeked to ${result.time} of ${result.duration}`)
  }

  // Art loads over the protocol (the CSP allows img-src, not fetch); unknown ids and paths do not.
  const loads = (url) =>
    page.evaluate(
      (source) =>
        new Promise((resolve) => {
          const image = new Image()
          image.onload = () => resolve(image.naturalWidth > 0)
          image.onerror = () => resolve(false)
          image.src = source
        }),
      url,
    )
  assert.equal(await loads(`keepfin-media://art/${mp4Item.id}`), true, 'poster loads')
  assert.equal(await loads(`keepfin-media://art/${mp3Item.id}`), false, 'mp3 without cover art is a 404')
  assert.equal(await loads('keepfin-media://art/C%3A%5CWindows%5Cweb%5Cscreen%5Cimg100.jpg'), false)

  assert.deepEqual(errors, [])
  console.log('PASS: imported into spaces; mp4, remuxed mkv and mp3 play over keepfin-media:// with readyState >= 2 and working seek; poster and confinement checks')
} finally {
  await app.close()
  await fs.rm(root, {recursive: true, force: true})
}
