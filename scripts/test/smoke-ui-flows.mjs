// Renderer flow tests against the synthetic v2 bridge (scripts/test/ui-fixtures/fake-bridge.js).
// Verifies what the UI SENDS (FormatRequest/options/ops) and what it shows. Fixtures never certify
// provider behaviour; real engine behaviour is covered by engine:test and gui:real-test.
import assert from 'node:assert/strict'
import {startServer, launch, openApp} from './ui-fixtures/harness.mjs'
import {registerLibrarySuite} from './ui-fixtures/suite-library.mjs'
import {registerSpacesV2Suite} from './ui-fixtures/suite-spaces-v2.mjs'
import {registerGuideSuite} from './ui-fixtures/suite-guide.mjs'
import {registerActivitySuite} from './ui-fixtures/suite-activity.mjs'
import {registerRefreshSuite} from './ui-fixtures/suite-refresh.mjs'
import {registerBeta2Suite} from './ui-fixtures/suite-beta2.mjs'
import {registerStorageSuite} from './ui-fixtures/suite-storage.mjs'
import {registerCopiesSuite} from './ui-fixtures/suite-copies.mjs'
import {registerAppIconSuite} from './ui-fixtures/suite-app-icon.mjs'
import {registerAwaySuite} from './ui-fixtures/suite-away.mjs'
import {registerDevicesSuite, registerDownloadOnSuite} from './ui-fixtures/suite-devices.mjs'
import {registerCombineSuite} from './ui-fixtures/suite-combine.mjs'
import {registerWhereSuite} from './ui-fixtures/suite-where.mjs'
import {registerSettingsSuite} from './ui-fixtures/suite-settings.mjs'
import {registerAtelierSuite} from './ui-fixtures/suite-atelier.mjs'
import {registerUiFixesSuite} from './ui-fixtures/suite-ui-fixes.mjs'
import {registerPlayerStylesSuite} from './ui-fixtures/suite-player-styles.mjs'
import {registerSongVideosSuite} from './ui-fixtures/suite-song-videos.mjs'
import {registerPreviewSuite} from './ui-fixtures/suite-preview.mjs'
import {registerHomeFollowSuite} from './ui-fixtures/suite-home-follow.mjs'

const YT = 'https://www.youtube.com/watch?v=lofoten8k'
const MUSIC = 'https://music.youtube.com/watch?v=tidal88'
const SPOT = 'https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC'
const SPOT_LIST = 'https://open.spotify.com/playlist/37i9dQZF1DX4sWSpwq3LiO'
const GALLERY = 'https://www.instagram.com/p/C9harbour/'
const BAD = 'https://example.com/not-media'
const VIMEO = 'https://vimeo.com/824512'

const only = process.env.ONLY
const srv = await startServer()
const browser = await launch()
let failed = 0
const results = []
// A slow CI runner can miss an 8 s wait: a timeout gets one fresh attempt in a new page, like the layout suite.
// Failed assertions and page errors never retry.
async function test(name, fn, opts = {}) {
  if (only && !name.includes(only)) return
  for (let attempt = 1; attempt <= 2; attempt++) {
    const page = await openApp(browser, srv.url, opts)
    try {
      await fn(page)
      if (page.errors.length) throw new Error('console errors: ' + page.errors.join(' | '))
      results.push(`ok   ${name}`)
      return
    } catch (error) {
      if (attempt === 1 && error?.name === 'TimeoutError') {
        console.log(`retry ${name}: ${String(error.message).split('\n')[0]}`)
        continue
      }
      failed++
      results.push(`FAIL ${name}\n     ${String(error.stack || error).split('\n').slice(0, 5).join('\n     ')}`)
      if (process.env.SHOT_FAIL) await page.screenshot({path: `${process.env.SHOT_FAIL}/${name.replace(/\W+/g, '-')}.png`}).catch(() => {})
      return
    } finally {
      await page.context().close()
    }
  }
}
const calls = (page, op) => page.evaluate((op) => window.__calls.filter((c) => !op || c.op === op), op)
const last = async (page, op) => (await calls(page, op)).at(-1)
const sent = (page, op) => page.waitForFunction((op) => window.__calls.some((c) => c.op === op), op)
const field = (page) => page.locator('textarea').first()
const capture = async (page, text) => {
  await field(page).fill(text)
  await page.locator('.download-btn').first().waitFor({timeout: 8000})
}
const finished = async (page, filter = 'Finished') => {
  await page.getByRole('navigation', {name: 'Main navigation'}).getByRole('button', {name: 'Downloads'}).click()
  await page.getByRole('radio', {name: new RegExp('^' + filter)}).click()
}
const nav = (page, name) => name === 'Settings' ? page.getByRole('navigation', {name: 'Main navigation'}).getByRole('button', {name: 'Settings', exact: true}).click() : page.getByRole('navigation', {name: 'Main navigation'}).getByRole('button', {name}).or(page.getByRole('navigation', {name: 'Main navigation'}).getByRole('link', {name})).first().click()

// The preview is one window: "What to keep" (Video, Audio, Both) and the sizes on the first tab, everything else under Extras.
const keep = (page, name) => page.getByRole('radiogroup', {name: 'What to keep'}).getByRole('radio', {name, exact: true})
const window_ = (page) => page.getByRole('dialog')
const openExtras = (page) => page.getByRole('tab', {name: /^Extras/}).click()
const closeReview = (page) => window_(page).getByRole('button', {name: 'Close', exact: true}).click()
/** Close puts the review away and keeps the link; Clear in the field discards it. */
const discard = async (page) => {
  await closeReview(page)
  await page.getByRole('button', {name: 'Clear link'}).click()
}

await test('preview without bridge is labelled and never fabricates inspection', async () => {
  const page = await openApp(browser, srv.url, {fake: false})
  try {
    await field(page).fill(YT)
    await field(page).press('Enter')
    await page.locator('.field-error, [role=alert]').filter({hasText: /browser preview/i}).first().waitFor()
    assert.equal(await page.getByRole('radiogroup', {name: 'Video quality'}).count(), 0)
  } finally {
    await page.context().close()
  }
})

await test('inspect skeleton then video review; enqueue carries FormatRequest', async (page) => {
  await field(page).fill(YT)
  await page.getByText(/Reading formats/).first().waitFor()
  await page.getByRole('radiogroup', {name: 'Video quality'}).waitFor()
  assert.equal((await calls(page, 'inspect')).length, 1)
  // The Format row and every size are on the tab at once; nothing is behind a fold.
  await page.getByRole('radio', {name: /^1080p, H.264/}).click()
  await page.getByRole('radio', {name: 'MKV'}).click()
  await page.locator('.download-btn').click()
  await sent(page, 'enqueue')
  const req = await last(page, 'enqueue')
  assert.equal(req.url, YT)
  assert.equal(req.format.type, 'video')
  assert.equal(req.format.container, 'mkv')
  assert.equal(req.format.maxHeight, 1080)
  assert.match(req.format.formatId, /./)
  assert.ok(req.options && typeof req.options === 'object')
  await page.getByText(/Added 1 download/).waitFor()
})

await test('audio tab: target reaches enqueue', async (page) => {
  await capture(page, YT)
  await keep(page, 'Audio').click()
  await page.getByRole('radio', {name: 'FLAC'}).click()
  await page.locator('.download-btn').click()
  await sent(page, 'enqueue')
  const req = await last(page, 'enqueue')
  assert.equal(req.format.type, 'audio')
  assert.equal(req.format.target, 'flac')
})

