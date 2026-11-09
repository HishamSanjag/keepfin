// The download preview (dev-docs/plans/DOWNLOAD-PREVIEW.md): one window for every case, with Extras apart; putting it away keeps the
// review. Registered by smoke-ui-flows.mjs. The synthetic bridge never certifies what the real engine does.
import assert from 'node:assert/strict'

const YT = 'https://www.youtube.com/watch?v=lofoten8k'
// a YouTube link with a real 11-character video id, which is what Both needs to keep the link itself
const YT_LINK = 'https://www.youtube.com/watch?v=dQw4w9WgXcQ'
const MUSIC = 'https://music.youtube.com/watch?v=tidal88'
const YTM_SONG = 'https://music.youtube.com/watch?v=ytm1'
const PLAYLIST = 'https://www.youtube.com/playlist?list=PLfocus'
const ALBUM = 'https://open.spotify.com/album/1A2B3C4D5E6F'
const GALLERY = 'https://www.instagram.com/p/C9harbour/'
const CAROUSEL = 'https://www.instagram.com/p/C9carousel/'
const PRIVATE = 'https://www.youtube.com/watch?v=private01'
// an Instagram post that is only readable once Keepfin is signed in to Instagram (the fake bridge)
const LOCKED = 'https://www.instagram.com/p/Locked01/'
const V2 = {libraryScenario: 'v2'}

