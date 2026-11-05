// Activity centre: the header bell, its panel, filters, actions, removal and the "while you were away" notice.
// Registered by smoke-ui-flows.mjs. Entries are SYNTHETIC (fake-bridge.js, options.activity); recording itself is
// covered by the main-process unit tests.
import assert from 'node:assert/strict'

const SEED = {scenario: 'groups', libraryScenario: 'v2', activity: 'seed', simulate: false}

export async function registerActivitySuite({test, calls, last, sent}) {
  const bell = (page) => page.locator('.act-bell')
  const panel = (page) => page.getByRole('dialog', {name: 'Activity'})
  const rows = (page) => panel(page).locator('.act-row')
  const open = async (page) => {
    await bell(page).click()
    await panel(page).waitFor()
  }

  await test('activity: the bell shows the unread count and a polite label', async (page) => {
    await page.getByRole('button', {name: 'Activity, 4 unread'}).waitFor()
    assert.equal((await bell(page).locator('.act-count').textContent()).trim(), '4')
  }, {fake: SEED})

  await test('activity: no bell badge and an honest empty state when nothing happened', async (page) => {
    await page.getByRole('button', {name: 'Activity', exact: true}).waitFor()
    assert.equal(await bell(page).locator('.act-count').count(), 0)
    await open(page)
    await panel(page).getByText('Nothing yet. Downloads, synced playlists and updates show up here.').waitFor()
    assert.equal(await rows(page).count(), 0)
  }, {fake: {scenario: 'empty', simulate: false}})

  await test('activity: opening marks everything read but keeps this visit\'s new dots', async (page) => {
    await open(page)
    await sent(page, 'activityRead')
    assert.equal((await last(page, 'activityRead')).read, 'all')
    await page.getByRole('button', {name: 'Activity', exact: true}).waitFor()
    assert.equal(await bell(page).locator('.act-count').count(), 0)
    assert.equal(await panel(page).locator('.act-row.is-new').count(), 4)
    await panel(page).getByRole('button', {name: /^Unread 4$/}).waitFor()
    // Mark all read clears the dots.
    await panel(page).getByRole('button', {name: 'Mark all read'}).click()
    await page.waitForFunction(() => document.querySelectorAll('.act-row.is-new').length === 0)
  }, {fake: SEED})

  await test('activity: entries are grouped by day with title, detail, time and actions', async (page) => {
    await open(page)
    const days = await panel(page).locator('.act-day').allTextContents()
    assert.equal(days[0], 'Today')
    assert.ok(days.includes('Yesterday'), days.join(','))
    const row = rows(page).filter({hasText: 'Road Trip Mix got 3 new songs'})
    await row.getByText('All 3 saved').waitFor()
    await row.getByRole('button', {name: 'Open', exact: true}).waitFor()
    const failed = rows(page).filter({hasText: '1 item from Road Trip Mix 2026 failed'})
    assert.deepEqual(await failed.locator('.act-action').allTextContents(), ['Retry', 'Details'])
    assert.equal(await rows(page).count(), 8)
  }, {fake: SEED})

  await test('activity: filters narrow the list', async (page) => {
    await open(page)
    await panel(page).getByRole('button', {name: /^Problems 2$/}).click()
    assert.deepEqual(await rows(page).locator('strong').allTextContents().then((list) => list.map((text) => text.replace(/^Unread\. /, '')).sort()), ['1 item from Road Trip Mix 2026 failed', 'Sign in to YouTube again'])
    await panel(page).getByRole('button', {name: /^Unread 4$/}).click()
    assert.equal(await rows(page).count(), 4)
    await panel(page).getByRole('button', {name: 'All', exact: true}).click()
    assert.equal(await rows(page).count(), 8)
  }, {fake: SEED})

  await test('activity: Open on a synced collection shows it in the Library', async (page) => {
    await open(page)
    await rows(page).filter({hasText: 'Road Trip Mix got 3 new songs'}).getByRole('button', {name: 'Open', exact: true}).click()
    await panel(page).waitFor({state: 'detached'})
    await page.getByRole('heading', {name: 'Road Trip Mix', level: 1}).or(page.getByRole('heading', {name: 'Road Trip Mix'})).first().waitFor()
    // The collection's page header is the page's one h1; its breadcrumb names the Library.
    assert.equal(await page.locator('.page-header h1:visible').textContent(), 'Road Trip Mix')
    await page.getByRole('navigation', {name: 'Breadcrumb'}).waitFor()
  }, {fake: SEED})

  await test('activity: Review opens the collection that is waiting; Sign in opens Accounts', async (page) => {
    await open(page)
    await rows(page).filter({hasText: 'Weekend clips has 28 new items waiting'}).getByRole('button', {name: 'Review', exact: true}).click()
    await panel(page).waitFor({state: 'detached'})
    await page.getByRole('button', {name: /Review 28 new/}).waitFor()
    await open(page)
    await rows(page).filter({hasText: 'Sign in to YouTube again'}).getByRole('button', {name: 'Sign in', exact: true}).click()
    await panel(page).waitFor({state: 'detached'})
    assert.equal(await page.locator('.page-header h1:visible').textContent(), 'Settings')
    await page.getByRole('heading', {name: /Website sign-in/}).first().waitFor()
  }, {fake: SEED})

  await test('activity: Retry sends the group retry and Details shows the failed items', async (page) => {
    await open(page)
    const failed = rows(page).filter({hasText: '1 item from Road Trip Mix 2026 failed'})
    await failed.getByRole('button', {name: 'Retry', exact: true}).click()
    await sent(page, 'retryGroup')
    assert.equal((await last(page, 'retryGroup')).id, 'grp-road')
    await failed.getByRole('button', {name: 'Details', exact: true}).click()
    await panel(page).waitFor({state: 'detached'})
    assert.equal(await page.locator('.page-header h1:visible').textContent(), 'Downloads')
    await page.getByRole('radio', {name: /^Finished/}).waitFor()
  }, {fake: SEED})

  await test('activity: an action whose target is gone says so instead of failing', async (page) => {
    await open(page)
    await rows(page).filter({hasText: 'Evening Radio got 2 new songs'}).getByRole('button', {name: 'Open', exact: true}).click()
    await page.getByRole('status').filter({hasText: 'That collection is gone'}).waitFor()
    await panel(page).waitFor()
  }, {fake: SEED})

  await test('activity: Install restarts into a ready update', async (page) => {
    await open(page)
    await rows(page).filter({hasText: 'Keepfin 0.4.1 is ready to install'}).getByRole('button', {name: 'Install', exact: true}).click()
    await sent(page, 'updateInstall')
  }, {fake: {...SEED, update: 'ready'}})

  await test('activity: What’s new opens the release notes of the running version', async (page) => {
    await open(page)
    await rows(page).filter({hasText: 'Keepfin updated to 0.4.0'}).getByRole('button', {name: 'What’s new', exact: true}).click()
    await page.getByRole('dialog', {name: /What’s new in v0\.4\.0/}).waitFor()
    await panel(page).waitFor({state: 'detached'})
  }, {fake: {...SEED, update: 'current', lastSeen: '0.4.0'}})

  await test('activity: removing an entry sends activityRemove and drops the row', async (page) => {
    await open(page)
    const row = rows(page).filter({hasText: 'Evening Radio got 2 new songs'})
    await row.hover()
    await row.getByRole('button', {name: /^Remove:/}).click()
    await sent(page, 'activityRemove')
    assert.equal((await last(page, 'activityRemove')).id, 'act-gone')
    await page.waitForFunction(() => ![...document.querySelectorAll('.act-row')].some((row) => row.textContent.includes('Evening Radio')))
    assert.equal(await rows(page).count(), 7)
  }, {fake: SEED})

  await test('activity: Escape and an outside click close the panel; focus returns to the bell', async (page) => {
    await open(page)
    assert.equal(await bell(page).getAttribute('aria-expanded'), 'true')
    await page.keyboard.press('Escape')
    await panel(page).waitFor({state: 'detached'})
    assert.equal(await page.evaluate(() => document.activeElement && document.activeElement.classList.contains('act-bell')), true)
    await open(page)
    await page.locator('.page-header h1:visible').click()
    await panel(page).waitFor({state: 'detached'})
  }, {fake: SEED})

  await test('activity: keyboard reaches every control and nothing opens by itself', async (page) => {
    await bell(page).focus()
    await page.keyboard.press('Enter')
    await panel(page).waitFor()
    assert.equal(await page.evaluate(() => document.activeElement.id), 'activity-panel')
    await page.keyboard.press('Tab')
    assert.equal(await page.evaluate(() => document.activeElement.textContent), 'Mark all read')
    for (let n = 0; n < 4; n++) await page.keyboard.press('Tab')
    assert.equal(await page.evaluate(() => document.activeElement.classList.contains('act-action') || document.activeElement.classList.contains('act-remove')), true)
  }, {fake: SEED})

  await test('activity: a new entry arrives live and raises the count', async (page) => {
    await page.getByRole('button', {name: 'Activity, 4 unread'}).waitFor()
    await page.evaluate(() => window.__fake.activity.record({kind: 'batch-done', title: 'Fresh batch finished', detail: '2 of 2 items saved', target: {groupId: 'grp-season'}}))
    await page.getByRole('button', {name: 'Activity, 5 unread'}).waitFor()
    await open(page)
    await rows(page).filter({hasText: 'Fresh batch finished'}).waitFor()
    // Arriving while the panel is open is read straight away.
    await page.evaluate(() => window.__fake.activity.record({kind: 'failed', title: 'Another failure', target: {jobIds: ['x']}}))
    await rows(page).filter({hasText: 'Another failure'}).waitFor()
    await page.getByRole('button', {name: 'Activity', exact: true}).waitFor()
  }, {fake: SEED})

  await test('activity: "while you were away" appears once with Show, which opens the panel', async (page) => {
    const notice = page.getByRole('status').filter({hasText: '3 things happened while you were away'})
    await notice.waitFor()
    assert.equal(await notice.count(), 1)
    await notice.getByRole('button', {name: 'Show'}).click()
    await panel(page).waitFor()
    assert.equal((await calls(page, 'activityList')).length, 1)
    // Toggling the panel never brings it back.
    await bell(page).click()
    await bell(page).click()
    await page.waitForTimeout(300)
    assert.equal(await page.getByRole('status').filter({hasText: 'while you were away'}).count(), 0)
  }, {fake: {...SEED, away: 3}})

  await test('activity: no notice when nothing happened while away', async (page) => {
    await page.getByRole('button', {name: 'Activity, 4 unread'}).waitFor()
    await page.waitForTimeout(400)
    assert.equal(await page.getByRole('status').filter({hasText: 'while you were away'}).count(), 0)
  }, {fake: SEED})

  await test('activity: opening and closing the panel never re-renders a download row', async (page) => {
    await page.locator('.download-row, .q-row, [data-job]').first().waitFor().catch(() => {})
    const before = await page.evaluate(() => JSON.stringify(window.__keepfinTest.rowRenders))
    await open(page)
    await page.keyboard.press('Escape')
    await page.waitForTimeout(200)
    const after = await page.evaluate(() => JSON.stringify(window.__keepfinTest.rowRenders))
    assert.equal(after, before)
  }, {fake: SEED})
}
