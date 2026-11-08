import {formatBytes} from '../../../shared/contracts'
import type {CopiesState, CopyDeviceChoice, CopyDraft, CopyPlaceView, CopyPreview, CopyRow} from '../../../shared/copies'
import {plural} from '../../format'

/** The pure rules of "what stays up to date" on a paired device: the draft from the choices, the review sentences, the card's status sentence. */

export type Direction = 'both' | 'only'
export type SetupChoices = {
  /** Space ids. */
  spaces: string[]
  favourites: boolean
  direction: Direction
  auto: boolean
}

/** The spaces and Favourites this library can keep on a device (playlists travel with the library list, not as files). */
export const setupRows = (rows: CopyRow[]): CopyRow[] => rows.filter((row) => row.kind === 'space' || row.kind === 'favourites')

/** Music and Videos are on to begin with; the rest is the person's choice. */
export function defaultChoices(rows: CopyRow[]): SetupChoices {
  const spaces = rows.filter((row) => row.kind === 'space' && (row.id === 'music' || row.id === 'videos' || /^(music|videos)$/i.test(row.name))).map((row) => row.id)
  return {spaces, favourites: false, direction: 'both', auto: true}
}

export const nothingChosen = (choices: SetupChoices) => choices.spaces.length === 0 && !choices.favourites

/** The copy to add: the chosen spaces (never "everything"), two-way or backup, kept up to date by itself or not. */
export function draftFromChoices(device: {deviceId: string; name: string}, choices: SetupChoices): CopyDraft {
  return {
    path: '',
    name: device.name,
    mode: choices.direction === 'both' ? 'twoWay' : 'backup',
    holds: {all: false, spaces: [...choices.spaces], playlists: [], favourites: choices.favourites},
    schedule: {everyHours: null, auto: choices.auto},
    device: {deviceId: device.deviceId},
  }
}

export const directionWords = (name: string): Record<Direction, {title: string; line: string}> => ({
  both: {title: 'Both ways', line: `New files on either device appear on the other.`},
  only: {title: `Only to ${name}`, line: `This device’s files go to ${name}. Nothing comes back.`},
})

/** Whether the person may continue: the device allows it and something is chosen. */
export function setupBlocker(choice: CopyDeviceChoice | undefined, name: string): string | null {
  if (!choice) return `${name} is not ready to keep a copy. Open Keepfin on ${name} and check that Devices is on, then try again.`
  if (choice.allowed) return null
  return choice.online ? `${name} does not keep a copy for this device yet. ${fixOnOther(name)}` : (choice.why ?? `${name} is not connected right now.`)
}
/**
 * The two devices hold different libraries: the other device says it does not share this library with this one (the same answer the
 * copies code reads), or it is paired and no library is shared yet. Making them one library is a later update, so the setup says so
 * instead of offering copies.
 */
export function differentLibraries(choice: Pick<CopyDeviceChoice, 'allowed' | 'why'> | undefined, paired: {libraries: unknown[]} | undefined): boolean {
  if (choice && !choice.allowed && /does not share this library/.test(choice.why ?? '')) return true
  return !!paired && paired.libraries.length === 0 && !choice?.allowed
}
/** How to fix it on the other device, in plain words. */
export const fixOnOther = (name: string) => `On ${name}, open Devices, open this PC and turn on Keep a copy here.`

export type ReviewSummary = {
  toDevice: string
  toHere: string | null
  space: {text: string; ok: boolean | null}
  additive: string
  /** A status that stops the copy from starting, in one sentence; null when it can start. */
  problem: string | null
  /** A note that does not stop it. */
  note: string | null
  canStart: boolean
  differences: {names: string[]; more: number} | null
}

export const DIFFERENCES_SHOWN = 5

/** The review step in plain sentences, from the engine's preview. */
export function summarisePreview(preview: CopyPreview, name: string, direction: Direction): ReviewSummary {
  const toDevice = preview.copyFiles === 0 ? `Nothing needs to go to ${name}: it has everything chosen already.` : `${plural(preview.copyFiles, 'file')} · ${formatBytes(preview.copyBytes)} will go to ${name}.`
  const toHere = direction === 'both' && preview.importsToLibrary > 0 ? `${plural(preview.importsToLibrary, 'file')} on ${name} will come here.` : null
  const free = preview.freeBytes !== undefined ? ` (${formatBytes(preview.freeBytes)} free)` : ''
  const space =
    preview.enough === true
      ? {text: `There is enough space on ${name}${free}.`, ok: true as const}
      : preview.enough === false
        ? {text: `There is not enough space on ${name}${free}. Free some space there or choose less, then check again.`, ok: false as const}
        : {text: `Keepfin could not check the free space on ${name}.`, ok: null}
  let problem: string | null = null
  let note: string | null = null
  if (preview.status === 'refused') problem = `${preview.reason ?? `${name} cannot keep this copy.`} Go back and change what stays up to date.`
  else if (preview.status === 'disconnected') problem = `${name} is not connected right now. Open Keepfin on ${name}, then check again.`
  else if (preview.status === 'busy') problem = `${name} is busy with another copy. Wait a moment, then check again.`
  else if (preview.status === 'held') note = `${preview.reason ?? 'This is a large change.'} Keepfin will ask you before it starts.`
  const total = preview.differences.length + preview.differencesMore
  const differences = total > 0 ? {names: preview.differences.slice(0, DIFFERENCES_SHOWN).map((item) => item.path.split('/').pop() || item.path), more: total - Math.min(DIFFERENCES_SHOWN, preview.differences.length)} : null
  const ready = preview.status === 'ready' || preview.status === 'held'
  return {
    toDevice,
    toHere,
    space,
    additive: 'Nothing is removed or replaced.',
    problem,
    note,
    canStart: ready && preview.enough !== false,
    differences,
  }
}

/** The copy of this library kept on that device, if there is one. */
export const copyOnDevice = (copies: CopiesState | undefined, deviceId: string): CopyPlaceView | undefined => copies?.places.find((place) => place.device?.deviceId === deviceId)

export const clockTime = (at: number) => new Date(at).toLocaleTimeString(undefined, {hour: '2-digit', minute: '2-digit'})

/** The card's sentence about the copy: "Copying 120 of 800 files · 2.1 GB left", "Up to date · checked 21:04", "Waiting for Laptop", "3 files need a decision". */
export function copyStatusLine(place: CopyPlaceView, name: string): string {
  const offline = place.device ? !place.device.online : false
  if (place.status === 'disconnected' || offline) return `Waiting for ${name}`
  if (place.status === 'syncing') {
    const progress = place.progress
    if (progress?.data) return 'Saving Keepfin’s own data…'
    if (!progress || progress.totalActions <= 0) return 'Copying…'
    return `Copying ${progress.doneActions.toLocaleString()} of ${plural(progress.totalActions, 'file')} · ${formatBytes(Math.max(0, progress.totalBytes - progress.doneBytes))} left`
  }
  if (place.review > 0) return `${plural(place.review, 'file')} ${place.review === 1 ? 'needs' : 'need'} a decision`
  switch (place.status) {
    case 'upToDate':
      return place.lastVerifiedAt ? `Up to date · checked ${clockTime(place.lastVerifiedAt)}` : 'Up to date'
    case 'paused':
      return 'Paused'
    case 'held':
      return 'Waiting for your OK'
    case 'refused':
      return place.statusText ?? 'This copy cannot be used'
    case 'review':
      return 'Some files need a decision'
    default:
      return 'Waiting to copy'
  }
}
