import type {CopyHolds, CopyMode, CopyPlaceView, CopyRow, CopyStatus} from '../../../shared/copies'
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
