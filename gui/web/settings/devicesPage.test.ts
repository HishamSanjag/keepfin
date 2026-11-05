import assert from 'node:assert/strict'
import {test} from 'node:test'
import {CIRCLE_MAX, autoView, circleSlots, deviceStatus, effectiveView, readViewChoice, reachLine, shortId, statusLine, slotPosition, writeViewChoice, SEARCH_MS, VIEW_KEY} from './devices/devicesLogic'
import {differentLibraries} from './devices/setupLogic'

test('the circle is for five devices or fewer at a normal text size, the list otherwise', () => {
  assert.equal(CIRCLE_MAX, 5)
  assert.equal(autoView(0, 100), 'circle')
  assert.equal(autoView(5, 100), 'circle')
  assert.equal(autoView(6, 100), 'list')
  assert.equal(autoView(2, 125), 'circle', 'exactly 125% is still normal')
  assert.equal(autoView(2, 130), 'list', 'large text')
})

test('the automatic rule applies only until the person chooses', () => {
  assert.equal(effectiveView(null, 8, 100), 'list')
  assert.equal(effectiveView('circle', 8, 100), 'circle')
  assert.equal(effectiveView('list', 1, 100), 'list')
})

test('the chosen view is remembered in storage and a broken storage is survived', () => {
  const store = new Map<string, string>()
  const real = (globalThis as {localStorage?: unknown}).localStorage
  ;(globalThis as {localStorage?: unknown}).localStorage = {getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => void store.set(k, v)}
  try {
    assert.equal(readViewChoice(), null)
    writeViewChoice('list')
    assert.equal(store.get('keepfin.devices.view'), 'list')
    assert.equal(VIEW_KEY, 'keepfin.devices.view')
    assert.equal(readViewChoice(), 'list')
    store.set(VIEW_KEY, 'nonsense')
    assert.equal(readViewChoice(), null)
    ;(globalThis as {localStorage?: unknown}).localStorage = {
      getItem: () => {
        throw new Error('blocked')
      },
      setItem: () => {
        throw new Error('blocked')
      },
    }
    assert.equal(readViewChoice(), null)
    assert.doesNotThrow(() => writeViewChoice('circle'))
  } finally {
    ;(globalThis as {localStorage?: unknown}).localStorage = real
  }
})

test('circle slots are fixed by id: the same ids always land in the same places, one device per slot', () => {
  const ids = ['a1b2-192.168.1.23', 'd4d3-100.101.20.5', 'ffff-10.0.0.9', '0001-10.0.0.2', '7777-10.0.0.3']
  const first = circleSlots(ids)
  for (const id of ids) assert.equal(circleSlots(ids).get(id), first.get(id), 'stable between calls')
  assert.equal(new Set(first.values()).size, ids.length, 'no two devices share a slot')
  for (const slot of first.values()) assert.ok(slot >= 0 && slot < CIRCLE_MAX)
  // the order the ids arrive in does not change where they sit
  const reversed = circleSlots([...ids].reverse())
  for (const id of ids) assert.equal(reversed.get(id), first.get(id))
  // a sixth device is not placed
  assert.equal(circleSlots([...ids, 'extra-1.1.1.1']).size, CIRCLE_MAX)
})

test('slot positions are inside the box and the first slot is at the top', () => {
  for (let slot = 0; slot < CIRCLE_MAX; slot++) {
    const {x, y} = slotPosition(slot)
    assert.ok(x > 5 && x < 95 && y > 5 && y < 95, `slot ${slot} at ${x},${y}`)
  }
  assert.equal(slotPosition(0).x, 50)
  assert.ok(slotPosition(0).y < 30)
})

test('a paired device says Up to date, Syncing…, Connected or Offline', () => {
  const lib = [{libraryId: 'l', name: 'L', actions: []}]
  assert.equal(deviceStatus({online: false, lastSync: null, libraries: lib}), 'Offline')
  assert.equal(deviceStatus({online: true, lastSync: null, libraries: lib}), 'Syncing…')
  assert.equal(deviceStatus({online: true, lastSync: {at: 1, pulled: 0, pushed: 0}, libraries: lib}), 'Up to date')
  assert.equal(deviceStatus({online: true, lastSync: null, libraries: []}), 'Connected')
})

test('the second line says what the device is and how it is reached or when it was last seen', () => {
  assert.equal(reachLine({platform: 'android', online: true, route: 'tailscale', lastSeen: 1}), 'Android phone · via Tailscale')
  assert.equal(reachLine({platform: 'windows', online: true, route: 'lan', lastSeen: 1}), 'Windows PC · Home network')
  assert.match(reachLine({platform: 'android', online: false, route: null, lastSeen: Date.now() - 5 * 60_000}), /^Android phone · last seen /)
  assert.equal(reachLine({platform: 'android', online: false, route: null, lastSeen: null}), 'Android phone · not seen yet')
})

test('an opened card has one status sentence that does not repeat the chip', () => {
  const lib = [{libraryId: 'l', name: 'L', actions: []}]
  assert.match(statusLine({online: false, lastSync: null, libraries: lib, unreachableSince: Date.now()}), /^Unreachable since .+ · Keepfin reconnects by itself$/)
  assert.equal(statusLine({online: false, lastSync: null, libraries: lib, unreachableSince: null}), 'Keepfin reconnects by itself')
  assert.equal(statusLine({online: true, lastSync: null, libraries: lib, unreachableSince: null}), 'Syncing…')
  assert.match(statusLine({online: true, lastSync: {at: Date.now(), pulled: 2, pushed: 1}, libraries: lib, unreachableSince: null}), /^3 changes synced /)
  assert.match(statusLine({online: true, lastSync: {at: Date.now(), pulled: 0, pushed: 0}, libraries: lib, unreachableSince: null}), /^Library in step/)
  for (const line of [statusLine({online: false, lastSync: null, libraries: lib, unreachableSince: 1})]) assert.doesNotMatch(line, /Offline/)
  assert.equal(statusLine({online: true, lastSync: null, libraries: [], unreachableSince: null}), 'Nothing shared yet')
  assert.doesNotMatch(statusLine({online: true, lastSync: null, libraries: [], unreachableSince: null}), /Connected/, 'the chip says Connected, the sentence does not repeat it')
})

test('the search lasts a minute', () => {
  assert.equal(SEARCH_MS, 60_000)
})

test('different libraries: a device that does not share this library is told apart from one that only has not allowed a copy', () => {
  assert.equal(differentLibraries({allowed: false, why: 'Galaxy S24 does not share this library with you.'}, undefined), true)
  assert.equal(differentLibraries({allowed: false, why: 'On Galaxy S24, turn on "Keep a copy on this device" for this device in Settings → Devices.'}, undefined), false)
  assert.equal(differentLibraries({allowed: true}, undefined), false)
  assert.equal(differentLibraries(undefined, {libraries: []}), true, 'paired with no library in common')
  assert.equal(differentLibraries(undefined, {libraries: [{libraryId: 'l'}]}), false)
  assert.equal(differentLibraries(undefined, undefined), false)
})
test('a found device has a short readable id', () => {
  assert.equal(shortId('a1b2c3d4e5f60718'), 'a1b2 c3d4')
})