await test('extras: subtitles validation, sponsor parts, cut a part reach enqueue; the tab counts what changed', async (page) => {
  await capture(page, YT)
  await openExtras(page)
  assert.equal(await page.getByRole('tab', {name: 'Extras', exact: true}).count(), 1, 'nothing changed yet')
  await page.getByRole('radiogroup', {name: 'Subtitles'}).getByRole('radio', {name: 'Save subtitles'}).click()
  const lang = page.locator('input[aria-invalid]').first()
  await lang.fill('en us!')
  await page.locator('.field-error').waitFor()
  assert.equal(await page.locator('.download-btn').isDisabled(), true)
  await lang.fill('en,de')
  await page.getByRole('radiogroup', {name: 'Sponsor parts'}).getByRole('radio', {name: 'Remove'}).click()
  await page.getByRole('radiogroup', {name: 'Cut a part'}).getByRole('radio', {name: 'Choose start and end'}).click()
  await page.getByRole('tab', {name: 'Extras · 2'}).waitFor()
  await page.getByPlaceholder('0:00').fill('0:10')
  await page.getByPlaceholder('8:32').fill('0:05')
  await page.getByText(/Trim end must be after/).waitFor()
  await page.getByPlaceholder('8:32').fill('1:30')
  await page.getByRole('tab', {name: 'Extras · 3'}).waitFor()
  await page.locator('.download-btn').click()
  await sent(page, 'enqueue')
  const {options} = await last(page, 'enqueue')
  assert.deepEqual([options.subtitles.enabled, options.subtitles.languages], [true, 'en,de'])
  assert.equal(options.sponsorBlock, 'remove')
  assert.deepEqual(options.section, {start: 10, end: 90})
})

await test('batch of 3 links enqueues each', async (page) => {
  await field(page).fill([YT, MUSIC, VIMEO].join('\n'))
  const all = page.getByRole('button', {name: /Download all 3/})
  await all.waitFor({timeout: 8000})
  await page.waitForFunction(() => window.__calls.filter((c) => c.op === 'inspect').length === 3)
  await page.waitForTimeout(600)
  await all.click()
  await page.waitForFunction(() => window.__calls.filter((c) => c.op === 'enqueue').length === 3)
  assert.deepEqual((await calls(page, 'enqueue')).map((c) => c.url).sort(), [YT, MUSIC, VIMEO].sort())
})

await test('inspection error is explained', async (page) => {
  await field(page).fill(BAD)
  await page.getByText(/No video or images were found/).waitFor()
})

await test('music track defaults to audio; spotify shows match', async (page) => {
  await capture(page, MUSIC)
  // Audio opens on MP3 at 320 kbps; the button names the size of that row.
  assert.equal(await page.getByRole('radiogroup', {name: 'Audio quality'}).getByRole('radio', {name: /^320 kbps, MP3/}).getAttribute('aria-checked'), 'true')
  await discard(page)
  await capture(page, SPOT)
  await page.getByText(/92%/).first().waitFor()
  await page.locator('.download-btn').click()
  await sent(page, 'enqueue')
  assert.equal((await last(page, 'enqueue')).format.type, 'audio')
})

await test('Both (Capture): a song looks for its video, Both is off while it looks, and Download sends the video with the song', async (page) => {
  await capture(page, MUSIC)
  await page.getByText('Looking for the music video…').waitFor()
  assert.equal(await keep(page, 'Both').isDisabled(), true, 'Both waits for the look')
  assert.equal(await page.locator('.download-btn').isEnabled(), true, 'looking never blocks the download')
  await page.waitForFunction(() => !document.body.textContent.includes('Looking for the music video…'))
  assert.equal(await keep(page, 'Both').isEnabled(), true)
  assert.equal(await keep(page, 'Audio').getAttribute('aria-checked'), 'true', 'never chosen for the person')
  assert.equal((await last(page, 'findMusicVideo')).url, MUSIC)
  await keep(page, 'Both').click()
  // the found video: picture, title, channel, views and length
  await page.getByText('Tidal (Night Version) (Official Video)').waitFor()
  await page.getByText('Mira Halden · 1.4M views · 3:51').waitFor()
  await page.getByText('They stay on the same second.').waitFor()
  await page.locator('.download-btn').click()
  await sent(page, 'enqueue')
  const sentJob = await last(page, 'enqueue')
  assert.equal(sentJob.format.type, 'audio')
  assert.equal(sentJob.videoId, 'fixtureVid02')
}, {fake: {captureVideo: 'slow'}})

await test('Both (Capture): no video found, or a failed look, turns Both off and says why; the song downloads without a video', async (page) => {
  await capture(page, MUSIC)
  await page.getByText('No official music video was found, so Both is off.').waitFor()
  assert.equal(await keep(page, 'Both').isDisabled(), true)
  await page.locator('.download-btn').click()
  await sent(page, 'enqueue')
  assert.equal((await last(page, 'enqueue')).videoId, undefined)
}, {fake: {captureVideo: 'error'}})

await test('Both (Capture): the setting "Keep music videos automatically" starts with Both chosen, and it can still be changed', async (page) => {
  await capture(page, MUSIC)
  await page.waitForFunction(() => document.querySelector('[aria-label="What to keep"] [aria-checked="true"]')?.textContent === 'Both')
  assert.equal((await calls(page, 'enqueue')).length, 0, 'the setting starts no download by itself')
  await keep(page, 'Audio').click()
  await page.locator('.download-btn').click()
  await sent(page, 'enqueue')
  assert.equal((await last(page, 'enqueue')).videoId, undefined, 'Audio alone for this download')
}, {fake: {musicVideos: true}})

await test('a download that used a sign-in says so quietly on its row', async (page) => {
  await page.getByRole('navigation', {name: 'Main navigation'}).getByRole('button', {name: 'Downloads'}).click()
  const row = page.getByRole('listitem', {name: 'Harbour photos'})
  await row.waitFor()
  assert.match(await row.locator('.job-meta').textContent(), /Signed in with Firefox/)
  assert.equal(await page.getByRole('alert').count(), 0, 'nothing pops up')
}, {fake: {signedIn: 'Signed in with Firefox'}})

await test('Both (Capture): only a song or a YouTube link has Both; a YouTube link uses the link itself and searches nothing', async (page) => {
  await capture(page, 'https://www.youtube.com/watch?v=dQw4w9WgXcQ')
  assert.equal((await calls(page, 'findMusicVideo')).length, 0, 'a plain video link never looks')
  assert.equal(await page.getByText(/as a song you can also watch/).count(), 0, 'a video saved to a video space is just a video')
  await keep(page, 'Both').click()
  await page.getByText('The song is saved with this video beside it.', {exact: true}).waitFor()
  assert.equal((await calls(page, 'findMusicVideo')).length, 0)
  await discard(page)
  await capture(page, 'https://cdn.example.net/plain-file.mp4')
  assert.equal(await keep(page, 'Both').count(), 0, 'a plain file has no video to keep beside a song')
  await discard(page)
  await capture(page, 'https://music.youtube.com/watch?v=ytm1')
  await page.getByText('No official music video was found, so Both is off.').waitFor()
  await keep(page, 'Video').click()
  assert.equal(await page.getByText('No official music video was found, so Both is off.').count(), 0, 'on Video the line goes')
  assert.equal(await page.getByText('Looking for the music video…').count(), 0)
  // the Video choice saved to Music is both: it says so
  await page.getByText(/Kept in Music as a song you can also watch/).waitFor()
  await keep(page, 'Audio').click()
  assert.equal(await page.getByText(/as a song you can also watch/).count(), 0, 'as audio it is a song file')
})

await test('link with no listed formats offers best-available video, not a music default', async (page) => {
  await capture(page, 'https://cdn.example.net/plain-file.mp4')
  await page.getByText(/This link doesn.t list its formats; the best available video and audio are saved/).waitFor()
  await page.locator('.download-btn').click()
  await sent(page, 'enqueue')
  const req = await last(page, 'enqueue')
  assert.equal(req.format.type, 'video')
  assert.equal(req.format.formatId, undefined)
})

await test('gallery post enqueues gallery format', async (page) => {
  await capture(page, GALLERY)
  await page.locator('.download-btn').click()
  await sent(page, 'enqueue')
  assert.equal((await last(page, 'enqueue')).format.type, 'gallery')
})

