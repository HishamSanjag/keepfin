// Find lyrics on a song that has none. The engine does the lookup and stores the result like a finished download does; this asks,
// tells the open lyrics panel to look again, and words the answer (the words are the core's).
import type {LyricsOutcome} from '../../shared/core/lyrics'
import {findLyricsWords} from '../../shared/core/lyrics'
import type {LibraryItem} from '../../shared/library'
import {lyricsChanged} from '../player/full/lyricsQueue'
import {toast} from '../toasts'
import {libraryAct} from './libStore'

export {findLyricsWords}
export type FindResult = {outcome: LyricsOutcome; message: string}

/** Asks for the lyrics of the song [id]; undefined when the request itself failed (its error is shown). */
export async function findLyrics(id: string): Promise<FindResult | undefined> {
  const result = await libraryAct<FindResult>('libraryFindLyrics', id)
  if (!result.ok) return
  if (result.value.outcome === 'found') lyricsChanged(id)
  return result.value
}

/** The menu's action: the answer comes as a notice, since nothing else on screen shows it. */
export async function findLyricsFromMenu(item: LibraryItem) {
  toast({tone: 'info', message: `Looking for lyrics for “${item.title}”…`})
  const found = await findLyrics(item.id)
  if (found) toast({tone: found.outcome === 'found' ? 'success' : found.outcome === 'busy' ? 'error' : 'info', message: found.message})
}
