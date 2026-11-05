// Settings -> Appearance on Windows: there is no app icon picker (Windows keeps one icon for the window, the taskbar and the
// shortcuts; the choice was removed on 2026-10-20). An earlier choice is reset by the main process, never offered here.
import assert from 'node:assert/strict'

export async function registerAppIconSuite({test}) {
  await test('app icon: Appearance offers no app icon picker on Windows', async (page) => {
    await page.getByRole('navigation', {name: 'Main navigation'}).getByRole('button', {name: 'Settings', exact: true}).click()
    await page.getByRole('navigation', {name: 'Settings sections'}).getByRole('button', {name: 'Appearance'}).click()
    await page.getByRole('radiogroup', {name: 'Open Keepfin on'}).waitFor()
    assert.equal(await page.getByRole('radiogroup', {name: 'App icon'}).count(), 0)
    assert.equal(await page.getByText('Changes the window, taskbar and Keepfin shortcuts.').count(), 0)
  })
}
