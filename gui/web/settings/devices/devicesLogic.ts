import type {PairedDevice} from '../../../shared/devices'
import {relativeTime} from '../../format'

/** The pure rules of the Devices page: which view Available devices uses, where a device sits in the circle, what a card says. */

export type ViewChoice = 'circle' | 'list'
/** The circle holds at most this many devices. */
export const CIRCLE_MAX = 5
/** Above this interface size (percent) the circle gives way to the list: names would collide. */
export const LARGE_TEXT_SCALE = 125
export const VIEW_KEY = 'keepfin.devices.view'

/** The automatic rule: a circle for a few devices at a normal size, a list otherwise. It applies only until the person chooses. */
export function autoView(count: number, scale: number): ViewChoice {
  return count <= CIRCLE_MAX && scale <= LARGE_TEXT_SCALE ? 'circle' : 'list'
}
export const effectiveView = (chosen: ViewChoice | null, count: number, scale: number): ViewChoice => chosen ?? autoView(count, scale)

export function readViewChoice(): ViewChoice | null {
  try {
    const value = localStorage.getItem(VIEW_KEY)
    return value === 'circle' || value === 'list' ? value : null
  } catch {
    return null
  }
}
export function writeViewChoice(choice: ViewChoice) {
  try {
    localStorage.setItem(VIEW_KEY, choice)
  } catch {
    // private window or blocked storage: the choice lasts until the page closes
  }
}

/** Slot angles (degrees, 0 = right, clockwise) of the circle, in reading order from the top. */
const SLOT_ANGLES = [-90, -18, 54, 126, 198]

function hash(text: string): number {
  let h = 2166136261
  for (let i = 0; i < text.length; i++) h = Math.imul(h ^ text.charCodeAt(i), 16777619)
  return h >>> 0
}

/**
 * A fixed slot (0-4) for each device, from its id alone, so a device keeps its place while others come and go. Two devices that
 * want the same slot are settled by id order. Returns the slot per id.
 */
export function circleSlots(ids: string[]): Map<string, number> {
  const taken = new Set<number>()
  const result = new Map<string, number>()
  const order = [...new Set(ids)].sort((a, b) => hash(a) - hash(b) || (a < b ? -1 : a > b ? 1 : 0))
  for (const id of order) {
    if (result.size >= SLOT_ANGLES.length) break
    let slot = hash(id) % SLOT_ANGLES.length
    while (taken.has(slot)) slot = (slot + 1) % SLOT_ANGLES.length
    taken.add(slot)
    result.set(id, slot)
  }
  return result
}

/** Where a slot sits inside the circle's box, as percentages. */
export function slotPosition(slot: number): {x: number; y: number} {
  const angle = (SLOT_ANGLES[slot] * Math.PI) / 180
  return {x: Math.round((50 + 36 * Math.cos(angle)) * 10) / 10, y: Math.round((50 + 34 * Math.sin(angle)) * 10) / 10}
}

/** A short, readable form of the id hint a found device announces: `a1b2 c3d4`. */
export const shortId = (hint: string) => hint.slice(0, 8).replace(/(.{4})(?=.)/g, '$1 ')

export type DeviceStatus = 'Up to date' | 'Syncing…' | 'Connected' | 'Offline'

/** The one-word status of a paired device. */
export function deviceStatus(device: Pick<PairedDevice, 'online' | 'lastSync' | 'libraries'>): DeviceStatus {
  if (!device.online) return 'Offline'
  if (device.libraries.length === 0) return 'Connected'
  return device.lastSync ? 'Up to date' : 'Syncing…'
}

export const platformName = (platform: string) => (platform === 'android' ? 'Android phone' : platform === 'windows' ? 'Windows PC' : 'Device')

/** How long Keepfin looks for devices once the page is open (or Search again is pressed). */
export const SEARCH_MS = 60_000

/** The second line of a paired device's row: "Android phone · via Tailscale", or "Android phone · last seen 11:37 PM" while it is away. */
export function reachLine(device: Pick<PairedDevice, 'platform' | 'online' | 'route' | 'lastSeen'>): string {
  const where = device.online ? (device.route === 'tailscale' ? 'via Tailscale' : 'Home network') : device.lastSeen ? `last seen ${relativeTime(device.lastSeen)}` : 'not seen yet'
  return `${platformName(device.platform)} · ${where}`
}

const clockTime = (at: number) => new Date(at).toLocaleTimeString(undefined, {hour: '2-digit', minute: '2-digit'})

/** One sentence for an opened card: what is happening and what is next. It does not repeat the status chip. */
export function statusLine(device: Pick<PairedDevice, 'online' | 'lastSync' | 'libraries' | 'unreachableSince'>): string {
  if (!device.online) return `${device.unreachableSince !== null ? `Unreachable since ${clockTime(device.unreachableSince)} · ` : ''}Keepfin reconnects by itself`
  if (device.libraries.length === 0) return 'Nothing shared yet'
  if (!device.lastSync) return 'Syncing…'
  const changes = device.lastSync.pulled + device.lastSync.pushed
  return changes ? `${changes === 1 ? '1 change' : `${changes} changes`} synced ${clockTime(device.lastSync.at)}` : `Library in step · checked ${clockTime(device.lastSync.at)}`
}
/** A found device's name in a sentence for screen readers and tooltips. */
export const connectLabel = (name: string) => `Connect to ${name}`
