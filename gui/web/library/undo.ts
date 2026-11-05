// Undo notices for library actions. An action that can be taken back is sent with a fresh `undoId`; the main process records how to
// reverse it from the state at that moment, and "Undo" sends the same id to libraryUndo, which reverses that one action on the state as
// it is then. Nothing here reads a list the page held.
import type {UndoResult} from '../../shared/library'
import {messageOf, request} from '../bridge'
import {toast, type Toast} from '../toasts'

/** A fresh id for one action. */
export const newUndoId = (): string => `undo-${crypto.randomUUID()}`

/** Runs the Undo and tells the person what came of it. A failure is an error notice: "Could not undo: <reason>". */
export async function runUndo(undoId: string) {
  try {
    const result = await request<UndoResult>({op: 'libraryUndo', undoId} as never)
    if (result?.message) toast({tone: result.failed ? 'error' : 'info', message: result.message})
  } catch (error) {
    toast({tone: 'error', message: `Could not undo: ${messageOf(error)}`})
  }
}

/** Delete for good: the notice went without Undo, so the files it could have brought back are erased now. */
export function eraseUndone(undoId: string) {
  void request({op: 'libraryEraseTrashed', undoId} as never).catch(() => undefined)
}

/**
 * A success notice with Undo. A new notice with the same key replaces the older one, so only the newest Undo shows. [eraseWhenGone]
 * (Delete for good) erases what Undo could bring back once the notice goes without Undo.
 */
export function undoToast(input: {message: string; undoId: string; tone?: Toast['tone']; secondary?: Toast['secondary']; duration?: number; eraseWhenGone?: boolean}) {
  return toast({
    tone: input.tone ?? 'success',
    key: 'library-undo',
    message: input.message,
    action: {label: 'Undo', run: () => void runUndo(input.undoId)},
    secondary: input.secondary,
    duration: input.duration,
    onGone: input.eraseWhenGone ? () => eraseUndone(input.undoId) : undefined,
  })
}