await test('collection: progressive discovery, select, filter, load more, enqueueDiscovered', async (page) => {
  await field(page).fill(SPOT_LIST)
  await page.getByRole('list', {name: 'Collection entries'}).waitFor({timeout: 8000})
  await page.getByText(/Showing positions 1–/).waitFor({timeout: 20000})
  await page.getByText(/Spotify lists only the first 100/).waitFor()
  assert.equal(await page.locator('.inline-alert-warning, [role=alert]').filter({hasText: /Spotify lists only/}).count(), 0, 'notice must not render as an error')
  // A playlist opens with everything selected; entries already in Music (every 4th track) are left out.
  await page.getByRole('button', {name: /Download 75 selected/}).waitFor({timeout: 8000})
  assert.match(await page.locator('.toolbar-count').textContent(), /75 selected/)
  await page.getByRole('button', {name: 'Clear', exact: true}).click()
  await page.getByLabel('Filter entries').fill('zzzz-no-match')
  await page.getByText(/No entries match/).waitFor()
  await page.getByLabel('Filter entries').fill('')
  const rows = () => page.locator('.entries .entry:not(.entry-loading)').count()
  const before = await rows()
  await page.getByRole('button', {name: 'Load more'}).click()
  await page.waitForFunction((n) => document.querySelectorAll('.entries .entry:not(.entry-loading)').length > n, before, {timeout: 20000})
  const boxes = page.locator('.entries .entry input[type=checkbox]')
  await boxes.nth(0).click()
  await boxes.nth(4).click({modifiers: ['Shift']})
  assert.match(await page.locator('.toolbar-count').textContent(), /5 selected/)
  await page.getByLabel('Quality for all').selectOption('audio-m4a')
  await page.getByRole('button', {name: /Download 5 selected/}).click()
  await sent(page, 'enqueueDiscovered')
  const req = await last(page, 'enqueueDiscovered')
  assert.equal(req.urls.length, 5)
  assert.equal(req.preset, 'audio-m4a')
  assert.ok(req.inspectionId)
})

// ---------------------------------------------------------------- what a collection opens with

const openCollection = async (page, url, total) => {
  await field(page).fill(url)
  await page.getByRole('list', {name: 'Collection entries'}).waitFor({timeout: 8000})
  // The filter placeholder shows how many entries were found; wait for the last page of discovery.
  await page.getByPlaceholder(`Filter ${total} entries`).waitFor({timeout: 20000})
}
const selectedText = (page) => page.locator('.toolbar-count').textContent()

await test('collection: a playlist opens with every entry selected, including ones that arrive later', async (page) => {
  await field(page).fill('https://www.youtube.com/playlist?list=PLfocus')
  await page.getByRole('list', {name: 'Collection entries'}).waitFor({timeout: 8000})
  await page.getByRole('button', {name: /Download \d+ selected/}).waitFor({timeout: 8000})
  await page.getByPlaceholder('Filter 64 entries').waitFor({timeout: 20000})
  assert.match(await selectedText(page), /^64 selected/)
  assert.equal(await page.locator('.entries input[type=checkbox]:checked').count(), 64)
  // "Select all" is already on: clicking it clears, it does not re-select.
  await page.getByText('Select all', {exact: true}).click()
  await page.getByText('Shift-click selects a range').waitFor()
})

await test('collection: a private, deleted or gone entry says why and is never selected', async (page) => {
  await field(page).fill('https://www.youtube.com/playlist?list=PLfocus')
  await page.getByPlaceholder('Filter 64 entries').waitFor({timeout: 20000})
  // 63 of 64: the gone one is left out, and Select all still counts as all
  assert.match(await selectedText(page), /^63 selected/)
  const gone = page.locator('.entry.unavailable')
  assert.equal(await gone.count(), 1)
  await gone.getByText('Unavailable', {exact: true}).waitFor()
  await gone.getByText(/No longer on YouTube/).waitFor()
  assert.equal(await gone.locator('input[type=checkbox]').isDisabled(), true)
  assert.equal(await page.getByRole('checkbox', {name: 'Select all'}).isChecked(), true)
  await page.getByText('Select all', {exact: true}).click()
  await page.getByText('Select all', {exact: true}).click()
  assert.match(await selectedText(page), /^63 selected/, 'Select all skips it')
  await gone.click()
  assert.match(await selectedText(page), /^63 selected/, 'clicking it does nothing')
}, {fake: {unavailable: true}})

await test('collection: an album opens selected, but songs already in the space stay unticked', async (page) => {
  await openCollection(page, 'https://open.spotify.com/album/1A2B3C4D5E6F', 9)
  // The fixture says tracks 1, 5 and 9 are already in Music.
  await page.getByText(/3 songs are already in Music/).waitFor({timeout: 8000})
  await page.getByRole('button', {name: /Download 6 selected/}).waitFor()
  assert.equal(await page.locator('.entries input[type=checkbox]:checked').count(), 6)
  assert.equal(await page.locator('.entries input[type=checkbox]:not(:checked)').count(), 3)
})

await test('collection: a channel opens with nothing selected', async (page) => {
  await field(page).fill('https://www.youtube.com/@northbound/videos')
  await page.getByRole('list', {name: 'Collection entries'}).waitFor({timeout: 8000})
  await page.locator('.entries .entry:not(.entry-loading)').first().waitFor()
  await page.getByRole('button', {name: 'Select entries to download'}).waitFor()
  await page.waitForTimeout(1200)
  assert.equal(await page.locator('.entries input[type=checkbox]:checked').count(), 0)
  assert.equal(await page.getByRole('button', {name: /Download \d+ selected/}).count(), 0)
})

await test('collection: a radio mix opens with nothing selected', async (page) => {
  await openCollection(page, 'https://www.youtube.com/watch?v=mixseed&list=RDmixseed', 25)
  await page.getByRole('button', {name: 'Select entries to download'}).waitFor()
  assert.equal(await page.locator('.entries input[type=checkbox]:checked').count(), 0)
})

await test('collection: changing the selection stops auto-selecting entries that arrive later', async (page) => {
  await field(page).fill('https://www.youtube.com/playlist?list=PLfocus')
  await page.getByRole('list', {name: 'Collection entries'}).waitFor({timeout: 8000})
  await page.getByRole('button', {name: /Download \d+ selected/}).waitFor({timeout: 8000})
  await page.locator('.entries .entry:not(.entry-loading) input[type=checkbox]').first().click()
  const total = () => page.locator('.entries .entry:not(.entry-loading)').count()
  const atClick = await page.locator('.entries input[type=checkbox]:checked').count()
  await page.getByPlaceholder('Filter 64 entries').waitFor({timeout: 20000})
  assert.equal(await total(), 64)
  // Only what was ticked before the first change stays ticked.
  assert.equal(await page.locator('.entries input[type=checkbox]:checked').count(), atClick)
  assert.ok(atClick < 63, 'entries that arrived after the change must not be selected')
})

await test('collection: after a successful add the collection closes', async (page) => {
  await field(page).fill('https://www.youtube.com/playlist?list=PLfocus')
  await page.getByRole('list', {name: 'Collection entries'}).waitFor({timeout: 8000})
  await page.getByRole('button', {name: /Download \d+ selected/}).click()
  await sent(page, 'enqueueDiscovered')
  await page.getByRole('list', {name: 'Collection entries'}).waitFor({state: 'detached', timeout: 8000})
})

