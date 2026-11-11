// Features & how-to guide, moving/copying a whole collection to another space, and the "Saved" notice that
// appears once when the queue drains. Registered by smoke-ui-flows.mjs, so `npm run gui:ui-test` runs them.
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'

const V2 = {libraryScenario: 'v2'}
const IDLE = {scenario: 'empty', simulate: false}
const shots = process.env.GUIDE_SHOTS
if (shots) fs.mkdirSync(shots, {recursive: true})
const shoot = async (page, name) => {
  if (shots) await page.screenshot({path: path.join(shots, `${name}.png`)})
}

export async function registerGuideSuite({test, last, sent}) {
  const mainNav = (page) => page.getByRole('navigation', {name: 'Main navigation'})
  const settingsNav = (page) => page.getByRole('navigation', {name: 'Settings sections'})
  const side = (page) => page.getByRole('navigation', {name: 'Your library'})
  const openGuide = async (page) => {
    await page.locator('aside.rail').getByRole('button', {name: 'Settings', exact: true}).click()
    await settingsNav(page).getByRole('button', {name: 'Help'}).click()
    await page.getByRole('searchbox', {name: 'Search features'}).waitFor()
  }
  const card = (page, title) => page.locator('.guide-card').filter({has: page.getByRole('button', {name: title})})

  for (const mode of ['dark', 'light']) {
    await test(`guide (${mode}): opens from Settings, hides Android-only items, search, Try it`, async (page) => {
      await openGuide(page)
      await page.getByRole('heading', {name: 'Downloading'}).waitFor()
      assert.equal(await page.getByRole('button', {name: 'Share straight to Keepfin'}).count(), 0, 'Android-only features are hidden')
      // Expand a card: summary, numbered steps and the desktop note.
      const follow = card(page, 'Keep a playlist or channel in sync')
      await follow.getByRole('button', {name: 'Keep a playlist or channel in sync'}).click()
      await follow.getByText('Checks run while Keepfin is open.').waitFor()
      assert.ok((await follow.locator('ol li').count()) >= 2)
      await shoot(page, `guide-${mode}`)
      // Search narrows to matching cards and opens them.
      await page.getByRole('searchbox', {name: 'Search features'}).fill('sync')
      await page.waitForFunction(() => document.querySelectorAll('.guide-card').length > 0 && document.querySelectorAll('.guide-card').length < 12)
      assert.equal(await page.getByRole('button', {name: 'Keep a playlist or channel in sync'}).getAttribute('aria-expanded'), 'true')
      await page.getByRole('searchbox', {name: 'Search features'}).fill('zzzz-nothing')
      await page.getByText(/Nothing matches/).waitFor()
      await page.getByRole('searchbox', {name: 'Search features'}).fill('Download from a link')
      const paste = card(page, 'Download from a link')
      await paste.getByRole('button', {name: /^Try it/}).click()
      await page.locator('textarea').first().waitFor()
      assert.equal(await settingsNav(page).count(), 0, 'Try it leaves Settings for Downloads')
    }, {theme: mode})
  }

  await test('guide: reachable from the Library', async (page) => {
    await mainNav(page).getByRole('button', {name: 'Library'}).click()
    await page.getByRole('button', {name: 'How to use Keepfin'}).click()
    await page.getByRole('searchbox', {name: 'Search features'}).waitFor()
  })
  await test('guide: See all features in What’s new', async (page) => {
    const dialog = page.getByRole('dialog', {name: /What’s new/})
    await dialog.getByRole('button', {name: 'See all features'}).click()
    await page.getByRole('searchbox', {name: 'Search features'}).waitFor()
    assert.equal(await page.getByRole('dialog').count(), 0)
  }, {fake: {update: 'current', lastSeen: '0.3.12'}})

  // ------------------------------------------------------------ move / copy a whole collection
  const pickSpace = async (page, title, space) => {
    const dialog = page.getByRole('dialog', {name: title})
    await dialog.waitFor()
    assert.equal(await dialog.getByRole('radio', {name: /^Music/}).count(), 0, 'the collection’s current space is not offered')
    await dialog.getByRole('radio', {name: new RegExp('^' + space)}).click()
    return dialog
  }
  for (const mode of ['dark', 'light']) {
    await test(`collection tile menu (${mode}): Move to space… sends the collection id and reports the result`, async (page) => {
      await mainNav(page).getByRole('button', {name: 'Library'}).click()
      await side(page).getByRole('button', {name: /^Music/}).click()
      await page.getByRole('tab', {name: /^Collections/}).click()
      await page.getByRole('button', {name: 'More actions for Night drive'}).click({force: true})
      await page.getByRole('menuitem', {name: 'Copy to space…'}).waitFor()
      await page.getByRole('menuitem', {name: 'Move to space…'}).click()
      const dialog = await pickSpace(page, 'Move to space…', 'Downloads')
      await shoot(page, `move-to-space-${mode}`)
      await dialog.getByRole('button', {name: 'Move', exact: true}).click()
      await sent(page, 'libraryAddFrom')
      const call = await last(page, 'libraryAddFrom')
      assert.deepEqual(call.collectionIds, ['pl-night'])
      assert.deepEqual(call.ids, [])
      assert.equal(call.addMode, 'move')
      assert.equal(call.space, 'downloads')
      await page.getByText('Moved 3 files to Downloads').waitFor()
    }, {fake: V2, theme: mode})
  }
  await test('collection detail: Copy to space… copies, and single items offer Move to… and Copy to…', async (page) => {
    await mainNav(page).getByRole('button', {name: 'Library'}).click()
    await side(page).getByRole('button', {name: /^Music/}).click()
    await page.getByRole('tab', {name: /^Collections/}).click()
    await page.getByRole('region', {name: 'Library content'}).getByRole('button', {name: /^Night drive/}).click()
    await page.getByRole('button', {name: 'More actions for Night drive', exact: true}).click()
    await page.getByRole('menuitem', {name: 'Move to space…'}).waitFor()
    await page.getByRole('menuitem', {name: 'Copy to space…'}).click()
    const dialog = await pickSpace(page, 'Copy to space…', 'Downloads')
    await dialog.getByRole('button', {name: 'Copy', exact: true}).click()
    await page.getByText('Copied 3 items to Downloads.').waitFor()
    const call = await last(page, 'libraryAddFrom')
    assert.deepEqual(call.collectionIds, ['pl-night'])
    assert.equal(call.addMode, 'copy')
    // A downloaded collection’s page has the same two actions.
    await page.getByRole('navigation', {name: 'Breadcrumb'}).getByRole('button', {name: 'Collections'}).click()
    await page.getByRole('region', {name: 'Library content'}).getByRole('button', {name: /^Road Trip Mix/}).click()
    await page.getByRole('button', {name: 'More actions for Road Trip Mix', exact: true}).click()
    await page.getByRole('menuitem', {name: 'Move to space…'}).waitFor()
    await page.getByRole('menuitem', {name: 'Copy to space…'}).waitFor()
    await page.keyboard.press('Escape')
    // A single item.
    await page.getByRole('navigation', {name: 'Breadcrumb'}).getByRole('button', {name: 'Collections'}).click()
    await page.getByRole('tab', {name: /^Songs/}).click()
    await page.getByRole('button', {name: /^Cold Front/}).click({button: 'right'})
    await page.getByRole('menuitem', {name: 'Move to…'}).waitFor()
    await page.getByRole('menuitem', {name: 'Copy to…'}).click()
    const one = page.getByRole('dialog', {name: 'Copy to…'})
    await one.getByRole('radio', {name: /^Downloads/}).click()
    await one.getByRole('button', {name: 'Copy', exact: true}).click()
    await page.waitForFunction(() => window.__calls.filter((c) => c.op === 'libraryAddFrom').length === 2)
    const single = await last(page, 'libraryAddFrom')
    assert.deepEqual(single.ids, ['a-front'])
    assert.equal(single.addMode, 'copy')
  }, {fake: V2})

  // ------------------------------------------------------------ the Collections list has its own actions
  await test('collections list: New collection creates one, and the list can be narrowed by kind', async (page) => {
    await mainNav(page).getByRole('button', {name: 'Library'}).click()
    await page.getByRole('group', {name: 'Show in Library'}).getByRole('button', {name: 'Playlists', exact: true}).click()
    const toolbar = page.getByRole('toolbar', {name: 'Library toolbar'})
    await toolbar.getByLabel('Show', {exact: true}).selectOption('yours')
    await page.getByRole('region', {name: 'Library content'}).getByRole('button', {name: /^Night drive/}).waitFor()
    assert.equal(await page.getByRole('region', {name: 'Library content'}).getByRole('button', {name: /^Road Trip Mix/}).count(), 0, 'downloaded collections are filtered out')
    await toolbar.getByRole('button', {name: 'New playlist'}).click()
    const dialog = page.getByRole('dialog', {name: 'New playlist'})
    await dialog.getByLabel('Name').fill('Rainy day')
    await dialog.getByRole('button', {name: 'Create'}).click()
    await sent(page, 'collectionSave')
    assert.equal((await last(page, 'collectionSave')).collection.name, 'Rainy day')
  }, {fake: V2})

  // ------------------------------------------------------------ the "Saved" notice
  const addJob = async (page, id, title) => {
    await page.evaluate(
      ([id, title]) =>
        window.__fake.addJob({id, url: `https://music.youtube.com/watch?v=${id}`, title, provider: 'YouTube Music', kind: 'audio', format: {type: 'audio', target: 'mp3', quality: '320'}, status: 'downloading', percent: 50, space: 'music', totalBytes: 1e6, downloadedBytes: 5e5}),
      [id, title],
    )
    await page.getByText(title).first().waitFor()
  }
  const complete = (page, id) => page.evaluate((id) => window.__fake.completeJob(id), id)
  const savedToasts = (page) => page.locator('.toast').filter({hasText: /^Saved/})
  for (const mode of ['dark', 'light']) {
    await test(`saved notice (${mode}): one summary when the queue drains, closable, newer replaces older`, async (page) => {
      for (const n of [1, 2, 3]) await addJob(page, `s${n}`, `Song ${n}`)
      await page.getByText('Song 3').first().waitFor()
      await complete(page, 's1')
      await complete(page, 's2')
      await page.waitForTimeout(400)
      assert.equal(await savedToasts(page).count(), 0, 'nothing while downloads are still running')
      await complete(page, 's3')
      const summary = savedToasts(page)
      await summary.getByText('Saved 3 items to Music').waitFor()
      assert.equal(await summary.count(), 1)
      await shoot(page, `saved-toast-${mode}`)
      // Closing it removes it.
      await summary.getByRole('button', {name: 'Dismiss notification'}).click()
      await summary.waitFor({state: 'detached'})
      // A newer one replaces an older one that is still showing.
      await addJob(page, 's4', 'Song 4')
      await complete(page, 's4')
      await savedToasts(page).getByText('Saved to Music').waitFor()
      await addJob(page, 's5', 'Song 5')
      await addJob(page, 's6', 'Song 6')
      await complete(page, 's5')
      await complete(page, 's6')
      await savedToasts(page).getByText('Saved 2 items to Music').waitFor()
      assert.equal(await savedToasts(page).count(), 1, 'the older notice was replaced')
    }, {fake: IDLE, theme: mode})
  }
  await test('saved notice: hides itself after a few seconds unless hovered or focused', async (page) => {
    await addJob(page, 'h1', 'Hover Song')
    await complete(page, 'h1')
    const notice = savedToasts(page)
    await notice.waitFor()
    await notice.hover()
    await page.waitForTimeout(6800)
    assert.equal(await notice.count(), 1, 'still there while hovered')
    await page.mouse.move(5, 5)
    await notice.waitFor({state: 'detached', timeout: 9000})
  }, {fake: IDLE})
}
