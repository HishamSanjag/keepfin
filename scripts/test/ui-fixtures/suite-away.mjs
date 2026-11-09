// Songs of a shared library that are not on this PC (spec 9.4): the row says where they are, playing one plays from the other device
// ("From <device>"), and a device that cannot be reached is named with when it was last seen. Registered by smoke-ui-flows.mjs.
// The bridge is synthetic: it proves what the renderer shows and asks for, not that a transfer happens (the node tests cover that).
import assert from 'node:assert/strict'

export async function registerAwaySuite({test}) {
  const openMusic = async (page) => {
    await page.getByRole('navigation', {name: 'Main navigation'}).getByRole('button', {name: 'Library'}).click()
    await page.getByRole('navigation', {name: 'Your library'}).getByRole('button', {name: /^Music/}).click()
  }
  const row = (page) => page.getByRole('button', {name: /^Far Song/}).locator('xpath=ancestor::div[contains(@class,"song-row")]')

  for (const [away, hint] of [
    [undefined, 'On Laptop'],
    ['offline', 'On Laptop (not reachable)'],
    ['none', 'On Laptop (playing from it is off)'],
  ]) {
    await test(`away: a song that is not on this PC says "${hint}" instead of "File missing"`, async (page) => {
      await openMusic(page)
      await page.getByRole('button', {name: /^Far Song/}).waitFor()
      await row(page).getByText(hint, {exact: true}).waitFor()
      assert.equal(await row(page).getByText('File missing').count(), 0)
      // the song that really is gone keeps its own words
      assert.ok((await page.getByRole('button', {name: /^Ghost Song/}).locator('xpath=ancestor::div[contains(@class,"song-row")]').textContent()).includes('File missing'))
    }, {fake: {awayItem: true, awayDevice: away}})
  }

  await test('away: when no paired device is known to hold the item, the row says so honestly', async (page) => {
    await openMusic(page)
    await page.getByRole('button', {name: /^Far Song/}).waitFor()
    await row(page).getByText('Not on this PC. No paired device is known to have it.', {exact: true}).waitFor()
    assert.equal(await row(page).getByText(/^On Laptop/).count(), 0, 'a device that merely allows playing is not named as the holder')
  }, {fake: {awayItem: true, awayHolder: 'nobody'}})

  await test('away: playing it asks the engine, plays, and the player says "From Laptop"', async (page) => {
    await openMusic(page)
    await page.getByRole('button', {name: /^Far Song/}).click()
    // the next song in the queue is prepared too, so this one is not always the last call
    await page.waitForFunction(() => window.__calls.some((c) => c.op === 'libraryPrepare' && c.id === 'a-far'))
    const bar = page.getByRole('region', {name: 'Now playing'})
    await bar.getByText('Far Artist · From Laptop', {exact: true}).waitFor()
  }, {fake: {awayItem: true}})

  await test('away: when the device is not reachable the player says who and when it was last seen', async (page) => {
    await openMusic(page)
    await page.getByRole('button', {name: /^Far Song/}).click()
    const bar = page.getByRole('region', {name: 'Now playing'})
    await bar.getByText(/^Laptop is not reachable\. It was last seen /).waitFor()
    assert.equal(await bar.getByText(/From Laptop/).count(), 0)
  }, {fake: {awayItem: true, awayDevice: 'offline'}})

  await test('away: with no device that lets this one play files, the answer is plain', async (page) => {
    await openMusic(page)
    await page.getByRole('button', {name: /^Far Song/}).click()
    const bar = page.getByRole('region', {name: 'Now playing'})
    await bar.getByText(/This is not on this PC/).waitFor()
  }, {fake: {awayItem: true, awayDevice: 'none'}})

  await test('away: the item menu offers Play only (removing it would remove it from the shared library)', async (page) => {
    await openMusic(page)
    await page.getByRole('button', {name: /^Far Song/}).click({button: 'right'})
    const labels = await page.getByRole('menu', {name: 'Item actions'}).getByRole('menuitem').allTextContents()
    assert.deepEqual(labels, ['Play'])
  }, {fake: {awayItem: true}})
}