await test('queue actions send the right ops; delete file asks first', async (page) => {
  const row = (title) => page.getByRole('listitem', {name: title})
  await row(/Aurora Borealis/).getByRole('button', {name: 'Pause'}).click()
  await row(/Field recordings/).getByRole('button', {name: 'Resume'}).click()
  await row(/Speedrun/).getByRole('button', {name: 'Cancel download'}).click()
  await row(/Members-only/).getByRole('button', {name: 'Retry'}).click()
  // At most two actions stay on a row; moving up lives in its menu.
  await row(/Ramen/).getByRole('button', {name: /More actions/}).click()
  await page.getByRole('menuitem', {name: 'Move up in queue'}).click()
  await row(/Salt Flats/).getByRole('button', {name: 'Open in another app', exact: true}).click()
  await row(/Salt Flats/).getByRole('button', {name: 'Show in folder'}).click()
  const ops = (await calls(page)).map((c) => c.op)
  for (const op of ['pause', 'resume', 'cancel', 'retry', 'moveUp', 'open', 'reveal']) assert.ok(ops.includes(op), `missing ${op}`)
  await row(/Salt Flats/).getByRole('button', {name: /More actions/}).click()
  await page.getByRole('menuitem', {name: /Delete file/}).click()
  assert.equal((await calls(page, 'deleteFile')).length, 0)
  await page.getByRole('dialog').getByRole('button', {name: /Delete/}).last().click()
  await sent(page, 'deleteFile')
})

await test('library: views, search, filters, sort, clear with confirmation', async (page) => {
  await finished(page)
  await page.getByRole('list', {name: 'Download history'}).waitFor()
  const count = () => page.locator('[aria-label="Download history"] > li').count()
  const all = await count()
  assert.ok(all > 5)
  await page.getByTitle('Grid view').click()
  await page.locator('.lib-grid').waitFor()
  await page.getByTitle('List view').click()
  await page.getByLabel('Search downloads').fill('ramen')
  assert.ok((await count()) < all)
  await page.getByLabel('Search downloads').fill('')
  await page.getByRole('radiogroup', {name: 'Media type'}).getByRole('radio', {name: 'Audio'}).click()
  const audio = await count()
  assert.ok(audio > 0 && audio < all)
  await page.getByRole('radiogroup', {name: 'Media type'}).getByRole('radio', {name: 'All'}).click()
  await page.getByLabel('Sort').selectOption('title')
  const titles = (await page.locator('.lib-title').allTextContents()).slice(0, 6)
  assert.deepEqual(titles, [...titles].sort((a, b) => a.localeCompare(b)))
  await page.getByRole('button', {name: 'Clear history'}).click()
  assert.equal((await calls(page, 'clear')).length, 0)
  await page.getByRole('button', {name: 'Clear history'}).last().click()
  await sent(page, 'clear')
})

await test('settings: groups send updateSettings; engine, cookies, proxy', async (page) => {
  await nav(page, 'Settings')
  const group = (name) => page.getByRole('navigation', {name: 'Settings sections'}).getByRole('button', {name}).click()
  const lastSettings = async () => (await last(page, 'updateSettings')).settings
  const sentCount = async () => (await calls(page, 'updateSettings')).length
  const openSpeed = async () => {
    await group('Downloads')
    const head = page.getByRole('button', {name: /Speed and connections/})
    if ((await head.getAttribute('aria-expanded')) !== 'true') await head.click()
  }
  // Downloads
  await group('Downloads')
  await page.getByRole('radio', {name: 'One folder'}).click()
  assert.equal((await lastSettings()).organization, 'flat')
  await page.getByLabel('Default quality').selectOption('1080')
  assert.equal((await lastSettings()).defaultPreset, '1080')
  await page.getByLabel('File name style').selectOption('uploader-title')
  assert.equal((await lastSettings()).filenameStyle, 'uploader-title')
  // Speed and connections (a card that opens)
  await openSpeed()
  await page.getByRole('spinbutton', {name: 'Parallel downloads'}).focus()
  await page.keyboard.press('ArrowUp')
  assert.equal((await lastSettings()).concurrency, 4)
  await page.getByRole('switch', {name: 'Turbo transfer'}).click()
  assert.equal((await lastSettings()).turbo.enabled, true)
  await page.getByRole('switch', {name: 'Limit download speed'}).click()
  assert.equal((await lastSettings()).rateLimit, 5 * 1048576)
  // Website sign-in: the switch for using a sign-in when a site asks for one (on by default)
  const autoSignIn = page.getByRole('switch', {name: 'Use a sign-in when a site asks for one'})
  assert.equal(await autoSignIn.isChecked(), true)
  await autoSignIn.click()
  assert.equal((await lastSettings()).autoSignIn, false)
  await autoSignIn.click()
  assert.equal((await lastSettings()).autoSignIn, true)
  await page.getByRole('radio', {name: 'Browser'}).click()
  assert.equal((await lastSettings()).cookies.mode, 'browser')
  await page.getByRole('radio', {name: 'cookies.txt'}).click()
  assert.equal((await lastSettings()).cookies.mode, 'file')
  await page.getByRole('button', {name: /Import|Choose|cookies/i}).filter({hasText: /import|choose/i}).first().click()
  await sent(page, 'importCookies')
  // Proxy validation (inside Speed and connections)
  const before = await sentCount()
  await page.locator('#proxy-input').fill('not a proxy')
  await page.locator('.field-error').waitFor()
  assert.equal(await page.getByRole('button', {name: 'Save', exact: true}).isDisabled(), true)
  await page.locator('#proxy-input').fill('socks5://127.0.0.1:1080')
  await page.getByRole('button', {name: 'Save', exact: true}).click()
  assert.equal((await lastSettings()).proxy, 'socks5://127.0.0.1:1080')
  assert.equal(await sentCount(), before + 1)
  // YouTube Music links default to Audio and can be switched to Video
  await group('Downloads')
  const ytm = page.getByRole('radiogroup', {name: 'YouTube Music links'})
  assert.equal(await ytm.getByRole('radio', {name: /Audio/}).isChecked(), true)
  await ytm.getByRole('radio', {name: 'Video'}).click()
  assert.equal((await lastSettings()).youtubeMusic, 'video')
  // About Keepfin: two clearly separate sections: Updates (app) and Download engine
  await group('About Keepfin')
  assert.equal(await page.getByRole('button', {name: /licen[cs]es/i}).count(), 0)
  await page.getByRole('region', {name: 'Updates', exact: true}).waitFor()
  const engineSection = page.getByRole('region', {name: 'Download engine'})
  await engineSection.getByText('Updates automatically', {exact: true}).waitFor()
  await engineSection.getByText(/Last checked .*Version 2026\.09\.28/).waitFor()
  assert.equal(await page.getByText(/Version 0\.4\.0/).count() > 0, true)
  assert.equal(await page.getByRole('navigation', {name: 'Settings sections'}).getByRole('button', {name: /^Download engine|^About$/}).count(), 0)
  // Components use generic names (kept under Advanced)
  await engineSection.getByText('Advanced', {exact: true}).click()
  assert.ok(await page.getByText('Media processor').first().isVisible())
  assert.equal(await page.getByText(/yt-dlp|ffmpeg|gallery-dl/i).count(), 0)
  await page.getByRole('radio', {name: 'Early access'}).click()
  assert.deepEqual((await lastSettings()).engine.channel, 'nightly')
  await engineSection.getByRole('button', {name: 'Check now'}).click()
  await sent(page, 'engineUpdate')
  // Support: its own page (Support Keepfin), apart from Help; buttons send fixed ids only
  await group('Downloads')
  await group('Support Keepfin')
  await page.getByRole('heading', {name: 'Hisham Sanjag'}).waitFor()
  for (const [name, id] of [
    ['GitHub Sponsors', 'sponsors'],
    ['Ko-fi', 'kofi'],
    ['PayPal', 'paypal'],
    ['@HishamSanjag', 'github'],
  ]) {
    await page.getByRole('button', {name: new RegExp(name)}).click()
    assert.equal((await last(page, 'openSupportLink')).id, id)
  }
  assert.equal((await calls(page, 'openSupportLink')).length, 4)
  // Notifications & About
  await group('Notifications')
  await page.getByRole('switch', {name: /Completed downloads/}).click()
  await sent(page, 'notificationConfigure')
  assert.equal((await last(page, 'notificationConfigure')).notifications.completed, false)
  // Appearance: theme + interface size
  await group('Appearance')
  await page.getByRole('radio', {name: 'Light'}).click()
  assert.equal(await page.evaluate(() => document.documentElement.dataset.theme), 'light')
})

