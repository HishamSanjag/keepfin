// Spaces v2 flows (hub edit, smart spaces, tag filter, add from library, collection follow, start page) against the
// synthetic bridge. Registered by smoke-ui-flows.mjs, so `npm run gui:ui-test` runs them.
import assert from 'node:assert/strict'

const V2 = {libraryScenario: 'v2'}

export async function registerSpacesV2Suite({test, last, calls, sent}) {
  const mainNav = (page) => page.getByRole('navigation', {name: 'Main navigation'})
  const side = (page) => page.getByRole('navigation', {name: 'Your library'})
  const toLibrary = (page) => mainNav(page).getByRole('button', {name: 'Library'}).click()
  const libMain = (page) => page.getByRole('region', {name: 'Library content'})
  const hub = (page) => page.getByRole('region', {name: 'Spaces', exact: true})
  const openSpace = async (page, name) => {
    await toLibrary(page)
    await side(page).getByRole('button', {name: new RegExp('^' + name)}).click()
  }
  const rows = (page, label) => page.getByRole('list', {name: label}).locator('[role=listitem]').count()

  await test('sidebar: hiding a space in Edit sidebar is the same switch as Home, and New space beside Spaces opens the dialog', async (page) => {
    await mainNav(page).getByRole('button', {name: 'Downloads'}).click()
    await page.locator('aside.rail').getByRole('button', {name: 'Edit sidebar'}).click()
    await side(page).getByRole('button', {name: 'Hide Videos'}).click()
    await sent(page, 'spaceSave')
    const saved = await last(page, 'spaceSave')
    assert.equal(saved.spaceDef.id, 'videos')
    assert.equal(saved.spaceDef.pinned, false)
    await page.locator('aside.rail').getByRole('button', {name: 'Done', exact: true}).click()
    await side(page).getByRole('button', {name: 'New space'}).click()
    await page.getByRole('dialog').waitFor()
    await page.keyboard.press('Escape')
  }, {fake: V2})

  await test('Favorites is a kept space: listed with the others right after Videos, and offers no Remove', async (page) => {
    await toLibrary(page)
    const cards = hub(page).getByRole('list', {name: 'Pinned spaces'})
    const names = (await cards.getByRole('button').allInnerTexts()).map((text) => text.split('\n')[0].trim())
    assert.deepEqual(names.slice(0, 4), ['Downloads', 'Music', 'Videos', 'Favorites'])
    assert.deepEqual((await side(page).getByRole('list', {name: 'Spaces'}).getByRole('button').allInnerTexts()).map((text) => text.split('\n')[0].trim()).slice(0, 4), ['Downloads', 'Music', 'Videos', 'Favorites'])
    if (process.env.SHOT_DIR) await page.screenshot({path: `${process.env.SHOT_DIR}/windows-library-spaces-favorites.png`})
    await side(page).getByRole('button', {name: /^Favorites/}).click()
    if (process.env.SHOT_DIR) await page.screenshot({path: `${process.env.SHOT_DIR}/windows-favorites-space.png`})
    await page.getByRole('button', {name: 'Favorites options'}).click()
    await page.getByRole('menuitem', {name: 'Edit space…'}).waitFor()
    assert.equal(await page.getByRole('menuitem', {name: 'Remove space…'}).count(), 0, 'a kept space cannot be removed')
    await page.keyboard.press('Escape')
    // the same for Music (a folder built-in)
    await side(page).getByRole('button', {name: /^Music/}).click()
    await page.getByRole('button', {name: 'Music options'}).click()
    assert.equal(await page.getByRole('menuitem', {name: 'Remove space…'}).count(), 0)
  })

  await test('Favorites with nothing hearted yet says how to fill it', async (page) => {
    await toLibrary(page)
    await side(page).getByRole('button', {name: /^Favorites/}).click()
    await page.getByText('No favorites yet').waitFor()
    await page.getByText(/Press the heart on any song or video/).waitFor()
    if (process.env.SHOT_DIR) await page.screenshot({path: `${process.env.SHOT_DIR}/windows-favorites-empty.png`})
  }, {fake: {libraryScenario: 'empty'}})

  await test('hub: pinned spaces in order, All spaces · N more, New space only in edit mode, reorder and pin', async (page) => {
    await toLibrary(page)
    const cards = hub(page).getByRole('list', {name: 'Pinned spaces'})
    for (const name of ['Downloads', 'Music', 'Videos']) await cards.getByRole('button', {name: new RegExp('^' + name)}).waitFor()
    await cards.getByRole('button', {name: 'All spaces · 3 more'}).waitFor()
    assert.equal(await page.locator('main.main').getByRole('button', {name: /New space/}).count(), 0, 'no New space outside edit mode')
    await page.getByRole('button', {name: 'Edit Library'}).click()
    await hub(page).getByRole('button', {name: 'New space'}).waitFor()
    // Keyboard-accessible reorder.
    await hub(page).getByRole('button', {name: 'Move Videos up'}).click()
    await sent(page, 'spaceReorder')
    assert.deepEqual((await last(page, 'spaceReorder')).ids.slice(0, 3), ['downloads', 'videos', 'music'])
    await page.getByRole('status').filter({hasText: 'Videos is now number 2'}).waitFor({state: 'attached'})
    // Drag the smart space to the top.
    const edit = hub(page).locator('.space-edit-row')
    await edit.nth(6).dragTo(edit.nth(0), {targetPosition: {x: 40, y: 4}})
    await page.waitForFunction(() => window.__calls.filter((c) => c.op === 'spaceReorder').length >= 2)
    assert.equal((await last(page, 'spaceReorder')).ids[0], 'gym')
    // Built-ins can be moved like any other space; pinning shows a space on home.
    await hub(page).getByRole('switch', {name: 'Show Kids on home'}).click()
    await page.waitForFunction(() => window.__calls.some((c) => c.op === 'spaceSave' && c.spaceDef.id === 'kids' && c.spaceDef.pinned === true))
    await page.getByRole('group', {name: 'Edit page layout'}).getByRole('button', {name: 'Done'}).click()
    await cards.getByRole('button', {name: /^Kids/}).waitFor()
    await cards.getByRole('button', {name: 'All spaces · 2 more'}).click()
    await page.getByRole('searchbox', {name: 'Filter spaces'}).waitFor()
    assert.equal(await page.getByRole('group', {name: 'Show in Library'}).getByRole('button', {name: 'Spaces', exact: true}).getAttribute('aria-pressed'), 'true')
    assert.equal(await page.locator('main.main').getByRole('button', {name: /New space/}).count(), 1, 'All spaces offers New space on its header')
    await page.getByRole('searchbox', {name: 'Filter spaces'}).fill('gym')
    await page.getByRole('region', {name: 'Smart spaces'}).getByRole('button', {name: /^Gym/}).waitFor()
    assert.equal(await page.getByRole('region', {name: 'Folders'}).count(), 0)
  }, {fake: V2})

  await test('smart space: create with rules and a live count, then open and play it as a whole', async (page) => {
    await toLibrary(page)
    await page.getByRole('button', {name: 'Edit Library'}).click()
    await hub(page).getByRole('button', {name: 'New space'}).click()
    const dialog = page.getByRole('dialog', {name: 'New space'})
    await dialog.getByLabel('Name').fill('Workout mix')
    await dialog.getByRole('radio', {name: 'Smart'}).click()
    assert.equal(await dialog.getByRole('textbox', {name: 'Folder'}).count(), 0, 'smart spaces have no folder')
    const colors = dialog.getByRole('radiogroup', {name: 'Space color'}).getByRole('radio')
    assert.equal(await colors.count(), 8)
    await dialog.getByRole('radio', {name: 'Teal'}).click()
    await dialog.getByRole('radio', {name: 'Dumbbell icon'}).click()
    assert.equal(await dialog.getByRole('button', {name: 'Create space'}).isDisabled(), true, 'needs a rule first')
    await dialog.getByRole('button', {name: 'Add rule'}).click()
    await dialog.getByLabel('Add a tag to rule 1').fill('workout')
    await dialog.getByLabel('Add a tag to rule 1').press('Enter')
    await dialog.getByRole('status').filter({hasText: /2 items match/}).waitFor()
    await dialog.getByRole('radio', {name: 'Any rule'}).waitFor()
    // Only kind narrows the count.
    await dialog.getByLabel('Only').selectOption('audio')
    await dialog.getByRole('status').filter({hasText: /1 item matches/}).waitFor()
    await dialog.getByLabel('Only').selectOption('any')
    await dialog.getByRole('button', {name: 'Create space'}).click()
    await sent(page, 'spaceSave')
    const def = (await last(page, 'spaceSave')).spaceDef
    assert.equal(def.type, 'smart')
    assert.equal(def.color, 'teal')
    assert.equal(def.icon, 'dumbbell')
    assert.deepEqual(def.rules.rules, [{field: 'tag', values: ['workout']}])
    await page.getByRole('group', {name: 'Edit page layout'}).getByRole('button', {name: 'Done'}).click()
    await side(page).getByRole('button', {name: /^Workout mix/}).click()
    await page.getByText(/Smart · 2 matches/).waitFor()
    await page.getByRole('button', {name: 'Play all'}).first().click()
    await page.getByRole('region', {name: 'Now playing'}).waitFor()
    // Add on a smart space opens its rules.
    await page.getByRole('button', {name: 'Add', exact: true}).click()
    const rules = page.getByRole('dialog', {name: 'Edit Workout mix'})
    await rules.getByRole('region', {name: 'Rules'}).waitFor()
    assert.match(await rules.getByText(/cannot be changed/).textContent(), /type/i)
  }, {fake: V2})

  await test('tags: filter chips with Any/All, Play N, Save as smart space, and user tags from the menu', async (page) => {
    await openSpace(page, 'Music')
    const tags = page.getByRole('group', {name: 'Tags'})
    await tags.getByRole('button', {name: /^ambient/}).waitFor()
    await tags.getByRole('button', {name: /^workout/}).waitFor()
    assert.equal(await tags.getByRole('button', {name: /^road trip/}).count(), 0, 'tags used once stay hidden')
    await tags.getByRole('button', {name: /^ambient/}).click()
    await page.waitForFunction(() => document.querySelectorAll('[aria-label=Songs] [role=listitem]').length === 3)
    await tags.getByRole('button', {name: /^indie/}).click()
    await page.waitForFunction(() => document.querySelectorAll('[aria-label=Songs] [role=listitem]').length === 5)
    await page.getByRole('radio', {name: 'All', exact: true}).click()
    await page.waitForFunction(() => document.querySelectorAll('[aria-label=Songs] [role=listitem]').length === 2)
    await page.getByRole('button', {name: 'Play 2'}).click()
    await page.getByRole('region', {name: 'Now playing'}).waitFor()
    await page.getByRole('button', {name: 'Save as smart space'}).click()
    const dialog = page.getByRole('dialog', {name: 'New space'})
    assert.equal(await dialog.getByLabel('Name').inputValue(), 'Ambient + Indie')
    await dialog.getByRole('button', {name: 'Create space'}).click()
    await sent(page, 'spaceSave')
    const rules = (await last(page, 'spaceSave')).spaceDef.rules
    assert.equal(rules.match, 'all')
    assert.deepEqual(rules.rules, [{field: 'tag', values: ['ambient']}, {field: 'tag', values: ['indie']}, {field: 'space', values: ['music']}])
    await page.getByRole('button', {name: 'Clear tags'}).click()
    // A user tag from the item menu.
    await page.getByRole('button', {name: /^Cold Front/}).click({button: 'right'})
    await page.getByRole('menuitem', {name: 'Tags…'}).click()
    const tagDialog = page.getByRole('dialog', {name: 'Tags'})
    await tagDialog.getByRole('button', {name: 'Remove tag workout'}).waitFor()
    await tagDialog.getByLabel('New tag').fill('Rainy day')
    await tagDialog.getByRole('button', {name: 'Add tag'}).click()
    await sent(page, 'libraryTag')
    assert.deepEqual((await last(page, 'libraryTag')).tags, {add: ['rainy day']})
    await tagDialog.getByRole('button', {name: 'Remove tag workout'}).click()
    await page.waitForFunction(() => window.__calls.some((c) => c.op === 'libraryTag' && c.tags.remove))
    await tagDialog.getByRole('button', {name: 'Done'}).click()
    // The selection bar offers the same.
    await page.getByRole('button', {name: 'Select', exact: true}).click()
    await page.getByRole('button', {name: /^Cold Front/}).click()
    await page.getByRole('region', {name: 'Selection'}).getByRole('button', {name: 'Tags…'}).click()
    await page.getByRole('dialog', {name: 'Tags'}).waitFor()
  }, {fake: V2})

  await test('add from library: picker first, Move by default, Copy, collections, and device files as a secondary action', async (page) => {
    await openSpace(page, 'Downloads')
    await page.getByRole('button', {name: 'Add', exact: true}).click()
    const dialog = page.getByRole('dialog', {name: 'Add to Downloads'})
    for (const name of ['Collections', 'Songs', 'Videos']) await dialog.getByRole('tab', {name}).waitFor()
    assert.equal(await dialog.getByRole('radio', {name: 'Move'}).getAttribute('aria-checked'), 'true', 'Move is the default')
    await dialog.getByRole('searchbox', {name: 'Search the library'}).fill('cold')
    await dialog.getByRole('checkbox', {name: /^Cold Front/}).click()
    await dialog.getByRole('button', {name: /^Move 1 selection/}).click()
    await sent(page, 'libraryAddFrom')
    const move = await last(page, 'libraryAddFrom')
    assert.deepEqual(move.ids, ['a-front'])
    assert.equal(move.addMode, 'move')
    assert.equal(move.space, 'downloads')
    await page.getByText('Moved to Downloads').waitFor()
    // Copy a whole collection by reference to its items.
    await page.getByRole('button', {name: 'Add', exact: true}).click()
    const again = page.getByRole('dialog', {name: 'Add to Downloads'})
    await again.getByRole('tab', {name: 'Collections'}).click()
    await again.getByRole('checkbox', {name: /^Night drive/}).click()
    await again.getByRole('radio', {name: 'Copy'}).click()
    await again.getByRole('button', {name: /^Copy 1 selection/}).click()
    await page.waitForFunction(() => window.__calls.filter((c) => c.op === 'libraryAddFrom').length === 2)
    const copy = await last(page, 'libraryAddFrom')
    assert.deepEqual(copy.collectionIds, ['pl-night'])
    assert.equal(copy.addMode, 'copy')
    await page.getByText('Copied 3 items to Downloads.').waitFor()
    // Files on this computer stay available.
    await page.getByRole('button', {name: 'Add', exact: true}).click()
    await page.getByRole('dialog', {name: 'Add to Downloads'}).getByRole('button', {name: /Choose files on this computer/}).click()
    await page.getByRole('dialog', {name: /Add files to Downloads/}).waitFor()
  }, {fake: V2})

  await test('collections: follow status, own are Yours, each has Play; detail with Check now, new items and removed upstream', async (page) => {
    await openSpace(page, 'Music')
    await page.getByRole('tab', {name: /^Collections/}).click()
    const tile = page.getByRole('region', {name: 'Library content'}).getByRole('button', {name: /^Road Trip Mix/})
    await tile.waitFor()
    assert.match(await tile.getAttribute('aria-label'), /following, checked 12 min ago · 1 new/)
    await page.getByRole('region', {name: 'Library content'}).getByRole('button', {name: /^Night drive/}).getAttribute('aria-label').then((label) => assert.match(label, /Yours/))
    await page.getByRole('button', {name: 'Play Road Trip Mix'}).click()
    await page.waitForFunction(() => /Zebra Crossing/.test(document.querySelector('[aria-label="Now playing"]')?.textContent || ''))
    await tile.click()
    await sent(page, 'collectionOpened')
    const follow = page.getByRole('region', {name: 'Following'})
    await follow.getByText(/Last checked 12 min ago/).waitFor()
    await page.getByRole('region', {name: 'New in this collection'}).getByText('Aurora Drive').waitFor()
    await page.getByText('Removed from the playlist · kept here').waitFor()
    assert.equal(await page.locator('.song-row.is-removed').count(), 1)
    await follow.getByRole('button', {name: 'Check now'}).click()
    await sent(page, 'collectionCheck')
    await page.getByText('Found 1 new song in Road Trip Mix · Downloading').waitFor()
    await follow.getByRole('button', {name: 'Follow settings…'}).click()
    const dialog = page.getByRole('dialog', {name: 'Follow settings'})
    await dialog.getByText('Checks run while Keepfin is open', {exact: false}).first().waitFor()
    assert.equal(await dialog.getByText(/Wi-?Fi/i).count(), 0, 'desktop has no Wi-Fi only option')
    await dialog.getByLabel('Check interval').selectOption('12')
    await dialog.getByRole('radio', {name: /When I click Check now/}).click()
    await dialog.getByRole('button', {name: 'Save'}).click()
    await sent(page, 'collectionFollow')
    const saved = (await last(page, 'collectionFollow')).follow
    assert.equal(saved.mode, 'manual')
    assert.equal(saved.intervalHours, 12)
  }, {fake: V2})

  await test('collections: Review N new when more than the limit are waiting', async (page) => {
    await openSpace(page, 'Downloads')
    await page.getByRole('tab', {name: /^Collections/}).click()
    const tile = page.getByRole('region', {name: 'Library content'}).getByRole('button', {name: /^Weekend clips/})
    assert.match(await tile.getAttribute('aria-label'), /28 waiting for review/)
    await tile.click()
    await page.getByRole('button', {name: 'Review 28 new'}).click()
    await sent(page, 'collectionReview')
    await page.getByText('Downloading 28 new items in Weekend clips.').waitFor()
    await page.getByRole('button', {name: /Review \d+ new/}).waitFor({state: 'detached'})
  }, {fake: V2})

  const PLAYLIST = 'https://www.youtube.com/playlist?list=PLfocus'
  const MIX = 'https://www.youtube.com/watch?v=mixseed&list=RDmixseed'
  const ALBUM = 'https://open.spotify.com/album/1A2B3C4D5E6F'
  const SINGLE = 'https://www.youtube.com/watch?v=lofoten8k'
  const field = (page) => page.locator('textarea').first()
  /** Close puts the review away and keeps the link; Clear in the field discards it. */
  const discard = async (page) => {
    await page.getByRole('dialog').getByRole('button', {name: 'Close', exact: true}).click()
    await page.getByRole('button', {name: 'Clear link'}).click()
  }
  const review = async (page, link, name) => {
    await field(page).fill(link)
    await page.getByRole('dialog', {name}).waitFor()
    await page.getByRole('list', {name: 'Collection entries'}).waitFor()
    // Playlists and albums start selected; radio mixes do not, so only wait where something is ticked.
    if (!/RD/.test(link)) await page.getByRole('button', {name: /^Download \d+ selected/}).waitFor({timeout: 8000})
  }

  await test('Check now: a toast says what it found and offers Show; a quiet check says so', async (page) => {
    await page.evaluate(() => window.__fake.freeze())
    await openSpace(page, 'Music')
    await page.getByRole('tab', {name: /^Collections/}).click()
    await page.getByRole('region', {name: 'Library content'}).getByRole('button', {name: /^Road Trip Mix/}).click()
    const follow = page.getByRole('region', {name: 'Following'})
    await follow.getByRole('button', {name: 'Check now'}).click()
    const toast = page.getByRole('status').filter({hasText: 'Found 3 new songs in Road Trip Mix · Downloading'})
    await toast.waitFor()
    await toast.getByRole('button', {name: 'Show'}).waitFor()
    // Nothing new the second time.
    await follow.getByRole('button', {name: 'Check now'}).click()
    await page.getByText('No new songs in Road Trip Mix').waitFor()
  }, {fake: {...V2, checkJobs: 3}})

  await test('collection: a progress strip while its new downloads run, on the truth spring, then it goes', async (page) => {
    await page.evaluate(() => window.__fake.freeze())
    await openSpace(page, 'Music')
    await page.getByRole('tab', {name: /^Collections/}).click()
    await page.getByRole('region', {name: 'Library content'}).getByRole('button', {name: /^Road Trip Mix/}).click()
    const strip = page.getByRole('region', {name: 'New downloads'})
    assert.equal(await strip.count(), 0, 'no strip while nothing is downloading')
    await page.getByRole('region', {name: 'Following'}).getByRole('button', {name: 'Check now'}).click()
    await strip.waitFor()
    await strip.getByText('Downloading 0 of 3 new').waitFor()
    // Their items are listed with their own state, in the New section.
    const news = page.getByRole('region', {name: 'New in this collection'})
    await news.getByText('Sunrise Tape').waitFor()
    await news.getByText('Waiting', {exact: false}).first().waitFor()
    // One finishes, one is half way: (100 + 50 + 0) / 3.
    await page.evaluate(() => {
      window.__fake.patchJob('job-sync-0', {status: 'completed', percent: 100, completedAt: Date.now()})
      window.__fake.patchJob('job-sync-1', {status: 'downloading', percent: 50})
    })
    await strip.getByText('Downloading 1 of 3 new').waitFor()
    // The bar rises towards the reported 50% and never passes it.
    const samples = await page.evaluate(
      () =>
        new Promise((resolve) => {
          const fill = document.querySelector('.run-fill')
          const seen = []
          const start = performance.now()
          const tick = () => {
            seen.push(Number(/scaleX\(([\d.e-]+)\)/.exec(fill.style.transform)?.[1] ?? 0))
            if (performance.now() - start < 1200) requestAnimationFrame(tick)
            else resolve(seen)
          }
          tick()
        }),
    )
    assert.ok(Math.max(...samples) <= 0.5 + 1e-6, `bar passed the reported value: ${Math.max(...samples)}`)
    assert.ok(samples.every((value, index) => index === 0 || value >= samples[index - 1] - 1e-9), 'bar only moves forward')
    assert.ok(Math.abs(samples.at(-1) - 0.5) < 0.02, `bar reaches the reported value: ${samples.at(-1)}`)
    assert.equal(await strip.getByRole('progressbar').getAttribute('aria-valuenow'), '50')
    await news.getByText('50%').waitFor()
    // Everything finished: the strip disappears.
    await page.evaluate(() => {
      for (const id of ['job-sync-1', 'job-sync-2']) window.__fake.patchJob(id, {status: 'completed', percent: 100, completedAt: Date.now()})
    })
    await strip.waitFor({state: 'detached'})
  }, {fake: {...V2, checkJobs: 3}})

  await test('an automatic check that finds songs while the app is open says so, and Show opens the collection', async (page) => {
    await page.evaluate(() => window.__fake.push({type: 'follow', found: {id: 'grp-drive', name: 'Road Trip Mix', added: 2}}))
    const toast = page.getByRole('status').filter({hasText: 'Found 2 new songs in Road Trip Mix · Downloading'})
    await toast.waitFor()
    await toast.getByRole('button', {name: 'Show'}).click()
    await page.getByRole('region', {name: 'Following'}).waitFor()
    await page.evaluate(() => window.__fake.push({type: 'follow', found: {id: 'grp-clips', name: 'Weekend clips', added: 0, pending: 28}}))
    await page.getByText('28 new items in Weekend clips wait for your review.').waitFor()
  }, {fake: V2})

  await test('review: Keep in sync is off by default for a playlist, and turning it on sends the interval', async (page) => {
    await review(page, PLAYLIST, 'Focus & Study Mixes')
    const row = page.getByRole('region', {name: 'Keep in sync'})
    await row.getByText('New ones download by themselves').waitFor()
    const toggle = row.getByRole('switch', {name: 'Keep in sync'})
    assert.equal(await toggle.getAttribute('aria-checked'), 'false')
    assert.equal(await row.getByLabel('How often to check for new ones').count(), 0, 'the interval appears only when it is on')
    await page.getByRole('button', {name: /^Download \d+ selected/}).click()
    await sent(page, 'enqueueDiscovered')
    assert.equal((await last(page, 'enqueueDiscovered')).keepInSync, undefined, 'off sends nothing')
  }, {fake: V2})

  await test('review: Keep in sync on shows the interval (default 6 hours) and is sent with the download', async (page) => {
    await review(page, PLAYLIST, 'Focus & Study Mixes')
    const row = page.getByRole('region', {name: 'Keep in sync'})
    await row.getByRole('switch', {name: 'Keep in sync'}).click()
    const interval = row.getByLabel('How often to check for new ones')
    assert.equal(await interval.inputValue(), '6')
    assert.deepEqual(await interval.locator('option').allTextContents(), ['Every hour', 'Every 6 hours', 'Every 12 hours', 'Every day', 'Every week'])
    await interval.selectOption('12')
    await page.getByRole('button', {name: /^Download \d+ selected/}).click()
    await sent(page, 'enqueueDiscovered')
    assert.deepEqual((await last(page, 'enqueueDiscovered')).keepInSync, {intervalHours: 12})
  }, {fake: V2})

  await test('review: Keep in sync is not offered for albums, radio mixes or single items', async (page) => {
    await review(page, ALBUM, 'Weather Systems')
    assert.equal(await page.getByRole('region', {name: 'Keep in sync'}).count(), 0, 'albums stay off')
    await discard(page)
    await review(page, MIX, 'Mix - Saltwater Radio')
    assert.equal(await page.getByRole('region', {name: 'Keep in sync'}).count(), 0, 'radio mixes never follow')
    await discard(page)
    await field(page).fill(SINGLE)
    await page.locator('.download-btn').first().waitFor()
    assert.equal(await page.getByRole('region', {name: 'Keep in sync'}).count(), 0, 'single items cannot follow')
  }, {fake: V2})

  const YTM = 'https://music.youtube.com/playlist?list=PLytm'
  // Format is a dropdown in a collection review: Video, Audio and, for a music collection, Both
  const format = (page) => page.getByLabel('Format', {exact: true})
  const hasBoth = async (page) => (await format(page).locator('option[value="both"]').count()) === 1

  await test('review: a music collection has Both, not chosen; choosing it shows the size note and Download sends nothing extra when it is not', async (page) => {
    await review(page, YTM, 'Late Night Drive')
    assert.equal(await hasBoth(page), true)
    assert.equal(await format(page).inputValue(), 'audio', 'Both is never chosen for the person')
    await format(page).selectOption('both')
    await page.getByText('Music videos are kept where there is one. They are large, about 50 to 100 MB each.').waitFor()
    await format(page).selectOption('audio')
    await page.getByRole('button', {name: /^Download \d+ selected/}).click()
    await sent(page, 'enqueueDiscovered')
    assert.equal((await last(page, 'enqueueDiscovered')).findVideo, undefined, 'Audio alone sends nothing')
  }, {fake: V2})

  await test('review: choosing Both marks the batch with findVideo', async (page) => {
    await review(page, YTM, 'Late Night Drive')
    await format(page).selectOption('both')
    await page.getByRole('button', {name: /^Download \d+ selected/}).click()
    await sent(page, 'enqueueDiscovered')
    assert.equal((await last(page, 'enqueueDiscovered')).findVideo, true)
  }, {fake: V2})

  await test('review: Video keeps no music videos, and a plain playlist has no Both', async (page) => {
    await review(page, YTM, 'Late Night Drive')
    await format(page).selectOption('both')
    await format(page).selectOption('video')
    assert.equal(await format(page).inputValue(), 'video')
    await page.getByRole('button', {name: /^Download \d+ selected/}).click()
    await sent(page, 'enqueueDiscovered')
    assert.equal((await last(page, 'enqueueDiscovered')).findVideo, undefined)
    await page.getByRole('dialog').waitFor({state: 'detached'})
    await review(page, PLAYLIST, 'Focus & Study Mixes')
    assert.equal(await hasBoth(page), false, 'a YouTube playlist is not a music collection')
  }, {fake: V2})

  await test('review: the setting "Keep music videos automatically" starts with Both chosen; choosing Audio for this download sends nothing', async (page) => {
    await review(page, YTM, 'Late Night Drive')
    assert.equal(await format(page).inputValue(), 'both')
    assert.equal((await calls(page, 'enqueueDiscovered')).length, 0, 'the setting starts no download by itself')
    await format(page).selectOption('audio')
    await page.getByRole('button', {name: /^Download \d+ selected/}).click()
    await sent(page, 'enqueueDiscovered')
    assert.equal((await last(page, 'enqueueDiscovered')).findVideo, undefined)
  }, {fake: {...V2, musicVideos: true}})

  await test('review: with the library off there is no Both', async (page) => {
    await review(page, YTM, 'Late Night Drive')
    assert.equal(await hasBoth(page), false)
  }, {fake: {libraryScenario: 'off'}})

  await test('settings: "Keep music videos automatically" is off, turns on, and is found by search', async (page) => {
    await page.locator('aside.rail').getByRole('button', {name: 'Settings', exact: true}).click()
    const switchEl = page.getByRole('switch', {name: 'Keep music videos automatically'})
    await switchEl.waitFor()
    await page.getByText('Songs you download as audio also get their official music video, where there is one. Videos are large.').waitFor()
    assert.equal(await switchEl.getAttribute('aria-checked'), 'false', 'off by default')
    await switchEl.click()
    await sent(page, 'updateSettings')
    assert.equal((await last(page, 'updateSettings')).settings.musicVideos, true)
    await page.getByRole('combobox', {name: 'Search settings'}).fill('music videos')
    await page.getByRole('option', {name: /Keep music videos automatically/}).waitFor()
  }, {fake: V2})

  await test('space Add: From a link reviews the link with Save to set to that space, then returns to the space', async (page) => {
    await openSpace(page, 'Music')
    await page.getByRole('button', {name: 'Add', exact: true}).click()
    const picker = page.getByRole('dialog', {name: 'Add to Music'})
    await picker.getByRole('button', {name: /Choose files on this computer/}).waitFor()
    await picker.getByRole('button', {name: 'From a link…'}).click()
    const dialog = page.getByRole('dialog', {name: 'Add to Music'})
    await dialog.getByLabel('Link').fill(PLAYLIST)
    await dialog.getByRole('button', {name: 'Review link'}).click()
    // The ordinary review, with this space chosen (a playlist like this would otherwise go to Downloads).
    await page.getByRole('dialog', {name: 'Focus & Study Mixes'}).waitFor()
    await page.getByRole('list', {name: 'Collection entries'}).waitFor()
    assert.match(await page.locator('.save-chip').first().textContent(), /Save to:\s*Music/)
    await page.getByRole('button', {name: /^Download \d+ selected/}).waitFor({timeout: 8000})
    const row = page.getByRole('region', {name: 'Keep in sync'})
    await row.getByRole('switch', {name: 'Keep in sync'}).click()
    await page.getByRole('button', {name: /^Download \d+ selected/}).click()
    await sent(page, 'enqueueDiscovered')
    const request = await last(page, 'enqueueDiscovered')
    assert.equal(request.space, 'music')
    assert.deepEqual(request.keepInSync, {intervalHours: 6})
    // Back in the space it came from, with a way to see the downloads.
    await libMain(page).waitFor()
    await page.getByRole('button', {name: 'Add', exact: true}).waitFor()
    await page.getByRole('status').filter({hasText: 'to the queue'}).getByRole('button', {name: 'Show downloads'}).waitFor()
  }, {fake: V2})

  await test('space Add: a single link works the same way, and a link needs a link', async (page) => {
    await openSpace(page, 'Videos')
    await page.getByRole('button', {name: 'Add', exact: true}).click()
    await page.getByRole('dialog', {name: 'Add to Videos'}).getByRole('button', {name: 'From a link…'}).click()
    const dialog = page.getByRole('dialog', {name: 'Add to Videos'})
    await dialog.getByLabel('Link').fill('not a link')
    await dialog.getByRole('button', {name: 'Review link'}).click()
    await dialog.getByRole('alert').getByText(/Paste a link/).waitFor()
    await dialog.getByLabel('Link').fill(SINGLE)
    await dialog.getByRole('button', {name: 'Review link'}).click()
    await page.locator('.download-btn').first().waitFor()
    assert.match(await page.locator('.save-chip').first().textContent(), /Save to:\s*Videos/)
    await page.locator('.download-btn').first().click()
    await sent(page, 'enqueue')
    assert.equal((await last(page, 'enqueue')).space, 'videos')
    await libMain(page).waitFor()
  }, {fake: V2})

  await test('settings: Open Keepfin on, Import tags, and the Settings › trail', async (page) => {
    await page.locator('aside.rail').getByRole('button', {name: 'Settings', exact: true}).click()
    await page.getByRole('navigation', {name: 'Settings sections'}).getByRole('button', {name: 'Library'}).click()
    await page.getByRole('heading', {name: 'Settings', level: 1}).waitFor()
    await page.getByRole('heading', {name: 'Library & playback', level: 2}).waitFor()
    await page.getByRole('switch', {name: 'Import tags from sources'}).click()
    await page.waitForFunction(() => window.__calls.some((c) => c.op === 'updateSettings' && c.settings.library && c.settings.library.importTags === false))
    await page.getByRole('navigation', {name: 'Settings sections'}).getByRole('button', {name: 'Appearance'}).click()
    await page.getByRole('radiogroup', {name: 'Open Keepfin on'}).getByRole('radio', {name: 'Home', exact: true}).click()
    await page.waitForFunction(() => window.__calls.some((c) => c.op === 'updateSettings' && c.settings.startPage === 'library'))
  }, {fake: V2})

  await test('start page: Home opens straight into the library', async (page) => {
    await page.getByRole('heading', {name: 'Home', level: 1}).waitFor()
    await side(page).waitFor()
  }, {fake: {...V2, startPage: 'library'}})

  await test('start page: Where I left off remembers the last surface', async (page) => {
    assert.equal(await libMain(page).count(), 0, 'first run starts on Downloads')
    await toLibrary(page)
    await page.reload()
    await libMain(page).waitFor()
    await mainNav(page).getByRole('button', {name: 'Downloads'}).click()
    await page.reload()
    await page.getByRole('radiogroup', {name: 'Show downloads'}).waitFor()
    assert.equal(await libMain(page).count(), 0)
  }, {fake: {...V2, startPage: 'last'}})
}
