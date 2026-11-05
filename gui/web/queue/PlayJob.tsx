// Finished downloads play in Keepfin's own player when the library is on; "Open in another app" stays as the quiet extra.
import type {ReactNode} from 'react'
import type {LibraryItem} from '../../shared/library'
import {getLibrary, useJobItem, useLibraryEnabled} from '../library/libStore'
import {playAll, playItem} from '../player/play'
import {getJob} from '../store'
import {Icon} from '../ui/Icon'

/** The library entry a finished download became, when it can be played in-app (full mode, file still there). */
export function usePlayableItem(jobId: string): LibraryItem | undefined {
  const on = useLibraryEnabled()
  const item = useJobItem(jobId)
  return on && item && !item.missing && item.kind !== 'other' ? item : undefined
}

/** The playable library entries of a set of downloads, in the order given (a collection's order). */
export function playableItems(jobIds: string[]): LibraryItem[] {
  const lib = getLibrary()
  const out: LibraryItem[] = []
  for (const id of jobIds) {
    const item = lib.byJob.get(id)?.[0]
    if (item && !item.missing && item.kind !== 'other' && getJob(id)?.status === 'completed') out.push(item)
  }
  return out
}

/** Plays a whole collection in order (songs queue up; videos and posts open the first with the rest as its list). */
export function playCollection(jobIds: string[]) {
  const items = playableItems(jobIds)
  if (!items.length) return false
  if (items.some((item) => item.kind === 'audio')) playAll(items)
  else playItem(items[0], items)
  return true
}

/** A cover that plays on click, with a play overlay on hover and focus. Plain content when there is nothing to play. */
export function PlayThumb({item, title, children}: {item?: LibraryItem; title: string; children: ReactNode}) {
  if (!item) return <>{children}</>
  return (
    <button type="button" className="thumb-play" aria-label={`Play ${title}`} onClick={() => playItem(item)}>
      {children}
      <span className="thumb-play-badge" aria-hidden="true">
        <Icon name="play" size={20} />
      </span>
    </button>
  )
}