await test('keyboard: Ctrl+L focuses the field, Ctrl+V outside inputs captures, Escape/Enter', async (page) => {
  await nav(page, 'Settings')
  await page.keyboard.press('Control+L')
  await page.waitForFunction(() => document.activeElement?.tagName === 'TEXTAREA')
  await page.locator('textarea').blur()
  await page.evaluate((url) => {
    const data = new DataTransfer()
    data.setData('text/plain', url)
    document.dispatchEvent(new ClipboardEvent('paste', {clipboardData: data, bubbles: true, cancelable: true}))
  }, YT)
  await page.getByRole('radiogroup', {name: 'Video quality'}).waitFor()
})

await test('drag and drop text captures a link', async (page) => {
  await page.evaluate((url) => {
    const data = new DataTransfer()
    data.setData('text/plain', url)
    for (const type of ['dragenter', 'dragover', 'drop']) window.dispatchEvent(new DragEvent(type, {dataTransfer: data, bubbles: true, cancelable: true}))
  }, YT)
  await page.getByRole('radiogroup', {name: 'Video quality'}).waitFor()
})

await test('theme toggle and scale', async (page) => {
  const theme = () => page.evaluate(() => document.documentElement.dataset.theme || document.documentElement.getAttribute('data-theme'))
  const before = await theme()
  await page.getByRole('button', {name: /theme|appearance|light|dark/i}).first().click()
  await page.waitForTimeout(150)
  console.log('   theme', before, '->', await theme())
})


// ---------------------------------------------------------------- YouTube Music defaults

await test('YouTube Music single link opens on Audio with the default format; Video stays one click away', async (page) => {
  await capture(page, 'https://music.youtube.com/watch?v=ytm1')
  assert.equal(await keep(page, 'Audio').getAttribute('aria-checked'), 'true')
  assert.equal(await page.getByRole('radiogroup', {name: 'Audio quality'}).getByRole('radio', {name: /^320 kbps, MP3/}).getAttribute('aria-checked'), 'true')
  await keep(page, 'Video').click()
  await page.getByRole('radiogroup', {name: 'Video quality'}).waitFor()
  await page.locator('.download-btn').click()
  await sent(page, 'enqueue')
  assert.equal((await last(page, 'enqueue')).format.type, 'video')
})

await test('YouTube Music collection defaults to audio, keeps tags on and no matched-service notice', async (page) => {
  await field(page).fill('https://music.youtube.com/playlist?list=PLytm')
  await page.getByRole('list', {name: 'Collection entries'}).waitFor({timeout: 8000})
  await page.locator('.entries .entry:not(.entry-loading)').first().waitFor()
  assert.equal(await page.getByLabel('Quality for all').inputValue(), 'audio-mp3')
  assert.equal(await page.getByLabel('Format', {exact: true}).inputValue(), 'audio')
  await page.getByLabel('Format', {exact: true}).selectOption('video')
  assert.equal(await page.getByLabel('Quality for all').locator('option').filter({hasText: '1080p'}).count(), 1, 'video sizes stay one click away')
  await page.getByLabel('Format', {exact: true}).selectOption('audio')
  assert.equal(await page.getByLabel('Quality for all').inputValue(), 'audio-mp3')
  assert.equal(await page.getByText(/DRM-protected/).count(), 0)
  await page.getByRole('button', {name: /Download \d+ selected/}).waitFor({timeout: 8000})
  await page.getByRole('button', {name: /Download \d+ selected/}).click()
  await sent(page, 'enqueueDiscovered')
  const req = await last(page, 'enqueueDiscovered')
  assert.equal(req.preset, 'audio-mp3')
  assert.equal(req.options.embedMetadata, true)
  assert.equal(req.options.embedThumbnail, true)
})

await test('YouTube Music setting set to Video opens links as video', async (page) => {
  await capture(page, 'https://music.youtube.com/watch?v=ytm1')
  assert.equal(await keep(page, 'Video').getAttribute('aria-checked'), 'true')
}, {fake: {youtubeMusic: 'video'}})

// ---------------------------------------------------------------- collections kept together

const openYtList = async (page) => {
  await field(page).fill('https://www.youtube.com/playlist?list=PLfocus')
  await page.getByRole('list', {name: 'Collection entries'}).waitFor({timeout: 8000})
  await page.locator('.entries .entry:not(.entry-loading)').first().waitFor()
  await page.getByRole('button', {name: /Download \d+ selected/}).waitFor({timeout: 8000})
}

await test('collection review: Keep together is the default and is sent; Separate items is remembered', async (page) => {
  await openYtList(page)
  await openExtras(page)
  const choice = page.getByRole('radiogroup', {name: 'How to queue the selected entries'})
  assert.equal(await choice.getByRole('radio', {name: 'Keep together'}).isChecked(), true)
  await page.getByRole('button', {name: /Download \d+ selected/}).click()
  await sent(page, 'enqueueDiscovered')
  assert.equal((await last(page, 'enqueueDiscovered')).grouped, true)
  // After a successful add the review closes by itself (the list is in the other tab, so wait for the window).
  await page.getByRole('dialog').waitFor({state: 'detached', timeout: 8000})
  await openYtList(page)
  await openExtras(page)
  await choice.getByRole('radio', {name: 'Separate items'}).click()
  await page.getByRole('tab', {name: 'Extras · 1'}).waitFor()
  assert.equal(await page.evaluate(() => localStorage.getItem('keepfin-collection-grouped')), 'false')
  await page.getByRole('button', {name: /Download \d+ selected/}).click()
  await page.waitForFunction(() => window.__calls.filter((c) => c.op === 'enqueueDiscovered').length === 2)
  assert.equal((await last(page, 'enqueueDiscovered')).grouped, false)
})

await test('queue: grouped collection is one collapsible row with counts, aggregate progress and group actions', async (page) => {
  const row = page.locator('.group-row[data-group="grp-road"]')
  await row.waitFor()
  assert.match(await row.locator('.group-counts').textContent(), /2 of 6 saved · 1 failed/)
  await row.getByRole('progressbar', {name: /Road Trip Mix 2026 progress/}).waitFor()
  const head = row.locator('.group-head')
  assert.match(await head.locator('.job-stats').textContent(), /\d+%/)
  assert.match(await head.locator('.job-stats').textContent(), /\/s/)
  // something is moving, so the collection starts open
  assert.equal(await row.locator('.job').count(), 6)
  assert.equal(await row.getByRole('button', {name: /Hide 6 items/}).getAttribute('aria-expanded'), 'true')
  // existing row component keeps its own actions
  await row.getByRole('listitem', {name: /Road track 3/}).getByRole('button', {name: 'Pause'}).click()
  await sent(page, 'pause')
  await row.getByRole('button', {name: /Hide 6 items/}).click()
  assert.equal(await row.locator('.job').count(), 0)
  // group actions
  await row.getByRole('button', {name: /Pause all in/}).click()
  await sent(page, 'pauseGroup')
  assert.equal((await last(page, 'pauseGroup')).id, 'grp-road')
  await row.getByRole('button', {name: /Resume all in/}).click()
  await sent(page, 'resumeGroup')
  await row.getByRole('button', {name: 'Retry failed'}).click()
  await sent(page, 'retryGroup')
  await row.getByRole('button', {name: /More actions for Road Trip Mix 2026/}).click()
  assert.equal(await page.getByRole('menuitem', {name: /Remove from history/}).count(), 0, 'active collections cannot be removed')
  await page.getByRole('menuitem', {name: /Open source page/}).click()
  await sent(page, 'openGroupLink')
  await row.getByRole('button', {name: 'Open Road Trip Mix 2026 on youtube.com'}).click()
  assert.equal((await calls(page, 'openGroupLink')).length, 2)
  await row.getByRole('button', {name: /More actions for Road Trip Mix 2026/}).click()
  await page.getByRole('menuitem', {name: /Copy source link/}).click()
  await row.getByRole('button', {name: /Cancel all in/}).click()
  await sent(page, 'cancelGroup')
}, {fake: {scenario: 'groups'}})

