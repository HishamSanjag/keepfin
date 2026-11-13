// Music on this PC: the Library card, the "This device" space, the item menu for its songs and the Settings section.
// Registered by smoke-ui-flows.mjs. The synthetic bridge supplies three invented songs; the real scan is covered by the
// desktop unit tests and the Electron check, never by this suite.
import assert from 'node:assert/strict'

export async function registerDeviceMusicSuite({test, calls, last, sent}) {
  const mainNav = (page) => page.getByRole('navigation', {name: 'Main navigation'})
  const toLibrary = (page) => mainNav(page).getByRole('button', {name: 'Library'}).click()
  const content = (page) => page.getByRole('region', {name: 'Library content'})
  const side = (page) => page.getByRole('navigation', {name: 'Your library'})
  const song = (page, title) => page.getByRole('button', {name: new RegExp('^' + title)})

  await test('Music on this PC: the card offers it, one click lists the songs in a "This device" space', async (page) => {
    await toLibrary(page)
    const card = content(page).locator('#setting-device-music-card')
    await card.getByText('See the music already on this PC, where it is. Nothing is copied.').waitFor()
    assert.equal(await side(page).getByRole('button', {name: /^This device/}).count(), 0, 'nothing listed before it is switched on')
    await card.getByRole('button', {name: 'Show my music'}).click()
    await sent(page, 'deviceMusicEnable')
    assert.equal((await last(page, 'deviceMusicEnable')).enabled, true)
    await side(page).getByRole('button', {name: /^This device/}).waitFor()
    await card.waitFor({state: 'detached'})
    await side(page).getByRole('button', {name: /^This device/}).click()
    await content(page).getByText(/Only on this PC/).first().waitFor()
    for (const title of ['Roadside', 'Slow Harbor', 'Winter Light']) await song(page, title).waitFor()
    assert.equal(await content(page).getByRole('button', {name: 'Add', exact: true}).count(), 0, 'nothing is added into the device space')
  })

  await test('Music on this PC: a device song cannot be moved, retagged or renamed, but can be played, favorited and removed from Keepfin', async (page) => {
    await toLibrary(page)
    await page.getByRole('button', {name: 'Show my music'}).click()
    await side(page).getByRole('button', {name: /^This device/}).click()
    await song(page, 'Roadside').click({button: 'right'})
    for (const name of ['Play', 'Play next', 'Add to queue', 'Add to playlist', 'Add to favorites', 'Remove from Keepfin', 'Delete file…'])
      await page.getByRole('menuitem', {name, exact: true}).waitFor()
    for (const name of ['Move to…', 'Copy to…', 'Edit info…', 'Rename…', 'Tags…'])
      assert.equal(await page.getByRole('menuitem', {name, exact: true}).count(), 0, `${name} is not offered`)
    await page.getByRole('menuitem', {name: 'Remove from Keepfin', exact: true}).click()
    await sent(page, 'libraryHide')
    assert.deepEqual((await last(page, 'libraryHide')).ids, ['dm-pc-1'])
  })

  await test('Music on this PC: Settings has the switch, the folders and Show hidden songs again', async (page) => {
    await mainNav(page).getByRole('button', {name: 'Settings'}).click().catch(() => page.locator('aside.rail').getByRole('button', {name: 'Settings', exact: true}).click())
    await page.getByRole('navigation', {name: 'Settings sections'}).getByRole('button', {name: 'Library & playback'}).click()
    const section = page.getByRole('region', {name: 'Music on this PC'})
    await section.waitFor()
    const toggle = section.getByRole('switch', {name: 'Show the music on this PC'})
    assert.equal(await toggle.getAttribute('aria-checked'), 'false')
    await toggle.click()
    await sent(page, 'deviceMusicEnable')
    await section.getByText('C:\\Users\\me\\Music').waitFor()
    await section.getByRole('button', {name: 'Add a folder'}).click()
    await sent(page, 'deviceMusicFolders')
    assert.equal((await last(page, 'deviceMusicFolders')).action, 'add')
    await section.getByRole('button', {name: 'Show hidden songs again'}).click()
    await sent(page, 'deviceMusicUnhide')
    await section.getByRole('button', {name: 'Remove C:\\Users\\me\\Music'}).click()
    assert.equal((await last(page, 'deviceMusicFolders')).action, 'remove')
    assert.ok((await calls(page, 'deviceMusicFolders')).length >= 2)
  })
}