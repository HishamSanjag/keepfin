// Devices on the network: Settings -> Devices (the header and switch, Available devices as a circle or a list, the QR code, Your devices
// as cards, the pairing dialog that opens on any screen, the scanned-code note with Undo, Away from home, Unpair).
// Registered by smoke-ui-flows.mjs, so `npm run gui:ui-test` runs it. Data comes from the fake bridge (synthetic).
// DEVICES_SHOTS=<folder> saves screenshots of the circle, the crowded list, an expanded card and the incoming request, light and dark.
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

export async function registerDevicesSuite({test, sent}) {
  const got = async (page, op) => {
    await sent(page, op)
    return page.evaluate((op) => [...window.__calls].reverse().find((c) => c.op === op), op)
  }
  const openDevices = async (page) => {
    await page.getByRole('navigation', {name: 'Main navigation'}).getByRole('button', {name: 'Settings', exact: true}).click()
    await page.getByRole('navigation', {name: 'Settings sections'}).getByRole('button', {name: 'Devices'}).click()
    await sent(page, 'devicesState')
  }
  const advanced = async (page) => {
    const head = page.getByRole('button', {name: /^Advanced/})
    await head.waitFor()
    if ((await head.getAttribute('aria-expanded')) !== 'true') await head.click()
  }
  const list = (page) => page.getByRole('list', {name: 'Paired devices'})
  const card = (page, name) => list(page).getByRole('article', {name, exact: true})
  const cardRow = (page, name) => card(page, name).locator('.devices-card-row')
  /** Opens a device's card (it is a row until opened) and returns it. */
  const details = async (page, name) => {
    const row = cardRow(page, name)
    if ((await row.getAttribute('aria-expanded')) !== 'true') await row.click()
    await card(page, name).locator('.devices-card-body').waitFor()
    return card(page, name)
  }
  const addPanel = (page) => page.getByRole('region', {name: 'Add by address', exact: true})
  const overflow = (page) => page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
  const available = (page) => page.getByRole('region', {name: 'Available devices', exact: true})
  const dialog = (page) => page.getByRole('dialog')
  const ready = (page) => page.waitForFunction(() => !!window.__fake)
  // the page looks for devices while it is open: Galaxy S24 shows under Available devices with a Connect button
  const startPairing = async (page) => {
    await openDevices(page)
    await sent(page, 'devicesWatch')
    await available(page).getByRole('button', {name: /^Connect to Galaxy S24/}).waitFor()
  }
  const connectGalaxy = async (page) => {
    await startPairing(page)
    await available(page).getByRole('button', {name: /^Connect to Galaxy S24/}).click()
    await got(page, 'devicesConnect')
  }
  // Add by address lives inside "Not seeing it?": this device then opens its side of pairing for two minutes
  const openAddress = async (page) => {
    await openDevices(page)
    await available(page).getByRole('button', {name: 'Not seeing it?'}).click()
    await dialog(page).getByRole('button', {name: 'Add by address'}).click()
    await sent(page, 'devicesPairingOpen')
    await page.getByRole('textbox', {name: 'Enter the code or address shown on your other device'}).waitFor()
  }
  const sideBySide = async (first, second) => {
    const a = await first.boundingBox()
    const b = await second.boundingBox()
    assert.ok(a && b, 'both buttons are drawn')
    assert.ok(Math.abs(a.y - b.y) < 3, `side by side (y ${a.y} vs ${b.y})`)
    assert.ok(a.x + a.width <= b.x + 1 || b.x + b.width <= a.x + 1, 'and not on top of each other')
  }

  for (const mode of ['dark', 'light']) {
    await test(`devices (${mode}): the header, and Your devices as cards that are one row until opened`, async (page) => {
      await openDevices(page)
      const header = page.locator('.device-status').first()
      await header.waitFor()
      assert.match(await header.textContent(), /Devices/)
      assert.match(await header.textContent(), /Desk PC\s*Rename · Visible on Home network/)
      await page.getByRole('switch', {name: 'Devices on'}).waitFor()
      const rows = await list(page).textContent()
      assert.match(rows, /Pixel 9/)
      assert.match(rows, /Android phone · last seen/)
      assert.match(rows, /Windows PC · Home network/, 'how a connected device is reached')
      assert.match(await card(page, 'Laptop').textContent(), /Syncing…/)
      assert.match(await card(page, 'Pixel 9').locator('.devices-card-status').textContent(), /Offline/)
      assert.equal(await cardRow(page, 'Pixel 9').getAttribute('aria-expanded'), 'false')
      assert.equal(await page.locator('.devices-card-body').count(), 0, 'every card starts collapsed')
      // a card opens with the keyboard, and several can be open at once
      await cardRow(page, 'Pixel 9').focus()
      await page.keyboard.press('Enter')
      assert.equal(await cardRow(page, 'Pixel 9').getAttribute('aria-expanded'), 'true')
      const phone = card(page, 'Pixel 9')
      assert.match(await phone.textContent(), /Unreachable since/)
      assert.match(await phone.textContent(), /Keepfin reconnects by itself/)
      assert.doesNotMatch(await phone.locator('.devices-card-body').textContent(), /Offline/, 'Offline is said once, by the chip')
      assert.equal(await phone.getByRole('button', {name: 'Play from here'}).getAttribute('aria-pressed'), 'false')
      await shoot(page, `devices-card-expanded-${mode}`, phone)
      const laptop = await details(page, 'Laptop')
      assert.equal(await laptop.getByRole('button', {name: 'Play from here'}).getAttribute('aria-pressed'), 'true')
      assert.equal(await page.locator('.devices-card-body').count(), 2, 'both stay open')
      await cardRow(page, 'Laptop').click()
      assert.equal(await page.locator('.devices-card-body').count(), 1, 'one closes, the other stays')
      await shoot(page, `devices-${mode}`)
      await page.locator('.device-status').getByRole('button', {name: 'Rename'}).click()
      const field = page.getByRole('textbox', {name: 'Name of this PC'})
      assert.equal(await field.inputValue(), 'Desk PC')
      assert.equal(await page.getByRole('button', {name: 'Save'}).isDisabled(), true, 'the same name is no change')
      await field.fill('Studio PC')
      await field.press('Enter')
      assert.equal((await got(page, 'devicesRename')).devices.name, 'Studio PC')
      await advanced(page)
      assert.match(await page.getByLabel('Device fingerprint').textContent(), /5d20 8ab4 f1e3 7c69 0b2d/)
      assert.equal(await overflow(page), 0, 'no sideways scrolling')
    }, {theme: mode, height: 1500})
  }

  await test('devices: choosing what a paired device may do sends the whole list for that library', async (page) => {
    await openDevices(page)
    const phone = await details(page, 'Pixel 9')
    await phone.getByRole('button', {name: 'Play from here'}).click()
    const call = await got(page, 'devicesAccess')
    assert.equal(call.devices.deviceId, 'dev-phone')
    assert.equal(call.devices.libraryId, 'lib-1')
    assert.deepEqual(call.devices.actions, ['metadata.read', 'metadata.write', 'media.read'])
    await phone.getByRole('button', {name: 'Play from here', pressed: true}).waitFor()
    assert.equal(await phone.getByRole('button', {name: 'Play from here'}).getAttribute('aria-pressed'), 'true')
  })

  await test('devices: the small pill on a collapsed card syncs (or retries), and the opened card carries the same button', async (page) => {
    await openDevices(page)
    // Pixel 9 is offline in the fixture: its pill says Retry; it asks the device to be reached again now
    await card(page, 'Pixel 9').getByRole('button', {name: 'Retry: Pixel 9'}).click()
    assert.equal((await got(page, 'devicesSyncNow')).devices.deviceId, 'dev-phone')
    const phone = await details(page, 'Pixel 9')
    // open: the pill goes, the status line has the button
    assert.equal(await phone.locator('.devices-card-pill').count(), 0)
    await phone.locator('.devices-card-statusrow').getByRole('button', {name: 'Retry: Pixel 9'}).waitFor()
  })

  await test('devices: Unpair says what it can and cannot do (copies stay), then forgets the device', async (page) => {
    await openDevices(page)
    const phone = await details(page, 'Pixel 9')
    assert.equal(await phone.getByRole('button', {name: 'Remove'}).count(), 0, 'nothing is called Remove any more')
    await phone.getByRole('button', {name: 'Unpair', exact: true}).click()
    const text = await phone.textContent()
    assert.match(text, /what it writes to the library afterwards is ignored/)
    assert.match(text, /Copies already made stay where they are/)
    assert.match(text, /switched off only learns this when it next syncs/)
    await phone.getByRole('button', {name: 'Unpair this device'}).click()
    const call = await got(page, 'devicesRemove')
    assert.equal(call.devices.deviceId, 'dev-phone')
    await phone.waitFor({state: 'detached'})
    await card(page, 'Laptop').waitFor()
  })

  await test('devices: when Devices is off the page is one calm state with the switch, and the switch turns it on', async (page) => {
    await openDevices(page)
    await page.getByText('Devices is off', {exact: true}).waitFor()
    assert.match(await page.locator('.device-status').textContent(), /Turning it on lets this PC find your other computers and phones/)
    assert.match(await page.locator('.device-status').textContent(), /Allow it, so your phone and other computers can reach this PC/)
    assert.doesNotMatch(await page.locator('.device-status').textContent(), /private/i)
    assert.equal(await page.getByRole('button', {name: 'Not seeing it?'}).count(), 0)
    assert.equal(await available(page).count(), 0, 'nothing else is shown')
    assert.equal(await page.getByRole('region', {name: 'Your devices'}).count(), 0)
    await page.getByRole('switch', {name: 'Devices on'}).click()
    await sent(page, 'devicesEnable')
    await page.getByRole('switch', {name: 'Devices on'}).waitFor()
    await page.getByText('No other devices yet').waitFor()
    await page.locator('.device-status').getByRole('button', {name: 'Rename'}).waitFor()
  }, {fake: {devices: 'off'}})

  await test('devices: no secure storage is explained, and a busy port is reported instead of worked around', async (page) => {
    await openDevices(page)
    await page.getByText('Devices are not available').waitFor()
    assert.match(await page.locator('.empty').textContent(), /Secure storage is not available/)
  }, {fake: {devices: 'unavailable'}})
  await test('devices: a port another program holds is shown with what to do', async (page) => {
    await openDevices(page)
    await page.getByText('Devices could not start').waitFor()
    assert.match(await page.locator('.inline-alert').first().textContent(), /Another program is using port 47215/)
    await available(page).getByRole('button', {name: 'Not seeing it?'}).click()
    assert.equal(await dialog(page).getByRole('button', {name: 'Add by address'}).isDisabled(), true)
  }, {fake: {devices: 'portBusy'}})

  for (const mode of ['dark', 'light']) {
    await test(`devices (${mode}): Add by address takes an address and no longer holds a QR code`, async (page) => {
      await openAddress(page)
      assert.match(await addPanel(page).textContent(), /open to new devices for [12]:\d\d/)
      assert.equal(await addPanel(page).getByRole('img').count(), 0, 'the QR code is its own card beside Available devices')
      assert.equal(await addPanel(page).getByText('Found on your network').count(), 0, 'found devices are under Available devices')
      assert.equal(await overflow(page), 0)
    }, {theme: mode, height: 1500})
  }

  // ---------------------------------------------------------------- the QR code
  for (const mode of ['dark', 'light']) {
    await test(`devices (${mode}): "Scan with your phone" shows a QR code that is asked for while the page is open`, async (page) => {
      await openDevices(page)
      await sent(page, 'devicesQr')
      const qr = page.getByRole('img', {name: 'QR code to connect a phone to Desk PC'})
      await qr.waitFor()
      const cardBox = page.getByRole('region', {name: 'Scan with your phone'})
      assert.match(await cardBox.textContent(), /Connects at once\./)
      assert.match(await cardBox.textContent(), /Use the phone’s camera, or Keepfin › Devices › Scan\. A new code every 2 minutes\./)
      assert.match(await cardBox.textContent(), /No camera\? Type this code on the other device/)
      assert.equal((await cardBox.locator('.devices-qr-code-text').textContent()).trim(), 'K7QPM-4X2TA', 'the typed code is shown under the QR')
      assert.doesNotMatch(await cardBox.locator('.devices-qr-code-text').evaluate((el) => getComputedStyle(el).fontFamily), /mono/i, 'the code is not in a monospace face')
      const asked = await page.evaluate(() => window.__calls.filter((c) => c.op === 'devicesQr').length)
      assert.equal(asked, 1, 'one code for now: the next is due in two minutes')
      await shoot(page, `devices-qr-${mode}`, cardBox)
      assert.equal(await overflow(page), 0)
    }, {theme: mode, height: 1200})
  }

  await test('devices: no code is asked for while Devices is off', async (page) => {
    await openDevices(page)
    await page.getByText('Devices is off', {exact: true}).waitFor()
    assert.equal(await page.evaluate(() => window.__calls.filter((c) => c.op === 'devicesQr').length), 0)
  }, {fake: {devices: 'off'}})

  // ---------------------------------------------------------------- connecting to a device
  for (const mode of ['dark', 'light']) {
    await test(`devices (${mode}): Connect asks the other device; both compare the code, the first tap waits (only Cancel) for the other person`, async (page) => {
      await startPairing(page)
      await shoot(page, `devices-circle-${mode}`, available(page))
      await available(page).getByRole('button', {name: /^Connect to Galaxy S24/}).click()
      const call = await got(page, 'devicesConnect')
      assert.equal(call.devices.address, '192.168.1.23:47215')
      const box = dialog(page)
      await box.getByRole('heading', {name: 'Connecting to Galaxy S24…'}).waitFor()
      const code = box.getByLabel('Pairing code')
      await code.waitFor()
      assert.equal((await code.textContent()).trim(), '482 193')
      assert.match(await box.textContent(), /Check that both devices show this code, then tap Codes match on both\./)
      assert.doesNotMatch(await box.textContent(), /open Add a device|open Settings/, 'the old instruction to open Add a device is gone')
      assert.doesNotMatch(await box.textContent(), /Waiting for Galaxy S24/, 'nobody tapped yet')
      const match = box.getByRole('button', {name: 'Codes match · Connect'})
      await sideBySide(match, box.getByRole('button', {name: "They don't match"}))
      await shoot(page, `devices-asking-${mode}`, box)
      await match.click()
      assert.equal((await got(page, 'devicesDecide')).devices.match, true)
      // the first tap: the code stays, this side says what is left, and only Cancel remains
      await box.getByText('On Galaxy S24, tap Codes match to finish.').waitFor()
      await box.getByText('You confirmed the code on this PC.').waitFor()
      await box.getByText('Waiting for Galaxy S24…').waitFor()
      assert.equal((await box.getByLabel('Pairing code').textContent()).trim(), '482 193', 'the code stays on screen')
      assert.deepEqual(await box.getByRole('button').allTextContents(), ['Cancel'], 'only Cancel')
      await shoot(page, `devices-asking-waiting-${mode}`, box)
      await page.evaluate(() => window.__fake.devicesPeerConfirms())
      await box.getByRole('heading', {name: 'Galaxy S24 is connected'}).waitFor()
      await box.getByRole('button', {name: 'Later'}).click()
      await sent(page, 'devicesDismiss')
      await dialog(page).waitFor({state: 'detached'})
      await card(page, 'Galaxy S24').waitFor()
    }, {theme: mode, height: 1500})
  }

  await test('devices: "They don\'t match" ends the connection and connects nothing', async (page) => {
    await connectGalaxy(page)
    const box = dialog(page)
    await box.getByLabel('Pairing code').waitFor()
    await box.getByRole('button', {name: "They don't match"}).click()
    assert.equal((await got(page, 'devicesDecide')).devices.match, false)
    await box.getByRole('heading', {name: 'Nothing was connected'}).waitFor()
    assert.match(await box.textContent(), /do not match/)
    await box.getByRole('button', {name: 'OK'}).click()
    await dialog(page).waitFor({state: 'detached'})
    assert.equal(await card(page, 'Galaxy S24').count(), 0)
  })

  await test('devices: when the other person declines after the first tap, this side says so in one sentence with OK', async (page) => {
    await connectGalaxy(page)
    const box = dialog(page)
    await box.getByLabel('Pairing code').waitFor()
    await box.getByRole('button', {name: 'Codes match · Connect'}).click()
    await box.getByText('Waiting for Galaxy S24…').waitFor()
    await page.evaluate(() => window.__fake.devicesPeerDeclines())
    await box.getByRole('heading', {name: 'Nothing was connected'}).waitFor()
    assert.match(await box.textContent(), /Galaxy S24 did not confirm, so nothing was paired\./)
    await box.getByRole('button', {name: 'OK'}).click()
    await dialog(page).waitFor({state: 'detached'})
    assert.equal(await card(page, 'Galaxy S24').count(), 0)
  })

  await test('devices: Cancel while waiting for the other device to confirm ends the connection and connects nothing', async (page) => {
    await connectGalaxy(page)
    const box = dialog(page)
    await box.getByLabel('Pairing code').waitFor()
    await box.getByRole('button', {name: 'Codes match · Connect'}).click()
    await box.getByText('Waiting for Galaxy S24…').waitFor()
    await page.evaluate(() => (window.__calls.length = 0))
    await box.getByRole('button', {name: 'Cancel'}).click()
    assert.equal((await got(page, 'devicesDecide')).devices.match, false, 'Cancel declines')
    await dialog(page).waitFor({state: 'detached'})
    assert.equal(await card(page, 'Galaxy S24').count(), 0)
  })

  await test('devices: the dialog says it is waiting for the other device to accept', async (page) => {
    await openAddress(page)
    await page.getByRole('textbox', {name: 'Enter the code or address shown on your other device'}).fill('10.0.0.7')
    await page.getByRole('button', {name: 'Connect', exact: true}).click()
    const box = dialog(page)
    await box.getByText('Waiting for the other device to accept…').waitFor()
    assert.equal(await box.getByLabel('Pairing code').count(), 0)
    await page.evaluate(() => window.__fake.devicesPeerOpened())
    await box.getByLabel('Pairing code').waitFor()
    await box.getByText(/Check that both devices show this code, then tap Codes match on both\./).waitFor()
  })

  await test('devices: Cancel while waiting for the other device ends the pairing and sends the dismiss', async (page) => {
    await openAddress(page)
    await page.getByRole('textbox', {name: 'Enter the code or address shown on your other device'}).fill('10.0.0.7')
    await page.getByRole('button', {name: 'Connect', exact: true}).click()
    await dialog(page).getByText(/Waiting for the other device to accept/).waitFor()
    await page.evaluate(() => (window.__calls.length = 0))
    await dialog(page).getByRole('button', {name: 'Cancel'}).click()
    await sent(page, 'devicesDismiss')
    await dialog(page).waitFor({state: 'detached'})
    await page.getByRole('textbox', {name: 'Enter the code or address shown on your other device'}).waitFor()
  })

  await test('devices: an address that is not one is explained; one nobody answers at fails plainly', async (page) => {
    await openAddress(page)
    const field = page.getByRole('textbox', {name: 'Enter the code or address shown on your other device'})
    await field.fill('not an address')
    await page.getByRole('button', {name: 'Connect', exact: true}).click()
    await page.getByText(/That is not an address/).waitFor()
    await field.fill('10.0.0.9')
    await page.getByRole('button', {name: 'Connect', exact: true}).click()
    const call = await got(page, 'devicesConnect')
    assert.equal(call.devices.address, '10.0.0.9')
    await dialog(page).getByText('Nothing is listening at that address').waitFor()
  })

  await test('devices: a code typed in Add by address is sent as it was typed (the engine reads it)', async (page) => {
    await openAddress(page)
    assert.match(await addPanel(page).textContent(), /type the code shown under the QR on the other device/)
    await page.getByRole('textbox', {name: 'Enter the code or address shown on your other device'}).fill('K7QPM-4X2TA')
    await page.getByRole('button', {name: 'Connect', exact: true}).click()
    const call = await got(page, 'devicesConnect')
    assert.equal(call.devices.address, 'K7QPM-4X2TA')
  })
  // ---------------------------------------------------------------- a request that comes in
  for (const mode of ['dark', 'light']) {
    await test(`devices (${mode}): a request that comes in opens a dialog on any screen: "<name> wants to connect" with the code`, async (page) => {
      await ready(page)
      await page.evaluate(() => window.__fake.devicesIncoming(true))
      const box = dialog(page)
      await box.getByRole('heading', {name: 'Galaxy S24 wants to connect'}).waitFor()
      assert.equal((await box.getByLabel('Pairing code').textContent()).trim(), '482 193')
      assert.match(await box.textContent(), /Check that both devices show this code, then tap Codes match on both\./)
      assert.match(await box.getByLabel('Fingerprint of the other device').textContent(), /77aa 12bc 90de 34f5 6a7b/)
      const match = box.getByRole('button', {name: 'Codes match · Connect'})
      const decline = box.getByRole('button', {name: "They don't match"})
      await sideBySide(match, decline)
      await shoot(page, `devices-incoming-${mode}`, box)
      await page.evaluate(() => (window.__fake.devicesHold = true)) // the asking person has not tapped yet
      await match.click()
      const decision = await got(page, 'devicesDecide')
      assert.equal(decision.devices.match, true)
      await box.getByText('On Galaxy S24, tap Codes match to finish.').waitFor()
      await box.getByText('You confirmed the code on this PC.').waitFor()
      await box.getByText('Waiting for Galaxy S24…').waitFor()
      assert.deepEqual(await box.getByRole('button').allTextContents(), ['Cancel'], 'only Cancel')
      await shoot(page, `devices-incoming-waiting-${mode}`, box)
      await page.evaluate(() => window.__fake.devicesPeerConfirmsToo())
      // the asked device's result offers the setup too
      await box.getByRole('heading', {name: 'Galaxy S24 is connected'}).waitFor()
      assert.match(await box.textContent(), /Choose what stays up to date/)
      await sideBySide(box.getByRole('button', {name: 'Later'}), box.getByRole('button', {name: 'Set up'}))
    }, {theme: mode})
  }

  await test('devices: the same dialog opens for a request from the window, from another page, and Decline pairs nothing', async (page) => {
    await openDevices(page)
    await ready(page)
    await page.evaluate(() => window.__fake.devicesIncoming(false))
    const box = dialog(page)
    await box.getByRole('heading', {name: 'Galaxy S24 wants to connect'}).waitFor()
    await box.getByRole('button', {name: "They don't match"}).click()
    const decision = await got(page, 'devicesDecide')
    assert.equal(decision.devices.match, false)
    await box.getByRole('heading', {name: 'Nothing was connected'}).waitFor()
    await box.getByRole('button', {name: 'OK'}).click()
    assert.equal(await card(page, 'Galaxy S24').count(), 0)
  })

  await test('devices: a scanned code connects at once: no dialog, a note "<name> connected" with Undo that removes it', async (page) => {
    await openDevices(page)
    await ready(page)
    await page.evaluate(() => window.__fake.devicesScanned())
    const note = page.getByRole('region', {name: 'Notifications'})
    await note.getByText('Pixel 9 Pro connected').waitFor()
    assert.equal(await dialog(page).count(), 0, 'nothing to approve')
    await sent(page, 'devicesDismiss')
    await card(page, 'Pixel 9 Pro').waitFor()
    await note.getByRole('button', {name: 'Undo'}).click()
    const removed = await got(page, 'devicesRemove')
    assert.equal(removed.devices.deviceId, 'dev-pixel-9-pro')
    await card(page, 'Pixel 9 Pro').waitFor({state: 'detached'})
  })

  // ---------------------------------------------------------------- the problem card
  for (const mode of ['dark', 'light']) {
    await test(`devices (${mode}): a blocking firewall is a problem card with Allow Keepfin, and the card goes once Windows allows it`, async (page) => {
      await openDevices(page)
      const alert = page.locator('.inline-alert-warning').filter({hasText: 'Windows Firewall is blocking other devices'})
      await alert.waitFor()
      assert.match(await alert.textContent(), /Windows is stopping other devices from reaching Keepfin on this public network\. Allow Keepfin once/)
      assert.doesNotMatch(await page.locator('.device-status').textContent(), /private/i, 'the old private-network advice is gone')
      assert.equal(await page.getByRole('button', {name: /private/i}).count(), 0)
      assert.equal(await page.locator('.device-status').first().getAttribute('data-state'), 'warn', 'the card is in its warning state')
      assert.equal(await page.getByRole('list', {name: 'Needs your attention'}).filter({hasText: 'Firewall'}).count(), 0, 'the problem is not repeated above itself')
      await shoot(page, `devices-firewall-${mode}`, alert)
      await alert.getByRole('button', {name: 'Allow Keepfin'}).click()
      const call = await got(page, 'devicesFirewallAllow')
      assert.equal(call.devices, undefined, 'no command text, no path and no network name is sent')
      await alert.waitFor({state: 'detached'})
      assert.equal(await page.locator('.device-status').first().getAttribute('data-state'), 'ok')
      assert.equal(await overflow(page), 0)
    }, {theme: mode, height: 1400, fake: {devicesFirewall: true}})
  }

  await test('devices: with the firewall open there is no problem card, and this device says where it is visible', async (page) => {
    await openDevices(page)
    await page.getByRole('switch', {name: 'Devices on'}).waitFor()
    assert.equal(await page.getByText('Windows Firewall is blocking other devices').count(), 0)
    assert.match(await page.locator('.device-status').textContent(), /Desk PC\s*Rename · Visible on Home network/)
  })

  await test('devices: with Tailscale off and a paired device away, one line under the switch says so and opens the guide at the Tailscale part', async (page) => {
    await openDevices(page)
    const line = page.locator('.devices-tailscale-line')
    await line.waitFor()
    assert.match(await line.textContent(), /Tailscale: not running · How\?/)
    await line.getByRole('button', {name: 'How?'}).click()
    const guide = dialog(page)
    await guide.getByRole('heading', {name: 'How devices work'}).waitFor()
    await guide.getByRole('region', {name: 'Away from home'}).waitFor()
    assert.match(await guide.textContent(), /Tailscale not detected/)
  })

  await test('devices: with every paired device here and Tailscale off there is no Tailscale line', async (page) => {
    await openDevices(page)
    await page.getByRole('switch', {name: 'Devices on'}).waitFor()
    assert.equal(await page.locator('.devices-tailscale-line').count(), 0)
  }, {fake: {devices: 'empty'}})

  await test('devices: with Tailscale on, the line says "Also on Tailscale", and the switch at the top turns devices off', async (page) => {
    await openDevices(page)
    await page.locator('.devices-tailscale-line').getByText('Also on Tailscale').waitFor()
    assert.match(await page.locator('.device-status').textContent(), /Desk PC\s*Rename · Visible on Home network/)
    await page.getByRole('switch', {name: 'Devices on'}).click()
    await sent(page, 'devicesDisable')
    await page.getByText('Devices is off', {exact: true}).waitFor()
  }, {fake: {devicesTailscale: true}})

  // ---------------------------------------------------------------- Available devices
  for (const mode of ['dark', 'light']) {
    await test(`devices (${mode}): Available devices is a circle with this PC in the middle, each found device one button`, async (page) => {
      await openDevices(page)
      await sent(page, 'devicesWatch')
      const group = available(page)
      const circle = group.locator('.devices-circle')
      await circle.waitFor()
      assert.match(await circle.textContent(), /This PC/)
      const nodes = circle.getByRole('button')
      await nodes.first().waitFor()
      assert.equal(await nodes.count(), 2)
      const galaxy = circle.getByRole('button', {name: 'Connect to Galaxy S24, Home network'})
      const studio = circle.getByRole('button', {name: 'Connect to Studio PC, via Tailscale'})
      assert.match(await galaxy.textContent(), /Galaxy S24/)
      assert.match(await galaxy.textContent(), /Connect/)
      assert.match(await studio.textContent(), /via Tailscale/)
      assert.equal(await circle.getByText('via Tailscale').count(), 1, 'only the Tailscale device says so')
      // the same device keeps its place
      const before = await galaxy.boundingBox()
      await page.evaluate(() => window.__fake.devicesPeerGone())
      const after = await galaxy.boundingBox()
      assert.deepEqual([Math.round(before.x), Math.round(before.y)], [Math.round(after.x), Math.round(after.y)], 'devices never jump')
      assert.match(await group.getByRole('status').textContent(), /Looking on this network and Tailscale…/)
      assert.equal(await group.getByRole('radio', {name: 'Circle'}).getAttribute('aria-checked'), 'true')
      await shoot(page, `devices-available-${mode}`, group)
      await studio.click()
      const call = await got(page, 'devicesConnect')
      assert.equal(call.devices.address, '100.101.20.5:47215')
      assert.equal(await overflow(page), 0)
    }, {theme: mode, height: 1500, fake: {devicesTailscale: true}})
  }

  await test('devices: the circle sweeps only when motion is allowed', async (page) => {
    await openDevices(page)
    await sent(page, 'devicesWatch')
    await available(page).locator('.devices-circle').waitFor()
    assert.equal(await available(page).locator('.devices-sweep').evaluate((el) => getComputedStyle(el).display), 'none')
  }, {reducedMotion: true})
  await test('devices: with motion allowed the circle sweeps', async (page) => {
    await openDevices(page)
    await available(page).locator('.devices-circle').waitFor()
    assert.notEqual(await available(page).locator('.devices-sweep').evaluate((el) => getComputedStyle(el).display), 'none')
  })

  await test('devices: the status line says Keepfin is looking, and a click on a node is a button for the keyboard', async (page) => {
    await openDevices(page)
    await sent(page, 'devicesWatch')
    await available(page).getByText('Looking on this network…').waitFor()
    const node = available(page).getByRole('button', {name: /^Connect to Galaxy S24/})
    await node.focus()
    await page.keyboard.press('Enter')
    await sent(page, 'devicesConnect')
  }, {fake: {devicesScanning: true}})

  for (const mode of ['dark', 'light']) {
    await test(`devices (${mode}): with nothing found it says so, and "Not seeing it?" opens what to check`, async (page) => {
      await openDevices(page)
      await sent(page, 'devicesWatch')
      const group = available(page)
      await group.getByText(/Nothing found yet/).waitFor()
      assert.equal(await group.getByText(/Looking on this network/).count(), 0)
      assert.equal(await group.getByRole('button').filter({hasText: /^Connect/}).count(), 0)
      await group.getByRole('button', {name: 'Not seeing it?'}).click()
      const box = dialog(page)
      await box.getByRole('heading', {name: 'Not seeing your device?'}).waitFor()
      assert.match(await box.textContent(), /Open Keepfin, then Devices, on the other device/)
      assert.match(await box.textContent(), /same Wi-Fi/)
      assert.match(await box.textContent(), /Tailscale/)
      assert.match(await box.textContent(), /Windows Firewall is not blocking Keepfin on this network/)
      await shoot(page, `devices-not-seeing-${mode}`, box)
      await box.getByRole('button', {name: 'Close'}).click()
      await dialog(page).waitFor({state: 'detached'})
    }, {theme: mode, height: 1500, fake: {devicesNearby: 'none'}})
  }

  await test('devices: the troubleshooting offers Allow Keepfin when the firewall blocks', async (page) => {
    await openDevices(page)
    await available(page).getByRole('button', {name: 'Not seeing it?'}).click()
    await dialog(page).getByRole('button', {name: 'Allow Keepfin'}).click()
    await sent(page, 'devicesFirewallAllow')
  }, {fake: {devicesNearby: 'none', devicesFirewall: true}})

  for (const mode of ['dark', 'light']) {
    await test(`devices (${mode}): more than five devices are a list with a search field, a short id and a Connect button each`, async (page) => {
      await openDevices(page)
      await sent(page, 'devicesWatch')
      const group = available(page)
      const rows = group.getByRole('list', {name: 'Available devices'})
      await rows.waitFor()
      assert.equal(await group.locator('.devices-circle').count(), 0)
      assert.equal(await rows.getByRole('listitem').count(), 8)
      assert.equal(await group.getByRole('radio', {name: 'List'}).getAttribute('aria-checked'), 'true')
      assert.match(await rows.textContent(), /Kitchen laptop/)
      assert.match(await rows.textContent(), /via Tailscale/)
      assert.match(await rows.textContent(), /[0-9a-f]{4} [0-9a-f]{4}/, 'a short id')
      await shoot(page, `devices-crowded-${mode}`, group)
      const search = group.getByRole('searchbox', {name: 'Find a device'})
      await search.fill('pixel')
      assert.equal(await rows.getByRole('listitem').count(), 1)
      await search.fill('zzz')
      await group.getByText('No device matches that.').waitFor()
      await search.fill('')
      await rows.getByRole('button', {name: 'Connect to Pixel 8'}).click()
      const call = await got(page, 'devicesConnect')
      assert.equal(call.devices.address, '192.168.1.64:47215')
      assert.equal(await overflow(page), 0)
    }, {theme: mode, height: 1700, fake: {devicesNearby: 'many'}})
  }

  await test('devices: the Circle | List choice is remembered, and the automatic rule applies only until it is made', async (page) => {
    await openDevices(page)
    await sent(page, 'devicesWatch')
    const group = available(page)
    await group.getByRole('list', {name: 'Available devices'}).waitFor()
    assert.equal(await page.evaluate(() => localStorage.getItem('keepfin.devices.view')), null, 'nothing chosen yet')
    await group.getByRole('radio', {name: 'Circle'}).click()
    assert.equal(await page.evaluate(() => localStorage.getItem('keepfin.devices.view')), 'circle')
    await group.locator('.devices-circle').waitFor()
    assert.equal(await group.locator('.devices-node').count(), 5, 'the circle holds five')
    await group.getByText('Showing 5 of 6.').waitFor()
    await group.getByRole('button', {name: 'Show all in a list'}).click()
    assert.equal(await page.evaluate(() => localStorage.getItem('keepfin.devices.view')), 'list')
    await group.getByRole('list', {name: 'Available devices'}).waitFor()
    // after a reload the choice is still there, even if the rule would say circle
    await group.getByRole('radio', {name: 'Circle'}).click()
    await page.reload()
    await openDevices(page)
    await sent(page, 'devicesWatch')
    await available(page).locator('.devices-circle').waitFor()
  }, {fake: {devicesNearby: 'six'}, height: 1500})

  await test('devices: a remembered List stays a list for a single device', async (page) => {
    await page.evaluate(() => localStorage.setItem('keepfin.devices.view', 'list'))
    await openDevices(page)
    await sent(page, 'devicesWatch')
    await available(page).getByRole('searchbox', {name: 'Find a device'}).waitFor()
    assert.equal(await available(page).locator('.devices-circle').count(), 0)
  })

  await test('devices: large text (above 125%) shows the list instead of the circle', async (page) => {
    await openDevices(page)
    await sent(page, 'devicesWatch')
    await available(page).getByRole('searchbox', {name: 'Find a device'}).waitFor()
    assert.equal(await available(page).locator('.devices-circle').count(), 0)
    assert.equal(await overflow(page), 0)
  }, {scale: 140, height: 1600})

  await test('devices: the page looks for devices only while it is open', async (page) => {
    await openDevices(page)
    await sent(page, 'devicesWatch')
    await page.getByRole('navigation', {name: 'Settings sections'}).getByRole('button', {name: 'Appearance'}).click()
    await sent(page, 'devicesWatchStop')
  })

  await test('devices: the search stops after 60 seconds, keeps what it found, and Search again looks for another minute', async (page) => {
    await page.clock.install()
    await openDevices(page)
    await sent(page, 'devicesWatch')
    const group = available(page)
    await group.getByRole('button', {name: /^Connect to Galaxy S24/}).waitFor()
    await group.getByRole('button', {name: 'Stop'}).waitFor()
    assert.equal(await group.getByRole('button', {name: 'Search again'}).count(), 0, 'it is looking: no Search again')
    await page.clock.runFor(59_000)
    assert.equal(await group.getByRole('button', {name: 'Stop'}).count(), 1, 'still looking at 59 seconds')
    assert.equal((await page.evaluate(() => window.__calls.filter((c) => c.op === 'devicesWatchStop').length)), 0)
    await page.clock.runFor(2_000)
    await group.getByRole('button', {name: 'Search again'}).waitFor()
    await sent(page, 'devicesWatchStop')
    assert.match(await group.getByRole('status').textContent(), /Search finished · 1 found/)
    assert.equal(await group.getByRole('button', {name: 'Stop'}).count(), 0)
    await group.getByRole('button', {name: /^Connect to Galaxy S24/}).waitFor() // what was found stays
    await page.evaluate(() => (window.__calls.length = 0))
    await group.getByRole('button', {name: 'Search again'}).click()
    await sent(page, 'devicesWatch')
    await group.getByRole('button', {name: 'Stop'}).waitFor()
    await page.clock.runFor(61_000)
    await group.getByRole('button', {name: 'Search again'}).waitFor()
    await sent(page, 'devicesWatchStop')
  })

  await test('devices: Stop ends the search at once', async (page) => {
    await openDevices(page)
    await sent(page, 'devicesWatch')
    const group = available(page)
    await group.getByRole('button', {name: 'Stop'}).click()
    await sent(page, 'devicesWatchStop')
    await group.getByRole('button', {name: 'Search again'}).waitFor()
  })

  await test('devices: nothing searches while Devices is off, and turning it on starts the search', async (page) => {
    await openDevices(page)
    await page.getByText('Devices is off', {exact: true}).waitFor()
    assert.equal((await page.evaluate(() => window.__calls.filter((c) => c.op === 'devicesWatch').length)), 0)
    await page.getByRole('switch', {name: 'Devices on'}).click()
    await sent(page, 'devicesEnable')
    await sent(page, 'devicesWatch')
  }, {fake: {devices: 'off'}})

  // ---------------------------------------------------------------- Away from home
  for (const mode of ['dark', 'light']) {
    await test(`devices (${mode}): one "?" at the top opens one guide: how devices work, and Away from home with the Tailscale steps`, async (page) => {
      await openDevices(page)
      // no guide cards on the page itself
      await page.getByRole('button', {name: 'How devices work'}).waitFor()
      assert.equal(await page.getByRole('region', {name: 'Away from home', exact: true}).count(), 0)
      assert.equal(await page.getByRole('button', {name: /^How devices work/}).count(), 1, 'one entry: the "?" button')
      await page.getByRole('button', {name: 'How devices work'}).click()
      const guide = dialog(page)
      await guide.getByRole('heading', {name: 'How devices work'}).waitFor()
      assert.match(await guide.textContent(), /Find each other/)
      assert.match(await guide.textContent(), /tap Codes match on both devices/)
      const group = guide.getByRole('region', {name: 'Away from home', exact: true})
      await group.waitFor()
      assert.match(await group.textContent(), /Tailscale is on: desk-pc\.tail1234\.ts\.net/)
      assert.match(await group.textContent(), /Install Tailscale on both devices \(tailscale\.com\/download\)/)
      assert.match(await group.textContent(), /Sign in with the same account on both/)
      assert.match(await group.textContent(), /Keep it on\. Paired devices then reconnect from anywhere/)
      assert.equal(await group.locator('ol li').count(), 3)
      assert.match((await group.getByRole('link', {name: 'Get Tailscale'}).getAttribute('href')) ?? '', /tailscale\.com\/download/)
      await shoot(page, `devices-guide-${mode}`, guide)
      assert.equal(await overflow(page), 0)
      await guide.getByRole('button', {name: 'Close'}).click()
      await dialog(page).waitFor({state: 'detached'})
    }, {theme: mode, height: 1500, fake: {devicesTailscale: true}})
  }

  await test('devices: without Tailscale the guide says it is not detected', async (page) => {
    await openDevices(page)
    await page.getByRole('button', {name: 'How devices work'}).click()
    const group = dialog(page).getByRole('region', {name: 'Away from home', exact: true})
    await group.getByText('Tailscale not detected').first().waitFor()
    assert.equal(await group.locator('ol li').count(), 3)
  })

  await test('devices: a paired device reached through Tailscale says so', async (page) => {
    await openDevices(page)
    assert.match(await card(page, 'Laptop').locator('.devices-card-row').textContent(), /via Tailscale/)
  }, {fake: {awayDevice: 'tailscale'}})

  await test('devices: a device that drops off shows as offline with when it was last seen', async (page) => {
    await openDevices(page)
    const laptop = cardRow(page, 'Laptop')
    await laptop.getByText('Home network').waitFor()
    await page.evaluate(() => window.__fake.devicesPeerGone())
    await laptop.getByText(/Windows PC · last seen/).waitFor()
    assert.match(await laptop.locator('.devices-card-status').textContent(), /Offline/)
  })

  // ---------------------------------------------------------------- away from home
  for (const mode of ['dark', 'light']) {
    await test(`devices (${mode}): an unreachable device says since when, shows its address, and Keepfin says it keeps trying`, async (page) => {
      await openDevices(page)
      const phone = await details(page, 'Pixel 9')
      await phone.locator('.devices-card-line').waitFor()
      assert.match(await phone.locator('.devices-card-line').textContent(), /Unreachable since .* · Keepfin reconnects by itself/)
      assert.match(await phone.getByLabel('Fingerprint').textContent(), /pixel-9\.tailnet\.ts\.net/, 'a Tailscale name stays a name')
      const laptop = await details(page, 'Laptop')
      assert.doesNotMatch(await laptop.locator('.devices-card-line').textContent(), /Unreachable/, 'a connected device has no warning')
      assert.match(await laptop.getByLabel('Fingerprint').textContent(), /192\.168\.1\.40/)
      await shoot(page, `devices-away-${mode}`, laptop)
      assert.equal(await overflow(page), 0)
    }, {theme: mode, height: 1400})
  }

  // ---------------------------------------------------------------- get this library
  for (const mode of ['dark', 'light']) {
    await test(`devices (${mode}): a library another device shares can be fetched into a folder of its own, with progress, then opened`, async (page) => {
      await openDevices(page)
      const group = page.getByRole('region', {name: 'Libraries on other devices', exact: true})
      await group.waitFor()
      assert.match(await group.textContent(), /Family music/)
      assert.match(await group.textContent(), /from Laptop/)
      await shoot(page, `devices-get-library-${mode}`, group)
      await page.evaluate(() => (window.__pickedPath = 'D:\\Family library'))
      await group.getByRole('button', {name: 'Get this library…'}).click()
      const call = await got(page, 'devicesJoin')
      assert.deepEqual([call.devices.deviceId, call.devices.libraryId, call.devices.path], ['dev-laptop', 'lib-2', 'D:\\Family library'])
      const progress = page.getByRole('status', {name: 'Taking in a library'})
      await progress.waitFor()
      await progress.getByText(/Taking in the library|Asking the other device|Preparing/).waitFor()
      await progress.getByText(/Songs and videos arrive with a copy|Done\./).first().waitFor()
      await progress.getByRole('button', {name: 'Open it (restarts Keepfin)'}).waitFor()
      assert.match(await progress.textContent(), /D:\\Family library/)
      assert.equal(await group.getByRole('button', {name: 'Get this library…'}).count(), 0, 'once held it is no longer offered')
      await shoot(page, `devices-library-joined-${mode}`, group)
      // and it appears among the libraries on this device, to be opened (a restart)
      const mine = page.getByRole('region', {name: 'Libraries on this device', exact: true})
      await mine.getByText('Family music').waitFor()
      await mine.getByRole('button', {name: 'Open (restarts Keepfin)'}).click()
      const opened = await got(page, 'devicesOpenLibrary')
      assert.equal(opened.devices.profileId, 'prof-joined')
      assert.equal(await overflow(page), 0)
    }, {theme: mode, height: 1500, fake: {devicesOffers: true}})
  }

  await test('devices: a join that breaks says why and can be continued in the same folder', async (page) => {
    await openDevices(page)
    await page.evaluate(() => (window.__joinFails = true))
    const group = page.getByRole('region', {name: 'Libraries on other devices', exact: true})
    await group.getByRole('button', {name: 'Get this library…'}).click()
    await got(page, 'devicesJoin')
    await group.getByText('The other device could not be reached.').waitFor()
    await page.evaluate(() => (window.__joinFails = false))
    await group.getByRole('button', {name: 'Continue'}).click()
    const again = await page.evaluate(() => [...window.__calls].filter((c) => c.op === 'devicesJoin').map((c) => c.devices.path))
    assert.equal(again.length, 2)
    assert.equal(again[0], again[1], 'the same folder: the join continues, it does not start again')
    await group.getByRole('button', {name: 'Open it (restarts Keepfin)'}).waitFor()
  }, {fake: {devicesOffers: true}})

  await test('devices: stopping a join keeps what arrived and offers to continue', async (page) => {
    await openDevices(page)
    const group = page.getByRole('region', {name: 'Libraries on other devices', exact: true})
    await group.getByRole('button', {name: 'Get this library…'}).click()
    await group.getByRole('button', {name: 'Stop'}).click()
    await sent(page, 'devicesJoinCancel')
    await group.getByText('The join was stopped.').waitFor()
    await group.getByRole('button', {name: 'Continue'}).waitFor()
  }, {fake: {devicesOffers: true}})

  // ---------------------------------------------------------------- Add a copy -> A paired device
  for (const mode of ['dark', 'light']) {
    await test(`copies (${mode}): Add a copy offers a paired device that allows it, and says what to turn on for one that does not`, async (page) => {
      await page.getByRole('navigation', {name: 'Main navigation'}).getByRole('button', {name: 'Settings', exact: true}).click()
      await page.getByRole('navigation', {name: 'Settings sections'}).getByRole('button', {name: 'Storage'}).click()
      await sent(page, 'copiesState')
      await page.getByRole('button', {name: /^Backup drive \(E:\):/}).waitFor()
      await page.getByRole('button', {name: 'Add a copy'}).first().click()
      const wizard = page.getByRole('dialog', {name: 'Add a copy'})
      await wizard.getByRole('radio', {name: 'A paired device'}).check()
      const list = wizard.getByRole('group', {name: 'Paired devices'})
      await list.waitFor()
      const laptop = list.getByRole('radio', {name: /Laptop/})
      const phone = list.getByRole('radio', {name: /Pixel 9/})
      assert.equal(await laptop.isEnabled(), true)
      assert.equal(await phone.isEnabled(), false, 'a device that is away cannot be chosen')
      assert.match(await list.textContent(), /Not connected/)
      assert.match(await list.textContent(), /Not connected right now\./)
      assert.match(await list.textContent(), /The device itself writes the files, and it keeps the bytes of anything it replaces/)
      assert.equal(await wizard.getByRole('button', {name: 'Continue'}).isDisabled(), true)
      await laptop.check()
      await shoot(page, `copies-wizard-device-${mode}`, wizard)
      await wizard.getByRole('button', {name: 'Continue'}).click()
      await wizard.getByRole('button', {name: 'Continue'}).click()
      assert.equal(await wizard.getByLabel('Name').inputValue(), 'Laptop')
      await wizard.getByRole('button', {name: 'Continue'}).click()
      await wizard.getByLabel('Plan for the first sync').getByText('Put on the copy').waitFor()
      const plan = await got(page, 'copiesPreview')
      assert.deepEqual(plan.copies.draft.device, {deviceId: 'dev-laptop'})
      assert.equal(plan.copies.draft.path, '')
      await wizard.getByRole('button', {name: 'Add this copy'}).click()
      await got(page, 'copiesAdd')
      await wizard.waitFor({state: 'detached'})
      await page.getByRole('button', {name: /^Laptop:/}).click()
      const card = page.getByRole('dialog', {name: 'Laptop'})
      assert.match(await card.textContent(), /Laptop · connected/)
      await page.keyboard.press('Escape')
      assert.equal(await overflow(page), 0)
    }, {theme: mode, height: 1500, fake: {copiesDevices: 'allowed'}})
  }

  await test('copies: when no paired device allows a copy the choices are shown disabled with the reason', async (page) => {
    await page.getByRole('navigation', {name: 'Main navigation'}).getByRole('button', {name: 'Settings', exact: true}).click()
    await page.getByRole('navigation', {name: 'Settings sections'}).getByRole('button', {name: 'Storage'}).click()
    await page.getByRole('button', {name: /^Backup drive \(E:\):/}).waitFor()
    await page.getByRole('button', {name: 'Add a copy'}).first().click()
    const wizard = page.getByRole('dialog', {name: 'Add a copy'})
    await wizard.getByRole('radio', {name: 'A paired device'}).check()
    const list = wizard.getByRole('group', {name: 'Paired devices'})
    assert.match(await list.textContent(), /turn on "Keep a copy on this device"/)
    assert.equal(await list.getByRole('radio', {name: /Laptop/}).isEnabled(), false)
    assert.equal(await wizard.getByRole('button', {name: 'Continue'}).isDisabled(), true)
  }, {fake: {copiesDevices: 'blocked'}})

  // ---------------------------------------------------------------- layout: one width, one rhythm
  for (const [mode, scale] of [['dark', 100], ['light', 100], ['dark', 80], ['light', 140]]) {
    await test(`devices layout (${mode}, ${scale}%): every section has the same width, the circle and the QR panel align, device rows are equally tall, the rings are round`, async (page) => {
      await page.evaluate(() => localStorage.setItem('keepfin.devices.view', 'circle')) // the circle even at large text
      await openDevices(page)
      await sent(page, 'devicesWatch')
      await sent(page, 'devicesQr')
      await page.getByRole('img', {name: /QR code/}).waitFor()
      await page.getByRole('button', {name: /^Advanced/}).waitFor()
      await page.waitForTimeout(300)
      const box = async (locator) => {
        const b = await locator.first().boundingBox()
        assert.ok(b, 'drawn')
        return b
      }
      const status = await box(page.locator('.device-status'))
      const paired = await box(page.locator('#setting-paired'))
      const advanced = await box(page.locator('#setting-advanced'))
      const nearby = await box(page.locator('#setting-nearby'))
      const scan = await box(page.locator('#setting-scan'))
      const near = (a, b, what) => assert.ok(Math.abs(a - b) <= 1.5, `${what}: ${a} vs ${b}`)
      for (const [name, b] of [['Your devices', paired], ['Advanced', advanced]]) {
        near(b.x, status.x, `${name} starts where the header starts`)
        near(b.x + b.width, status.x + status.width, `${name} ends where the header ends`)
      }
      const top = [nearby, scan].sort((a, b) => a.x - b.x)
      const narrow = top[1].x < top[0].x + top[0].width - 1 // stacked on a narrow window
      if (!narrow) {
        near(top[0].x, status.x, 'Available devices starts at the page edge')
        near(top[1].x + top[1].width, status.x + status.width, 'the QR section ends at the page edge')
        const a = await box(page.locator('#setting-nearby .devices-panel'))
        const q = await box(page.locator('#setting-scan .devices-panel'))
        near(a.y, q.y, 'the two panels start at the same top')
        near(a.height, q.height, 'and have the same height')
      } else {
        near(nearby.x, status.x, 'stacked: Available devices is full width')
        near(scan.x + scan.width, status.x + status.width, 'stacked: the QR section is full width')
      }
      // device rows: same height whatever they hold
      const rows = await page.locator('.devices-card-head').evaluateAll((els) => els.map((el) => el.getBoundingClientRect().height))
      assert.ok(rows.length >= 2)
      assert.ok(Math.max(...rows) - Math.min(...rows) <= 1.5, `collapsed rows are equally tall: ${rows.join(', ')}`)
      // the status chip and the chevron sit in the same column on every row
      const columns = await page.locator('.devices-card-head').evaluateAll((els) =>
        els.map((el) => {
          const chip = el.querySelector('.devices-card-status').getBoundingClientRect()
          const chevron = el.querySelector('.devices-card-chevron').getBoundingClientRect()
          return {chip: Math.round(chip.left), chevron: Math.round(chevron.left)}
        }),
      )
      assert.ok(columns.every((c) => Math.abs(c.chip - columns[0].chip) <= 1 && Math.abs(c.chevron - columns[0].chevron) <= 1), `chips and chevrons line up: ${JSON.stringify(columns)}`)
      // the radar is one square: every ring is as wide as it is tall
      const rings = await page.locator('.devices-ring').evaluateAll((els) => els.map((el) => el.getBoundingClientRect()).map((r) => [r.width, r.height]))
      assert.equal(rings.length, 2, 'two rings')
      for (const [w, h] of rings) assert.ok(Math.abs(w - h) <= 1, `a ring is round: ${w} x ${h}`)
      const sq = await page.locator('.devices-circle').boundingBox()
      assert.ok(Math.abs(sq.width - sq.height) <= 1, `the radar is a square: ${sq.width} x ${sq.height}`)
      assert.equal(await overflow(page), 0)
      await shoot(page, `devices-layout-${mode}-${scale}`)
    }, {theme: mode, scale, height: 1500, width: scale === 140 ? 1400 : 1280})
  }

  await test('devices: the rows are one grid: the Syncing row has no border of its own, an open row does', async (page) => {
    await openDevices(page)
    const laptop = card(page, 'Laptop')
    await laptop.waitFor()
    const border = (el) => el.evaluate((node) => getComputedStyle(node).boxShadow)
    const closedLaptop = await border(laptop)
    assert.equal(closedLaptop, await border(card(page, 'Pixel 9')), 'collapsed rows look the same, whatever their state')
    await details(page, 'Laptop')
    assert.notEqual(await border(laptop), closedLaptop, 'an open card is marked')
  })

  await test('devices: the permissions are switches labelled "Allowed for <name>", on is filled with a check, off is a dashed outline', async (page) => {
    await openDevices(page)
    const phone = await details(page, 'Pixel 9')
    const group = phone.getByRole('group', {name: 'Allowed for Pixel 9'})
    await group.waitFor()
    assert.equal(await group.getByRole('button', {name: 'Add devices to the library'}).count(), 1)
    const off = group.getByRole('button', {name: 'Play from here'})
    const lap = await details(page, 'Laptop')
    const on = lap.getByRole('button', {name: 'Play from here'})
    assert.equal(await off.getAttribute('aria-pressed'), 'false')
    const style = (el) => el.evaluate((node) => ({border: getComputedStyle(node).borderTopStyle, icon: !!node.querySelector('svg')}))
    assert.deepEqual(await style(off), {border: 'dashed', icon: false})
    const sees = await style(on)
    assert.equal(sees.icon, true, 'on has a check')
    assert.notEqual(sees.border, 'dashed')
  })

  await test('devices: the guide has no placeholders in angle brackets', async (page) => {
    await openDevices(page)
    await page.getByRole('button', {name: 'How devices work'}).click()
    const text = await dialog(page).textContent()
    assert.doesNotMatch(text, /<[a-z]+>/i)
    assert.match(text, /On the other device/)
  })

  await test('devices: Stop says "Search stopped" in the same place as Search again', async (page) => {
    await openDevices(page)
    await sent(page, 'devicesWatch')
    const group = available(page)
    await group.getByRole('button', {name: /^Connect to Galaxy S24/}).waitFor()
    const at = (name) => group.getByRole('button', {name}).evaluate((el) => { const a = el.getBoundingClientRect(); const b = el.parentElement.getBoundingClientRect(); return {x: Math.round(a.left - b.left), y: Math.round(a.top - b.top)} })
    const stop = await at('Stop')
    await group.getByRole('button', {name: 'Stop'}).click()
    await group.getByText('Search stopped').waitFor()
    const again = await at('Search again')
    assert.ok(Math.abs(again.y - stop.y) < 4 && Math.abs(again.x - stop.x) < 4, `Search again is exactly where Stop was: ${JSON.stringify([stop, again])}`)
  })

  await test('devices: the Tailscale words appear in the search line only when Tailscale runs', async (page) => {
    await openDevices(page)
    await sent(page, 'devicesWatch')
    await available(page).getByText('Looking on this network and Tailscale…').waitFor()
  }, {fake: {devicesTailscale: true, devicesScanning: true}})

  await test('devices: the firewall line in Not seeing it? is an action, the QR code row wraps and the typed code stays in one piece', async (page) => {
    await openDevices(page)
    await sent(page, 'devicesQr')
    await page.getByRole('img', {name: /QR code/}).waitFor()
    const code = page.locator('.devices-qr-code-text')
    assert.equal(await code.evaluate((el) => getComputedStyle(el).whiteSpace), 'nowrap')
    assert.equal(await page.locator('.devices-qr-code-row').evaluate((el) => getComputedStyle(el).flexWrap), 'wrap')
    await available(page).getByRole('button', {name: 'Not seeing it?'}).click()
    await dialog(page).getByRole('button', {name: 'Allow Keepfin through Windows Firewall on private networks'}).waitFor()
  }, {fake: {devicesFirewall: true}})

  await test('devices: no fingerprint or code is drawn in a monospace face', async (page) => {
    await openDevices(page)
    await sent(page, 'devicesQr')
    await page.getByRole('img', {name: /QR code/}).waitFor()
    const phone = await details(page, 'Pixel 9')
    const faces = [
      await phone.getByLabel('Fingerprint').evaluate((el) => getComputedStyle(el).fontFamily),
      await page.locator('.devices-qr-code-text').evaluate((el) => getComputedStyle(el).fontFamily),
    ]
    await advanced(page)
    faces.push(await page.getByLabel('Device fingerprint').evaluate((el) => getComputedStyle(el).fontFamily))
    for (const face of faces) assert.doesNotMatch(face, /mono|consolas|courier/i, `font: ${face}`)
    assert.equal(await phone.getByLabel('Fingerprint').evaluate((el) => getComputedStyle(el).fontVariantNumeric), 'tabular-nums')
  })

  await test('devices: the circle has no empty band under it: the status line sits directly under it with "Not seeing it?" on the same line', async (page) => {
    await openDevices(page)
    await sent(page, 'devicesWatch')
    const group = available(page)
    const circle = await group.locator('.devices-circle').boundingBox()
    const status = group.locator('.devices-available-status')
    const line = await status.boundingBox()
    assert.ok(line.y - (circle.y + circle.height) <= 12, `the line is ${line.y - (circle.y + circle.height)}px under the circle`)
    const link = await status.getByRole('button', {name: 'Not seeing it?'}).boundingBox()
    assert.ok(Math.abs(link.y - line.y) < line.height, 'on the same line')
    const panel = await group.locator('.devices-panel').boundingBox()
    assert.ok(panel.y + panel.height - (line.y + line.height) <= 20, 'nothing empty under the status line')
    assert.equal(await group.getByRole('button', {name: 'Add by address'}).count(), 0, 'Add by address is inside Not seeing it?')
  })

  await test('devices: allowing a copy on this device also allows seeing its files and reading them', async (page) => {
    await openDevices(page)
    const phone = await details(page, 'Pixel 9')
    await phone.getByRole('button', {name: 'Keep a copy here'}).click()
    const call = await got(page, 'devicesAccess')
    assert.deepEqual([...call.devices.actions].sort(), ['copy.mutate', 'inventory.read', 'media.read', 'metadata.read', 'metadata.write'])
  })

  // ---------------------------------------------------------------- the setup after pairing (what stays up to date)
  const setupDialog = (page) => page.locator('dialog.devices-setup')
  /** Connects Galaxy S24 from this side (it asks); the other device's person taps Codes match (the fake plays that side). */
  const pairGalaxy = async (page) => {
    await connectGalaxy(page)
    const box = dialog(page)
    await box.getByLabel('Pairing code').waitFor()
    await box.getByRole('button', {name: 'Codes match · Connect'}).click()
    await got(page, 'devicesDecide')
    await page.evaluate(() => window.__fake.devicesPeerConfirms())
    return box
  }
  /** Pairs Galaxy S24 and opens the setup with Set up. */
  const openSetup = async (page) => {
    const box = await pairGalaxy(page)
    await box.getByRole('button', {name: 'Set up'}).click()
    await sent(page, 'devicesDismiss')
    const setup = setupDialog(page)
    await setup.getByRole('heading', {name: 'What stays up to date'}).waitFor()
    return setup
  }
  const allowed = {copiesDevices: 'allowed'}

  for (const mode of ['dark', 'light']) {
    await test(`setup (${mode}): after a pairing this device started, "<name> is connected" offers Set up and Later side by side`, async (page) => {
      const box = await pairGalaxy(page)
      await box.getByRole('heading', {name: 'Galaxy S24 is connected'}).waitFor()
      assert.match(await box.textContent(), /Choose what stays up to date/)
      const later = box.getByRole('button', {name: 'Later'})
      const setUp = box.getByRole('button', {name: 'Set up'})
      await sideBySide(later, setUp)
      await shoot(page, `devices-setup-offer-${mode}`, box)
      await later.click()
      await sent(page, 'devicesDismiss')
      await dialog(page).waitFor({state: 'detached'})
      assert.equal((await page.evaluate(() => window.__calls.filter((c) => c.op === 'copiesPreview').length)), 0, 'Later changes nothing')
      // the card offers the same choice later
      const galaxy = await details(page, 'Galaxy S24')
      await galaxy.getByRole('button', {name: 'Choose what stays up to date'}).waitFor()
    }, {theme: mode, height: 1300, fake: allowed})
  }

  await test('setup: the device that was asked (answering side) is offered the setup too, and Set up opens it', async (page) => {
    await openDevices(page)
    await ready(page)
    await page.evaluate(() => window.__fake.devicesIncoming(true))
    const box = dialog(page)
    await box.getByRole('button', {name: 'Codes match · Connect'}).click()
    await got(page, 'devicesDecide')
    await box.getByRole('heading', {name: 'Galaxy S24 is connected'}).waitFor()
    assert.match(await box.textContent(), /Choose what stays up to date/)
    await sideBySide(box.getByRole('button', {name: 'Later'}), box.getByRole('button', {name: 'Set up'}))
    await box.getByRole('button', {name: 'Set up'}).click()
    await setupDialog(page).getByRole('heading', {name: 'What stays up to date'}).waitFor()
    await setupDialog(page).getByRole('checkbox', {name: 'Music'}).waitFor()
  }, {fake: allowed})

  for (const mode of ['dark', 'light']) {
    await test(`setup (${mode}): two devices that each hold their own library start with the Combine step instead of copies (the full flow is in suite-combine)`, async (page) => {
      const box = await pairGalaxy(page)
      await box.getByRole('button', {name: 'Set up'}).click()
      const setup = setupDialog(page)
      await setup.getByRole('heading', {name: 'Combine libraries'}).waitFor()
      await setup.getByRole('radio').first().waitFor()
      assert.doesNotMatch(await setup.textContent(), /stay separate|coming in an update/, 'the old "stay separate" message is gone')
      assert.doesNotMatch(await setup.textContent(), /Step 1 of 2/, 'the Combine step comes before the copies steps')
      assert.equal(await setup.getByRole('checkbox').count(), 0, 'no copies are offered yet')
      assert.deepEqual(await setup.locator('.devices-dialog-actions').getByRole('button').allTextContents(), ['Later', 'Review'])
      await shoot(page, `devices-setup-different-libraries-${mode}`, setup)
      await setup.getByRole('button', {name: 'Later'}).click()
      await setupDialog(page).waitFor({state: 'detached'})
      assert.equal((await page.evaluate(() => window.__calls.filter((c) => c.op === 'copiesPreview' || c.op === 'combineStart').length)), 0, 'Later changes nothing')
    }, {theme: mode, height: 1300, fake: {copiesDevices: 'allowed', devicesLibraries: 'different'}})
  }

  for (const mode of ['dark', 'light']) {
    await test(`setup (${mode}): step 1 offers the spaces with Music and Videos on, both ways, by itself; Later and Next side by side`, async (page) => {
      const setup = await openSetup(page)
      assert.match(await setup.textContent(), /Step 1 of 2/)
      assert.match(await setup.textContent(), /Your library list \(names, playlists, what you played\) stays in step by itself\. It is small\./)
      const files = setup.getByRole('group', {name: 'Files to keep up to date'})
      assert.equal(await files.getByRole('checkbox', {name: 'Music'}).isChecked(), true)
      assert.equal(await files.getByRole('checkbox', {name: 'Videos'}).isChecked(), true)
      assert.equal(await files.getByRole('checkbox', {name: 'Downloads'}).isChecked(), false)
      assert.equal(await files.getByRole('checkbox', {name: 'Favourites'}).isChecked(), false)
      assert.equal(await files.getByRole('checkbox', {name: 'Road trip'}).count(), 0, 'playlists travel with the library list')
      assert.equal(await setup.getByRole('radio', {name: /Both ways/}).isChecked(), true)
      assert.match(await setup.textContent(), /New files on either device appear on the other\./)
      await setup.getByRole('radio', {name: /Only to Galaxy S24/}).check()
      assert.match(await setup.textContent(), /This device’s files go to Galaxy S24\. Nothing comes back\./)
      assert.equal(await setup.getByRole('radio', {name: /Mirror/i}).count(), 0, 'no mirror here')
      await setup.getByRole('radio', {name: /Both ways/}).check()
      assert.equal(await setup.getByRole('switch', {name: 'Keep it up to date by itself'}).getAttribute('aria-checked'), 'true')
      const later = setup.getByRole('button', {name: 'Later'})
      const next = setup.getByRole('button', {name: 'Next'})
      await sideBySide(later, next)
      assert.equal(await next.isEnabled(), true)
      await shoot(page, `devices-setup-step1-${mode}`, setup)
      assert.equal(await overflow(page), 0)
      // nothing chosen: Next waits
      await files.getByRole('checkbox', {name: 'Music'}).uncheck()
      await files.getByRole('checkbox', {name: 'Videos'}).uncheck()
      assert.equal(await next.isDisabled(), true)
      await later.click()
      await setupDialog(page).waitFor({state: 'detached'})
      assert.equal((await page.evaluate(() => window.__calls.filter((c) => c.op === 'copiesPreview').length)), 0)
    }, {theme: mode, height: 1300, fake: allowed})
  }

  for (const mode of ['dark', 'light']) {
    await test(`setup (${mode}): a device that does not allow a copy says why and how to fix it; Check again; Next waits`, async (page) => {
      const setup = await openSetup(page)
      const alert = setup.getByRole('alert')
      await alert.waitFor()
      assert.match(await alert.textContent(), /Galaxy S24 does not keep a copy for this device yet\./)
      assert.match(await alert.textContent(), /On Galaxy S24, open Devices, open this PC and turn on Keep a copy here\./)
      assert.equal(await setup.getByRole('button', {name: 'Next'}).isDisabled(), true)
      await shoot(page, `devices-setup-blocked-${mode}`, setup)
      await page.evaluate(() => (window.__calls.length = 0))
      await alert.getByRole('button', {name: 'Check again'}).click()
      await sent(page, 'copiesState')
      assert.equal(await setup.getByRole('button', {name: 'Next'}).isDisabled(), true, 'still not allowed')
      assert.equal(await overflow(page), 0)
    }, {theme: mode, height: 1300, fake: {copiesDevices: 'blocked'}})
  }

  for (const mode of ['dark', 'light']) {
    await test(`setup (${mode}): step 2 shows sizes, the files that come here, space and "Nothing is removed or replaced."; no differences list when there are none`, async (page) => {
      const setup = await openSetup(page)
      await setup.getByRole('button', {name: 'Next'}).click()
      const plan = await got(page, 'copiesPreview')
      assert.deepEqual(plan.copies.draft.device, {deviceId: 'dev-galaxy-s24'})
      assert.equal(plan.copies.draft.mode, 'twoWay')
      assert.deepEqual(plan.copies.draft.holds, {all: false, spaces: ['music', 'videos'], playlists: [], favourites: false})
      assert.equal(plan.copies.draft.path, '')
      assert.equal(plan.copies.draft.schedule.auto, true)
      const review = setup.getByLabel('Review')
      await review.waitFor()
      assert.match(await setup.textContent(), /Step 2 of 2/)
      assert.match(await review.textContent(), /800 files · 12\.5 GB will go to Galaxy S24\./)
      assert.match(await review.textContent(), /36 files on Galaxy S24 will come here\./)
      assert.match(await review.textContent(), /There is enough space on Galaxy S24 \(412(\.0)? GB free\)\./)
      assert.match(await review.textContent(), /The first sync only adds files\. Nothing is removed or replaced\./)
      assert.equal(await setup.getByRole('group', {name: 'Files that differ'}).count(), 0, 'no differences, no list')
      const back = setup.getByRole('button', {name: 'Back'})
      const start = setup.getByRole('button', {name: 'Start'})
      await sideBySide(back, start)
      assert.equal(await start.isEnabled(), true)
      await shoot(page, `devices-setup-step2-${mode}`, setup)
      assert.equal(await overflow(page), 0)
      // Back keeps the choices
      await back.click()
      assert.equal(await setup.getByRole('checkbox', {name: 'Music'}).isChecked(), true)
    }, {theme: mode, height: 1300, fake: allowed})
  }

  await test('setup: only to the device sends a backup and no files come back', async (page) => {
    const setup = await openSetup(page)
    await setup.getByRole('radio', {name: /Only to Galaxy S24/}).check()
    await setup.getByRole('checkbox', {name: 'Favourites'}).check()
    await setup.getByRole('switch', {name: 'Keep it up to date by itself'}).click()
    await setup.getByRole('button', {name: 'Next'}).click()
    const plan = await got(page, 'copiesPreview')
    assert.equal(plan.copies.draft.mode, 'backup')
    assert.equal(plan.copies.draft.holds.favourites, true)
    assert.equal(plan.copies.draft.schedule.auto, false)
    await setup.getByLabel('Review').waitFor()
    assert.doesNotMatch(await setup.getByLabel('Review').textContent(), /will come here/)
  }, {fake: allowed})

  for (const mode of ['dark', 'light']) {
    await test(`setup (${mode}): files that differ are listed, five at most, then "and N more", and Keepfin keeps both`, async (page) => {
      await page.evaluate(() => (window.__devicePreview = 'differences'))
      const setup = await openSetup(page)
      await setup.getByRole('button', {name: 'Next'}).click()
      const diffs = setup.getByRole('group', {name: 'Files that differ'})
      await diffs.waitFor()
      assert.match(await diffs.textContent(), /Files that differ: Keepfin keeps both until you choose/)
      assert.equal(await diffs.locator('li').count(), 5)
      assert.match(await diffs.textContent(), /and 2 more/)
      await shoot(page, `devices-setup-differences-${mode}`, setup)
      assert.equal(await overflow(page), 0)
    }, {theme: mode, height: 1300, fake: allowed})
  }

  await test('setup: not enough space disables Start and says how much is free', async (page) => {
    await page.evaluate(() => (window.__devicePreview = 'full'))
    const setup = await openSetup(page)
    await setup.getByRole('button', {name: 'Next'}).click()
    await setup.getByText(/There is not enough space on Galaxy S24 \(2\.0 GB free\)/).waitFor()
    assert.equal(await setup.getByRole('button', {name: 'Start'}).isDisabled(), true)
    await page.evaluate(() => (window.__devicePreview = ''))
    await setup.getByRole('button', {name: 'Check again'}).click()
    await setup.getByText(/There is enough space/).waitFor()
    assert.equal(await setup.getByRole('button', {name: 'Start'}).isEnabled(), true)
  }, {fake: allowed})

  for (const [kind, words] of [['busy', /busy with another copy/], ['disconnected', /Galaxy S24 is not connected right now/], ['refused', /That device cannot keep this copy\./], ['held', /Keepfin will ask you before it starts/]]) {
    await test(`setup: a preview that is ${kind} is explained in a sentence${kind === 'held' ? ' and can still start' : ' and cannot start'}`, async (page) => {
      await page.evaluate((k) => (window.__devicePreview = k), kind)
      const setup = await openSetup(page)
      await setup.getByRole('button', {name: 'Next'}).click()
      await setup.getByText(words).waitFor()
      assert.equal(await setup.getByRole('button', {name: 'Start'}).isEnabled(), kind === 'held')
    }, {fake: allowed})
  }

  await test('setup: Start adds the previewed copy, closes the setup, and the card shows the copy going on', async (page) => {
    const setup = await openSetup(page)
    await setup.getByRole('button', {name: 'Next'}).click()
    const plan = await got(page, 'copiesPreview')
    await setup.getByLabel('Review').waitFor()
    await setup.getByRole('button', {name: 'Start'}).click()
    const added = await got(page, 'copiesAdd')
    assert.equal(added.copies.placeId, plan.copies.placeId ?? 'draft-1', 'the add is for the previewed place')
    await setupDialog(page).waitFor({state: 'detached'})
    const galaxy = await details(page, 'Galaxy S24')
    const line = galaxy.getByLabel('Files kept up to date')
    await line.getByText(/^Copying [\d,]+ of 1,548 files · .* left$/).waitFor()
    assert.equal(await galaxy.getByRole('button', {name: 'Choose what stays up to date'}).count(), 0, 'the status replaces the button')
    await line.getByText(/^Up to date · checked /).waitFor({timeout: 8000})
    await page.evaluate(() => window.__fake.copiesDevicePlace({device: {online: false}}))
    await line.getByText('Waiting for Galaxy S24').waitFor()
    await page.evaluate(() => window.__fake.copiesDevicePlace({status: 'review', review: 3, device: {online: true}}))
    await line.getByText('3 files need a decision').waitFor()
    await galaxy.getByRole('button', {name: /^Review/}).click()
    await page.getByRole('region', {name: /Needs review/}).first().waitFor({timeout: 5000}).catch(() => undefined)
  }, {height: 1300, fake: allowed})

  await test('setup: leaving the review without Start cancels the prepared copy', async (page) => {
    const setup = await openSetup(page)
    await setup.getByRole('button', {name: 'Next'}).click()
    await setup.getByLabel('Review').waitFor()
    await page.keyboard.press('Escape')
    await setupDialog(page).waitFor({state: 'detached'})
    await sent(page, 'copiesCancelPreview')
    assert.equal((await page.evaluate(() => window.__calls.filter((c) => c.op === 'copiesAdd').length)), 0)
  }, {fake: allowed})

  await test('setup: a paired device card offers the setup when it has no copy yet', async (page) => {
    await openDevices(page)
    const laptop = await details(page, 'Laptop')
    await laptop.getByRole('button', {name: 'Choose what stays up to date'}).click()
    const setup = setupDialog(page)
    await setup.getByRole('heading', {name: 'What stays up to date'}).waitFor()
    assert.match(await setup.textContent(), /Step 1 of 2 · Laptop/)
    await setup.getByRole('checkbox', {name: 'Music'}).waitFor()
    assert.equal(await setup.getByRole('button', {name: 'Next'}).isEnabled(), true)
  }, {fake: allowed})
}

