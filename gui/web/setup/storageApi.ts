import {useEffect, useState} from 'react'
import type {Operation} from '../../shared/contracts'
import type {
  FolderChoice,
  MoveCheck,
  SetupInventory,
  SetupPlan,
  SetupReport,
  SourceChoice,
  StorageArgs,
  StorageOverview,
  StorageState,
} from '../../shared/storage'
import {request} from '../bridge'

/** Typed wrappers over the storage* and setup* operations (gui/shared/storage.ts). */
const call = <T>(op: Operation, storage?: StorageArgs) => request<T>({op, storage})

export const getStorageState = () => call<StorageState>('storageState')

let keepfinRoot: string | undefined
/** The Keepfin folder's path (read once), or undefined until known or without one. */
export function useKeepfinRoot(): string | undefined {
  const [root, setRoot] = useState(keepfinRoot)
  useEffect(() => {
    if (keepfinRoot !== undefined) return
    getStorageState()
      .then((state) => setRoot((keepfinRoot = state.folder?.path ?? '')))
      .catch(() => undefined)
  }, [])
  return root || undefined
}
/** The path the user picked in the native dialog, or null when they cancelled. */
export const pickFolder = () => call<string | null>('storagePickFolder')
export const inspectFolder = (path: string) => call<FolderChoice>('storageInspectFolder', {path})
export const createFolder = (path: string, musicShortcut: boolean) => call<StorageState>('storageCreate', {path, musicShortcut})
export const openExisting = (path: string) => call<StorageState>('storageOpenExisting', {path})

export const getOverview = () => call<StorageOverview>('storageOverview')
export const openFolder = () => call<null>('storageOpenFolder')
export const checkFiles = () => call<StorageOverview>('storageCheckFiles')
export const setMusicShortcut = (enabled: boolean) => call<StorageOverview>('storageSetMusicShortcut', {enabled})

// Storage › Change location (dev-docs/plans/KEEPFIN-FOLDER-MOVE.md)
export const checkMove = (path: string) => call<MoveCheck>('storageMoveCheck', {path})
/** Saves the move; the library closes and the window shows the move (Keepfin restarts once, at the end). */
export const requestMove = (path: string, keepOld: boolean) => call<null>('storageMoveRequest', {path, keepOld})
export const startMove = () => call<null>('storageMoveStart')
export const cancelMove = () => call<null>('storageMoveCancel')

export const getInventory = () => call<SetupInventory>('setupInventory')
export const getPlan = (path: string, choices: Record<string, SourceChoice>) => call<SetupPlan>('setupPlan', {path, choices})
export const startSetup = (runId: string, acceptFailures = false) => call<null>('setupStart', {runId, acceptFailures})
export const abandonSetup = (runId: string) => call<StorageState>('setupAbandon', {runId})
export const getReport = (runId: string) => call<SetupReport>('setupReport', {runId})
export const saveReport = (runId: string) => call<unknown>('setupSaveReport', {runId})
