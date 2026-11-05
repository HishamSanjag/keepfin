// The Keepfin folder: first start, the setup window for an earlier version's files, and Settings → Storage.
// Registered by smoke-ui-flows.mjs, so `npm run gui:ui-test` runs them. Data comes from the fake bridge (synthetic).
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'

const shots = process.env.STORAGE_SHOTS
if (shots) fs.mkdirSync(shots, {recursive: true})
const shoot = async (page, name) => {
  if (shots) await page.screenshot({path: path.join(shots, `${name}.png`), fullPage: true})
}

export async function registerStorageSuite({test, sent}) {
  // sent() waits for a call; got() also returns the latest call of that op.
  const got = async (page, op) => {
    await sent(page, op)
    return page.evaluate((op) => [...window.__calls].reverse().find((c) => c.op === op), op)
  }
  const appShown = (page) => page.getByRole('navigation', {name: 'Main navigation'}).waitFor()

  // The start pages: Skip intro lands on How will you use Keepfin?, Next on the folder page.
  const toFolder = async (page) => {
    await page.getByRole('button', {name: 'Skip intro'}).click()
    await page.getByRole('heading', {name: 'How will you use Keepfin?'}).waitFor()
    await page.getByRole('button', {name: 'Next', exact: true}).click()
    await page.getByRole('heading', {name: 'Where should your files live?'}).waitFor()
  }
  const start = (page) => page.getByRole('button', {name: 'Start using Keepfin'})

  for (const mode of ['dark', 'light']) {
    await test(`start pages (${mode}): welcome, how it works, how you'll use it, where, then the app`, async (page) => {
      await page.getByRole('heading', {name: 'Keep the videos and music you love'}).waitFor()
      assert.equal(await page.getByRole('navigation', {name: 'Main navigation'}).count(), 0, 'the app waits behind the start pages')
      assert.equal(await page.getByRole('img', {name: 'Page 1 of 4'}).count(), 1)
      await shoot(page, `start-1-${mode}`)
      await page.getByRole('button', {name: 'Next', exact: true}).click()
      await page.getByRole('heading', {name: "Three steps, and it's yours"}).waitFor()
      await shoot(page, `start-2-${mode}`)
      await page.getByRole('button', {name: 'Next', exact: true}).click()
      await page.getByRole('heading', {name: 'How will you use Keepfin?'}).waitFor()
      assert.equal(await page.getByRole('button', {name: 'Skip intro'}).count(), 0, 'Skip intro only on the introduction')
      assert.equal(await page.getByRole('radio', {name: /^Keepfin Complete/}).isChecked(), true, 'Keepfin Complete is the default')
      await page.getByText('Recommended').waitFor()
      await shoot(page, `start-3-${mode}`)
      await page.getByRole('radio', {name: /^Just downloads/}).check({force: true})
      await page.getByRole('button', {name: 'Next', exact: true}).click()
      await page.getByText('C:\\Users\\you\\Keepfin').first().waitFor()
      await sent(page, 'storageInspectFolder')
      const shortcut = page.getByRole('switch', {name: 'Show my music in Windows Music'})
      assert.equal(await shortcut.isChecked(), true, 'the Windows Music shortcut is on by default')
      await page.getByRole('button', {name: 'Advanced setup ›'}).waitFor()
      await shoot(page, `start-4-${mode}`)
      await start(page).click()
      const create = await got(page, 'storageCreate')
      assert.equal(create.storage.path, 'C:\\Users\\you\\Keepfin')
      assert.equal(create.storage.musicShortcut, true)
      await appShown(page)
      // The answer to page 3 is saved once the folder exists.
      await page.waitForFunction(() => window.__calls.some((c) => c.op === 'updateSettings' && c.settings?.library?.enabled === false))
    }, {theme: mode, fake: {storagePhase: 'firstStart'}})
  }

  await test('start pages: Skip intro goes to the first setup page; Back comes back', async (page) => {
    await page.getByRole('button', {name: 'Skip intro'}).click()
    await page.getByRole('heading', {name: 'How will you use Keepfin?'}).waitFor()
    await page.getByRole('button', {name: 'Back', exact: true}).click()
    await page.getByRole('heading', {name: "Three steps, and it's yours"}).waitFor()
  }, {fake: {storagePhase: 'firstStart'}})

  await test('start pages: Advanced setup opens every storage step: start fresh, where, extra copies', async (page) => {
    await toFolder(page)
    await page.getByRole('button', {name: 'Advanced setup ›'}).click()
    await page.getByText('Welcome to Keepfin').waitFor()
    // Back from its first step returns to the start pages.
    await page.getByRole('button', {name: 'Back', exact: true}).click()
    await page.getByRole('heading', {name: 'Where should your files live?'}).waitFor()
    await page.getByRole('button', {name: 'Advanced setup ›'}).click()
    await shoot(page, 'first-start-1')
    await page.getByRole('button', {name: 'Start fresh'}).click()
    await page.getByText('C:\\Users\\you\\Keepfin').first().waitFor()
    await shoot(page, 'first-start-2')
    await page.getByRole('button', {name: 'Continue', exact: true}).click()
    await page.getByRole('list', {name: 'Places a copy can live'}).waitFor()
    await shoot(page, 'first-start-3')
    await page.getByRole('button', {name: 'Create my Keepfin folder'}).click()
    const create = await got(page, 'storageCreate')
    assert.equal(create.storage.path, 'C:\\Users\\you\\Keepfin')
    await appShown(page)
  }, {fake: {storagePhase: 'firstStart'}})

  await test('start pages: a folder inside OneDrive shows the cloud tip; a locked folder blocks Start', async (page) => {
    await toFolder(page)
    await page.evaluate(() => (window.__pickedPath = 'C:\\Users\\you\\OneDrive\\Cloud\\Keepfin'))
    await page.getByRole('button', {name: 'Change…'}).click()
    await page.getByText(/OneDrive/).first().waitFor()
    await shoot(page, 'first-start-cloud')
    // A cloud folder is advised against, not forbidden: Start comes back once the folder has been checked.
    await page.waitForFunction(() => [...document.querySelectorAll('button')].some((b) => b.textContent.trim() === 'Start using Keepfin' && !b.disabled))
    await page.evaluate(() => (window.__pickedPath = 'E:\\Locked\\Keepfin'))
    await page.getByRole('button', {name: 'Change…'}).click()
    await page.getByText('Keepfin cannot write to this folder. Pick another one.').first().waitFor()
    assert.equal(await start(page).isDisabled(), true)
  }, {fake: {storagePhase: 'firstStart'}})

  await test('start pages: picking a drive or a filled folder makes a Keepfin folder inside it', async (page) => {
    await toFolder(page)
    await page.evaluate(() => (window.__pickedPath = 'F:\\'))
    await page.getByRole('button', {name: 'Change…'}).click()
    await page.getByTestId('setup-path').getByText('F:\\Keepfin', {exact: true}).waitFor()
    await page.getByText('Keepfin makes a new folder named Keepfin in').waitFor()
    // A drive root is never offered as the Keepfin folder itself.
    assert.equal(await page.getByRole('button', {name: /itself/}).count(), 0)
    await page.evaluate(() => (window.__pickedPath = 'D:\\Stuff'))
    await page.getByRole('button', {name: 'Change…'}).click()
    await page.getByTestId('setup-path').getByText('D:\\Stuff\\Keepfin', {exact: true}).waitFor()
    await shoot(page, 'first-start-made-in')
    await page.getByRole('button', {name: 'Use D:\\Stuff itself'}).click()
    await page.getByTestId('setup-path').getByText('D:\\Stuff', {exact: true}).waitFor()
    await page.getByText('This folder already has files in it.').waitFor()
    await start(page).click()
    const create = await got(page, 'storageCreate')
    assert.equal(create.storage.path, 'D:\\Stuff')
  }, {fake: {storagePhase: 'firstStart'}})

  await test('start pages: I already have a Keepfin folder opens an existing one; a newer one offers read-only', async (page) => {
    await toFolder(page)
    await page.evaluate(() => (window.__pickedPath = 'D:\\Newer\\Keepfin'))
    await page.getByRole('button', {name: 'I already have a Keepfin folder'}).click()
    await page.getByRole('button', {name: 'Open read-only'}).waitFor()
    await page.evaluate(() => (window.__pickedPath = 'D:\\Existing\\Keepfin'))
    await page.getByRole('button', {name: 'I already have a Keepfin folder'}).click()
    await page.getByRole('button', {name: 'Open it'}).first().click()
    const open = await got(page, 'storageOpenExisting')
    assert.equal(open.storage.path, 'D:\\Existing\\Keepfin')
    await appShown(page)
  }, {fake: {storagePhase: 'firstStart'}})

  for (const mode of ['dark', 'light']) {
    await test(`setup window (${mode}): what you have, where, move or copy, the plan, bring it over, report`, async (page) => {
      await page.getByText('Keepfin setup').waitFor()
      // The welcome step explains the Keepfin folder before anything is counted or chosen.
      await page.getByRole('heading', {name: 'Keepfin now keeps everything in one folder'}).waitFor()
      await page.getByRole('list', {name: 'What the Keepfin folder gives you'}).waitFor()
      await shoot(page, `setup-0-${mode}`)
      await page.getByRole('button', {name: 'Get started'}).click()
      await sent(page, 'setupInventory')
      await page.getByLabel('Where your files are today').getByText('Music').first().waitFor()
      await shoot(page, `setup-1-${mode}`)
      await page.getByRole('button', {name: 'Continue', exact: true}).click()
      await page.getByRole('switch', {name: 'Show my music in Windows Music'}).waitFor()
      await page.getByRole('navigation', {name: 'Where the Keepfin folder will be'}).waitFor()
      await page.waitForTimeout(300)
      await shoot(page, `setup-2-${mode}`)
      await page.getByRole('button', {name: 'Continue', exact: true}).click()
      // Move or copy: the suggestion is preselected per source.
      await page.getByText('Suggested').first().waitFor()
      await shoot(page, `setup-3-${mode}`)
      await page.getByRole('button', {name: 'Review the plan'}).click()
      const plan = await got(page, 'setupPlan')
      assert.ok(plan.storage.choices && Object.keys(plan.storage.choices).length >= 1, 'choices are sent with the plan')
      await page.getByRole('heading', {name: 'The plan'}).waitFor()
      await shoot(page, `setup-4-${mode}`)
      await page.getByRole('button', {name: 'Start', exact: true}).click()
      await sent(page, 'setupStart')
      await page.getByRole('progressbar', {name: 'Setup progress'}).waitFor()
      await shoot(page, `setup-5-${mode}`)
      await page.getByRole('list', {name: 'Counted before and after'}).waitFor({timeout: 15000})
      await page.getByRole('heading', {name: 'Nearly there'}).waitFor()
      await shoot(page, `setup-6-${mode}`)
      await page.getByRole('button', {name: 'Save report'}).click()
      await sent(page, 'setupSaveReport')
      await page.getByRole('button', {name: 'Done', exact: true}).click()
      await appShown(page)
    }, {theme: mode, fake: {storagePhase: 'needsSetup'}})
  }

  await test('setup window: a full drive stops the run and returns to the choices', async (page) => {
    await page.evaluate(() => (window.__setupDiskFull = true))
    await page.getByRole('button', {name: 'Get started'}).click()
    await page.getByRole('button', {name: 'Continue', exact: true}).click()
    await page.getByRole('button', {name: 'Continue', exact: true}).click()
    await page.getByRole('button', {name: 'Review the plan'}).click()
    await page.getByRole('button', {name: 'Start', exact: true}).click()
    await page.getByText('The drive ran out of space').waitFor({timeout: 15000})
    await shoot(page, 'setup-disk-full')
    await page.getByRole('button', {name: 'Change the plan'}).click()
    await page.getByRole('button', {name: 'Review the plan'}).waitFor()
  }, {fake: {storagePhase: 'needsSetup'}})

  await test('setup window: an interrupted run resumes straight at the progress step', async (page) => {
    // Nothing runs after a restart: the window asks main to resume the run from its journal.
    const start = await got(page, 'setupStart')
    assert.equal(start.storage.runId, 'run-fake')
    await page.getByRole('progressbar', {name: 'Setup progress'}).waitFor()
    assert.equal(await page.getByRole('button', {name: 'Review the plan'}).count(), 0)
  }, {fake: {storagePhase: 'resumeSetup'}})

  await test('settings: dragging Interface size previews the number and applies the size on release; keys apply at once', async (page) => {
    await page.getByRole('navigation', {name: 'Main navigation'}).getByRole('button', {name: 'Settings', exact: true}).click()
    await page.getByRole('navigation', {name: 'Settings sections'}).getByRole('button', {name: 'Appearance'}).click()
    const slider = page.getByRole('slider', {name: 'Interface size'})
    await slider.waitFor()
    // "When Keepfin opens" comes first on Appearance now: the slider can start below the fold
    await slider.scrollIntoViewIfNeeded()
    const scale = () => page.evaluate(() => localStorage.getItem('keepfin-scale'))
    const box = await slider.boundingBox()
    const y = box.y + box.height / 2
    // 100% sits a third of the way along 80–140.
    await page.mouse.move(box.x + box.width / 3, y)
    await page.mouse.down()
    await page.mouse.move(box.x + box.width * 0.6, y, {steps: 8})
    await page.mouse.move(box.x + box.width * 0.75, y, {steps: 8})
    assert.equal(await scale(), '100', 'the size is not applied while dragging')
    const preview = await page.locator('.scale-value').textContent()
    assert.notEqual(preview, '100%', 'the number previews the new size')
    const after = await slider.boundingBox()
    assert.equal(Math.round(after.width), Math.round(box.width), 'the slider does not move under the pointer while dragging')
    await page.mouse.up()
    await page.waitForFunction((value) => localStorage.getItem('keepfin-scale') === value, preview.replace('%', ''))
    // The owner's report: dragging 140 → 100 landed on 125 after release. The value must land where the pointer let go
    // and stay there (the page rescales on release, so a late value read from the moved slider must be ignored).
    const dragTo = async (from, to) => {
      await slider.focus()
      await slider.press(from === 140 ? 'End' : 'Home')
      if (from === 100) for (let i = 0; i < 4; i++) await slider.press('ArrowRight')
      assert.equal(await scale(), String(from))
      // the page rescaled: bring the slider back on screen before dragging it
      await slider.scrollIntoViewIfNeeded()
      const rect = await slider.boundingBox()
      const at = (value) => rect.x + 8 + ((rect.width - 16) * (value - 80)) / 60
      const mid = rect.y + rect.height / 2
      await page.mouse.move(at(from), mid)
      await page.mouse.down()
      await page.mouse.move(at(to), mid, {steps: 12})
      await page.mouse.up()
      await page.waitForTimeout(600)
      assert.equal(await scale(), String(to), `dragging ${from} → ${to} lands on ${to}`)
      assert.equal(await page.locator('.scale-value').textContent(), `${to}%`)
      assert.equal(await slider.inputValue(), String(to))
    }
    await dragTo(140, 100)
    await dragTo(100, 140)
    await slider.focus()
    await slider.press('Home')
    assert.equal(await scale(), '80', 'keyboard steps apply at once')
    await page.getByRole('button', {name: 'Reset size'}).click()
    assert.equal(await scale(), '100')
  })

  await test('settings: Storage shows the folder, its health, Check files and the Windows Music switch', async (page) => {
    await page.getByRole('navigation', {name: 'Main navigation'}).getByRole('button', {name: 'Settings', exact: true}).click()
    await page.getByRole('navigation', {name: 'Settings sections'}).getByRole('button', {name: 'Storage'}).click()
    await sent(page, 'storageOverview')
    await page.getByText('C:\\Users\\you\\Keepfin').first().waitFor()
    await shoot(page, 'storage-overview')
    await page.getByRole('button', {name: 'Check files'}).click()
    await sent(page, 'storageCheckFiles')
    await page.getByRole('button', {name: 'Open folder'}).click()
    await sent(page, 'storageOpenFolder')
    await page.getByRole('switch', {name: 'Show in Windows Music'}).click()
    const toggle = await got(page, 'storageSetMusicShortcut')
    assert.equal(typeof toggle.storage.enabled, 'boolean')
  })

  await test('settings: Storage counts the music videos in a line of their own, only when there are some', async (page) => {
    await page.getByRole('navigation', {name: 'Main navigation'}).getByRole('button', {name: 'Settings', exact: true}).click()
    await page.getByRole('navigation', {name: 'Settings sections'}).getByRole('button', {name: 'Storage'}).click()
    await sent(page, 'storageOverview')
    await page.getByText('C:\\Users\\you\\Keepfin').first().waitFor()
    await page.getByRole('list', {name: 'What is in it'}).waitFor()
    assert.equal(await page.getByText(/^Music videos · /).count(), 0, 'no videos, no line')
  })
  await test('settings: Storage shows Music videos with their size', async (page) => {
    await page.getByRole('navigation', {name: 'Main navigation'}).getByRole('button', {name: 'Settings', exact: true}).click()
    await page.getByRole('navigation', {name: 'Settings sections'}).getByRole('button', {name: 'Storage'}).click()
    await page.getByRole('list', {name: 'What is in it'}).getByText('Music videos · 4.1 GB').waitFor()
  }, {fake: {musicVideoBytes: 4.1 * 1024 ** 3}})

  // Storage › Change location (dev-docs/plans/KEEPFIN-FOLDER-MOVE.md)
  const openStorage = async (page) => {
    await page.getByRole('navigation', {name: 'Main navigation'}).getByRole('button', {name: 'Settings', exact: true}).click()
    await page.getByRole('navigation', {name: 'Settings sections'}).getByRole('button', {name: 'Storage'}).click()
    await sent(page, 'storageOverview')
  }
  const openMove = async (page, picked) => {
    await openStorage(page)
    await page.getByRole('button', {name: 'Change location…'}).click()
    const dialog = page.getByRole('dialog', {name: 'Move your Keepfin folder'})
    await dialog.waitFor()
    await page.evaluate((picked) => (window.__pickedPath = picked), picked)
    await dialog.getByRole('button', {name: 'Change…'}).click()
    await dialog.getByText(picked).first().waitFor()
    return dialog
  }

  await test('change location: Where, Review, Start; Keepfin restarts into the move, then opens from the new place', async (page) => {
    const dialog = await openMove(page, 'D:\\Keepfin')
    await dialog.getByRole('button', {name: 'Review'}).click()
    assert.equal((await got(page, 'storageMoveCheck')).storage.path, 'D:\\Keepfin')
    await dialog.getByText('To copy 29.2 GB').waitFor()
    await shoot(page, 'move-review')
    await dialog.getByRole('button', {name: 'Start'}).click()
    assert.equal((await got(page, 'storageMoveRequest')).storage.path, 'D:\\Keepfin')
    await page.getByText('Moving your Keepfin folder').waitFor()
    await sent(page, 'storageMoveStart')
    await page.getByRole('heading', {name: 'Your Keepfin folder has moved'}).waitFor()
    await page.getByRole('button', {name: 'Open Keepfin'}).click()
    await appShown(page)
    await openStorage(page)
    await page.getByText('D:\\Keepfin').first().waitFor()
  })

  await test('change location: on the same drive nothing is copied, unless the old place is kept as a backup copy', async (page) => {
    const dialog = await openMove(page, 'C:\\Moved\\Keepfin')
    await dialog.getByRole('button', {name: 'Review'}).click()
    await dialog.getByText('Same drive: the folder is renamed in a moment, nothing is copied.').waitFor()
    assert.equal(await dialog.getByText('To copy 29.2 GB').count(), 0)
    await dialog.getByRole('switch', {name: 'Keep the old place as a backup copy'}).click()
    await dialog.getByText('The old place stays as a backup copy.', {exact: false}).waitFor()
    await dialog.getByText('To copy 29.2 GB').waitFor()
    await dialog.getByRole('button', {name: 'Start'}).click()
    assert.equal((await got(page, 'storageMoveRequest')).storage.keepOld, true)
    await page.getByText('Moving your Keepfin folder').waitFor()
  })

  await test('change location: a place inside the Keepfin folder is refused in plain words', async (page) => {
    const dialog = await openMove(page, 'C:\\Users\\you\\Keepfin\\Music\\Moved')
    await dialog.getByRole('button', {name: 'Review'}).click()
    await dialog.getByText('The new place cannot be inside your Keepfin folder.').waitFor()
    assert.equal(await page.evaluate(() => window.__calls.filter((c) => c.op === 'storageMoveRequest').length), 0)
  })

  await test('change location: Stop during the move keeps the folder where it was', async (page) => {
    const dialog = await openMove(page, 'D:\\Keepfin')
    await dialog.getByRole('button', {name: 'Review'}).click()
    await dialog.getByRole('button', {name: 'Start'}).click()
    await page.getByText('Copying and checking your files').waitFor()
    await page.getByRole('button', {name: 'Stop', exact: true}).click()
    await page.getByRole('button', {name: 'Stop and keep it where it was'}).click()
    await sent(page, 'storageMoveCancel')
    await appShown(page)
  }, {fake: {moveDelay: 3000}})

  await test('change location: spaces outside the Keepfin folder get a notice, and Bring them in moves their files in', async (page) => {
    await openStorage(page)
    await page.getByText('2 spaces are outside your Keepfin folder.').waitFor()
    await page.getByRole('button', {name: 'Bring them in'}).click()
    const dialog = page.getByRole('dialog', {name: 'Bring these spaces into your Keepfin folder'})
    await dialog.getByText('C:\\Users\\you\\Keepfin\\Kids').first().waitFor()
    await dialog.getByRole('button', {name: 'Start'}).click()
    await page.waitForFunction(() => window.__calls.filter((c) => c.op === 'spaceSave').length >= 2)
    const saved = await page.evaluate(() => window.__calls.filter((c) => c.op === 'spaceSave').map((c) => [c.spaceDef.id, c.spaceDef.folder]))
    assert.deepEqual(saved, [['kids', 'C:\\Users\\you\\Keepfin\\Kids'], ['podcasts', 'C:\\Users\\you\\Keepfin\\Podcasts']])
    await page.getByText('2 spaces are outside your Keepfin folder.').waitFor({state: 'detached'})
  }, {fake: {libraryScenario: 'v2'}})

  await test('space editor: a new space lives in the Keepfin folder; Use the default folder brings a linked one back', async (page) => {
    await page.getByRole('navigation', {name: 'Main navigation'}).getByRole('button', {name: 'Library'}).click()
    await page.getByRole('button', {name: 'Edit', exact: true}).first().click()
    await page.getByRole('button', {name: 'Edit Kids'}).click()
    const edit = page.getByRole('dialog', {name: 'Edit Kids'})
    await edit.getByRole('button', {name: 'Use the default folder'}).click()
    await page.getByRole('dialog', {name: 'Bring Kids into your Keepfin folder'}).getByRole('button', {name: 'Start'}).click()
    await page.waitForFunction(() => window.__calls.some((c) => c.op === 'spaceSave' && c.spaceDef.id === 'kids'))
    assert.equal(await page.evaluate(() => window.__calls.find((c) => c.op === 'spaceSave').spaceDef.folder), 'C:\\Users\\you\\Keepfin\\Kids')
    await page.getByRole('button', {name: 'New space'}).first().click()
    const fresh = page.getByRole('dialog', {name: 'New space'})
    await fresh.getByLabel('Name').fill('Watch later')
    assert.equal(await fresh.locator('#sp-folder').getAttribute('placeholder'), 'C:\\Users\\you\\Keepfin\\Watch later')
  }, {fake: {libraryScenario: 'v2'}})
}
