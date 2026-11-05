// "Made from your listening": rows built locally from play counts and dates. Nothing leaves the computer and
// nothing is guessed: a row with too little data to be useful is simply not returned.
import type {LibraryItem} from './library.js'

export type ListeningRowId = 'on-repeat' | 'forgotten' | 'recent' | 'never'
export type ListeningRow = {id: ListeningRowId; title: string; blurb: string; items: LibraryItem[]}

/** A row needs at least this many songs, or it is hidden. */
export const MIN_LISTENING_ROW = 5
const DAY = 86_400_000
const ROW_LIMIT = 30

const byPlays = (a: LibraryItem, b: LibraryItem) => (b.plays ?? 0) - (a.plays ?? 0) || (b.lastPlayedAt ?? 0) - (a.lastPlayedAt ?? 0)
const byAdded = (a: LibraryItem, b: LibraryItem) => b.addedAt - a.addedAt

/** Songs only; files that are gone are not offered. */
export function listeningRows(items: readonly LibraryItem[], now = Date.now()): ListeningRow[] {
  const songs = items.filter((item) => item.kind === 'audio' && !item.missing)
  const rows: ListeningRow[] = [
    {
      id: 'on-repeat',
      title: 'On repeat',
      blurb: 'Most played in the last 30 days',
      items: songs.filter((item) => (item.plays ?? 0) > 0 && (item.lastPlayedAt ?? 0) >= now - 30 * DAY).sort(byPlays),
    },
    {
      id: 'forgotten',
      title: 'Forgotten favorites',
      blurb: 'Loved or played a lot, not played for 60 days',
      items: songs.filter((item) => (item.favorite || (item.plays ?? 0) >= 5) && (item.lastPlayedAt ?? 0) < now - 60 * DAY).sort(byPlays),
    },
    {id: 'recent', title: 'Recently added', blurb: 'Newest songs', items: songs.slice().sort(byAdded)},
    {id: 'never', title: 'Never played', blurb: 'Saved but not heard yet', items: songs.filter((item) => !item.plays).sort(byAdded)},
  ]
  return rows.map((row) => ({...row, items: row.items.slice(0, ROW_LIMIT)})).filter((row) => row.items.length >= MIN_LISTENING_ROW)
}
