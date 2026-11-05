// Items of a shared library that have no file on this PC (they were added on another device): the hint a library row shows in place of
// "File missing". It follows the paired devices, which are read once the first such row is on screen and then kept current by pushes.
import {useSyncExternalStore} from 'react'
import type {DevicesState, PairedDevice} from '../../shared/devices'
import {notOnThisPc, type LibraryItem} from '../../shared/library'
import {native, onPush, request} from '../bridge'

let devices: readonly PairedDevice[] = []
let started = false
const listeners = new Set<() => void>()
const emit = () => listeners.forEach((listener) => listener())

function start() {
  if (started || !native) return
  started = true
  onPush((event) => {
    if (event.type === 'devices') {
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

/** The words for an item that is not on this PC, from the paired devices that let this one play their files. */
export function elsewhereHint(list: readonly PairedDevice[]): string {
  const offering = list.filter((device) => device.playFrom)
  if (!offering.length) return 'Not on this PC'
  const online = offering.find((device) => device.online)
  if (online) return `On ${online.name}`
  const latest = [...offering].sort((a, b) => (b.lastSeen ?? 0) - (a.lastSeen ?? 0))[0]
  return `On ${latest.name} (not reachable)`
}

/** The hint for [item], or undefined when the file is simply missing from disk (that keeps "File missing"). */
export function useElsewhereHint(item: Pick<LibraryItem, 'missing' | 'path'> | undefined): string | undefined {
  const here = !!item && notOnThisPc(item)
  const list = useSyncExternalStore(here ? subscribe : quiet, () => devices)
  return here ? elsewhereHint(list) : undefined
}