export async function registerPreviewSuite({test, calls, last, sent, field, capture}) {
  const keep = (page) => page.getByRole('radiogroup', {name: 'What to keep'})
  const win = (page) => page.getByRole('dialog')
  const focusedTag = (page) => page.evaluate(() => document.activeElement?.tagName)

  await test('preview: one window with a main tab and Extras; Esc and Close put it away, keep the link and the choices, and return focus', async (page) => {
    await field(page).fill(YT_LINK)
    const dialog = page.getByRole('dialog', {name: /Clip from youtube.com/})
    await dialog.waitFor()
    await page.getByRole('radiogroup', {name: 'Video quality'}).waitFor()
    assert.deepEqual(await dialog.getByRole('tab').allTextContents(), ['What to save', 'Extras'])
    assert.deepEqual(await keep(page).getByRole('radio').allTextContents(), ['Video', 'Audio', 'Both'])
    // the bottom is always Save to, Download on… and Download
    await dialog.getByRole('button', {name: /^Save to:/}).waitFor()
    await dialog.locator('.download-btn').waitFor()
    // a choice made now must still be there when the window comes back
    await page.getByRole('radio', {name: /^1080p, H.264/}).click()
    await page.keyboard.press('Escape')
    await dialog.waitFor({state: 'hidden'})
    assert.equal(await field(page).inputValue(), YT_LINK, 'the link stays in the field')
    assert.equal(await focusedTag(page), 'TEXTAREA', 'focus returns to where it was')
    await page.getByRole('button', {name: 'Open review'}).click()
    await dialog.waitFor()
    assert.equal(await page.getByRole('radio', {name: /^1080p, H.264/}).getAttribute('aria-checked'), 'true', 'the review was kept, not read again')
    assert.equal((await calls(page, 'inspect')).length, 1)
    // Close does the same as Esc, and Esc from the reopened window returns to the button it was opened from
    await dialog.getByRole('button', {name: 'Close', exact: true}).click()
    await dialog.waitFor({state: 'hidden'})
    await page.getByRole('button', {name: 'Open review'}).focus()
    await page.keyboard.press('Enter')
    await dialog.waitFor()
    await page.keyboard.press('Escape')
    await dialog.waitFor({state: 'hidden'})
    assert.equal(await page.evaluate(() => document.activeElement?.textContent?.trim()), 'Open review')
    // Clear is what discards it
    await page.getByRole('button', {name: 'Clear link'}).click()
    assert.equal(await field(page).inputValue(), '')
    assert.equal(await page.getByRole('button', {name: 'Open review'}).count(), 0)
  })

  await test('preview: the downloads list behind the window does not move when a link is added', async (page) => {
    const list = page.locator('.downloads-list')
    await list.waitFor()
    const before = await list.boundingBox()
    await field(page).fill(YT)
    await win(page).waitFor()
    assert.deepEqual(await list.boundingBox(), before)
    await page.keyboard.press('Escape')
    assert.deepEqual(await list.boundingBox(), before)
  })

  await test('preview, playlist: closing keeps the selection and does not search again; Keep in sync is on the main tab with its interval, never in Extras', async (page) => {
    await field(page).fill(PLAYLIST)
    await page.getByRole('list', {name: 'Collection entries'}).waitFor({timeout: 8000})
    await page.getByPlaceholder('Filter 64 entries').waitFor({timeout: 20000})
    assert.deepEqual(await win(page).getByRole('tab').allTextContents(), ['Videos · 64', 'Extras'])
    const row = page.getByRole('region', {name: 'Keep in sync'})
    await row.getByRole('switch', {name: 'Keep in sync'}).click()
    await row.getByLabel('How often to check for new ones').waitFor()
    await page.locator('.entries .entry:not(.entry-loading) input[type=checkbox]').first().click()
    const selected = await page.locator('.toolbar-count').textContent()
    // Extras: the queue choice and the other groups, but not Keep in sync
    await page.getByRole('tab', {name: /^Extras/}).click()
    assert.equal(await row.isVisible(), false)
    assert.equal(await page.locator('.extras').getByText('Keep in sync').count(), 0)
    await page.getByRole('radiogroup', {name: 'How to queue the selected entries'}).waitFor()
    await page.keyboard.press('Escape')
    await win(page).waitFor({state: 'hidden'})
    await page.getByRole('button', {name: 'Open review'}).click()
    await win(page).waitFor()
    // it comes back on the tab it was left on
    await page.getByRole('tab', {name: /^Extras/}).waitFor()
    assert.equal(await page.getByRole('tab', {name: /^Extras/}).getAttribute('aria-selected'), 'true')
    await page.getByRole('tab', {name: /^Videos/}).click()
    assert.equal(await page.locator('.toolbar-count').textContent(), selected)
    assert.equal(await row.getByRole('switch', {name: 'Keep in sync'}).getAttribute('aria-checked'), 'true')
    assert.equal((await calls(page, 'startDiscovery')).length, 1, 'the list was not searched again')
  }, {fake: V2})

  await test('preview, album: no Keep in sync, no Cut a part; the tab counts the songs', async (page) => {
    await field(page).fill(ALBUM)
    await page.getByPlaceholder('Filter 9 entries').waitFor({timeout: 20000})
    assert.deepEqual(await win(page).getByRole('tab').allTextContents(), ['Songs · 9', 'Extras'])
    assert.equal(await page.getByRole('region', {name: 'Keep in sync'}).count(), 0)
    await page.getByRole('tab', {name: /^Extras/}).click()
    assert.equal(await page.getByRole('radiogroup', {name: 'Cut a part'}).count(), 0, 'one cut cannot fit many songs')
  }, {fake: V2})

  await test('preview: Extras counts what was changed from the defaults, and nothing when it is back', async (page) => {
    await capture(page, YT)
    const extras = page.getByRole('tab', {name: /^Extras/})
    await extras.click()
    assert.equal((await extras.textContent()).trim(), 'Extras')
    const file = page.getByRole('group', {name: 'What goes in the file'})
    const cover = file.getByRole('button', {name: 'Cover'})
    const before = await cover.getAttribute('aria-pressed')
    await cover.click()
    assert.equal((await extras.textContent()).trim(), 'Extras · 1')
    await page.getByRole('group', {name: 'Also save beside the file'}).getByRole('button', {name: 'Description'}).click()
    assert.equal((await extras.textContent()).trim(), 'Extras · 2')
    await cover.click()
    await page.getByRole('group', {name: 'Also save beside the file'}).getByRole('button', {name: 'Description'}).click()
    assert.equal(await cover.getAttribute('aria-pressed'), before)
    assert.equal((await extras.textContent()).trim(), 'Extras')
  })

  await test('preview, song with no video found: Audio, and Both is off with the reason on the line', async (page) => {
    await capture(page, YTM_SONG)
    assert.equal(await keep(page).getByRole('radio', {name: 'Audio'}).getAttribute('aria-checked'), 'true')
    await page.getByText('No official music video was found, so Both is off.').waitFor()
    assert.equal(await keep(page).getByRole('radio', {name: 'Both'}).isDisabled(), true)
  })

  await test('preview, a song you already have: Audio says so and the button says Download anyway', async (page) => {
    await capture(page, MUSIC)
    const note = page.getByRole('status').filter({hasText: 'Already in Music'})
    await note.waitFor()
    await note.getByRole('button', {name: 'Play'}).waitFor()
    assert.match(await page.locator('.download-btn').textContent(), /^Download anyway/)
  })

  await test('preview, photo post: no Video, Audio or Both; the tab is the photos and there is no Extras', async (page) => {
    await field(page).fill(GALLERY)
    const dialog = win(page)
    await dialog.getByRole('tab', {name: /^Photos · \d+/}).waitFor()
    assert.equal(await dialog.getByRole('tab').count(), 1)
    assert.equal(await keep(page).count(), 0)
    await page.locator('.download-btn').click()
    await sent(page, 'enqueue')
    assert.equal((await last(page, 'enqueue')).format.type, 'gallery')
  })

  await test('preview, photo post: a photo left out is missing from the request and the button says the count', async (page) => {
    await field(page).fill(GALLERY)
    const dialog = win(page)
    const button = page.locator('.download-btn')
    await button.waitFor()
    assert.equal(await dialog.getByRole('button', {name: /^Photo \d/}).count(), 8)
    assert.match(await button.textContent(), /^Download 8 photos$/)
    await dialog.getByRole('button', {name: 'Photo 3'}).click()
    assert.equal(await dialog.getByRole('button', {name: 'Photo 3'}).getAttribute('aria-pressed'), 'false')
    await dialog.getByText('7 of 8 chosen. Click a photo to leave it out.').waitFor()
    assert.match(await button.textContent(), /^Download 7 photos$/)
    await button.click()
    await sent(page, 'enqueue')
    assert.deepEqual((await last(page, 'enqueue')).format, {type: 'gallery', items: [1, 2, 4, 5, 6, 7, 8]})
  })

  await test('preview, photo post with a video: the button names photos and videos; the video tile shows its cover and a video mark; no item chosen disables Download', async (page) => {
    await field(page).fill(CAROUSEL)
    const dialog = win(page)
    const button = page.locator('.download-btn')
    await button.waitFor()
    assert.match(await button.textContent(), /^Download 6 photos, 1 video$/)
    await dialog.getByText('7 of 7 chosen. Click an item to leave it out.').waitFor()
    const video = dialog.getByRole('button', {name: 'Video 4'})
    await video.locator('.gallery-play').waitFor()
    await video.locator('.thumb img').waitFor()
    await dialog.getByRole('button', {name: 'Photo 2'}).click()
    await dialog.getByText('6 of 7 chosen.').waitFor()
    assert.match(await button.textContent(), /^Download 5 photos, 1 video$/)
    for (const name of ['Photo 1', 'Video 4', 'Photo 3', 'Photo 5', 'Photo 6', 'Photo 7']) await dialog.getByRole('button', {name}).click()
    await dialog.getByText('0 of 7 chosen.').waitFor()
    assert.equal(await button.isDisabled(), true)
    await dialog.getByRole('button', {name: 'Photo 2'}).click()
    await dialog.getByRole('button', {name: 'Photo 3'}).click()
    assert.match(await button.textContent(), /^Download 2 photos$/)
    await button.click()
    await sent(page, 'enqueue')
    assert.deepEqual((await last(page, 'enqueue')).format, {type: 'gallery', items: [2, 3]})
  })

  await test('preview, reading: the window opens at once with placeholders and Cancel; Cancel says it could not be read, and Try again reads it again', async (page) => {
    await field(page).fill(YT)
    const dialog = page.getByRole('dialog', {name: /^Reading formats from youtube\.com/})
    await dialog.waitFor()
    await dialog.locator('.sk-row').first().waitFor()
    await dialog.getByRole('button', {name: 'Cancel'}).click()
    const problem = page.getByRole('dialog', {name: 'This link could not be read'})
    await problem.waitFor()
    await problem.getByText('Inspection cancelled.').waitFor()
    await problem.getByRole('button', {name: 'Try again'}).click()
    await page.getByRole('dialog', {name: /^Reading formats/}).waitFor()
    assert.equal((await calls(page, 'inspect')).length, 2)
  }, {fake: {latency: 4}})

  await test('preview, could not read: the message, Try again, and Sign in when that can help', async (page) => {
    // example.com is no site Keepfin can sign in to: the plain message with Try again, no sign-in button for a site
    await field(page).fill('https://example.com/not-media')
    const problem = page.getByRole('dialog', {name: 'This link could not be read'})
    await problem.getByText(/No video or images were found/).waitFor()
    assert.equal(await problem.getByRole('tab').count(), 0, 'no tabs on a link that could not be read')
    await problem.getByRole('button', {name: 'Try again'}).waitFor()
    assert.equal(await problem.getByRole('button', {name: /^Sign in to/}).count(), 0)
  })

  await test('preview, sign-in needed (design A): the site says so, Download is off, and Sign in to the site opens the window and reads the link again', async (page) => {
    await field(page).fill(LOCKED)
    const dialog = page.getByRole('dialog', {name: 'Instagram link'})
    await dialog.getByText('Instagram shows this post only to signed-in people.').waitFor()
    await dialog.getByText(/Your password stays with Instagram/).waitFor()
    assert.equal(await dialog.getByRole('tab').count(), 0)
    assert.equal(await dialog.locator('.download-btn').isDisabled(), true, 'Download stays off until signed in')
    await dialog.getByRole('button', {name: 'Not now'}).waitFor()
    await dialog.getByRole('button', {name: 'Sign in to Instagram'}).click()
    assert.deepEqual((await last(page, 'signInOpen')).site, 'instagram', 'only the site key is sent')
    // signed in: the link is read again by itself and the photos are shown
    await page.getByRole('dialog').getByRole('tab', {name: /^Photos · \d+/}).waitFor()
    assert.equal((await calls(page, 'inspect')).length, 2)
    assert.equal(await page.locator('.download-btn').isEnabled(), true)
  })

  await test('preview, sign-in needed: Not now keeps the honest message; a window closed before signing in says so and keeps Download off', async (page) => {
    await field(page).fill(LOCKED)
    const dialog = page.getByRole('dialog', {name: 'Instagram link'})
    await dialog.getByRole('button', {name: 'Sign in to Instagram'}).click()
    await dialog.getByText(/The window was closed before the sign-in finished/).waitFor()
    assert.equal(await dialog.locator('.download-btn').isDisabled(), true)
    assert.equal((await calls(page, 'inspect')).length, 1, 'no new look when nothing was signed in')
    await dialog.getByRole('button', {name: 'Not now'}).click()
    const problem = page.getByRole('dialog', {name: 'This link could not be read'})
    await problem.getByText(/Sign-in required/).waitFor()
    await problem.getByRole('button', {name: 'Try again'}).waitFor()
  }, {fake: {signInWindow: 'closed'}})

  await test('preview, sign-in needed on a video link: the same design, for the site of the link', async (page) => {
    await field(page).fill(PRIVATE)
    const dialog = page.getByRole('dialog', {name: 'YouTube link'})
    await dialog.getByText('YouTube shows this post only to signed-in people.').waitFor()
    await dialog.getByRole('button', {name: 'Sign in to YouTube'}).waitFor()
  })

  await test('preview, several links: Quality for all changes every link, a link that failed has Try again, Dismiss all discards', async (page) => {
    await field(page).fill([YT, MUSIC, PRIVATE].join('\n'))
    const dialog = win(page)
    await dialog.getByRole('button', {name: /Download all 2/}).waitFor({timeout: 8000})
    assert.deepEqual(await dialog.getByRole('tab').allTextContents(), ['Links · 3', 'Extras'])
    await dialog.getByRole('button', {name: 'Try again'}).waitFor()
    await dialog.getByLabel('Format', {exact: true}).selectOption('audio')
    await dialog.getByLabel('Quality for all').selectOption('audio-m4a')
    for (const select of await dialog.locator('.batch-actions select').all()) assert.equal(await select.inputValue(), 'a:m4a')
    await dialog.getByRole('button', {name: 'Dismiss all'}).click()
    assert.equal(await field(page).inputValue(), '')
    assert.equal(await page.getByRole('dialog').count(), 0)
  })

  await test('preview, one video: a Format row and one list of every size, no fold, the top row is Best, Download names the selected row', async (page) => {
    await field(page).fill(YT)
    const list = page.getByRole('radiogroup', {name: 'Video quality'})
    await list.waitFor()
    assert.equal(await page.getByRole('button', {name: /More sizes/}).count(), 0, 'nothing is behind a fold')
    assert.equal(await page.getByRole('radiogroup', {name: 'All video formats'}).count(), 0, 'the same sizes are not listed twice')
    const dialog = win(page)
    assert.deepEqual(await dialog.getByRole('radiogroup', {name: 'Container'}).getByRole('radio').allTextContents(), ['MP4', 'MKV', 'WebM'])
    await dialog.getByText('Format', {exact: true}).first().waitFor()
    const rows = list.getByRole('radio')
    assert.equal(await rows.count(), 12, 'every size the engine listed, once')
    const names = await rows.evaluateAll((nodes) => nodes.map((node) => node.getAttribute('aria-label')))
    assert.equal(new Set(names).size, names.length, 'no row twice')
    assert.match(await rows.first().textContent(), /Best/)
    assert.equal(await list.locator('[aria-checked="true"]').count(), 1, 'a row is always selected')
    const sizeOf = async (row) => (await row.locator('.size-row-size').textContent()).trim()
    const buttonSize = async () => ((await page.locator('.download-btn').textContent()).split('·')[1] || '').trim()
    assert.equal(await buttonSize(), await sizeOf(list.locator('[aria-checked="true"]')))
    await rows.nth(3).click()
    assert.equal(await rows.nth(3).getAttribute('aria-checked'), 'true')
    assert.equal(await buttonSize(), await sizeOf(rows.nth(3)))
  }, {fake: {simulate: false}})

  await test('preview, a reel with two sizes: converted sizes carry a "converted" note and send the source format with scaleHeight', async (page) => {
    await field(page).fill('https://www.instagram.com/reel/C9reel01/')
    const list = page.getByRole('radiogroup', {name: 'Video quality'})
    await list.waitFor()
    const rows = list.getByRole('radio')
    assert.equal(await rows.count(), 4)
    const served = rows.nth(1)
    assert.doesNotMatch(await served.textContent(), /converted/)
    const converted = list.locator('[data-row="480p-converted"]')
    assert.match(await converted.textContent(), /^480p.*converted/)
    assert.equal(await converted.getAttribute('title'), 'Keepfin makes this size from the 540p version after downloading')
    assert.match(await converted.locator('.size-row-size').textContent(), /^≈/, 'an estimate carries the approximate marker')
    await converted.click()
    await page.locator('.download-btn').click()
    const {format} = await last(page, 'enqueue')
    assert.equal(format.formatId, 'reel-540')
    assert.equal(format.scaleHeight, 480)
    assert.equal(format.maxHeight, 540)
  })

  await test('preview, one song: Format row (Original, MP3, M4A, Opus, FLAC, WAV), qualities for a lossy format', async (page) => {
    await field(page).fill(MUSIC)
    const dialog = win(page)
    const formats = dialog.getByRole('radiogroup', {name: 'Audio format'})
    await formats.waitFor()
    assert.deepEqual(await formats.getByRole('radio').allTextContents(), ['Original', 'MP3', 'M4A', 'Opus', 'FLAC', 'WAV'])
    const qualities = dialog.getByRole('radiogroup', {name: 'Audio quality'})
    assert.equal(await dialog.getByRole('button', {name: /More sizes/}).count(), 0)
    assert.deepEqual(await qualities.locator('.size-row-label').allTextContents(), ['Best', '320 kbps', '256 kbps', '192 kbps', '128 kbps'])
    assert.equal(await qualities.locator('[data-row="320"]').getAttribute('aria-checked'), 'true', 'High (MP3 320) stays the default')
    const buttonSize = ((await page.locator('.download-btn').textContent()).split('·')[1] || '').trim()
    assert.equal((await qualities.locator('[data-row="320"] .size-row-size').textContent()).trim(), buttonSize)
    await qualities.locator('[data-row="128"]').click()
    await page.locator('.download-btn').click()
    await sent(page, 'enqueue')
    assert.equal((await last(page, 'enqueue')).format.quality, '128')
  }, {fake: {simulate: false}})

  await test('preview, one song: FLAC has no list and Original lists the streams', async (page) => {
    await field(page).fill(MUSIC)
    const dialog = win(page)
    const formats = dialog.getByRole('radiogroup', {name: 'Audio format'})
    await formats.waitFor()
    await formats.getByRole('radio', {name: 'FLAC'}).click()
    assert.equal(await dialog.getByRole('radiogroup', {name: 'Audio quality'}).count(), 0)
    assert.equal(await dialog.getByRole('radiogroup', {name: 'Original audio streams'}).count(), 0)
    await formats.getByRole('radio', {name: 'Original'}).click()
    const streams = dialog.getByRole('radiogroup', {name: 'Original audio streams'})
    await streams.waitFor()
    assert.equal(await streams.locator('[aria-checked="true"]').count(), 1)
    assert.match(await streams.getByRole('radio').first().textContent(), /Best/)
  }, {fake: {simulate: false}})

  await test('preview, Add to playlist: in the footer beside Save to, off by default, travels with the download, New playlist makes one', async (page) => {
    await field(page).fill(YT)
    const dialog = win(page)
    const button = dialog.getByRole('button', {name: /Choose a playlist for this download/})
    await button.waitFor()
    assert.equal((await button.textContent()).trim(), 'Add to playlist')
    await button.click()
    const items = await page.getByRole('menuitemradio').allTextContents()
    assert.equal(items[0], 'No playlist')
    assert.ok(items.includes('Night drive'), 'the own playlists of the person')
    await page.getByRole('menuitemradio', {name: 'Night drive'}).click()
    assert.equal((await button.textContent()).trim(), 'Night drive')
    await button.click()
    await page.getByRole('menuitem', {name: /^New playlist/}).click()
    await page.getByLabel('Name').fill('Weekend')
    await page.getByRole('button', {name: 'Create', exact: true}).click()
    await page.waitForFunction(() => document.querySelector('dialog.preview [aria-label*="Choose a playlist"]')?.textContent?.trim() === 'Weekend')
    await dialog.locator('.download-btn').click()
    await sent(page, 'enqueue')
    assert.match((await last(page, 'enqueue')).joinPlaylist, /^pl-/)
  }, {fake: V2})

  await test('preview, Add to playlist: nothing chosen sends nothing, several links offer it', async (page) => {
    await field(page).fill(YT)
    const dialog = win(page)
    await dialog.getByRole('button', {name: /Choose a playlist for this download/}).waitFor()
    await dialog.locator('.download-btn').click()
    await sent(page, 'enqueue')
    assert.equal((await last(page, 'enqueue')).joinPlaylist, undefined, 'off by default')
  }, {fake: V2})

  await test('preview, Add to playlist: several links offer it', async (page) => {
    await field(page).fill([YT, MUSIC].join('\n'))
    await win(page).getByRole('button', {name: /Download all 2/}).waitFor({timeout: 8000})
    await win(page).getByRole('button', {name: /Choose a playlist for this download/}).waitFor()
  }, {fake: V2})

  await test('preview, photo post: no Add to playlist', async (page) => {
    await field(page).fill(GALLERY)
    await win(page).locator('.download-btn').waitFor({timeout: 8000})
    assert.equal(await win(page).getByRole('button', {name: /Choose a playlist/}).count(), 0)
  }, {fake: V2})

  await test('preview, a playlist: Format and Quality for all are two dropdowns on one line, the entries stay the main thing', async (page) => {
    await field(page).fill(PLAYLIST)
    const dialog = win(page)
    await dialog.getByRole('list', {name: 'Collection entries'}).waitFor({timeout: 8000})
    assert.equal(await dialog.getByRole('radiogroup', {name: 'What to keep'}).count(), 0)
    const format = dialog.getByLabel('Format', {exact: true})
    const quality = dialog.getByLabel('Quality for all')
    assert.equal(await format.evaluate((node) => node.tagName), 'SELECT')
    assert.equal(await quality.evaluate((node) => node.tagName), 'SELECT')
    const [a, b] = [await format.boundingBox(), await quality.boundingBox()]
    assert.ok(Math.abs(a.y - b.y) < 8, 'one line')
    await dialog.getByRole('button', {name: /Choose a playlist for this download/}).waitFor()
  }, {fake: V2})

  for (const scale of [80, 140]) {
    await test(`preview at ${scale}%: the window fits the page, its body does not scroll sideways and the buttons stay inside`, async (page) => {
      await field(page).fill(PLAYLIST)
      await page.getByRole('list', {name: 'Collection entries'}).waitFor({timeout: 8000})
      await page.getByRole('button', {name: /Download \d+ selected/}).waitFor({timeout: 8000})
      for (const tab of ['Videos', 'Extras']) {
        await page.getByRole('tab', {name: new RegExp('^' + tab)}).click()
        const fit = await page.evaluate(() => {
          const dialog = document.querySelector('dialog.preview')
          const box = dialog.getBoundingClientRect()
          const scroller = dialog.querySelector('.preview-scroll')
          const button = dialog.querySelector('.download-btn').getBoundingClientRect()
          const zoom = Number(getComputedStyle(document.documentElement).zoom) || 1
          return {
            inside: box.left >= -1 && box.top >= -1 && box.right <= innerWidth + 1 && box.bottom <= innerHeight + 1,
            sideways: scroller.scrollWidth - scroller.clientWidth,
            button: button.left >= box.left - 1 && button.right <= box.right + 1 && button.bottom <= box.bottom + 1,
            zoom,
          }
        })
        assert.equal(fit.inside, true, JSON.stringify(fit))
        assert.ok(fit.sideways <= 1, JSON.stringify(fit))
        assert.equal(fit.button, true, JSON.stringify(fit))
      }
    }, {scale, fake: V2})
  }
}
