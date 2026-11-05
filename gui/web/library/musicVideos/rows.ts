// A song's music video as a row of its own. The list is decided by the core (musicVideoRows); this file reads the library for it.
import {useMemo} from 'react'
import {musicVideoRows, type MusicVideoRow} from '../../../shared/library'
import {useLibrary} from '../libStore'
import {norm} from '../model'

/** Every music video in the library, newest first. Empty when no song has one: the shelf and the page then show nothing. */
export function useMusicVideoRows(): MusicVideoRow[] {
  const {snapshot} = useLibrary()
  return useMemo(() => musicVideoRows(snapshot.items.filter((item) => !item.missing)), [snapshot.items])
}

/** The rows whose title or artist hold every word of a search. */
export const matchingRows = (rows: readonly MusicVideoRow[], words: string[]): MusicVideoRow[] =>
  rows.filter((row) => {
    const key = norm(`${row.title}\n${row.artist ?? ''}`)
    return words.every((word) => key.includes(word))
  })
