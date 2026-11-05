// Where your files are (dev-docs/plans/WHERE-FILES.md): the same view at the top of Settings → Storage and in Settings → Devices (below Your devices),
// the files that only one place holds with Keep a copy…, the sentence for a library in one place, and the per-file line in Details….
// Registered by smoke-ui-flows.mjs (run it alone with ONLY=where). Data comes from the fake bridge (synthetic): it proves what the
// renderer shows and asks for; the counting itself is tested in gui/desktop/store/where.test.ts.
// WHERE_SHOTS=<folder> saves screenshots of the view, light and dark.
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'

const shots = process.env.WHERE_SHOTS
if (shots) fs.mkdirSync(shots, {recursive: true})
const shoot = async (page, name, locator) => {
  if (!shots) return
  const file = path.join(shots, `${name}.png`)
  if (locator) await locator.screenshot({path: file})
  else await page.screenshot({path: file, fullPage: true})
}

export async function registerWhereSuite({test, sent}) {
  const settings = async (page, category) => {
    await page.getByRole('navigation', {name: 'Main navigation'}).getByRole('button', {name: 'Settings', exact: true}).click()
    await page.getByRole('navigation', {name: 'Settings sections'}).getByRole('button', {name: category}).click()
  }
  // Devices: the view below Your devices
  const view = (page) => page.getByRole('region', {name: 'Where your files are', exact: true})
  const list = (page) => page.getByRole('list', {name: 'Places that hold your files'})
  const row = (page, name) => list(page).getByRole('listitem').filter({has: page.getByText(name, {exact: true})})
  // Storage: Your library, the sentence on whether every file has a second copy and the places with the main storage first
  const library = (page) => page.getByRole('region', {name: 'Your library', exact: true})
  const places = (page) => page.getByRole('list', {name: 'Places that keep your library'})
  const place = (page, name) => places(page).getByRole('listitem').filter({has: page.getByText(name, {exact: true})})
  const overflow = (page) => page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)

  for (const mode of ['dark', 'light']) {
    await test(`where (${mode}): Storage opens with whether every file has a second copy, then a row per place`, async (page) => {
      await settings(page, 'Storage')
      await sent(page, 'whereState')
      await places(page).waitFor()
      // the sentence, from the shared core
      await library(page).getByRole('heading', {name: '15 files have only one copy'}).waitFor()
      await library(page).getByText('12 videos are only in your main storage on this PC. 3 songs are only on Pixel 9.').waitFor()
      // the order: the main storage first, then the devices, then the copies
      const names = await places(page).locator('.where-name strong').allTextContents()
      assert.deepEqual(names, ['Main storage', 'Laptop', 'Pixel 9', 'Backup drive (E:)'])
      const main = place(page, 'Main storage')
      assert.match(await main.textContent(), /Main/)
      assert.match(await main.textContent(), /every copy is made from it/)
      assert.match(await main.textContent(), /1,548 files/)
      assert.match(await main.textContent(), /up to date/)
      // a phone keeps what it has: how many of the library's files; it is away, so it says when it was last seen
      const phone = place(page, 'Pixel 9')
      assert.match(await phone.textContent(), /410 of 1,548 files/)
      assert.match(await phone.textContent(), /6\.2 GB/)
      assert.match(await phone.textContent(), /offline · last seen 5 min ago/)
      assert.match(await place(page, 'Laptop').textContent(), /up to date 2 min ago/)
      assert.match(await place(page, 'Backup drive (E:)').textContent(), /up to date 3 h ago/)
      // Your library is above the main storage
      const order = await page.evaluate(() => [...document.querySelectorAll('.settings-group')].map((g) => g.getAttribute('aria-label')).slice(0, 2))
      assert.deepEqual(order, ['Your library', 'Main storage: your Keepfin folder'])
      await shoot(page, `where-storage-${mode}`, library(page))
      assert.equal(await overflow(page), 0, 'no sideways scrolling')
    }, {theme: mode, height: 1000, fake: {where: 'several'}})
  }

  await test('where: every state in words: copying with its count, waiting for Wi-Fi, not plugged in with the last copy, needs a decision', async (page) => {
    await settings(page, 'Storage')
    await places(page).waitFor()
    const row = place
    assert.match(await row(page, 'Work laptop').textContent(), /Music and 4 more/)
    assert.match(await row(page, 'Work laptop').textContent(), /copying · 40 of 210/)
    // a copy that has not finished has no size yet: its files so far
    assert.match(await row(page, 'Work laptop').textContent(), /40 files/)
    assert.match(await row(page, 'Car USB stick (F:)').textContent(), /last copied 9 days ago · not plugged in/)
    assert.match(await row(page, 'Car USB stick (F:)').textContent(), /Gym/)
    assert.match(await row(page, 'Google Drive').textContent(), /waiting for Wi-Fi/)
    assert.match(await row(page, 'OneDrive').textContent(), /Music, Videos/)
    assert.match(await row(page, 'OneDrive').textContent(), /needs a decision/)
    await shoot(page, 'where-storage-all-dark', library(page))
    assert.equal(await overflow(page), 0)
  }, {height: 1300, fake: {where: 'all'}})

  await test('where: a copy in progress shows its count; a drive that is not plugged in shows when it was last copied', async (page) => {
    await settings(page, 'Storage')
    await places(page).waitFor()
    assert.match(await place(page, 'Work laptop').textContent(), /copying · 40 of 210/)
  }, {fake: {where: 'copying'}})
  await test('where: a drive that is not plugged in', async (page) => {
    await settings(page, 'Storage')
    await places(page).waitFor()
    assert.match(await place(page, 'Car USB stick (F:)').textContent(), /last copied 9 days ago · not plugged in/)
  }, {fake: {where: 'unplugged'}})

  await test('where: the state follows a push (the copy finishes, the phone comes back)', async (page) => {
    await settings(page, 'Storage')
    await places(page).waitFor()
    await page.waitForFunction(() => !!window.__fake)
    await page.evaluate(() => window.__fake.whereChange('dev-phone', {status: {state: 'upToDate', at: Date.now() - 20_000}}))
    await place(page, 'Pixel 9').getByText('up to date just now').waitFor()
    assert.equal(await place(page, 'Pixel 9').getByText(/offline/).count(), 0)
  }, {fake: {where: 'several'}})

  await test('where: files that only one place holds are named, with Keep a copy… because some are on this computer', async (page) => {
    await settings(page, 'Storage')
    await places(page).waitFor()
    assert.equal(await library(page).getByRole('button', {name: 'Keep a copy…'}).count(), 1)
    // in Devices, one warning per kind; only this computer's files can be kept from here
    await settings(page, 'Devices')
    const warnings = view(page).getByRole('group', {name: 'Files that are in one place only'})
    await warnings.getByText('12 videos are only on this PC').waitFor()
    await warnings.getByText('3 songs are only on Pixel 9').waitFor()
    assert.equal(await warnings.getByRole('button', {name: /Keep a copy/}).count(), 1, 'a copy is taken from this computer: only its files can be kept')
  }, {fake: {where: 'several'}})

  await test('where: every file in two places shows no warning', async (page) => {
    await settings(page, 'Storage')
    await places(page).waitFor()
    await library(page).getByRole('heading', {name: 'Your library is in 3 places'}).waitFor()
    assert.equal(await library(page).getByText(/only on/).count(), 0)
    assert.equal(await library(page).getByRole('button', {name: /Keep a copy/}).count(), 0)
    // a copy says its type beside its name
    await library(page).getByRole('button', {name: /^Backup drive \(E:\) \(Backup\):/}).getByText('Backup', {exact: true}).waitFor()
  }, {fake: {where: 'safe'}})

  await test('where: Keep a copy… opens with the spaces of the files that have only one copy, and says so', async (page) => {
    await settings(page, 'Storage')
    await library(page).getByRole('button', {name: 'Keep a copy…'}).click()
    const wizard = page.getByRole('dialog', {name: 'Keep a copy'})
    await wizard.getByText('For the 12 files that are only on this PC').waitFor()
    await wizard.getByRole('heading', {name: 'Where should the copy live?'}).waitFor()
    await page.evaluate(() => (window.__pickedCopyPath = 'G:\\Spare drive'))
    await wizard.getByRole('button', {name: 'Choose a folder…'}).click()
    await wizard.getByText('412 GB free of 931 GB').waitFor()
    await wizard.getByRole('button', {name: 'Continue'}).click()
    await wizard.getByRole('heading', {name: 'What should it hold?'}).waitFor()
    assert.equal(await wizard.getByRole('radio', {name: /Only some of it/}).isChecked(), true)
    assert.equal(await wizard.getByRole('checkbox', {name: 'Videos'}).isChecked(), true)
    assert.equal(await wizard.getByRole('checkbox', {name: 'Music'}).isChecked(), false)
    assert.equal(await wizard.getByRole('button', {name: 'Continue'}).isDisabled(), false, 'a chosen space is a copy that can go on')
  }, {height: 1200, fake: {where: 'several'}})

  await test('where: Keep a copy… in Devices opens the same dialog in Storage with that space chosen', async (page) => {
    await settings(page, 'Devices')
    await view(page).getByRole('group', {name: 'Files that are in one place only'}).getByRole('button', {name: /Keep a copy of the videos/}).click()
    const wizard = page.getByRole('dialog', {name: 'Add a copy'})
    await page.evaluate(() => (window.__pickedCopyPath = 'G:\\Spare drive'))
    await wizard.getByRole('button', {name: 'Choose a folder…'}).click()
    await wizard.getByRole('button', {name: 'Continue'}).click()
    assert.equal(await wizard.getByRole('checkbox', {name: 'Videos'}).isChecked(), true)
  }, {height: 1200, fake: {where: 'several'}})

  await test('where: tapping a device opens its card in Devices; tapping a copy opens that copy in Storage', async (page) => {
    await settings(page, 'Storage')
    await place(page, 'Pixel 9').getByRole('button').click()
    // the Devices page, with that device\'s card open
    const card = page.getByRole('list', {name: 'Paired devices'}).getByRole('article', {name: 'Pixel 9', exact: true})
    await card.locator('.devices-card-body').waitFor()
    assert.equal(await card.locator('.devices-card-row').getAttribute('aria-expanded'), 'true')
    // the view in Devices opens a copy in Storage
    await list(page).waitFor()
    await row(page, 'Backup drive (E:)').getByRole('button').click()
    await page.getByRole('dialog', {name: 'Backup drive (E:)'}).waitFor()
    await page.keyboard.press('Escape')
    // the main storage is not a link: nothing to open
    assert.equal(await place(page, 'Main storage').getByRole('button').count(), 0)
  }, {height: 1000, fake: {where: 'several'}})

  await test('where: a library in one place says so and offers Keep a copy…', async (page) => {
    await settings(page, 'Storage')
    await places(page).waitFor()
    assert.equal(await places(page).getByRole('listitem').count(), 1)
    await library(page).getByRole('heading', {name: 'Your library is only on this PC'}).waitFor()
    await library(page).getByRole('button', {name: 'Keep a copy…'}).click()
    const wizard = page.getByRole('dialog', {name: 'Keep a copy'})
    await wizard.getByText('For your library, which is only on this PC').waitFor()
    await wizard.getByRole('heading', {name: 'Where should the copy live?'}).waitFor()
  }, {fake: {where: 'one'}})

  await test('where: Devices shows the view below Your devices', async (page) => {
    await settings(page, 'Devices')
    await sent(page, 'whereState')
    await list(page).waitFor()
    const order = await page.evaluate(() => [...document.querySelectorAll('.settings-group')].map((g) => g.getAttribute('aria-label')).filter((label) => label === 'Your devices' || label === 'Where your files are'))
    assert.deepEqual(order, ['Your devices', 'Where your files are'])
    assert.match(await row(page, 'Pixel 9').textContent(), /410 of 1,548 files/)
    await page.getByText('12 videos are only on this PC').waitFor()
  }, {fake: {where: 'several'}})

  await test('where: without a library the view is not there at all', async (page) => {
    await settings(page, 'Storage')
    await sent(page, 'whereState')
    await page.getByRole('region', {name: 'Keepfin folder'}).waitFor()
    assert.equal(await view(page).count(), 0)
  })

  for (const mode of ['dark', 'light']) {
    await test(`where (${mode}): Details… on a file says where it is: this PC, a phone that is away, a drive`, async (page) => {
      await page.getByRole('navigation', {name: 'Main navigation'}).getByRole('button', {name: 'Library'}).click()
      await page.getByRole('navigation', {name: 'Your library'}).getByRole('button', {name: /^Music/}).click()
      await page.getByRole('button', {name: /^Cold Front/}).click({button: 'right'})
      await page.getByRole('menuitem', {name: 'Details…'}).click()
      const dialog = page.getByRole('dialog', {name: 'Details'})
      await sent(page, 'whereItem')
      assert.ok((await page.evaluate(() => window.__calls.filter((c) => c.op === 'whereItem').at(-1))).id, 'the item is named in the request')
      await dialog.getByText('On: this PC, Pixel 9 (offline), Backup drive (E:)').waitFor()
      assert.match(await dialog.textContent(), /Song/)
      await shoot(page, `where-item-${mode}`, dialog)
    }, {theme: mode, fake: {where: 'several'}})
  }

  await test('where: a file only this computer has says "Only on this PC"', async (page) => {
    await page.getByRole('navigation', {name: 'Main navigation'}).getByRole('button', {name: 'Library'}).click()
    await page.getByRole('navigation', {name: 'Your library'}).getByRole('button', {name: /^Music/}).click()
    await page.getByRole('button', {name: /^Cold Front/}).click({button: 'right'})
    await page.getByRole('menuitem', {name: 'Details…'}).click()
    await page.getByRole('dialog', {name: 'Details'}).getByText('Only on this PC', {exact: true}).waitFor()
  }, {fake: {where: 'one'}})
}