await test('library: collections appear once settled as one row; remove keeps media; active ones stay out', async (page) => {
  await finished(page)
  await page.getByRole('list', {name: 'Download history'}).waitFor()
  const lib = page.getByRole('list', {name: 'Download history'})
  assert.equal(await lib.locator('.group-row[data-group="grp-road"]').count(), 0, 'live collection stays in Downloads')
  const album = lib.locator('.group-row[data-group="grp-album"]')
  assert.match(await album.locator('.group-counts').textContent(), /4 saved · 1 failed/)
  assert.match(await lib.locator('.group-row[data-group="grp-season"] .group-counts').textContent(), /4 saved/)
  // a failure keeps the collection open; a fully saved one starts closed. The chevron opens and closes the items inline.
  assert.equal(await album.locator('.lib-row').count(), 5)
  assert.equal(await lib.locator('.group-row[data-group="grp-season"] .lib-row').count(), 0)
  await album.getByRole('button', {name: /Hide 5 items/}).click()
  assert.equal(await album.locator('.lib-row').count(), 0)
  const season = lib.locator('.group-row[data-group="grp-season"]')
  await season.getByRole('button', {name: /Show .* in folder/}).click()
  await sent(page, 'reveal')
  await season.getByRole('button', {name: /More actions for/}).click()
  await page.getByRole('menuitem', {name: /Remove from history/}).click()
  await sent(page, 'removeGroup')
  assert.equal((await last(page, 'removeGroup')).id, 'grp-season')
  await page.getByText(/Removed from history/).waitFor()
  await season.waitFor({state: 'detached'})
  await album.getByRole('button', {name: 'Retry failed'}).click()
  await sent(page, 'retryGroup')
}, {fake: {scenario: 'groups'}})

await test('collections: one that is finished starts closed and the chevron opens it; one with something moving or failed starts open', async (page) => {
  await finished(page)
  const lib = page.getByRole('list', {name: 'Download history'})
  const season = lib.locator('.group-row[data-group="grp-season"]')
  assert.equal(await season.locator('.lib-row').count(), 0, 'every item saved: closed')
  assert.equal(await season.getByRole('button', {name: /Show 4 items/}).getAttribute('aria-expanded'), 'false')
  await season.getByRole('button', {name: /Show 4 items/}).click()
  assert.equal(await season.locator('.lib-row').count(), 4)
  await season.getByRole('button', {name: /Hide 4 items/}).click()
  assert.equal(await season.locator('.lib-row').count(), 0)
  assert.equal(await lib.locator('.group-row[data-group="grp-album"] .lib-row').count(), 5, 'a failed item: open')
}, {fake: {scenario: 'groups'}})

await test('deleted files: history rows and collections say so, keep Remove from history, and follow the library', async (page) => {
  await finished(page)
  const lib = page.getByRole('list', {name: 'Download history'})
  // A download whose file the library deleted, and one whose file left its folder.
  for (const title of ['Deleted clip', 'Moved away clip']) {
    const row = lib.getByRole('listitem').filter({hasText: title}).first()
    await row.getByText('Deleted', {exact: true}).waitFor()
    assert.equal(await row.getByRole('button', {name: /Show in folder/}).isDisabled(), true)
    await row.getByRole('button', {name: /More actions for/}).click()
    assert.equal(await page.getByRole('menuitem', {name: /Delete file/}).count(), 0, 'nothing left to delete')
    await page.getByRole('menuitem', {name: /Remove from history/}).waitFor()
    await page.keyboard.press('Escape')
    await page.getByRole('menuitem', {name: /Remove from history/}).waitFor({state: 'detached'})
  }
  // A collection with some of its files gone says how many; one with all of them gone says Deleted.
  const album = lib.locator('.group-row[data-group="grp-album"]')
  assert.match((await album.locator('.group-counts').textContent()).replace(/\s+/g, ' '), /2 of 4 files removed/)
  const season = lib.locator('.group-row[data-group="grp-season"]')
  assert.equal((await season.locator('.group-counts').textContent()).replace(/\s+/g, ' ').trim(), 'Deleted')
  assert.equal(await season.getByRole('button', {name: /in folder/}).count(), 0)
  // Remove from history is still there for a collection with nothing left, and keeps the media.
  await page.getByRole('searchbox', {name: 'Search downloads'}).fill('Northbound track')
  await season.getByRole('button', {name: /More actions for/}).click()
  await page.getByRole('menuitem', {name: /Remove from history/}).click()
  await sent(page, 'removeGroup')
  await page.getByText(/Removed from history/).waitFor()
  assert.equal((await calls(page, 'deleteFile')).length, 0)
}, {fake: {scenario: 'groups', goneFiles: true, simulate: false}})

await test('update channel: Stable by default, Beta is saved, and a beta build on Stable explains itself', async (page) => {
  await nav(page, 'Settings')
  await page.getByRole('navigation', {name: 'Settings sections'}).getByRole('button', {name: 'About Keepfin'}).click()
  await page.getByText('Version 0.5.0-beta.1').waitFor()
  await page.locator('.about-card', {hasText: 'Beta'}).getByText('Beta', {exact: true}).waitFor()
  await page.getByText('Tested releases. Recommended.').waitFor()
  await page.getByRole('status').getByText(/You.re on beta 0\.5\.0-beta\.1\. You.ll move to the next stable version automatically when it.s released\. Your library and settings stay\./).waitFor()
  await page.getByRole('radio', {name: 'Beta'}).click()
  await sent(page, 'updateSettings')
  assert.equal((await last(page, 'updateSettings')).settings.updateChannel, 'beta')
  await page.getByText('Try new features early. Betas can have bugs.').waitFor()
  assert.equal(await page.getByRole('status').getByText(/move to the next stable version/).count(), 0)
  await page.getByRole('radio', {name: 'Stable'}).click()
  await page.getByRole('status').getByText(/move to the next stable version/).waitFor()
}, {fake: {update: 'beta'}})

await test('partly failed collection: visible once nothing is downloading, summary, retry and failures listed first', async (page) => {
  // All: the settled collection sits under Needs attention, not Transfers.
  const attention = page.getByRole('region', {name: 'Needs attention'})
  const row = attention.locator('.group-row[data-group="grp-trip"]')
  await row.waitFor()
  assert.equal(await page.getByRole('region', {name: 'Transfers'}).locator('.group-row[data-group="grp-trip"]').count(), 0)
  assert.match((await row.locator('.group-counts').textContent()).trim(), /^3 saved · 1 failed · 1 retrying · 1 cancelled/)
  await row.getByRole('button', {name: 'Retry failed'}).waitFor()
  // A failure keeps it open. Order: Needs attention (failed, cancelled), then in progress (retrying), then saved in playlist order.
  const order = await row.locator('.group-items > li').evaluateAll((nodes) =>
    nodes.map((node) => (node.classList.contains('group-sub') ? `# ${node.textContent.replace(/\s+/g, ' ').trim()}` : node.getAttribute('aria-label'))),
  )
  assert.deepEqual(order, [
    '# Needs attention',
    'Lisbon walk 2',
    'Lisbon walk 5',
    '# In progress · 1',
    'Lisbon walk 4',
    '# Saved · 3',
    'Lisbon walk 1',
    'Lisbon walk 3',
    'Lisbon walk 6',
  ])
  const failedRow = row.getByRole('listitem', {name: 'Lisbon walk 2'})
  await failedRow.getByRole('button', {name: 'Retry'}).waitFor()
  await failedRow.getByRole('button', {name: /More actions/}).click()
  await page.getByRole('menuitem', {name: 'Error details'}).click()
  await page.getByRole('dialog', {name: 'Error details'}).waitFor()
  await page.keyboard.press('Escape')
  await page.getByRole('dialog', {name: 'Error details'}).waitFor({state: 'detached'})
  // Finished lists it too, with the same failures first.
  await finished(page)
  const lib = page.getByRole('list', {name: 'Download history'})
  const group = lib.locator('.group-row[data-group="grp-trip"]')
  await group.waitFor()
  assert.equal(await group.locator('.lib-row').count(), 6)
  assert.equal((await group.locator('.group-items > li').first().textContent()).replace(/\s+/g, ' ').trim(), 'Needs attention')
  assert.match(await group.locator('.lib-row', {hasText: 'Lisbon walk 4'}).textContent(), /Retrying in/)
  await group.getByRole('button', {name: 'Retry failed'}).click()
  await sent(page, 'retryGroup')
}, {fake: {scenario: 'groups', partialGroup: true, simulate: false}})

