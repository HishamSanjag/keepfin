// The rule for the one-time "What is new" dialog: show it once when the running version is newer than the last version whose
// notes were seen, however the new version got installed (in-app update, Setup over the old copy, MSI, a portable copy).
import {compareReleases, whatsNewDue} from '../shared/updates'

export type NotesAnswer = {notes?: string; notesVersion?: string}

/**
 * The notes to show now, or undefined. Nothing stored means a fresh install (no dialog; the version is recorded). The stored
 * version moves forward once the notes were shown or turned out to be none; when they could not be loaded (`load` returns
 * undefined) it stays, so the next start asks again instead of losing the dialog.
 */
export async function whatsNewToShow(o: {
  current: string
  read: () => string | null
  write: (version: string) => void
  load: () => Promise<NotesAnswer | undefined>
}): Promise<string | undefined> {
  const seen = o.read()
  if (!whatsNewDue(seen, o.current)) {
    // A fresh install, the same version or a downgrade: remember what runs now, show nothing.
    if (seen !== o.current) o.write(o.current)
    return undefined
  }
  const answer = await o.load()
  if (!answer) return undefined
  o.write(o.current)
  const notes = answer.notes?.trim() ? answer.notes : undefined
  // Only notes that belong to the version now running.
  if (!notes || (answer.notesVersion && compareReleases(answer.notesVersion, o.current) !== 0)) return undefined
  return notes
}
