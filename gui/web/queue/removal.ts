// Removing download history, with an Undo notice. Main keeps the removed records (and their artwork for a minute) so Undo brings them back
// whole; media files are never touched.
import {newUndoId, undoToast} from '../library/undo'
import {act} from '../store'

/** "Removed from history" for one entry or a set, "Removed N from history" for several. */
export const removedFromHistory = (count = 1): string => (count <= 1 ? 'Removed from history' : `Removed ${count} from history`)

/** Runs a history removal sent with an undoId and shows the notice with Undo when it worked. */
export async function removeFromHistory(op: 'remove' | 'removeGroup' | 'removeFailed' | 'clear', extra: {id?: string}, message: string) {
  const undoId = newUndoId()
  if (await act(op, {...extra, undoId})) undoToast({undoId, message})
}