await test('queue set members: Remove on failed, cancelled and retry-waiting items; Remove failed in the set actions', async (page) => {
  const row = page.locator('.group-row[data-group="grp-trip"]').first()
  const counts = async () => (await row.locator('.group-counts').first().textContent()).trim()
  assert.match(await counts(), /^3 saved · 1 failed · 1 retrying · 1 cancelled/)
  await row.getByRole('listitem', {name: 'Lisbon walk 2'}).getByRole('button', {name: 'Remove'}).waitFor()
  // The set action removes every failed member and keeps the rest.
  await row.locator('.group-head').getByRole('button', {name: 'Remove failed'}).click()
  await sent(page, 'removeFailed')
  assert.equal((await last(page, 'removeFailed')).id, 'grp-trip')
  await page.waitForFunction(() => !document.querySelector('[aria-label="Lisbon walk 2"]'))
  assert.match(await counts(), /^3 saved · 1 retrying · 1 cancelled/)
  // A cancelled member has its own Remove.
  await row.getByRole('listitem', {name: 'Lisbon walk 5'}).getByRole('button', {name: 'Remove'}).click()
  await page.waitForFunction(() => !document.querySelector('[aria-label="Lisbon walk 5"]'))
  // A retry-waiting member: Remove sits in its menu and stops the retry first.
  await row.getByRole('button', {name: 'More actions for Lisbon walk 4'}).click()
  await page.getByRole('menuitem', {name: 'Remove', exact: true}).click()
  await sent(page, 'remove')
  await page.waitForFunction(() => !document.querySelector('[aria-label="Lisbon walk 4"]'))
  assert.match(await counts(), /^3 saved/)
  assert.doesNotMatch(await counts(), /failed|retrying|cancelled/)
}, {fake: {scenario: 'groups', partialGroup: true, simulate: false}})

await test('queue and library group sets render in the grid view too', async (page) => {
  await finished(page)
  await page.getByTitle('Grid view').click()
  await page.locator('.lib-grid .group-card[data-group="grp-album"]').waitFor()
}, {fake: {scenario: 'groups'}})

// ---------------------------------------------------------------- automatic retries & engine

await test('automatic retry shows a truthful countdown; engine refresh shows an inline notice', async (page) => {
  await page.getByText(/Retrying in \d+ s \(2 of 3\)/).first().waitFor()
  const waiting = page.getByRole('listitem', {name: /Coastal timelapse/})
  assert.equal(await waiting.getByText('Waiting for a free slot').count(), 0)
  await page.getByText('Updating engine, then retrying (2 of 3)').waitFor()
  await page.getByRole('status').filter({hasText: 'Updating download engine…'}).waitFor()
  // The countdown ticks locally, without a push per second
  const first = await waiting.locator('.retry-note').textContent()
  await page.waitForTimeout(2200)
  assert.notEqual(await waiting.locator('.retry-note').textContent(), first)
}, {fake: {scenario: 'groups', simulate: false}})

await test('engine version and status are not on main surfaces; a broken engine offers Fix', async (page) => {
  assert.equal(await page.locator('.engine-chip').count(), 0)
  assert.equal(await page.getByText(/Engine 20\d\d/).count(), 0)
  await page.getByText('The download engine needs attention').waitFor()
  await page.getByRole('button', {name: 'Fix', exact: true}).click()
  await sent(page, 'engineUpdate')
}, {fake: {engine: 'error'}})

await test('a gallery engine that installs when needed is not a problem', async (page) => {
  await page.getByRole('navigation', {name: 'Main navigation'}).waitFor()
  await page.waitForTimeout(500)
  assert.equal(await page.getByText(/needs attention|is missing/).count(), 0)
  await page.getByRole('navigation', {name: 'Main navigation'}).getByRole('button', {name: 'Settings', exact: true}).click()
  await page.getByRole('navigation', {name: 'Settings sections'}).getByRole('button', {name: /Updates|About Keepfin/}).first().click()
  // the per-tool list is folded away; the badge is there, the status line above it is not a warning
  await page.getByText('Installs when needed').waitFor({state: 'attached'})
  assert.equal(await page.getByText('Needs attention', {exact: true}).count(), 0)
}, {fake: {engine: 'gallery-later'}})

await test('healthy engine shows nothing on the Downloads screen', async (page) => {
  await page.getByRole('navigation', {name: 'Main navigation'}).waitFor()
  assert.equal(await page.locator('.engine-notice, .engine-chip').count(), 0)
  assert.equal(await page.getByText(/Updating download engine|needs attention/).count(), 0)
})

// ---------------------------------------------------------------- notification focus is one-shot

await test('notification click focuses a collection once; later renders and scrolling are not overridden', async (page) => {
  await page.evaluate(() => {
    window.__scrolls = 0
    const original = Element.prototype.scrollIntoView
    Element.prototype.scrollIntoView = function (...args) {
      window.__scrolls++
      return original.apply(this, args)
    }
  })
  await page.evaluate(() => window.__fake.push({type: 'reveal', tab: 'Library', group: 'grp-album'}))
  const album = page.locator('.group-library[data-group="grp-album"]')
  await album.waitFor()
  await page.waitForFunction(() => window.__scrolls === 1)
  await page.waitForFunction(() => document.querySelector('.group-library[data-group="grp-album"]')?.classList.contains('group-flash'))
  await page.waitForFunction(() => !document.querySelector('.group-library[data-group="grp-album"]')?.classList.contains('group-flash'), null, {timeout: 4000})
  // Structural pushes, expanding, scrolling: nothing re-focuses
  await album.getByRole('button', {name: /Hide 5 items/}).click()
  await page.evaluate(() => window.scrollTo(0, 0))
  await page.evaluate(() => window.keepfin.request({op: 'engineUpdate'}))
  await page.waitForTimeout(600)
  assert.equal(await page.evaluate(() => window.__scrolls), 1)
  assert.equal(await album.evaluate((el) => el.classList.contains('group-flash')), false)
  // A later click focuses again
  await page.evaluate(() => window.__fake.push({type: 'reveal', tab: 'Library', group: 'grp-album'}))
  await page.waitForFunction(() => window.__scrolls === 2)
}, {fake: {scenario: 'groups', simulate: false}})

const updBtn = (page) => page.getByRole('button', {name: /Update available|Downloading \d+%|Restart to update|App updates/})
const promptDialog = (page, name = 'Keepfin 0.4.1 is available') => page.getByRole('dialog', {name})
const later = async (page) => {
  const dialog = promptDialog(page)
  await dialog.waitFor({timeout: 6000})
  await dialog.getByRole('button', {name: 'Later'}).click()
  await dialog.waitFor({state: 'detached'})
}

