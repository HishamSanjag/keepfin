// What's new as pages after an update: one picture and paragraph per top change, then the full notes. Registered by smoke-ui-flows.mjs.
// The page texts come from content/whats-new/<version>/pages.json. WHATS_NEW_SHOTS=<folder> also saves the five pictures of 1.1.0
// (the fake bridge supplies the data and the photos are generated, so they show the screens, not real provider results).
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import {spawnSync} from 'node:child_process'
import {createRequire} from 'node:module'
import {fileURLToPath} from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')
const VERSION = '1.1.0'
const content = JSON.parse(fs.readFileSync(path.join(root, 'content/whats-new', VERSION, 'pages.json'), 'utf8'))
const pages = content.pages.filter((page) => page.windows)
const shots = process.env.WHATS_NEW_SHOTS
if (shots) fs.mkdirSync(shots, {recursive: true})

const SEEN = {update: 'current', appVersion: VERSION, lastSeen: VERSION}

// A generated scene in place of a photo (the fixtures' photo links point at hosts with no network).
const scene = (seed) => {
  const hue = (seed * 47 + 200) % 360
  return `<svg xmlns="http://www.w3.org/2000/svg" width="480" height="480"><defs><linearGradient id="s" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="hsl(${hue},55%,62%)"/><stop offset="1" stop-color="hsl(${(hue + 40) % 360},50%,28%)"/></linearGradient></defs><rect width="480" height="480" fill="url(#s)"/><circle cx="${120 + ((seed * 71) % 240)}" cy="150" r="46" fill="hsl(${(hue + 150) % 360},80%,85%)" opacity=".85"/><path d="M0 340 Q120 ${260 + ((seed * 13) % 50)} 240 330 T480 320 V480 H0Z" fill="hsl(${(hue + 20) % 360},45%,20%)"/></svg>`
}

