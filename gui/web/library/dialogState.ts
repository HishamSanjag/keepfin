// Which library dialog is open. One host (LibraryDialogs) renders it, so menus, toolbars, the player and
// Settings can all ask for the same dialogs.
import {useSyncExternalStore} from 'react'
import type {Space} from '../../shared/library'

export type LibraryDialog =
  /** `mode: 'copy'` keeps the originals; `collection` acts on every item of that collection instead of `ids`. */
  | {type: 'move'; ids: string[]; mode?: 'move' | 'copy'; collection?: {id: string; name: string}}
  | {type: 'playlistAdd'; ids: string[]}
  | {type: 'playlistName'; id?: string; ids?: string[]; /** Called with the new playlist's id once it is made (the review's "Add to playlist"). */ onMade?: (id: string) => void}
  | {type: 'playlistDelete'; id: string}
  | {type: 'editInfo'; id: string}
  | {type: 'details'; id: string}
  | {type: 'rename'; id: string}
  | {type: 'musicVideo'; id: string}
  | {type: 'removeMusicVideo'; id: string}
  | {type: 'deleteFiles'; ids: string[]}
  | {type: 'import'; space: string; paths?: string[]}
  | {type: 'space'; space?: Space; /** Starting values for a new space (Save as smart space). */ draft?: Partial<Space>; section?: 'rules'}
  | {type: 'addFrom'; space: string}
  | {type: 'addLink'; space: string}
  | {type: 'tags'; ids: string[]}
  | {type: 'follow'; id: string}
  | {type: 'removeSpace'; space: Space}

let current: LibraryDialog | undefined
const listeners = new Set<() => void>()
export function openDialog(dialog: LibraryDialog) {
  current = dialog
  listeners.forEach((listener) => listener())
}
export function closeDialog() {
  current = undefined
  listeners.forEach((listener) => listener())
}
export const useDialog = () =>
  useSyncExternalStore(
    (listener) => {
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
    () => current,
  )
