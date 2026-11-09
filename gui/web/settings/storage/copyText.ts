import type {CopyHolds, CopyMode, CopyPlaceView, CopyProgress, CopyRow, CopyStatus} from '../../../shared/copies'
import {plural} from '../../format'

export const modeWords: Record<CopyMode, {name: string; short: string; long: string}> = {
  twoWay: {
    name: 'Two-way',
    short: 'Changes on either side reach the other.',
    long: 'Edits you make on the copy come back to your library as new versions, and edits in your library reach the copy. If both change, both are kept.',
  },
  backup: {
    name: 'Backup',
    short: 'Keeps earlier versions of changed and removed files.',
    long: 'Your Keepfin folder is copied here. A file that changes or is removed is kept as an earlier version on the backup until you clean up.',
  },
  mirror: {
    name: 'Mirror',
    short: 'Matches your Keepfin folder exactly.',
    long: 'The copy matches what your library holds. A file removed from your library goes to the copy’s trash and is deleted for good after 30 days.',
  },
}

export const statusWords: Record<CopyStatus, {label: string; tone: 'neutral' | 'accent' | 'success' | 'warning' | 'danger'}> = {
  upToDate: {label: 'Up to date', tone: 'success'},
  waiting: {label: 'Waiting to sync', tone: 'accent'},
  syncing: {label: 'Syncing', tone: 'accent'},
  paused: {label: 'Paused', tone: 'neutral'},
  disconnected: {label: 'Not connected', tone: 'warning'},
  held: {label: 'Needs your OK', tone: 'warning'},
  preview: {label: 'Check the first sync', tone: 'warning'},
  refused: {label: 'Cannot be used', tone: 'danger'},
  review: {label: 'Needs review', tone: 'warning'},
  problem: {label: 'Some files not copied', tone: 'warning'},
  new: {label: 'Not synced yet', tone: 'neutral'},
}

/** "Everything", or the names of what the copy holds ("Music, Favourites and 2 playlists"). */
export function holdsText(holds: CopyHolds, rows: CopyRow[]): string {
  if (holds.all) return 'Everything in your library'
  const parts: string[] = []
  const spaces = holds.spaces.map((id) => rows.find((r) => r.kind === 'space' && r.id === id)?.name).filter((n): n is string => !!n)
  parts.push(...spaces)
  const smart = (holds.smart ?? []).map((id) => rows.find((r) => r.kind === 'smart' && r.id === id)?.name).filter((n): n is string => !!n)
  parts.push(...smart)
  if (holds.favourites) parts.push('Favourites')
  if (holds.playlists.length) parts.push(plural(holds.playlists.length, 'playlist'))
  if (parts.length === 0) return 'Nothing yet'
  if (parts.length === 1) return parts[0]
  return `${parts.slice(0, -1).join(', ')} and ${parts[parts.length - 1]}`
}

export function cellWords(cell: string): string {
  switch (cell) {
    case 'kept':
      return 'kept'
    case 'waiting':
      return 'waiting to sync'
    case 'review':
      return 'needs review'
    default:
      return 'not held'
  }
}

export const scheduleOptions: {value: string; label: string; hours: number | null}[] = [
  {value: 'never', label: 'Only when it changes', hours: null},
  {value: '6', label: 'Also every 6 hours', hours: 6},
  {value: '12', label: 'Also every 12 hours', hours: 12},
  {value: '24', label: 'Also once a day', hours: 24},
]

export function scheduleText(place: Pick<CopyPlaceView, 'schedule'>): string {
  const { auto, everyHours } = place.schedule
  const parts: string[] = []
  if (auto) parts.push('when the drive is connected and your library changes')
  if (everyHours) parts.push(everyHours === 24 ? 'once a day' : `every ${everyHours} hours`)
  return parts.length ? `Syncs ${parts.join(', and ')}.` : 'Syncs only when you press Sync now.'
}

/**
 * What the step that moves Keepfin's own data is doing, and how far: "Cleaning up old Keepfin records on Google Drive · 100 of 1,021"
 * while old records are removed from the account, "Saving Keepfin's own data · 3 of 12" otherwise (no count while the size is unknown).
 */
export function dataProgressWords(progress: Pick<CopyProgress, 'dataStep' | 'dataDone' | 'dataTotal'>, service?: string): string {
  const total = progress.dataTotal ?? 0
  const count = total > 0 ? ` · ${(progress.dataDone ?? 0).toLocaleString('en')} of ${total.toLocaleString('en')}` : ''
  if (progress.dataStep === 'tidying') return `Cleaning up old Keepfin records${service ? ` on ${service}` : ''}${count}`
  return `Saving Keepfin’s own data${count || '…'}`
}

/** How far the data step is, 0 to 100, or undefined while it cannot be told (the bar then keeps moving without a value). */
export const dataProgressPercent = (progress: Pick<CopyProgress, 'dataDone' | 'dataTotal'>): number | undefined =>
  progress.dataTotal && progress.dataTotal > 0 ? Math.min(100, ((progress.dataDone ?? 0) / progress.dataTotal) * 100) : undefined

/** The person's files a copy has and still waits for, counted the way the main storage counts them (sidecars and Keepfin's own data are not files). */
export function copyFiles(place: Pick<CopyPlaceView, 'kept' | 'waiting' | 'items' | 'itemsKept'>): {kept: number; waiting: number} {
  if (place.items === undefined || place.itemsKept === undefined) return {kept: place.kept, waiting: place.waiting}
  return {kept: place.itemsKept, waiting: Math.max(0, place.items - place.itemsKept)}
}