/** Download on...: the capture bar, the collection page and the Downloads page (remote rows, and the question about a large download). */
export async function registerDownloadOnSuite({test, sent}) {
  const got = async (page, op) => {
    await sent(page, op)
    return page.evaluate((op) => [...window.__calls].reverse().find((c) => c.op === op), op)
  }
  const YT = 'https://www.youtube.com/watch?v=aqz-KE-bpKQ'
  const field = (page) => page.locator('textarea').first()
  const capture = async (page, text) => {
    await field(page).fill(text)
    await page.locator('.download-btn').first().waitFor({timeout: 8000})
  }
  const overflow = (page) => page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
  const downloads = (page) => page.getByRole('navigation', {name: 'Main navigation'}).getByRole('button', {name: 'Downloads'}).click()

  for (const mode of ['dark', 'light']) {
    await test(`download on (${mode}): the capture bar offers the devices that allow it, and the other device's progress shows in Downloads`, async (page) => {
      await capture(page, YT)
      const trigger = page.getByRole('button', {name: 'Download on…'})
      await trigger.waitFor()
      await page.getByRole('radiogroup', {name: 'Video quality'}).getByRole('radio', {name: /^1080p/}).click()
      await shoot(page, `download-on-capture-${mode}`, page.locator('.preview .dialog-foot'))
      await trigger.click()
      const menu = page.getByRole('menu', {name: 'Download on…'})
      await menu.getByRole('menuitem', {name: /Laptop/}).first().waitFor()
      assert.equal(await menu.getByRole('menuitem', {name: /Pixel 9/}).count(), 0, 'a device that is away or does not allow it is not offered')
      await menu.getByRole('menuitem', {name: /Laptop · Music/}).click()
      const job = await got(page, 'devicesJob')
      assert.equal(job.devices.deviceId, 'dev-laptop')
      assert.equal(job.devices.url, YT)
      assert.equal(job.devices.format.type, 'video')
      assert.equal(job.devices.format.maxHeight, 1080)
      assert.equal(job.devices.space, 'music')
      assert.equal((await page.evaluate(() => window.__calls.filter((c) => c.op === 'enqueue').length)), 0, 'nothing enters the local queue')
      await page.getByText(/Sent to Laptop/).waitFor()
      // it was handed to the other device, so the review is done with
      await page.getByRole('dialog').waitFor({state: 'detached'})
      await downloads(page)
      const section = page.getByRole('region', {name: 'On other devices', exact: true})
      await section.waitFor()
      await section.getByText('Clip from the link').waitFor()
      assert.match(await section.textContent(), /Laptop/)
      assert.match(await section.textContent(), /Downloading on the other device/)
      await shoot(page, `download-on-downloads-${mode}`, section)
      await section.getByText('Saved on the other device').waitFor()
      await section.getByRole('button', {name: 'Dismiss'}).click()
      await sent(page, 'devicesJobDismiss')
      await section.waitFor({state: 'detached'})
      assert.equal(await overflow(page), 0)
    }, {theme: mode, height: 1300})
  }

  await test('download on: with no device that allows it the capture bar has no such button', async (page) => {
    await capture(page, YT)
    assert.equal(await page.getByRole('button', {name: 'Download on…'}).count(), 0)
  }, {fake: {devices: 'empty'}})
}
