// Home's "Made from your listening", the new-releases card and its Review window (GitHub #51, option B), the Follow toggle on every artist
// row, the Following filter of the artist list and the Follow actions on an artist page and a song menu. Registered by smoke-ui-flows.mjs.
// The synthetic bridge supplies invented artists and releases; it never certifies what the real follow check finds (the unit tests of the
// artist follow engine and the core fixtures do).
import assert from 'node:assert/strict'

export async function registerHomeFollowSuite({test, calls, last}) {
  const nav = (page) => page.getByRole('navigation', {name: 'Main navigation'})
  const home = (page) => nav(page).getByRole('button', {name: 'Home'}).click()
  const card = (page) => page.getByRole('region', {name: 'New releases from artists you follow'})
  const mixes = (page) => page.getByRole('region', {name: 'Made from your listening'})
  const review = (page) => page.getByRole('dialog', {name: 'New releases'})
  const artistList = async (page) => {
    await nav(page).getByRole('button', {name: 'Library'}).click()
    await page.getByRole('group', {name: 'Show in Library'}).getByRole('button', {name: 'Artists', exact: true}).click()
    return page.getByRole('region', {name: 'Library content'})
  }
  const seeded = {fake: {following: 'seeded'}}

  await test('Home: mixes from your own plays, and no card when nothing is followed', async (page) => {
    await home(page)
    await mixes(page).waitFor()
    // the fixture library has songs but too few played in the last 30 days for On repeat: Recently added and Never played are there
    await mixes(page).getByRole('button', {name: /^Recently added music/}).waitFor()
    assert.equal(await mixes(page).getByRole('button', {name: /^On repeat/}).count(), 0, 'a mix with too little data is not shown')
    assert.equal(await card(page).count(), 0, 'nobody followed: no card')
    assert.equal(await page.getByText('New from artists you follow').count(), 0, 'the old Home feed is gone')
    assert.equal(await page.getByRole('complementary', {name: 'Following'}).count(), 0, 'and its side panel')
  })

  await test('Home: a mix opens as a song list and See all lists every mix', async (page) => {
    await home(page)
    const crumbs = page.getByRole('navigation', {name: 'Breadcrumb'})
    await mixes(page).getByRole('button', {name: /^Recently added music/}).click()
    await page.getByRole('heading', {name: 'Recently added music'}).first().waitFor()
    const first = page.getByRole('list', {name: 'Recently added music'}).getByRole('listitem').first()
    await first.waitFor()
    const box = await first.boundingBox()
    assert.ok(box && box.height > 20 && box.y < (page.viewportSize()?.height ?? 800), `the first song is visible: ${JSON.stringify(box)}`)
    await crumbs.getByRole('button', {name: 'Back'}).click()
    await mixes(page).getByRole('button', {name: 'See all mixes made from your listening'}).click()
    await page.getByRole('heading', {name: 'Made from your listening'}).first().waitFor()
    await page.getByRole('region', {name: 'Recently added music'}).waitFor()
    await crumbs.getByRole('button', {name: 'Home'}).click()
    await mixes(page).waitFor()
  })

  await test('Home: followed artists but nothing new (all seen) shows no card', async (page) => {
    await home(page)
    await mixes(page).waitFor()
    assert.equal(await card(page).count(), 0)
  }, {fake: {following: 'seeded', followingSeen: true}})

  await test('Home: one slim card counts the new releases (not old ones), names the artists and has Review', async (page) => {
    await home(page)
    await card(page).waitFor()
    await card(page).getByText('3 new releases from artists you follow').waitFor()
    await card(page).getByText('Lumen Harbor, Oren Vale and 1 more').waitFor()
    await card(page).getByRole('button', {name: 'Review'}).waitFor()
    await card(page).getByRole('button', {name: 'Dismiss new releases'}).waitFor()
    await card(page).getByRole('button', {name: 'Don’t show on Home'}).waitFor()
    assert.equal(await page.getByRole('button', {name: /^Keep /}).count(), 0, 'Home itself has no Keep it: that is in Review')
    assert.equal(await page.getByText('Old Echoes').count(), 0, 'a release found 45 days ago is not new')
  }, seeded)

  await test('Home: x hides the card until a newer release arrives', async (page) => {
    await home(page)
    await card(page).getByRole('button', {name: 'Dismiss new releases'}).click()
    await page.waitForFunction(() => window.__calls.some((c) => c.op === 'followingSeen'))
    await card(page).waitFor({state: 'detached'})
    // leaving and coming back keeps it hidden
    await nav(page).getByRole('button', {name: 'Library'}).click()
    await home(page)
    await mixes(page).waitFor()
    assert.equal(await card(page).count(), 0)
    await page.evaluate(() => window.__fake.followingNewRelease('Fresh Tide'))
    await card(page).getByText('1 new release from artists you follow').waitFor()
    await card(page).getByText('Lumen Harbor', {exact: true}).waitFor()
  }, seeded)

  await test('Home: Don’t show on Home turns the card off for good, and the Settings switch brings it back', async (page) => {
    await home(page)
    await card(page).getByRole('button', {name: 'Don’t show on Home'}).click()
    await page.waitForFunction(() => window.__calls.some((c) => c.op === 'updateSettings' && c.settings && c.settings.newReleasesOnHome === false))
    await card(page).waitFor({state: 'detached'})
    // even a newer release does not bring it back while the setting is off
    await page.evaluate(() => window.__fake.followingNewRelease('Fresh Tide'))
    await mixes(page).waitFor()
    assert.equal(await card(page).count(), 0)
    await nav(page).getByRole('button', {name: 'Settings', exact: true}).click()
    await page.getByRole('navigation', {name: 'Settings sections'}).getByRole('button', {name: 'Library'}).click()
    const toggle = page.getByRole('switch', {name: 'Show new releases on Home'})
    await toggle.waitFor()
    assert.equal(await toggle.getAttribute('aria-checked'), 'false')
    await toggle.click()
    await home(page)
    await card(page).getByText(/new releases? from artists you follow/).waitFor()
  }, seeded)

  await test('Home: the setting off from the start shows no card', async (page) => {
    await home(page)
    await mixes(page).waitFor()
    assert.equal(await card(page).count(), 0)
  }, {fake: {following: 'seeded', newReleasesOnHome: false}})

  await test('Home: Review lists the new releases with kind and age, and Keep it downloads only on the tap', async (page) => {
    await home(page)
    await card(page).getByRole('button', {name: 'Review'}).click()
    const dialog = review(page)
    await dialog.waitFor()
    assert.deepEqual(await dialog.locator('.release-title').allTextContents(), ['Salt Season', 'Night Ferry', 'Paper Moons'])
    await dialog.getByText('Lumen Harbor · Album · 3 days ago').waitFor()
    await dialog.getByRole('group', {name: 'This week'}).waitFor()
    await dialog.getByRole('group', {name: 'Earlier this month'}).waitFor()
    assert.equal((await calls(page, 'keepRelease')).length, 0, 'nothing downloads before the tap')
    await dialog.getByRole('button', {name: 'Keep Salt Season'}).click()
    await page.waitForFunction(() => window.__calls.some((c) => c.op === 'keepRelease'))
    assert.equal((await last(page, 'keepRelease')).id, 'https://music.youtube.com/playlist?list=OLAKfake1')
    await dialog.locator('.release-title', {hasText: 'Salt Season'}).waitFor({state: 'detached'})
    await dialog.getByRole('button', {name: 'Close'}).count()
  }, seeded)

  await test('Home: Review, Manage who you follow opens Library Artists on Following', async (page) => {
    await home(page)
    await card(page).getByRole('button', {name: 'Review'}).click()
    await review(page).getByRole('button', {name: 'Manage who you follow'}).click()
    const following = page.getByRole('group', {name: 'Show artists'}).getByRole('button', {name: /^Following/})
    await following.waitFor()
    assert.equal(await following.getAttribute('aria-pressed'), 'true')
    await page.getByText(/^Checked /).waitFor()
  }, seeded)

  await test('Home: Review, Mark all seen hides the card', async (page) => {
    await home(page)
    await card(page).getByRole('button', {name: 'Review'}).click()
    await review(page).getByRole('button', {name: 'Mark all seen'}).click()
    await page.waitForFunction(() => window.__calls.some((c) => c.op === 'followingSeen'))
    await card(page).waitFor({state: 'detached'})
  }, seeded)

  await test('artist list: every row has a one-tap Follow toggle, and the All / Following (n) chips filter', async (page) => {
    const content = await artistList(page)
    await content.getByRole('button', {name: /^Mira Halden/}).waitFor()
    const chips = page.getByRole('group', {name: 'Show artists'})
    await chips.getByRole('button', {name: 'Following (0)'}).waitFor()
    await page.getByRole('button', {name: 'Follow Mira Halden'}).click()
    await page.waitForFunction(() => window.__calls.some((c) => c.op === 'followArtist'))
    assert.equal((await last(page, 'followArtist')).text, 'Mira Halden')
    await page.getByRole('button', {name: 'Following Mira Halden. Press to unfollow'}).waitFor()
    await chips.getByRole('button', {name: 'Following (1)'}).click()
    await content.getByRole('button', {name: /^Mira Halden/}).waitFor()
    assert.equal(await content.getByRole('button', {name: /^Glass Harbour|^The Overcast Choir/}).count(), 0, 'only followed artists')
    await page.getByRole('button', {name: 'Following Mira Halden. Press to unfollow'}).click()
    await page.waitForFunction(() => window.__calls.some((c) => c.op === 'unfollowArtist'))
    await page.getByText('You are not following anyone yet').waitFor()
    await chips.getByRole('button', {name: 'All'}).click()
    await content.getByRole('button', {name: /^Mira Halden/}).waitFor()
  })

  await test('artist list: Following lists everyone followed, says when it was checked, and Check now asks for a check', async (page) => {
    const content = await artistList(page)
    const chips = page.getByRole('group', {name: 'Show artists'})
    await chips.getByRole('button', {name: 'Following (5)'}).click()
    for (const name of ['Lumen Harbor', 'Pale Cassette', 'Oren Vale', 'The Quiet Tides', 'Sable & Ash']) await content.getByRole('button', {name: new RegExp(`^${name.replace('&', '\\&')}`)}).waitFor()
    // none of the five is in the fixture library: they are listed, with their toggle, and have no page to open
    await content.getByText('Not in your library yet').first().waitFor()
    await page.getByText(/^Checked 1 h ago/).waitFor()
    await page.getByRole('button', {name: 'Check now'}).click()
    await page.waitForFunction(() => window.__calls.some((c) => c.op === 'followingCheck'))
    await page.getByText(/^Checked just now/).waitFor()
    assert.equal(await content.getByText('Couldn’t check').count(), 0, 'no failed check, no note')
  }, seeded)

  await test('artist list: an artist whose last check failed gets a calm line, never engine text', async (page) => {
    const content = await artistList(page)
    await page.getByRole('group', {name: 'Show artists'}).getByRole('button', {name: 'Following (5)'}).click()
    const note = content.getByText('Couldn’t check · will try again')
    await note.waitFor()
    assert.equal(await note.count(), 1, 'only the artist that failed')
    const row = content.locator('.card, .open-row', {hasText: 'The Quiet Tides'})
    await row.getByText('Couldn’t check · will try again').waitFor()
    const dot = await note.evaluate((el) => getComputedStyle(el, '::before').backgroundColor)
    assert.notEqual(dot, 'rgba(0, 0, 0, 0)', 'a small coloured dot')
    const body = await content.innerText()
    assert.doesNotMatch(body, /YouTube|yt-dlp|could not reach/i)
    // the note is only in the Following view
    await page.getByRole('group', {name: 'Show artists'}).getByRole('button', {name: 'All'}).click()
    assert.equal(await content.getByText('Couldn’t check').count(), 0)
  }, {fake: {following: 'seeded', followingFailed: true}})

  await test('Follow: a song menu and the artist page offer Follow, then Unfollow', async (page) => {
    await nav(page).getByRole('button', {name: 'Library'}).click()
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
