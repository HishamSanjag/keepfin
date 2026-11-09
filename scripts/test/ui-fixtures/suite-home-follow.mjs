// Home's "Made from your listening" and "New from artists you follow" (GitHub #35, option B), and the Follow actions on an artist page and a
// song's menu. Registered by smoke-ui-flows.mjs. The synthetic bridge supplies invented artists and releases; it never certifies what the real
// follow check finds (the unit tests of the artist follow engine and the core fixtures do).
import assert from 'node:assert/strict'

export async function registerHomeFollowSuite({test, calls, last}) {
  const home = (page) => page.getByRole('navigation', {name: 'Main navigation'}).getByRole('button', {name: 'Home'}).click()
  const feed = (page) => page.getByRole('region', {name: 'New from artists you follow'})
  const mixes = (page) => page.getByRole('region', {name: 'Made from your listening'})
  const seeded = {fake: {following: 'seeded'}}

  await test('Home: mixes from your own plays, and the empty follow state names what to do', async (page) => {
    await home(page)
    await mixes(page).waitFor()
    // the fixture library has songs but too few played in the last 30 days for On repeat: Recently added and Never played are there
    await mixes(page).getByRole('button', {name: /^Recently added music/}).waitFor()
    assert.equal(await mixes(page).getByRole('button', {name: /^On repeat/}).count(), 0, 'a mix with too little data is not shown')
    await feed(page).getByText('Follow artists, get their new releases').waitFor()
    assert.equal(await feed(page).getByRole('button', {name: /^Keep /}).count(), 0)
    await feed(page).getByRole('button', {name: 'Find an artist'}).waitFor()
  })

  await test('Home: a mix opens as a song list and See all lists every mix', async (page) => {
    await home(page)
    await mixes(page).getByRole('button', {name: /^Recently added music/}).click()
    await page.getByRole('heading', {name: 'Recently added music'}).first().waitFor()
    await page.getByRole('button', {name: 'Back to Home'}).click()
    await mixes(page).getByRole('button', {name: 'See all mixes made from your listening'}).click()
    await page.getByRole('heading', {name: 'Made from your listening'}).first().waitFor()
    await page.getByRole('region', {name: 'Recently added music'}).waitFor()
    await page.getByRole('button', {name: 'Back to Home'}).click()
    await mixes(page).waitFor()
  })

  await test('Home: the feed is dated This week and Earlier this month, newest first, and old releases are left out', async (page) => {
    await home(page)
    const week = feed(page).getByRole('group', {name: 'This week'})
    const earlier = feed(page).getByRole('group', {name: 'Earlier this month'})
    await week.waitFor()
    await earlier.waitFor()
    assert.deepEqual(await week.locator('.release-title').allTextContents(), ['Salt Season', 'Night Ferry'])
    assert.deepEqual(await earlier.locator('.release-title').allTextContents(), ['Paper Moons'])
    assert.equal(await feed(page).getByText('Old Echoes').count(), 0, 'a release found 45 days ago is out of the feed')
    await feed(page).getByText('Lumen Harbor · Album · 3 days ago').waitFor()
    const panel = feed(page).getByRole('complementary', {name: 'Following'})
    for (const name of ['Lumen Harbor', 'Pale Cassette', 'Oren Vale', 'The Quiet Tides', 'Sable & Ash']) await panel.getByText(name, {exact: true}).waitFor()
    // a dot beside the artists with something new (Lumen Harbor, Oren Vale, Sable & Ash), not Pale Cassette
    assert.equal(await panel.getByRole('img', {name: 'New release'}).count(), 3)
  }, seeded)

  await test('Keep it downloads only on the tap, through keepRelease, and the release leaves the feed', async (page) => {
    await home(page)
    await feed(page).getByRole('button', {name: 'Keep Salt Season'}).waitFor()
    assert.equal((await calls(page, 'keepRelease')).length, 0, 'nothing downloads before the tap')
    await feed(page).getByRole('button', {name: 'Keep Salt Season'}).click()
    await page.waitForFunction(() => window.__calls.some((c) => c.op === 'keepRelease'))
    assert.equal((await last(page, 'keepRelease')).id, 'https://music.youtube.com/playlist?list=OLAKfake1')
    await page.waitForFunction(() => !document.body.textContent.includes('Salt Season') || document.querySelector('[role=status], .toast'))
    assert.equal(await feed(page).locator('.release-title', {hasText: 'Salt Season'}).count(), 0)
  }, seeded)

  await test('Manage: unfollow removes the artist and their releases; follow by name; Check now', async (page) => {
    await home(page)
    await feed(page).getByRole('button', {name: 'Manage'}).click()
    const panel = page.getByRole('dialog', {name: /^Following/})
    await panel.getByRole('heading', {name: 'Following · 5'}).waitFor()
    await panel.getByText('1 new release').first().waitFor()
    await panel.getByRole('button', {name: 'Following Oren Vale. Press to unfollow'}).click()
    await panel.getByRole('heading', {name: 'Following · 4'}).waitFor()
    assert.equal((await last(page, 'unfollowArtist')).id, 'fa-2')
    await panel.getByLabel('Artist to follow').fill('Nova Kite')
    await panel.getByRole('button', {name: 'Follow', exact: true}).click()
    await panel.getByRole('heading', {name: 'Following · 5'}).waitFor()
    assert.equal((await last(page, 'followArtist')).text, 'Nova Kite')
    await panel.getByRole('button', {name: 'Check now'}).click()
    await page.waitForFunction(() => window.__calls.some((c) => c.op === 'followingCheck'))
    await panel.getByRole('button', {name: 'Done'}).click()
    await feed(page).locator('.release-title', {hasText: 'Salt Season'}).waitFor()
    assert.equal(await feed(page).locator('.release-title', {hasText: 'Night Ferry'}).count(), 0, "the unfollowed artist's release is gone")
  }, seeded)

  await test('Manage with nobody followed says so', async (page) => {
    await home(page)
    await feed(page).getByRole('button', {name: 'Find an artist'}).waitFor()
    // the empty state offers the top artists from what the person keeps; pressing it follows them one by one
    const top = feed(page).getByRole('button', {name: /^Follow your top/})
    await top.waitFor()
    await top.click()
    await page.waitForFunction(() => window.__calls.filter((c) => c.op === 'followArtist').length >= 2)
    const names = (await calls(page, 'followArtist')).map((c) => c.text)
    assert.equal(new Set(names).size, names.length, 'each artist once')
    assert.ok(names.length <= 5)
  })

  await test('Follow: a song menu and the artist page offer Follow, then Unfollow', async (page) => {
    await page.getByRole('navigation', {name: 'Main navigation'}).getByRole('button', {name: 'Library'}).click()
    await page.getByRole('region', {name: 'Spaces', exact: true}).getByRole('button', {name: /^Music/}).click()
    await page.getByRole('button', {name: /^Glass Harbour/}).click({button: 'right'})
    await page.getByRole('menuitem', {name: 'Follow The Overcast Choir'}).click()
    await page.waitForFunction(() => window.__calls.some((c) => c.op === 'followArtist'))
    assert.equal((await last(page, 'followArtist')).text, 'The Overcast Choir')
    await page.getByRole('button', {name: /^Glass Harbour/}).click({button: 'right'})
    await page.getByRole('menuitem', {name: 'Unfollow The Overcast Choir'}).waitFor()
    await page.keyboard.press('Escape')
    // the artist page has the visible button, in the same state
    await page.getByRole('button', {name: /^Glass Harbour/}).click({button: 'right'})
    await page.getByRole('menuitem', {name: 'Go to artist'}).click()
    await page.getByRole('button', {name: 'Following The Overcast Choir. Press to unfollow'}).click()
    await page.getByRole('button', {name: 'Follow The Overcast Choir'}).waitFor()
    assert.equal((await last(page, 'unfollowArtist')).op, 'unfollowArtist')
  })
}
