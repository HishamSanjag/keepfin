// Items of a shared library that have no file on this PC (they were added on another device): the hint a library row shows in place of
// "File missing". It names the paired device the library says holds the item (the placements the sync already carries), and follows the
// paired devices, which are read once the first such row is on screen and then kept current by pushes.
import {useEffect, useSyncExternalStore} from 'react'
import type {DevicesState, PairedDevice} from '../../shared/devices'
import {playHint, type PlayHolder} from '../../shared/core/playFrom'
import {notOnThisPc, type LibraryItem} from '../../shared/library'
import type {WhereItemView} from '../../shared/where'
import {native, onPush, request} from '../bridge'
import {getWhereItem} from '../settings/where/whereApi'

let devices: readonly PairedDevice[] = []
let started = false
let version = 0
const listeners = new Set<() => void>()
const emit = () => {
  version++
  listeners.forEach((listener) => listener())
}

/** Where one item is held, asked once per item, again after a minute, and again when the set of paired devices changes. */
const views = new Map<string, {at: number; view: WhereItemView | null}>()
const VIEW_TTL_MS = 60_000

function ensure(id: string) {
  const known = views.get(id)
  if (known && Date.now() - known.at < VIEW_TTL_MS) return
  views.set(id, {at: Date.now(), view: known?.view ?? null})
  getWhereItem(id)
    .then((view) => {
      views.set(id, {at: Date.now(), view})
      emit()
    })
    .catch(() => undefined)
}

function start() {
  if (started || !native) return
  started = true
  onPush((event) => {
    if (event.type === 'devices') {
      if (event.state.paired.map((d) => d.deviceId).join() !== devices.map((d) => d.deviceId).join()) views.clear()
      devices = event.state.paired
      emit()
    }
  })
  request<DevicesState>({op: 'devicesState'})
    .then((state) => {
      devices = state.paired
      emit()
    })
    .catch(() => undefined)
}

const subscribe = (listener: () => void) => {
  start()
  listeners.add(listener)
  return () => void listeners.delete(listener)
}
const quiet = () => () => undefined

/**
 * The words for an item that is not on this PC: the paired device the library says holds it (a connected one that lets this PC play its
 * files first, one on this network before one through Tailscale), or an honest "not known" when no device is known to have it.
 */
export function elsewhereHint(list: readonly PairedDevice[], view: WhereItemView | null): string {
  const holders: PlayHolder[] = []
  for (const place of view?.places ?? []) {
    const device = place.deviceId ? list.find((d) => d.deviceId === place.deviceId) : undefined
    if (device) holders.push({name: device.name, online: device.online, offersMedia: !!device.playFrom, near: device.route !== 'tailscale', lastSeen: device.lastSeen})
  }
  return playHint('this PC', holders).text
}

/** The hint for [item], or undefined when the file is simply missing from disk (that keeps "File missing"). */
export function useElsewhereHint(item: Pick<LibraryItem, 'id' | 'missing' | 'path'> | undefined): string | undefined {
  const here = !!item && notOnThisPc(item)
  useSyncExternalStore(here ? subscribe : quiet, () => version)
  const id = item?.id
  useEffect(() => {
    if (here && id) ensure(id)
  }, [here, id, version])
  return here && id ? elsewhereHint(devices, views.get(id)?.view ?? null) : undefined
}
