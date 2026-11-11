// Edit Home (media surfaces): hide, Row/Grid, move with the keyboard, Done, the saved layout, and the Card size keys.
// Registered by smoke-ui-flows.mjs. The synthetic library only supplies sections to arrange; it says nothing about real card sizes.
import assert from 'node:assert/strict'

export async function registerEditModeSuite({test}) {
  const saved = (page, key) => page.evaluate((name) => JSON.parse(localStorage.getItem(name) ?? 'null'), key)
  const order = async (page) => (await saved(page, 'keepfin-layout-home')).sections.map((section) => section.id)

  await test('Edit Home: hide a section, switch one to Grid, move one with the keyboard, then Done', async (page) => {
    await page.getByRole('navigation', {name: 'Main navigation'}).getByRole('button', {name: 'Home'}).click()
    await page.getByRole('button', {name: 'Edit Home'}).click()
    const bar = page.getByRole('group', {name: 'Edit page layout'})
    await bar.waitFor()
    await page.getByRole('button', {name: 'Hide Made from your listening'}).click()
    await page.getByRole('button', {name: 'Show Made from your listening'}).waitFor()
    await page.getByRole('radiogroup', {name: 'New videos layout'}).getByRole('radio', {name: 'Grid'}).click()
    const before = await order(page)
    await page.getByRole('button', {name: 'Move New videos'}).focus()
    await page.keyboard.press('ArrowUp')
    await page.waitForFunction(
      (ids) =>
        JSON.parse(localStorage.getItem('keepfin-layout-home'))
          .sections.map((s) => s.id)
          .join() !== ids,
      before.join(),
    )
    const after = await order(page)
    assert.equal(after.indexOf('added-videos'), before.indexOf('added-videos') - 1, 'New videos moved up one place')
    await bar.getByRole('button', {name: 'Done'}).click()
    await bar.waitFor({state: 'detached'})
    assert.equal(await page.getByRole('region', {name: 'Made from your listening'}).count(), 0, 'a hidden section is gone outside edit mode')
    const layout = await saved(page, 'keepfin-layout-home')
    assert.equal(layout.sections.find((section) => section.id === 'mixes').visible, false)
    assert.equal(layout.sections.find((section) => section.id === 'added-videos').style, 'grid')
  })

  await test('Card size: Ctrl+= makes cards bigger and says so', async (page) => {
    await page.getByRole('navigation', {name: 'Main navigation'}).getByRole('button', {name: 'Home'}).click()
    await page.keyboard.press('Control+=')
    await page.getByText('Card size 105%').waitFor()
    assert.equal((await saved(page, 'keepfin-card-prefs')).scale, 1.05)
  })
}
