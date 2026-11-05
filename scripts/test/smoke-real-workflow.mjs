import {_electron as electron, expect} from '@playwright/test'
import {spawnSync} from 'node:child_process'
import {createServer} from 'node:http'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import os from 'node:os'
import ffmpeg from 'ffmpeg-static'

// Exercise the actual renderer -> trusted IPC -> DownloadManager -> yt-dlp/ffmpeg.
// All source media, profile, output files and screenshots live in an isolated temp directory.
const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-real-ui-'))
const profile = path.join(dir, 'profile'),
  output = path.join(dir, 'downloads')
await fs.mkdir(profile)
const source = path.join(dir, 'fixture.mp4')
const ff = spawnSync(
  ffmpeg,
  [
    '-y',
    '-f',
    'lavfi',
    '-i',
    'testsrc=size=320x180:rate=10',
    '-f',
    'lavfi',
    '-i',
    'sine=frequency=440',
    '-t',
    '2',
    '-c:v',
    'libx264',
    '-pix_fmt',
    'yuv420p',
    '-c:a',
    'aac',
    source,
  ],
  {encoding: 'utf8'},
)
assert.equal(ff.status, 0, ff.stderr)
const body = await fs.readFile(source)
const server = createServer((req, res) => {
  res.writeHead(200, {
    'Content-Type': 'video/mp4',
    'Content-Length': body.length,
  })
  res.end(req.method === 'HEAD' ? undefined : body)
})
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
const url = `http://127.0.0.1:${server.address().port}/fixture.mp4`
let app
try {
  app = await electron.launch({args: ['.', `--user-data-dir=${profile}`], env: {...process.env, KEEPFIN_FOLDER: '0'}, timeout: 60_000})
  const page = await app.firstWindow()
  const errors = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.setDefaultTimeout(120_000)
  const nav = page.getByRole('navigation', {name: 'Main navigation'})
  await nav.waitFor()
  const request = (req) => page.evaluate((req) => window.keepfin.request(req), req)
  const state = await request({op: 'state'})
  assert.equal(state.platform, 'desktop')
  assert.deepEqual(state.jobs, [])
  assert.equal(await page.evaluate(() => typeof window.require), 'undefined')
  await request({op: 'updateSettings', settings: {outDir: output, organization: 'flat', filenameStyle: 'title'}})

  const field = page.locator('textarea').first()
  const finished = async (count) => {
    await expect
      .poll(async () => (await request({op: 'state'})).jobs.filter((job) => ['completed', 'failed'].includes(job.status)).length, {timeout: 180_000})
      .toBe(count)
    return (await request({op: 'state'})).jobs
  }

  // 1. Video: inspect the locally served mp4 through the UI and download it.
  await field.fill(url)
  await page.locator('.download-btn').waitFor()
  await page.getByRole('radiogroup', {name: 'Video quality'}).waitFor()
  await page.locator('.download-btn').click()
  let jobs = await finished(1)
  const video = jobs.find((job) => job.kind === 'video')
  assert.ok(video, 'a video job exists')
  assert.equal(video.status, 'completed', video.error)
  assert.equal(path.extname(video.file), '.mp4')
  assert.ok(video.file.startsWith(output))
  assert.ok((await fs.stat(video.file)).size > 0)

  // 2. Audio: same link as MP3 via Audio.
  await field.fill('')
  await field.fill(url)
  await page.locator('.download-btn').waitFor()
  await page.getByRole('radiogroup', {name: 'What to keep'}).getByRole('radio', {name: 'Audio', exact: true}).click()
  await page.getByRole('radio', {name: /^High, MP3/}).click()
  await page.locator('.download-btn').click()
  jobs = await finished(2)
  const audio = jobs.find((job) => job.kind === 'audio')
  assert.ok(audio, 'an audio job exists')
  assert.equal(audio.status, 'completed', audio.error)
  assert.equal(path.extname(audio.file), '.mp3')
  assert.ok((await fs.stat(audio.file)).size > 0)

  // 3. Library lists both; history survives a restart; clearing history keeps the files.
  const libraryCount = (target) => target.evaluate(async () => (await window.keepfin.request({op: 'libraryState'})).items.length)
  await expect.poll(() => libraryCount(page)).toBe(2)
  await app.close()
  app = await electron.launch({args: ['.', `--user-data-dir=${profile}`], env: {...process.env, KEEPFIN_FOLDER: '0'}, timeout: 60_000})
  const again = await app.firstWindow()
  again.on('pageerror', (error) => errors.push(error.message))
  await again.getByRole('navigation', {name: 'Main navigation'}).getByRole('button', {name: 'Downloads'}).click()
  await expect.poll(() => libraryCount(again)).toBe(2)
  await again.getByRole('radio', {name: /^Finished/}).click()
  await again.getByRole('button', {name: 'Clear history'}).click()
  await again.getByRole('button', {name: 'Clear history'}).last().click()
  await expect.poll(async () => (await again.evaluate(() => window.keepfin.request({op: 'state'}))).jobs.length).toBe(0)
  for (const file of [video.file, audio.file]) assert.ok((await fs.stat(file)).size > 0, 'Clear history must keep saved files')
  assert.deepEqual(errors, [])
  console.log('PASS: real renderer/IPC/yt-dlp video and MP3 through the v2 UI; Library, restart persistence, clear history keeps files.')
} finally {
  await app?.close()
  if (server.listening) await new Promise((resolve) => server.close(resolve))
  // This exact directory was created by mkdtemp above and contains disposable test data only.
  await fs.rm(dir, {recursive: true, force: true})
}
