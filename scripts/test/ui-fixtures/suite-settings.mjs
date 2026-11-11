// Settings: the category rail, search, "needs your attention", the redirect from old section ids, and the Devices order.
// Registered by smoke-ui-flows.mjs, so `npm run gui:ui-test` runs it. Data comes from the fake bridge (synthetic).
// SETTINGS_SHOTS=<folder> saves screenshots of every category, the search, Devices and the 80% / 140% sizes (light and dark).
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'

const shots = process.env.SETTINGS_SHOTS
if (shots) fs.mkdirSync(shots, {recursive: true})
const shoot = async (page, name) => {
  if (shots) await page.screenshot({path: path.join(shots, `${name}.png`), fullPage: true})
}

const labels = ['Downloads', 'Library & playback', 'Storage & copies', 'Devices', 'Appearance', 'Notifications', 'Help', 'Support Keepfin', 'About Keepfin']

export async function registerSettingsSuite({test, sent}) {
  const rail = (page) => page.getByRole('navigation', {name: 'Settings sections'})
  const open = async (page, label) => {
    await page.locator('aside.rail').getByRole('button', {name: 'Settings', exact: true}).click()
    if (label) await rail(page).getByRole('button', {name: label}).click()
  }
  const title = (page) => page.locator('.settings-title')
  const search = (page) => page.getByRole('combobox', {name: 'Search settings'})
  const attention = (page) => page.getByRole('list', {name: 'Needs your attention'})
  const overflow = (page) => page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)

  await test('settings: the rail lists the nine categories in order, each with an icon, and search sits above it', async (page) => {
    await open(page)
    const buttons = rail(page).getByRole('button')
    assert.deepEqual((await buttons.allTextContents()).map((text) => text.trim()), labels)
    assert.equal(await rail(page).locator('button svg').count(), 9, 'an icon each')
    assert.equal(await title(page).textContent(), 'Downloads', 'Downloads is the first category and the one Settings opens on')
    assert.equal(await rail(page).getByRole('button', {name: 'Downloads'}).getAttribute('aria-current'), 'page')
    const searchBox = await search(page).boundingBox()
    const navBox = await rail(page).boundingBox()
    assert.ok(searchBox.y < navBox.y, 'the search field is at the top of the rail')
    for (const label of labels) {
      await rail(page).getByRole('button', {name: label}).click()
      assert.equal(await title(page).textContent(), label)
    }
    assert.equal(await overflow(page), 0)
  })

  await test('settings: Keepfin mode is set only under Library & playback', async (page) => {
    await open(page, 'Appearance')
    assert.equal(await page.getByRole('radiogroup', {name: 'Keepfin mode'}).count(), 0)
    await rail(page).getByRole('button', {name: 'Library & playback'}).click()
    await page.getByRole('radiogroup', {name: 'Keepfin mode'}).waitFor()
    assert.equal(await page.getByRole('radiogroup', {name: 'Keepfin mode'}).count(), 1)
  })

  await test('settings: Performance and Network moved under Downloads > Speed and connections, sign-in under Website sign-in', async (page) => {
    await open(page, 'Downloads')
    await page.getByRole('region', {name: 'Website sign-in'}).getByRole('radio', {name: 'Browser'}).waitFor()
    const head = page.getByRole('button', {name: /Speed and connections/})
    assert.equal(await head.getAttribute('aria-expanded'), 'false', 'collapsed until opened')
    assert.equal(await page.locator('#proxy-input').count(), 0)
    await head.click()
    await page.locator('#proxy-input').waitFor()
    await page.getByRole('spinbutton', {name: 'Parallel downloads'}).waitFor()
    await page.getByRole('switch', {name: 'Turbo transfer'}).waitFor()
  })

  await test('settings, Website sign-in: Keepfin lists its own sign-ins with Sign out, and Sign in… opens the window for a chosen site', async (page) => {
    await open(page, 'Downloads')
    const region = page.getByRole('region', {name: 'Website sign-in'})
    await region.getByText('Sign in to a site…').waitFor()
    assert.equal(await region.getByText(/^Signed in to /).count(), 1, 'only the site that was signed in at start')
    await region.getByText('Signed in to Reddit').waitFor()
    await region.getByLabel('Site', {exact: true}).selectOption('instagram')
    await region.getByRole('button', {name: 'Sign in…'}).click()
    await region.getByText('Signed in to Instagram').waitFor()
    const open1 = await page.evaluate(() => window.__calls.filter((c) => c.op === 'signInOpen'))
    assert.deepEqual(open1.map((c) => c.site), ['instagram'], 'only a site key is sent, never a link or a path')
    await region.getByRole('button', {name: 'Sign out of Reddit'}).click()
    await page.waitForFunction(() => !document.body.textContent.includes('Signed in to Reddit'))
    assert.equal((await page.evaluate(() => window.__calls.filter((c) => c.op === 'signInRemove'))).pop().site, 'reddit')
    await region.getByLabel('Site', {exact: true}).selectOption('reddit')
  }, {fake: {signedInTo: ['reddit']}})

  await test('settings, Website sign-in: a window closed before signing in says so; no password store means no Sign in…', async (page) => {
    await open(page, 'Downloads')
    const region = page.getByRole('region', {name: 'Website sign-in'})
    await region.getByRole('button', {name: 'Sign in…'}).click()
    await region.getByText(/The window was closed before the sign-in finished/).waitFor()
  }, {fake: {signInWindow: 'closed'}})

  await test('settings, Website sign-in: YouTube carries the Firefox hint, and the window reaching Google sign-in leaves the plain message here', async (page) => {
    await open(page, 'Downloads')
    const region = page.getByRole('region', {name: 'Website sign-in'})
    await region.getByLabel('Site', {exact: true}).selectOption('youtube')
    await region.getByText('Needs Firefox: Google blocks sign-in here').waitFor()
    await region.getByLabel('Site', {exact: true}).selectOption('reddit')
    assert.equal(await region.getByText('Needs Firefox: Google blocks sign-in here').count(), 0, 'other sites carry no hint')
    await region.getByLabel('Site', {exact: true}).selectOption('youtube')
    await region.getByRole('button', {name: 'Sign in…'}).click()
    await region.getByText("Google doesn't allow signing in from this window. Sign in to YouTube in Firefox, and Keepfin will use that sign-in automatically.").waitFor()
  }, {fake: {signInWindow: 'google'}})

  await test('settings, Website sign-in: with no password store on this PC Sign in… is off and says why', async (page) => {
    await open(page, 'Downloads')
    const region = page.getByRole('region', {name: 'Website sign-in'})
    await region.getByText(/no password store/).waitFor()
    assert.equal(await region.getByRole('button', {name: 'Sign in…'}).isDisabled(), true)
  }, {fake: {canKeepSignIn: false}})

  await test('settings search: Proxy opens Downloads > Speed and connections and highlights the setting', async (page) => {
    await open(page, 'Appearance')
    await search(page).fill('Proxy')
    const results = page.getByRole('listbox', {name: 'Matching settings'})
    await results.waitFor()
    const first = results.getByRole('option').first()
    assert.match(await first.textContent(), /Proxy/)
    assert.match(await first.textContent(), /Downloads › Speed and connections/)
    await shoot(page, 'search-open')
    await search(page).press('Enter')
    assert.equal(await title(page).textContent(), 'Downloads')
    await page.locator('#setting-proxy').waitFor()
    await page.locator('#setting-proxy.is-highlight').waitFor()
    assert.equal(await page.getByRole('button', {name: /Speed and connections/}).getAttribute('aria-expanded'), 'true', 'the expander opened for it')
    assert.equal(await search(page).inputValue(), '', 'the search is cleared')
    await page.locator('#setting-proxy.is-highlight').waitFor({state: 'detached', timeout: 6000})
  })

  await test('settings search: arrow keys choose, Esc clears, a click opens, nothing found says so', async (page) => {
    await open(page)
    await search(page).fill('cross')
    await page.getByRole('option', {name: /Crossfade/}).waitFor()
    await search(page).press('Escape')
    assert.equal(await search(page).inputValue(), '')
    assert.equal(await page.getByRole('listbox').count(), 0)
    await search(page).fill('theme')
    await page.getByRole('option', {name: /Theme/}).waitFor()
    await search(page).press('ArrowDown')
    await search(page).press('ArrowUp')
    await page.getByRole('option', {name: /Theme/}).click()
    assert.equal(await title(page).textContent(), 'Appearance')
    await page.locator('#setting-theme.is-highlight').waitFor()
    await search(page).fill('zzzzqq')
    await page.getByText('No setting matches').waitFor()
    await search(page).press('Escape')
    await search(page).fill('lyrics')
    await search(page).press('Enter')
    assert.equal(await title(page).textContent(), 'Downloads')
    await page.locator('#setting-lyrics.is-highlight').waitFor()
  })

  await test('settings: nothing needs attention, so no line is shown', async (page) => {
    await open(page)
    await page.waitForTimeout(500)
    assert.equal(await attention(page).count(), 0)
    await rail(page).getByRole('button', {name: 'Appearance'}).click()
    await page.waitForTimeout(300)
    assert.equal(await attention(page).count(), 0)
  }, {fake: {copies: 'empty'}})

  await test('settings: copies that need a decision add a line whose Review opens Needs a decision in Storage', async (page) => {
    await open(page, 'Appearance')
    const line = attention(page).getByRole('listitem').filter({hasText: 'need a decision'})
    await line.waitFor()
    await line.getByRole('button', {name: /^Review/}).click()
    assert.equal(await title(page).textContent(), 'Storage & copies')
    await page.getByRole('dialog', {name: 'Needs a decision'}).waitFor()
    assert.equal(await attention(page).count(), 0)
  })

  for (const mode of ['dark', 'light']) {
    await test(`settings (${mode}): a blocking firewall shows a line on every other category, and Fix opens Devices at the status card`, async (page) => {
      await open(page)
      const line = attention(page).getByRole('listitem').filter({hasText: 'Windows Firewall'})
      await line.waitFor()
      await shoot(page, `attention-${mode}`)
      await rail(page).getByRole('button', {name: 'Appearance'}).click()
      await line.waitFor()
      await line.getByRole('button', {name: /^Fix/}).click()
      assert.equal(await title(page).textContent(), 'Devices')
      await page.locator('.device-status[data-state="warn"]').waitFor()
      await page.locator('#setting-status.is-highlight').waitFor()
      assert.equal(await attention(page).count(), 0, 'the problem is not repeated on the page that shows it')
      await page.getByRole('button', {name: 'Allow Keepfin'}).click()
      await sent(page, 'devicesFirewallAllow')
      await rail(page).getByRole('button', {name: 'Downloads'}).click()
      await page.waitForTimeout(300)
      assert.equal(await attention(page).count(), 0, 'fixed: the line is gone')
    }, {theme: mode, fake: {devicesFirewall: true, copies: 'empty'}})
  }

  await test('settings: missing files add a line whose Fix opens Storage at Check files', async (page) => {
    await open(page, 'Appearance')
    const line = attention(page).getByRole('listitem').filter({hasText: '3 files'})
    await line.waitFor()
    await line.getByRole('button', {name: /^Fix/}).click()
    assert.equal(await title(page).textContent(), 'Storage & copies')
    await page.locator('#setting-files.is-highlight').waitFor()
    assert.equal(await attention(page).count(), 0)
  }, {fake: {storageMissing: 3, copies: 'empty'}})

  await test('settings: old section ids still land in the right category (the engine notice opens About Keepfin at Updates)', async (page) => {
    // Downloads page, engine broken: its Details button asks for the old "updates" section.
    await page.getByRole('button', {name: 'Details', exact: true}).click()
    assert.equal(await title(page).textContent(), 'About Keepfin')
    await page.locator('#setting-updates.is-highlight').waitFor()
    assert.equal(await rail(page).getByRole('button', {name: 'About Keepfin'}).getAttribute('aria-current'), 'page')
    assert.equal(await rail(page).getByRole('button').filter({hasText: /^(General|Updates|Performance|Network|Support)$/}).count(), 0)
  }, {fake: {engine: 'error'}})

  await test('settings: Support Keepfin is its own page, apart from Help', async (page) => {
    await open(page, 'Support Keepfin')
    assert.equal(await title(page).textContent(), 'Support Keepfin')
    await page.getByRole('heading', {name: 'Hisham Sanjag'}).waitFor()
    await open(page, 'Help')
    assert.equal(await title(page).textContent(), 'Help')
    assert.equal(await page.getByRole('heading', {name: 'Hisham Sanjag'}).count(), 0, 'Help has no donation links')
    await open(page, 'Support Keepfin')
    await page.getByRole('heading', {name: 'Hisham Sanjag'}).waitFor()
  })

  await test('sidebar: the last row is the version number, beside Edit sidebar and Settings, and opens About Keepfin', async (page) => {
    const entry = page.locator('.rail-foot .side-version')
    await entry.waitFor()
    assert.match(await entry.textContent(), /^0\.4\.0$/)
    assert.match(await entry.getAttribute('aria-label'), /About Keepfin, version 0\.4\.0/)
    // one row: the version, then the two buttons
    const [a, s] = [await entry.boundingBox(), await page.locator('.rail-foot').getByRole('button', {name: 'Settings', exact: true}).boundingBox()]
    assert.ok(Math.abs(a.y + a.height / 2 - (s.y + s.height / 2)) < 2 && s.x > a.x, 'the version and Settings share one row')
    await entry.click()
    assert.equal(await title(page).textContent(), 'About Keepfin')
    assert.equal(await entry.getAttribute('aria-current'), 'page')
  })

  await test('settings: Start over has two buttons, each needs DELETE typed, and sends only the intent (no path)', async (page) => {
    await open(page, 'About Keepfin')
    await page.getByRole('region', {name: 'Start over'}).waitFor()
    await page.getByRole('button', {name: 'Reset Keepfin'}).click()
    const dialog = page.getByRole('dialog', {name: 'Reset Keepfin'})
    const confirm = dialog.getByRole('button', {name: 'Reset Keepfin'})
    assert.equal(await confirm.isDisabled(), true)
    await dialog.getByRole('textbox').fill('delete')
    assert.equal(await confirm.isDisabled(), true, 'the word is case sensitive')
    await dialog.getByRole('textbox').fill('DELETE')
    await confirm.click()
    await sent(page, 'startOver')
    const call = await page.evaluate(() => window.__calls.filter((c) => c.op === 'startOver').pop())
    assert.equal(call.deleteFolder, false)
    assert.deepEqual(Object.keys(call).sort(), ['deleteFolder', 'op'])
  })

  await test('settings: Delete everything is confirmed the same way and also appears under Storage & copies', async (page) => {
    await open(page, 'Storage & copies')
    await page.getByRole('region', {name: 'Start over'}).waitFor()
    await page.getByRole('button', {name: 'Delete everything'}).click()
    const dialog = page.getByRole('dialog', {name: 'Delete everything'})
    const confirm = dialog.getByRole('button', {name: 'Delete everything'})
    assert.equal(await confirm.isDisabled(), true)
    await dialog.getByRole('textbox').fill('DELETE')
    await confirm.click()
    await sent(page, 'startOver')
    assert.equal((await page.evaluate(() => window.__calls.filter((c) => c.op === 'startOver').pop())).deleteFolder, true)
  })

  await test('settings: Keyboard shortcuts are listed in Help, with search, settings and play/pause', async (page) => {
    await open(page, 'Help')
    // folded under Help until opened
    await page.getByRole('button', {name: /Keyboard shortcuts/}).click()
    const list = page.locator('dl.shortcuts')
    await list.waitFor()
    const text = await list.textContent()
    for (const word of ['Ctrl', 'K', 'Search your library', 'Open Settings', 'Play or pause', 'Esc']) assert.ok(text.includes(word), `lists ${word}`)
  })
  await test('devices: this device comes first, then Available devices, your devices, libraries, and Advanced last (the guide is one "?" at the top)', async (page) => {
    await open(page, 'Devices')
    await page.locator('.device-status').waitFor()
    const status = await page.locator('.device-status').boundingBox()
    const paired = await page.getByRole('region', {name: 'Your devices'}).boundingBox()
    const nearby = await page.getByRole('region', {name: 'Available devices', exact: true}).boundingBox()
    const offers = await page.getByRole('region', {name: 'Libraries on other devices'}).boundingBox()
    const advanced = await page.getByRole('button', {name: /^Advanced/}).boundingBox()
    assert.ok(status.y < nearby.y && nearby.y < paired.y && paired.y < offers.y && offers.y < advanced.y, JSON.stringify({status, paired, nearby, offers, advanced}))
    assert.equal(await page.getByRole('region', {name: 'Away from home'}).count(), 0, 'Away from home is in the guide, not on the page')
    assert.equal(await page.getByRole('button', {name: 'How devices work'}).count(), 1)
    assert.ok(status.y < paired.y)
    assert.equal(await page.locator('.device-status').getAttribute('data-state'), 'ok')
    assert.match(await page.locator('.device-status').textContent(), /Desk PC\s*Rename · Visible on Home network/)
    await page.locator('.device-status').getByRole('switch', {name: 'Devices on'}).waitFor()
    assert.equal(await page.getByRole('button', {name: 'Turn off devices'}).count(), 0, 'the switch at the top replaced it')
    // the name other devices see is at the top with Rename, not inside Advanced
    await page.locator('.device-status').getByRole('button', {name: 'Rename'}).click()
    await page.getByRole('textbox', {name: 'Name of this PC'}).waitFor()
    await page.getByRole('button', {name: 'Cancel'}).click()
    assert.equal(await page.getByRole('textbox', {name: 'Name of this PC'}).count(), 0)
  }, {fake: {devicesOffers: true}})

  // ---------------------------------------------------------------- screenshots (SETTINGS_SHOTS)
  if (shots) {
    for (const mode of ['dark', 'light']) {
      for (const label of labels) {
        await test(`shots (${mode}): ${label}`, async (page) => {
          await open(page, label)
          if (label === 'Downloads') await page.getByRole('button', {name: /Speed and connections/}).click()
          if (label === 'Devices') await page.getByRole('button', {name: /^Advanced/}).click()
          await page.waitForTimeout(400)
          await shoot(page, `${label.split(' ')[0].toLowerCase()}-${mode}`)
        }, {theme: mode, height: label === 'Devices' ? 1500 : 2000, fake: {devicesOffers: true, devicesFirewall: label === 'Devices', devicesTailscale: true}})
      }
      await test(`shots (${mode}): Devices details and the Add by address panel`, async (page) => {
        await open(page, 'Devices')
        await page.getByRole('list', {name: 'Paired devices'}).getByRole('article', {name: 'Pixel 9', exact: true}).locator('.devices-card-row').click()
        await page.locator('.devices-card-body').waitFor()
        await shoot(page, `devices-details-${mode}`)
        await page.getByRole('button', {name: 'Add by address'}).click()
        await page.getByRole('textbox', {name: 'Enter the code or address shown on your other device'}).waitFor()
        await shoot(page, `devices-add-${mode}`)
      }, {theme: mode, height: 1500})
      await test(`shots (${mode}): Devices, normal`, async (page) => {
        await open(page, 'Devices')
        await page.locator('.device-status[data-state="ok"]').waitFor()
        await shoot(page, `devices-normal-${mode}`)
      }, {theme: mode, height: 1500})
    }
    for (const scale of [80, 140]) {
      await test(`shots: Downloads at ${scale}%`, async (page) => {
        await open(page, 'Downloads')
        await page.getByRole('button', {name: /Speed and connections/}).click()
        await page.waitForTimeout(400)
        await shoot(page, `downloads-${scale}pct`)
        await rail(page).getByRole('button', {name: 'Devices'}).click()
        await page.waitForTimeout(300)
        await shoot(page, `devices-${scale}pct`)
      }, {scale, height: 1500, fake: {devicesFirewall: true, devicesTailscale: true}})
    }
  }
}