await test('updates: one dialog with release highlights, then a labelled pill that opens the sheet and downloads', async (page) => {
  const dialog = promptDialog(page)
  await dialog.waitFor({timeout: 6000})
  assert.equal(await page.locator('.upd-pill').count(), 1, 'pill is already in the header')
  // Highlights: this platform's bullets only, at most three, plain text.
  await dialog.getByText('Adds Spotify playlist matching and per-track tags.').waitFor()
  assert.equal(await dialog.locator('.update-bullets li').count(), 3)
  assert.equal(await dialog.getByText('Share-sheet').count(), 0)
  await dialog.getByRole('button', {name: 'Skip this version'}).waitFor()
  await dialog.getByRole('button', {name: 'Update now'}).waitFor()
  await dialog.getByRole('button', {name: 'Later'}).click()
  await dialog.waitFor({state: 'detached'})
  const button = page.getByRole('button', {name: 'Update available · v0.4.1'})
  assert.equal((await button.textContent()).trim(), 'Update available')
  assert.equal(await button.getAttribute('title'), 'Update available · v0.4.1')
  await button.click()
  const pop = page.getByRole('dialog', {name: 'App updates'})
  await pop.getByText('Spotify playlist').waitFor()
  await pop.getByRole('button', {name: 'Download v0.4.1'}).click()
  await sent(page, 'updateDownload')
  await pop.getByRole('button', {name: 'Restart to update'}).waitFor({timeout: 8000})
  await pop.getByRole('button', {name: 'Restart to update'}).click()
  await sent(page, 'updateInstall')
  assert.equal(await promptDialog(page).count(), 0, 'Later is not asked again in the same launch')
}, {fake: {update: 'available'}})

await test('updates: Update now downloads inside the dialog, then offers the restart', async (page) => {
  const dialog = promptDialog(page)
  await dialog.waitFor({timeout: 6000})
  await dialog.getByRole('button', {name: 'Update now'}).click()
  await sent(page, 'updateDownload')
  const ready = promptDialog(page, 'Keepfin 0.4.1 is ready')
  await ready.getByRole('button', {name: 'Restart to update'}).waitFor({timeout: 8000})
  await ready.getByRole('button', {name: 'Restart to update'}).click()
  await sent(page, 'updateInstall')
}, {fake: {update: 'available'}})

await test('updates: Skip this version stores the version, removes the pill and stays quiet after relaunch', async (page) => {
  const dialog = promptDialog(page)
  await dialog.waitFor({timeout: 6000})
  await dialog.getByRole('button', {name: 'Skip this version'}).click()
  await dialog.waitFor({state: 'detached'})
  assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem('keepfin-update-skipped'))), '0.4.1')
  await page.getByRole('button', {name: 'App updates'}).waitFor()
  assert.equal(await page.locator('.upd-pill').count(), 0)
  await page.reload()
  await page.getByRole('button', {name: 'App updates'}).waitFor()
  await page.waitForTimeout(1800)
  assert.equal(await promptDialog(page).count(), 0)
  assert.equal(await page.locator('.upd-pill').count(), 0)
  // A newer version than the skipped one asks again.
  await page.evaluate(() => localStorage.setItem('keepfin-update-skipped', JSON.stringify('0.4.0')))
  await page.reload()
  await promptDialog(page).waitFor({timeout: 6000})
}, {fake: {update: 'available'}})

await test('updates: the pill closes for this version only, and Later asks again next launch', async (page) => {
  await later(page)
  await page.getByRole('button', {name: 'Hide update notice for v0.4.1'}).click()
  assert.equal(await page.locator('.upd-pill').count(), 0)
  assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem('keepfin-update-hidden'))), '0.4.1')
  assert.equal(await page.evaluate(() => localStorage.getItem('keepfin-update-skipped')), null)
  await page.reload()
  await promptDialog(page).waitFor({timeout: 6000})
  assert.equal(await page.locator('.upd-pill').count(), 0, 'the pill stays hidden for this version')
}, {fake: {update: 'available'}})

await test('updates: portable offers View releases only', async (page) => {
  const dialog = promptDialog(page)
  await dialog.waitFor({timeout: 6000})
  assert.equal(await dialog.getByRole('button', {name: 'Update now'}).count(), 0)
  await dialog.getByRole('button', {name: 'View releases'}).click()
  await sent(page, 'updateReleases')
  await dialog.waitFor({state: 'detached'})
  await updBtn(page).click()
  const pop = page.getByRole('dialog', {name: 'App updates'})
  assert.equal(await pop.getByRole('button', {name: /^Download/}).count(), 0)
  await pop.getByRole('button', {name: 'View releases'}).click()
  assert.equal((await calls(page, 'updateReleases')).length, 2)
}, {fake: {update: 'portable'}})

await test('updates: downloading shows progress; ready shows restart', async (page) => {
  await updBtn(page).click()
  const pop = page.getByRole('dialog', {name: 'App updates'})
  await pop.getByRole('progressbar').waitFor()
  await pop.getByRole('button', {name: 'Cancel'}).click()
  await sent(page, 'updateCancel')
}, {fake: {update: 'downloading'}})

await test('updates: up to date is neutral, Check now works, no badge', async (page) => {
  const button = page.getByRole('button', {name: 'App updates'})
  await button.waitFor()
  assert.equal(await page.locator('.upd-pill').count(), 0)
  await page.waitForTimeout(1600)
  assert.equal(await promptDialog(page).count(), 0, 'no dialog without an update')
  await button.click()
  const pop = page.getByRole('dialog', {name: 'App updates'})
  await pop.getByText('You’re on v0.4.0').waitFor()
  await pop.getByRole('button', {name: 'Check now'}).click()
  assert.equal((await calls(page, 'updateCheck')).length, 1)
  await page.keyboard.press('Escape')
  await pop.waitFor({state: 'detached'})
}, {fake: {update: 'current'}})

await test('updates: stale check triggers updateCheck once on startup', async (page) => {
  await sent(page, 'updateCheck')
  await page.waitForTimeout(500)
  assert.equal((await calls(page, 'updateCheck')).length, 1)
}, {fake: {update: 'stale'}})

await test('updates: fresh check does not call updateCheck on startup', async (page) => {
  await page.getByRole('button', {name: 'App updates'}).waitFor()
  await page.waitForTimeout(600)
  assert.equal((await calls(page, 'updateCheck')).length, 0)
}, {fake: {update: 'current'}})

await test('whats new: opens after an update, stores version, reopenable from popover', async (page) => {
  const dialog = page.getByRole('dialog', {name: 'What’s new in v0.4.0'})
  await dialog.getByText('Rebuilt interface').waitFor()
  await dialog.getByRole('button', {name: 'Done'}).click()
  assert.equal(await page.evaluate(() => localStorage.getItem('keepfin-last-seen-version')), '0.4.0')
  await page.getByRole('button', {name: 'App updates'}).click()
  await page.getByRole('button', {name: 'What’s new in v0.4.0'}).click()
  await dialog.getByText('Rebuilt interface').waitFor()
}, {fake: {update: 'current', lastSeen: '0.3.12'}})

await test('whats new: not shown on same version', async (page) => {
  await page.getByRole('button', {name: 'App updates'}).waitFor()
  await page.waitForTimeout(600)
  assert.equal(await page.getByRole('dialog', {name: /What’s new/}).count(), 0)
}, {fake: {update: 'current', lastSeen: '0.4.0'}})

await registerLibrarySuite({test, calls, last, sent, field})
await registerSpacesV2Suite({test, calls, last, sent})
await registerGuideSuite({test, last, sent})
await registerActivitySuite({test, calls, last, sent})
await registerRefreshSuite({test, calls, last, sent})
await registerBeta2Suite({test, calls, last, sent})
await registerStorageSuite({test, sent})
await registerCopiesSuite({test, sent})
await registerDevicesSuite({test, sent})
await registerDownloadOnSuite({test, sent})
await registerCombineSuite({test, sent})
await registerWhereSuite({test, sent})
await registerAwaySuite({test, last, sent})
await registerAppIconSuite({test, last, sent})
await registerSettingsSuite({test, sent})
await registerAtelierSuite({test, calls, last, sent})
await registerUiFixesSuite({test, sent})
await registerPlayerStylesSuite({test, calls})
await registerSongVideosSuite({test, calls, last, sent, capture})
await registerPreviewSuite({test, calls, last, sent, field, capture})
await registerHomeFollowSuite({test, calls, last})

await browser.close()
await srv.close()
console.log(results.join('\n'))
console.log(failed ? `\n${failed} failed` : '\nall passed')
process.exit(failed ? 1 : 0)
