// Writes the desktop screenshots used by the product website (keepfin-website `npm run images`): the library with
// music playing, the full player with timed lyrics, Activity and Keep in sync. Fixture data only; library artwork
// and song lengths are replaced with generated covers and a full-length quiet track so the pictures read well.
// Usage: SITE_OUT=<dir> node scripts/capture/capture-site.mjs   (default docs/screenshots/site)
import fs from 'node:fs'
import path from 'node:path'
import {spawnSync} from 'node:child_process'
import {createRequire} from 'node:module'
import {startServer, launch, openApp} from '../test/ui-fixtures/harness.mjs'
import {mediaDir} from '../test/ui-fixtures/media.mjs'

const out = process.env.SITE_OUT || 'docs/screenshots/site'
fs.mkdirSync(out, {recursive: true})

// Fixture songs are songSeconds plus a per-title offset (fake-bridge.js); Glass Harbour comes out at 4:01.
const SONG_SECONDS = 180
const GLASS_SECONDS = 241
const longTrack = path.join(mediaDir, `long-${GLASS_SECONDS}.mp3`)
if (!fs.existsSync(longTrack)) {
  const ffmpeg = createRequire(import.meta.url)('ffmpeg-static')
  const run = spawnSync(ffmpeg, ['-hide_banner', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i', `sine=frequency=220:duration=${GLASS_SECONDS}`, '-af', 'volume=0.05', '-ac', '1', '-c:a', 'libmp3lame', '-b:a', '32k', longTrack])
  if (run.status !== 0) throw new Error(`ffmpeg failed: ${run.stderr}`)
}
const trackBytes = fs.readFileSync(longTrack)

// Sample lyrics written for these screenshots.
const lyricLines = [
  {time: 52, text: 'Lights on the water, slow and low'},
  {time: 58, text: 'Every boat remembers where to go'},
  {time: 64, text: 'Hold the line until the morning breaks'},
  {time: 70, text: 'Glass harbour, keep what the tide forsakes'},
  {time: 77, text: 'Fog on the window, salt in the air'},
  {time: 83, text: 'Every song I saved is waiting there'},
  {time: 89, text: 'Hold the line, hold the line'},
  {time: 95, text: 'Glass harbour, all of it is mine'},
  {time: 102, text: 'Turn the tide and bring it home'},
]
// The pictures go on the website, so the app shows the version being released.
const APP_VERSION = JSON.parse(fs.readFileSync('package.json', 'utf8')).version.replace(/-.*$/, '')
const V2 = {libraryScenario: 'v2', simulate: false, lyricLines, songSeconds: SONG_SECONDS, appVersion: APP_VERSION}

// Generated artwork: album covers for songs, landscapes for videos. Deterministic per id.
const palettes = [
  ['#1b2a6b', '#4c7eff', '#a7c3ff', '#f4d35e', '#0b1020'],
  ['#3a0f2e', '#c2185b', '#ff8a65', '#ffd180', '#1a0612'],
  ['#0f3d3e', '#1f9e89', '#9be3c4', '#f6f7d7', '#06201f'],
  ['#2b1b4f', '#7c4dff', '#e1bee7', '#ffab91', '#120a24'],
  ['#3b2a10', '#e0892b', '#ffd59e', '#fff3e0', '#1c1206'],
  ['#102a43', '#2f80ed', '#56ccf2', '#e0f7fa', '#081521'],
  ['#2d0f0f', '#d64545', '#ffb199', '#ffe8d6', '#160707'],
]
const hash = (s) => [...s].reduce((h, c) => (Math.imul(h, 31) + c.charCodeAt(0)) >>> 0, 7)
function cover(id) {
  const h = hash(id)
  const p = palettes[h % palettes.length]
  const r = (n) => ((h >>> n) & 255) / 255
  if (id.startsWith('v-')) {
    return `<svg xmlns="http://www.w3.org/2000/svg" width="640" height="360" viewBox="0 0 640 360"><defs><linearGradient id="s" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${p[0]}"/><stop offset=".7" stop-color="${p[1]}"/><stop offset="1" stop-color="${p[2]}"/></linearGradient></defs><rect width="640" height="360" fill="url(#s)"/><circle cx="${180 + r(3) * 280}" cy="${120 + r(5) * 50}" r="${40 + r(7) * 26}" fill="${p[3]}" opacity=".92"/><path d="M0 260 L${120 + r(9) * 60} ${170 + r(11) * 40} L${260 + r(2) * 60} 240 L${400 + r(4) * 60} ${160 + r(6) * 50} L640 250 V360 H0Z" fill="${p[4]}" opacity=".85"/><path d="M0 300 L${200 + r(8) * 80} 250 L${420 + r(10) * 80} 290 L640 270 V360 H0Z" fill="${p[4]}"/></svg>`
  }
  const cx = 110 + r(3) * 100
  const cy = 100 + r(5) * 80
  return `<svg xmlns="http://www.w3.org/2000/svg" width="400" height="400" viewBox="0 0 400 400"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="${p[0]}"/><stop offset="1" stop-color="${p[1]}"/></linearGradient><radialGradient id="o" cx=".5" cy=".5" r=".5"><stop offset="0" stop-color="${p[3]}"/><stop offset="1" stop-color="${p[2]}"/></radialGradient></defs><rect width="400" height="400" fill="url(#g)"/><circle cx="${cx}" cy="${cy}" r="${70 + r(7) * 40}" fill="url(#o)"/>${[0, 1, 2, 3].map((i) => `<rect x="0" y="${250 + i * 30}" width="400" height="${14 + i * 2}" fill="${p[4]}" opacity="${0.35 + i * 0.15}"/>`).join('')}<circle cx="${cx}" cy="${cy}" r="${150 + r(9) * 30}" fill="none" stroke="${p[2]}" stroke-opacity=".35" stroke-width="2"/></svg>`
}

const srv = await startServer()
const browser = await launch()
async function open(theme, fake, size = {width: 1440, height: 900}) {
  const page = await openApp(browser, srv.url, {...size, scale: 90, theme, fake})
  await page.context().route(/\/__media\/art\//, (route) => {
    const id = decodeURIComponent(route.request().url().split('/__media/art/')[1].split(/[/?]/)[0])
    route.fulfill({status: 200, contentType: 'image/svg+xml', body: cover(id)})
  })
  await page.context().route(/\/__media\/item\/a-/, (route) => {
    const range = /bytes=(\d+)-(\d*)/.exec(route.request().headers().range || '')
    if (!range) return route.fulfill({status: 200, contentType: 'audio/mpeg', headers: {'accept-ranges': 'bytes'}, body: trackBytes})
    const start = Number(range[1])
    const end = range[2] ? Number(range[2]) : trackBytes.length - 1
    route.fulfill({status: 206, contentType: 'audio/mpeg', headers: {'accept-ranges': 'bytes', 'content-range': `bytes ${start}-${end}/${trackBytes.length}`}, body: trackBytes.subarray(start, end + 1)})
  })
  // Routes apply to requests made from now on; reload so artwork already requested is replaced too.
  await page.reload()
  await page.getByRole('navigation', {name: 'Main navigation'}).waitFor({timeout: 30_000})
  return page
}
const nav = (page, name) => page.getByRole('navigation', {name: 'Main navigation'}).getByRole('button', {name}).click()
const openSpace = async (page, name) => {
  await nav(page, 'Library')
  await page.getByRole('navigation', {name: 'Your library'}).getByRole('button', {name: new RegExp('^' + name)}).click()
}
const audio = (page) => page.evaluate(() => window.__keepfinTest.audio().active.currentTime)
const playAt = async (page, title, seconds) => {
  await page.getByRole('button', {name: new RegExp('^' + title)}).click()
  const bar = page.getByRole('region', {name: 'Now playing'})
  await bar.waitFor()
  await page.waitForFunction(() => window.__keepfinTest.audio().active.duration > 200)
  await page.evaluate((t) => { window.__keepfinTest.audio().active.currentTime = t }, seconds)
  await page.waitForFunction((t) => window.__keepfinTest.audio().active.currentTime > t + 0.3, seconds)
  await bar.getByRole('button', {name: 'Pause'}).click()
  return audio(page)
}

const scenes = {
  // Music space: album grid with a song playing in the bar.
  async 'desktop-music'(page) {
    await openSpace(page, 'Music')
    await page.getByRole('tab', {name: /^Albums/}).click()
    await playAt(page, 'Glass Harbour', 71).catch(async () => {
      await page.getByRole('tab', {name: /^Songs/}).click()
      await playAt(page, 'Glass Harbour', 71)
      await page.getByRole('tab', {name: /^Albums/}).click()
    })
  },
  // Library home: shelves and spaces.
  async 'desktop-library-home'(page) {
    await nav(page, 'Library')
    await page.getByRole('region', {name: 'Continue watching'}).first().waitFor()
  },
  // Full player on the lyrics, paused mid-song so the current line is centred.
  async 'desktop-player-lyrics'(page) {
    await openSpace(page, 'Music')
    await playAt(page, 'Glass Harbour', 71)
    await page.getByRole('region', {name: 'Now playing'}).getByRole('button', {name: /Open the player/}).click()
    const full = page.getByRole('dialog', {name: 'Now playing'})
    await full.waitFor()
    await full.getByRole('tab', {name: 'Lyrics'}).click().catch(() => {})
    await full.getByRole('button', {name: lyricLines[3].text}).waitFor()
  },
  // Activity panel open with seeded entries.
  async 'desktop-activity'(page) {
    await page.locator('.act-bell').click()
    await page.getByRole('dialog', {name: 'Activity'}).waitFor()
  },
  // A playlist review with Keep in sync switched on, filtered so the whole card fits.
  async 'desktop-keep-in-sync'(page) {
    await page.locator('textarea').first().fill('https://www.youtube.com/playlist?list=PLfocus')
    await page.getByRole('list', {name: 'Collection entries'}).waitFor()
    await page.getByText('Select all', {exact: true}).click()
    const row = page.getByRole('region', {name: 'Keep in sync'})
    await row.getByRole('switch', {name: 'Keep in sync'}).click()
    await row.getByLabel('How often to check for new ones').waitFor()
    await page.getByPlaceholder(/^Filter \d+ entries/).fill('Velvet')
    await page.evaluate(() => window.scrollTo(0, 0))
    await page.locator('main, [role=main]').first().evaluate((el) => el.scrollTo?.(0, 0)).catch(() => {})
  },
}
Object.assign(scenes, {
  // A 4K video review: qualities with sizes.
  async 'desktop-review-video'(page) {
    await page.locator('textarea').first().fill('https://www.youtube.com/watch?v=lofoten8k')
    await page.getByRole('radiogroup', {name: 'Video quality'}).waitFor()
  },
  // A YouTube Music song: audio formats with the matched cover.
  async 'desktop-review-audio'(page) {
    await page.locator('textarea').first().fill('https://music.youtube.com/watch?v=tidal88')
    await page.getByRole('radiogroup', {name: 'Audio quality'}).waitFor()
  },
  // Downloads with a playlist kept together, expanded to show its songs.
  async 'desktop-downloads'(page) {
    await page.getByRole('button', {name: /^(Show|Hide) \d+ items? in Road Trip Mix/}).first().waitFor()
    const show = page.getByRole('button', {name: /^Show \d+ items? in Road Trip Mix/})
    if (await show.count()) await show.first().click() // a running playlist is already open
    await page.waitForTimeout(500)
    // Expanding scrolls the group into view; start from the top so the link box and filters show too.
    await page.evaluate(() => document.querySelectorAll('*').forEach((el) => { if (el.scrollTop > 0) el.scrollTop = 0 }))
  },
})
const fakeFor = {
  'desktop-activity': {scenario: 'groups', libraryScenario: 'v2', activity: 'seed', simulate: false, appVersion: APP_VERSION},
  'desktop-review-video': {libraryScenario: 'v2', simulate: true, appVersion: APP_VERSION},
  'desktop-review-audio': {libraryScenario: 'v2', simulate: true, appVersion: APP_VERSION},
  'desktop-downloads': {scenario: 'groups', libraryScenario: 'v2', simulate: true, appVersion: APP_VERSION},
}

const only = process.argv.slice(2)
for (const [name, scene] of Object.entries(scenes)) {
  if (only.length && !only.includes(name)) continue
  for (const theme of ['dark', 'light']) {
    const page = await open(theme, fakeFor[name] || V2)
    await scene(page)
    await page.waitForTimeout(1200)
    await page.screenshot({path: `${out}/${name}-${theme}.png`})
    await page.context().close()
  }
  console.log('captured', name)
}
await browser.close()
await srv.close()
console.log('site screenshots written to', out)
