// Combining two libraries on Windows: the Combine step and its review in the setup (when the two devices hold different libraries),
// the progress and states on the device card, and Separate again. Registered by smoke-ui-flows.mjs. Data comes from the fake bridge
// (combine* fakes, synthetic). DEVICES_SHOTS=<folder> saves screenshots of every step, light and dark.
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'

const shots = process.env.DEVICES_SHOTS
if (shots) fs.mkdirSync(shots, {recursive: true})
const shoot = async (page, name, locator) => {
  if (!shots) return
  const file = path.join(shots, `${name}.png`)
  if (locator) await locator.screenshot({path: file})
  else await page.screenshot({path: file, fullPage: true})
}

export async function registerCombineSuite({test, sent}) {
  const got = async (page, op) => {
    await sent(page, op)
    return page.evaluate((op) => [...window.__calls].reverse().find((c) => c.op === op), op)
  }
  const countOf = (page, op) => page.evaluate((op) => window.__calls.filter((c) => c.op === op).length, op)
  const dialog = (page) => page.locator('dialog.devices-setup')
  const list = (page) => page.getByRole('list', {name: 'Paired devices'})
  const card = (page, name) => list(page).getByRole('article', {name, exact: true})
  const openDevices = async (page) => {
    await page.getByRole('navigation', {name: 'Main navigation'}).getByRole('button', {name: 'Settings', exact: true}).click()
    await page.getByRole('navigation', {name: 'Settings sections'}).getByRole('button', {name: 'Devices'}).click()
    await sent(page, 'devicesState')
  }
  const details = async (page, name) => {
    const row = card(page, name).locator('.devices-card-row')
    if ((await row.getAttribute('aria-expanded')) !== 'true') await row.click()
    await card(page, name).locator('.devices-card-body').waitFor()
    return card(page, name)
  }
  const overflow = (page) => page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
  const sideBySide = async (first, second) => {
    const a = await first.boundingBox()
    const b = await second.boundingBox()
    assert.ok(a && b, 'both buttons are drawn')
    assert.ok(Math.abs(a.y - b.y) < 3, `side by side (y ${a.y} vs ${b.y})`)
  }
  /** Pairs Galaxy S24 (it holds its own library) and opens the first step of the setup. */
  const openCombine = async (page) => {
    await openDevices(page)
    await sent(page, 'devicesWatch')
    await page.getByRole('region', {name: 'Available devices', exact: true}).getByRole('button', {name: /^Connect to Galaxy S24/}).click()
    await got(page, 'devicesConnect')
    const box = page.getByRole('dialog')
    await box.getByLabel('Pairing code').waitFor()
    await box.getByRole('button', {name: 'Codes match · Connect'}).click()
    await got(page, 'devicesDecide')
    await page.evaluate(() => window.__fake.devicesPeerConfirms())
    await box.getByRole('button', {name: 'Set up'}).click()
    await sent(page, 'devicesDismiss')
    const step = dialog(page)
    await step.getByRole('heading', {name: 'Combine libraries'}).waitFor()
    return step
  }
  const different = {copiesDevices: 'allowed', devicesLibraries: 'different'}

  // ---------------------------------------------------------------- the Combine step
  for (const mode of ['dark', 'light']) {
    await test(`combine (${mode}): different libraries get the Combine step, not the old "stay separate" message; the other library is the default when it has more items`, async (page) => {
      const step = await openCombine(page)
      await step.getByRole('radio').first().waitFor()
      const text = await step.textContent()
      assert.doesNotMatch(text, /stay separate|coming in an update/)
      assert.match(text, /Galaxy S24 has 1,326 items\. This PC has 210 items\. Combine them into one library that both keep up to date\?/)
      assert.match(text, /Keep the name, spaces and settings of/)
      assert.equal(await step.getByRole('radio', {name: /Galaxy S24’s library/}).isChecked(), true, 'the bigger library is the default')
      assert.equal(await step.getByRole('radio', {name: /This PC’s library/}).isChecked(), false)
      assert.deepEqual(await step.locator('.devices-dialog-actions').getByRole('button').allTextContents(), ['Later', 'Review'])
      await sideBySide(step.getByRole('button', {name: 'Later'}), step.getByRole('button', {name: 'Review'}))
      assert.equal((await countOf(page, 'combinePreview')), 1, 'the dry run is asked for once')
      assert.equal((await page.evaluate(() => window.__calls.find((c) => c.op === 'combinePreview').combine.deviceId)), 'dev-galaxy-s24')
      assert.equal(await countOf(page, 'combineStart'), 0, 'nothing has changed')
      await shoot(page, `combine-step-${mode}`, step)
      assert.equal(await overflow(page), 0)
    }, {theme: mode, height: 1300, fake: different})
  }

  await test('combine: when this PC has more items, its library is the default', async (page) => {
    const step = await openCombine(page)
    await step.getByRole('radio').first().waitFor()
    assert.match(await step.textContent(), /Galaxy S24 has 40 items\. This PC has 210 items\./)
    assert.equal(await step.getByRole('radio', {name: /This PC’s library/}).isChecked(), true)
    assert.equal(await step.getByRole('radio', {name: /Galaxy S24’s library/}).isChecked(), false)
  }, {height: 1300, fake: {...different, combineBigger: 'mine'}})

  await test('combine: Later closes the step and changes nothing (the review is dropped, nothing is started)', async (page) => {
    const step = await openCombine(page)
    await step.getByRole('radio').first().waitFor()
    await step.getByRole('button', {name: 'Later'}).click()
    await dialog(page).waitFor({state: 'detached'})
    await sent(page, 'combineCancel')
    assert.equal(await countOf(page, 'combineStart'), 0)
    assert.equal(await countOf(page, 'copiesPreview'), 0)
    assert.equal(await card(page, 'Galaxy S24').locator('.devices-combine').count(), 0, 'the card shows nothing')
  }, {height: 1300, fake: different})

  // ---------------------------------------------------------------- the review
  for (const mode of ['dark', 'light']) {
    await test(`combine (${mode}): Review shows the dry run in plain words; Back returns to the choice`, async (page) => {
      const step = await openCombine(page)
      await step.getByRole('button', {name: 'Review'}).click()
      const review = step.getByLabel('Review', {exact: true})
      await review.waitFor()
      const text = await review.textContent()
      assert.match(text, /Your files stay where they are\. Nothing is deleted\./)
      assert.match(text, /180 items are added/)
      assert.match(text, /28 items are already in Galaxy S24’s library \(same file\): counted once/)
      assert.match(text, /2 items are the same file twice here: both are kept/)
      assert.match(text, /4 items have a different title or detail: Galaxy S24’s stays/)
      assert.match(text, /1 file could not be found on this PC: it comes along without a file/)
      // the picture: this PC + the other library -> one library (1,326 + 180 added + 2 kept twice)
      const flow = await review.getByLabel('Before and after').textContent()
      assert.match(flow, /This PC\s*210/)
      assert.match(flow, /Galaxy S24\s*1,326/)
      assert.match(flow, /One library\s*1,508/)
      const along = await review.getByRole('list', {name: 'Comes along'}).textContent()
      assert.match(along, /3 playlists/)
      assert.match(along, /2 spaces/)
      assert.match(along, /favourites/)
      assert.match(along, /play counts/)
      assert.match(text, /To check · 8/)
      assert.match(text, /You can separate them again for 30 days\./)
      assert.equal(await review.getByRole('list', {name: 'Details'}).count(), 0, 'the details are folded')
      await review.getByRole('button', {name: /^Show the details \(8\)/}).click()
      const details = review.getByRole('list', {name: 'Details'})
      const entries = await details.textContent()
      assert.match(entries, /“Harbour Lights” is the same file twice on this PC\. Both are kept\./)
      assert.match(entries, /“Night Ferry” could not be found on this PC\. It comes along without a file\./)
      assert.match(entries, /“Glass Harbour \(live\)”: the title is different\. Galaxy S24’s stays\./)
      assert.match(entries, /A playlist called “Road trip” is on both\. Both are kept\./)
      assert.doesNotMatch(entries, /item-\d|list-\d/, 'names, never ids')
      assert.match(await review.textContent(), /and 4 more/)
      await sideBySide(step.getByRole('button', {name: 'Back'}), step.getByRole('button', {name: 'Combine'}))
      await shoot(page, `combine-review-${mode}`, step)
      assert.equal(await overflow(page), 0)
      await step.getByRole('button', {name: 'Back'}).click()
      await step.getByRole('radio', {name: /Galaxy S24’s library/}).waitFor()
      assert.equal(await countOf(page, 'combineStart'), 0)
    }, {theme: mode, height: 1500, fake: different})
  }

  // ---------------------------------------------------------------- Combine, progress and done
  await test('combine: Combine sends the choice, the dialog closes, the card shows progress and then "Combined · Separate again until", and the setup continues', async (page) => {
    const step = await openCombine(page)
    await step.getByRole('radio', {name: /This PC’s library/}).check()
    await step.getByRole('button', {name: 'Review'}).click()
    await step.getByRole('button', {name: 'Combine', exact: true}).click()
    const call = await got(page, 'combineStart')
    assert.deepEqual([call.combine.deviceId, call.combine.keep], ['dev-galaxy-s24', 'mine'])
    await dialog(page).waitFor({state: 'detached'})
    const galaxy = card(page, 'Galaxy S24')
    const line = galaxy.locator('.devices-combine-line')
    await line.getByText(/^Combined · Separate again until /).waitFor()
    assert.match(await line.textContent(), /Separate again until .*\d{4}/)
    assert.equal(await galaxy.getByRole('progressbar').count(), 0, 'no bar once it is done')
    // then the existing step continues: what stays up to date
    const open = await details(page, 'Galaxy S24')
    await open.getByRole('button', {name: 'Choose what stays up to date'}).click()
    await dialog(page).getByRole('heading', {name: 'What stays up to date'}).waitFor()
    assert.equal(await dialog(page).getByRole('heading', {name: 'Combine libraries'}).count(), 0)
  }, {height: 1500, fake: different})

  // ---------------------------------------------------------------- the card while it runs
  for (const mode of ['dark', 'light']) {
    await test(`combine (${mode}): a combine that is already running shows on the card with a bar, Pause and Cancel; Pause becomes Resume`, async (page) => {
      await openDevices(page)
      await sent(page, 'combineState')
      const galaxy = card(page, 'Laptop')
      const line = galaxy.locator('.devices-combine-line')
      await line.waitFor()
      assert.equal((await line.textContent()).trim(), 'Combining libraries · 1,230 of 1,450')
      const bar = galaxy.getByRole('progressbar', {name: 'Combining libraries with Laptop'})
      assert.equal(await bar.getAttribute('aria-valuenow'), '85')
      await shoot(page, `combine-card-running-${mode}`, galaxy)
      await galaxy.getByRole('button', {name: 'Pause combining with Laptop'}).click()
      await sent(page, 'combinePause')
      await galaxy.getByText(/^Combining paused/).waitFor()
      assert.match(await line.textContent(), /You paused it\./)
      await shoot(page, `combine-card-paused-${mode}`, galaxy)
      await galaxy.getByRole('button', {name: 'Resume: combining with Laptop'}).click()
      await sent(page, 'combineResume')
      await galaxy.getByText(/^Combined · Separate again until/).waitFor()
      assert.equal(await overflow(page), 0)
    }, {theme: mode, height: 1300, fake: {combine: 'running'}})
  }

  await test('combine: Cancel stops it and the card goes back to normal', async (page) => {
    await openDevices(page)
    const laptop = card(page, 'Laptop')
    await laptop.getByRole('button', {name: 'Cancel combining with Laptop'}).click()
    await sent(page, 'combineCancel')
    await laptop.locator('.devices-combine').waitFor({state: 'detached'})
  }, {height: 1300, fake: {combine: 'running'}})

  await test('combine: a paused combine (after a restart, or offline) shows why, with Resume and Cancel; a stopped one says what to do and offers Try again', async (page) => {
    await openDevices(page)
    const laptop = card(page, 'Laptop')
    await laptop.getByText(/^Combining paused · Laptop is not reachable/).waitFor()
    assert.equal(await laptop.getByRole('button', {name: 'Resume: combining with Laptop'}).count(), 1)
    assert.equal(await laptop.getByRole('button', {name: 'Cancel combining with Laptop'}).count(), 1)
  }, {height: 1300, fake: {combine: 'paused'}})

  for (const mode of ['dark', 'light']) {
    await test(`combine (${mode}): a failed combine says what happened in one sentence and offers Try again`, async (page) => {
      await openDevices(page)
      const laptop = card(page, 'Laptop')
      await laptop.getByText(/^Combining stopped · The folder could not be written to\. Check that the drive is connected, then try again\./).waitFor()
      await shoot(page, `combine-card-failed-${mode}`, laptop)
      await laptop.getByRole('button', {name: 'Try again: combining with Laptop'}).click()
      await sent(page, 'combineResume')
    }, {theme: mode, height: 1300, fake: {combine: 'failed'}})
  }

  await test('combine: a review that is ready after a restart shows on the card and Review reopens the step', async (page) => {
    await openDevices(page)
    const laptop = card(page, 'Laptop')
    await laptop.getByText(/^Ready to combine/).waitFor()
    await laptop.getByRole('button', {name: 'Review: combining with Laptop'}).click()
    await dialog(page).getByRole('heading', {name: 'Combine libraries'}).waitFor()
    await dialog(page).getByRole('radio').first().waitFor()
    assert.equal(await countOf(page, 'combinePreview'), 0, 'the review that is there is used as it is')
  }, {height: 1300, fake: {combine: 'ready', copiesDevices: 'allowed', devicesLibraries: 'different'}})

  // ---------------------------------------------------------------- Separate again
  for (const mode of ['dark', 'light']) {
    await test(`combine (${mode}): a finished combine offers Separate libraries… and Keep combined in the opened card; the dialog says what goes back and defaults to Keep`, async (page) => {
      await openDevices(page)
      const laptop = await details(page, 'Laptop')
      assert.match(await laptop.locator('.devices-combine-line').textContent(), /^Combined · Separate again until .*\d{4}$/)
      await laptop.getByRole('button', {name: 'Keep combined'}).waitFor()
      await shoot(page, `combine-card-done-${mode}`, laptop)
      await laptop.getByRole('button', {name: 'Separate libraries…'}).click()
      await sent(page, 'combineSeparatePreview')
      const box = dialog(page)
      await box.getByRole('heading', {name: 'Separate libraries'}).waitFor()
      await box.getByRole('radio').first().waitFor()
      const text = await box.textContent()
      assert.match(text, /182 items this PC brought are taken out of Laptop’s library\. This PC goes back to its own library\./)
      assert.match(text, /3 items added here since combining come back to this PC’s own library\./)
      assert.match(text, /Laptop has copies of 1 item this PC brought\./)
      assert.match(text, /Changes you made since combining are not brought back\./)
      assert.match(text, /Nothing is deleted from this PC’s folder\./)
      assert.equal(await box.getByRole('radio', {name: 'Keep them on Laptop'}).isChecked(), true, 'Keep is the default')
      await sideBySide(box.getByRole('button', {name: 'Cancel'}), box.getByRole('button', {name: 'Separate', exact: true}))
      await shoot(page, `combine-separate-${mode}`, box)
      assert.equal(await overflow(page), 0)
      await box.getByRole('button', {name: 'Separate', exact: true}).click()
      const call = await got(page, 'combineSeparate')
      assert.deepEqual([call.combine.deviceId, call.combine.keepOnPeer, call.combine.anyway], ['dev-laptop', 'all', undefined])
      await dialog(page).waitFor({state: 'detached'})
      await laptop.locator('.devices-combine').waitFor({state: 'detached'})
    }, {theme: mode, height: 1500, fake: {combine: 'done'}})
  }

  await test('combine: Remove them sends keepOnPeer "none"; Cancel sends nothing', async (page) => {
    await openDevices(page)
    const laptop = await details(page, 'Laptop')
    await laptop.getByRole('button', {name: 'Separate libraries…'}).click()
    await dialog(page).getByRole('radio', {name: 'Remove them'}).check()
    await dialog(page).getByRole('button', {name: 'Cancel'}).click()
    await dialog(page).waitFor({state: 'detached'})
    assert.equal(await countOf(page, 'combineSeparate'), 0)
    await laptop.getByRole('button', {name: 'Separate libraries…'}).click()
    await dialog(page).getByRole('radio', {name: 'Keep them on Laptop'}).waitFor()
    assert.equal(await dialog(page).getByRole('radio', {name: 'Keep them on Laptop'}).isChecked(), true, 'it opens with Keep again')
    await dialog(page).getByRole('radio', {name: 'Remove them'}).check()
    await dialog(page).getByRole('button', {name: 'Separate', exact: true}).click()
    assert.equal((await got(page, 'combineSeparate')).combine.keepOnPeer, 'none')
  }, {height: 1500, fake: {combine: 'done'}})

  for (const mode of ['dark', 'light']) {
    await test(`combine (${mode}): when the other device cannot be reached the dialog says so and Separate anyway goes ahead`, async (page) => {
      await openDevices(page)
      const laptop = await details(page, 'Laptop')
      await laptop.getByRole('button', {name: 'Separate libraries…'}).click()
      const box = dialog(page)
      await box.getByRole('heading', {name: 'Separate libraries'}).waitFor()
      await box.getByRole('button', {name: 'Separate anyway'}).waitFor()
      const text = await box.textContent()
      assert.match(text, /Laptop is not reachable\. Separate anyway: Laptop keeps everything this PC brought\./)
      assert.equal(await box.getByRole('radio').count(), 0, 'nothing to choose about copies the other device cannot report')
      await shoot(page, `combine-separate-unreachable-${mode}`, box)
      await box.getByRole('button', {name: 'Separate anyway'}).click()
      const call = await got(page, 'combineSeparate')
      assert.deepEqual([call.combine.deviceId, call.combine.anyway], ['dev-laptop', true])
      await dialog(page).waitFor({state: 'detached'})
    }, {theme: mode, height: 1500, fake: {combine: 'done', combineUnreachable: true}})
  }

  await test('combine: Keep combined sends combineKeep and takes Separate away', async (page) => {
    await openDevices(page)
    const laptop = await details(page, 'Laptop')
    await laptop.getByRole('button', {name: 'Keep combined'}).click()
    assert.equal((await got(page, 'combineKeep')).combine.deviceId, 'dev-laptop')
    await laptop.getByText(/^Combined$/).waitFor()
    assert.equal(await laptop.getByRole('button', {name: 'Separate libraries…'}).count(), 0)
    assert.equal(await laptop.getByRole('button', {name: 'Keep combined'}).count(), 0)
  }, {height: 1500, fake: {combine: 'done'}})

  await test('combine: with no combine going on the card has nothing extra', async (page) => {
    await openDevices(page)
    await sent(page, 'combineState')
    const laptop = await details(page, 'Laptop')
    assert.equal(await laptop.locator('.devices-combine').count(), 0)
    assert.equal(await laptop.getByRole('button', {name: 'Separate libraries…'}).count(), 0)
  }, {height: 1300})

  // ---------------------------------------------------------------- layout
  for (const [mode, scale] of [['dark', 80], ['light', 140]]) {
    await test(`combine layout (${mode}, ${scale}%): the step, the review and the card fit without sideways scrolling`, async (page) => {
      const step = await openCombine(page)
      await step.getByRole('radio').first().waitFor()
      assert.equal(await overflow(page), 0)
      const box = await step.boundingBox()
      const view = page.viewportSize()
      assert.ok(box.x >= 0 && box.x + box.width <= view.width, 'the step is inside the window')
      await step.getByRole('button', {name: 'Review'}).click()
      await step.getByLabel('Review', {exact: true}).waitFor()
      assert.equal(await overflow(page), 0)
      await step.getByRole('button', {name: 'Combine', exact: true}).click()
      await dialog(page).waitFor({state: 'detached'})
      await card(page, 'Galaxy S24').locator('.devices-combine-line').waitFor()
      assert.equal(await overflow(page), 0)
    }, {theme: mode, scale, height: 1500, fake: different})
  }
}