export async function registerWhatsNewSuite({test}) {
  const dialog = (page) => page.getByRole('dialog', {name: /What’s new in 1\.1\.0/})
  const title = (page) => dialog(page).getByRole('heading', {level: 2})
  // A 4:3 cut around the box with a margin, kept inside the window, taken at the page's pixel ratio and scaled to 1200x900.
  const shoot = async (page, name, box, {margin = 16, top = false} = {}) => {
    const view = page.viewportSize()
    let width = Math.max(box.width + margin * 2, ((box.height + margin * 2) * 4) / 3)
    if (width > view.width) width = view.width
    const height = (width * 3) / 4
    const x = Math.min(Math.max(0, box.x + box.width / 2 - width / 2), view.width - width)
    const centred = box.y + box.height / 2 - height / 2
    const y = Math.min(Math.max(0, top ? box.y - margin : centred), view.height - height)
    const raw = path.join(shots, `${name}-raw.png`)
    await page.screenshot({path: raw, clip: {x, y, width, height}})
    const bin = createRequire(import.meta.url)('ffmpeg-static')
    const run = spawnSync(bin, ['-hide_banner', '-loglevel', 'error', '-y', '-i', raw, '-vf', 'scale=1200:900:flags=lanczos', '-compression_level', '9', path.join(shots, `${name}-windows.png`)], {encoding: 'utf8'})
    fs.rmSync(raw)
    if (run.status !== 0) throw new Error(`ffmpeg failed: ${run.stderr}`)
  }
  await test('whats new pages: a picture and paragraph per change, Next, arrows, dots, Full list and Done', async (page) => {
    const total = pages.length
    await dialog(page).waitFor()
    assert.equal(await page.locator('[id="release-notes-title"]').textContent(), `What’s new in ${VERSION} · 1 of ${total}`)
    await title(page).filter({hasText: pages[0].windows.title}).waitFor()
    await dialog(page).getByText(pages[0].windows.text).waitFor()
    const picture = dialog(page).locator('.wn-pic img')
    await picture.waitFor()
    assert.ok(await picture.evaluate((img) => img.complete && img.naturalWidth > 0), 'the picture loaded')
    await page.waitForTimeout(500) // the window finishes opening
    const first = await dialog(page).boundingBox()

    await dialog(page).getByRole('button', {name: 'Next'}).click()
    await title(page).filter({hasText: pages[1].windows.title}).waitFor()
    assert.match(await page.locator('[id="release-notes-title"]').textContent(), new RegExp(`2 of ${total}$`))
    await page.keyboard.press('ArrowRight')
    await title(page).filter({hasText: pages[2].windows.title}).waitFor()
    await page.keyboard.press('ArrowLeft')
    await title(page).filter({hasText: pages[1].windows.title}).waitFor()
    await dialog(page).getByRole('button', {name: 'Page 4'}).click()
    await title(page).filter({hasText: pages[3].windows.title}).waitFor()
    assert.equal(await dialog(page).getByRole('button', {name: 'Page 4'}).getAttribute('aria-current'), 'step')
    assert.equal(await dialog(page).getByRole('button', {name: 'Previous page'}).isEnabled(), true)

    await dialog(page).getByRole('button', {name: 'Full list'}).first().click()
    await title(page).filter({hasText: `Everything in ${VERSION}`}).waitFor()
    await dialog(page).getByText('Rebuilt interface with a real format picker').waitFor()
    const notes = await dialog(page).boundingBox()
    assert.deepEqual([notes.width, notes.height], [first.width, first.height], `the window keeps its size between pages ${JSON.stringify([first, notes])}`)
    await page.keyboard.press('ArrowRight')
    await title(page).filter({hasText: `Everything in ${VERSION}`}).waitFor()
    await dialog(page).getByRole('button', {name: 'Previous page'}).click()
    await title(page).filter({hasText: pages.at(-1).windows.title}).waitFor()
    await dialog(page).getByRole('button', {name: 'Full list', exact: true}).last().click()

    await dialog(page).getByRole('button', {name: 'Done'}).click()
    assert.equal(await page.getByRole('dialog').count(), 0)
    assert.equal(await page.evaluate(() => localStorage.getItem('keepfin-last-seen-version')), VERSION)
    // reopening shows the same pages from the first one
    await page.getByRole('button', {name: 'App updates'}).click()
    await page.getByRole('button', {name: `What’s new in v${VERSION}`}).click()
    await title(page).filter({hasText: pages[0].windows.title}).waitFor()
  }, {fake: {...SEEN, lastSeen: '1.0.2'}})

  await test('whats new pages: See all features on the last page opens the guide', async (page) => {
    await dialog(page).getByRole('button', {name: `Full list`, exact: true}).first().click()
    await dialog(page).getByRole('button', {name: 'See all features'}).click()
    await page.getByRole('searchbox', {name: 'Search features'}).waitFor()
    assert.equal(await page.getByRole('dialog').count(), 0)
  }, {fake: {...SEEN, lastSeen: '1.0.2'}})

  await test('whats new pages: a swipe moves a page and reduced motion keeps the pages', async (page) => {
    await title(page).filter({hasText: pages[0].windows.title}).waitFor()
    const box = await dialog(page).locator('.wn-stage').boundingBox()
    const y = box.y + box.height / 2
    await page.mouse.move(box.x + box.width - 40, y)
    await page.mouse.down()
    await page.mouse.move(box.x + 60, y, {steps: 5})
    await page.mouse.up()
    await title(page).filter({hasText: pages[1].windows.title}).waitFor()
    assert.equal(await page.locator('.wn-page').evaluate((el) => getComputedStyle(el).animationName), 'none')
  }, {fake: {...SEEN, lastSeen: '1.0.2'}, reducedMotion: true})

  await test('whats new pages: a version without pages shows the plain notes', async (page) => {
    const plain = page.getByRole('dialog', {name: 'What’s new in v0.4.0'})
    await plain.getByText('Rebuilt interface').waitFor()
    assert.equal(await plain.getByRole('button', {name: 'Next'}).count(), 0)
    assert.equal(await plain.locator('.wn-dot').count(), 0)
    await plain.getByRole('button', {name: 'See all features'}).waitFor()
  }, {fake: {update: 'current', lastSeen: '0.3.12'}})

  for (const mode of ['dark', 'light']) {
    await test(`whats new pages (${mode}): the picture sits inside the window at 140% size`, async (page) => {
      const picture = dialog(page).locator('.wn-pic')
      await picture.waitFor()
      await page.waitForTimeout(500)
      const [outer, inner] = [await dialog(page).boundingBox(), await picture.boundingBox()]
      assert.ok(inner.x >= outer.x && inner.x + inner.width <= outer.x + outer.width + 1, 'the picture fits across')
      assert.ok(inner.y + inner.height <= outer.y + outer.height + 1, 'and down')
      const foot = await dialog(page).locator('.wn-foot').boundingBox()
      assert.ok(foot.y + foot.height <= outer.y + outer.height + 1, 'the buttons stay inside')
    }, {fake: {...SEEN, lastSeen: '1.0.2'}, theme: mode, scale: 140, width: 1600, height: 1000})
  }

  if (!shots) return


  const settle = (page) => page.waitForTimeout(700)
  const nav = (page, name) => page.getByRole('navigation', {name: 'Main navigation'}).getByRole('button', {name})
  const photos = (page) =>
    page.route('https://instagram.fcdn/**', (route) => {
      const seed = Number(/-(\d+)\.jpg/.exec(route.request().url())?.[1] ?? 0)
      return route.fulfill({status: 200, contentType: 'image/svg+xml', body: scene(seed)})
    })
  const opts = {theme: 'dark', width: 1280, height: 960, deviceScaleFactor: 2, fake: {...SEEN}}
  const home = {...opts, width: 900, height: 1300, fake: {...SEEN, following: 'seeded', libraryScenario: 'v2'}}

  await test('whats new shot: photos', async (page) => {
    await photos(page)
    await page.locator('textarea').first().fill('https://www.instagram.com/p/C9harbour/')
    const post = page.getByRole('dialog')
    await post.getByRole('button', {name: 'Photo 3'}).click()
    await post.getByText('7 of 8 chosen. Click a photo to leave it out.').waitFor()
    await settle(page)
    await shoot(page, 'photos', await post.boundingBox())
  }, {...opts, height: 640})

  await test('whats new shot: signin', async (page) => {
    await nav(page, 'Downloads').click()
    await page.getByRole('radio', {name: /^Finished/}).click()
    const row = page.getByRole('listitem').filter({hasText: 'Signed in with Firefox'}).first()
    await row.waitFor()
    await settle(page)
    // a narrow cut of three rows so the text reads when the picture is small
    const [one, list] = [await row.boundingBox(), await page.getByRole('list').filter({has: row}).first().boundingBox()]
    await shoot(page, 'signin', {x: list.x, y: one.y - one.height + 6, width: 440, height: one.height * 4}, {margin: 12, top: true})
  }, {...opts, deviceScaleFactor: 3, fake: {...SEEN, signedIn: 'Signed in with Firefox', signedInFinished: true}})

  await test('whats new shot: mixes', async (page) => {
    await nav(page, 'Home').click()
    const [mixes, card] = [page.getByRole('region', {name: 'Made from your listening'}), page.getByRole('region', {name: 'New from artists you follow'})]
    await mixes.waitFor()
    await card.waitFor()
    await settle(page)
    const [a, b] = [await mixes.boundingBox(), await card.boundingBox()]
    const top = Math.min(a.y, b.y)
    await shoot(page, 'mixes', {x: Math.min(a.x, b.x), y: top, width: Math.max(a.width, b.width), height: Math.max(a.y + a.height, b.y + b.height) - top}, {top: true})
  }, {...home, width: 800, height: 1800})

  await test('whats new shot: arrange', async (page) => {
    await nav(page, 'Home').click()
    await page.getByRole('button', {name: 'Edit Home'}).click()
    await page.getByRole('group', {name: 'Edit page layout'}).waitFor()
    await settle(page)
    const watching = await page.getByRole('region', {name: 'Continue watching'}).boundingBox()
    const next = await page.getByRole('heading', {name: 'Made from your listening'}).boundingBox()
    await shoot(page, 'arrange', {x: watching.x, y: watching.y, width: watching.width, height: next.y + next.height + 30 - watching.y}, {top: true})
  }, home)

  await test('whats new shot: copies', async (page) => {
    await page.locator('aside.rail').getByRole('button', {name: 'Settings', exact: true}).click()
    await page.getByRole('navigation', {name: 'Settings sections'}).getByRole('button', {name: 'Storage'}).click()
    await page.getByRole('button', {name: 'Add a copy'}).first().click()
    const wizard = page.getByRole('dialog', {name: 'Add a copy'})
    await wizard.getByRole('radio', {name: 'A cloud account'}).check()
    await wizard.getByRole('button', {name: 'Add another account'}).click()
    await wizard.getByRole('button', {name: /Dropbox/}).click()
    // the steps after sign-in: the folder, then what it holds
    const everything = wizard.getByRole('radio', {name: /Everything/})
    for (let tries = 0; tries < 8 && !(await everything.isVisible()); tries++) {
      await wizard.getByRole('button', {name: 'Continue'}).click({timeout: 3000}).catch(() => {})
      await page.waitForTimeout(400)
    }
    await wizard.getByRole('button', {name: 'Continue'}).click()
    await wizard.getByRole('radio', {name: /Backup/}).check()
    await wizard.getByRole('button', {name: 'Continue'}).click()
    await wizard.getByRole('button', {name: 'Add this copy'}).click()
    await wizard.waitFor({state: 'detached'})
    const list = page.getByRole('list', {name: 'Places that keep your library'})
    await list.getByRole('button', {name: /^Dropbox:/}).waitFor()
    await list.scrollIntoViewIfNeeded()
    await settle(page)
    const [top, notice] = [await page.getByRole('heading', {name: 'Your library'}).boundingBox(), await page.getByText('Copy synced').first().boundingBox()]
    await shoot(page, 'copies', {x: top.x, y: top.y, width: (await list.boundingBox()).width, height: notice.y + notice.height + 30 - top.y}, {top: true})
  }, {...opts, width: 1000, height: 1300, fake: {...SEEN, copies: 'cloud-ok'}})
}