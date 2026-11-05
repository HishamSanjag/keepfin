// Where a picked folder puts the Keepfin folder (first start, setup and Change location). The rule is the shared core's
// (core/spaces/Folders.kt), the same on both apps: a folder named Keepfin, or one that already is a Keepfin folder, is used
// as it is; any other folder, a drive or an empty folder included, gets a "Keepfin" folder inside it.
import {keepfinFolderFor, keepfinInside} from '../../shared/core/spaces'

export {keepfinInside}

/** The last part of a Windows or POSIX path ("" for a drive root). */
export function folderName(path: string): string {
  const trimmed = path.replace(/[\\/]+$/, '')
  if (/^[A-Za-z]:$/.test(trimmed)) return ''
  return trimmed.split(/[\\/]/).pop() ?? ''
}

/** A drive or volume root: C:\, F:, \\server\share, or /. */
export function isDriveRoot(path: string): boolean {
  if (/^[A-Za-z]:[\\/]*$/.test(path) || /^\/+$/.test(path)) return true
  return /^\\\\[^\\]+\\[^\\]+\\?$/.test(path)
}

/** What the folder check knows about the picked folder itself. */
export type PickedFolder = {exists: boolean; empty: boolean; isKeepfinFolder: boolean}

/** The Keepfin folder path for a folder the person picked. */
export const placeFor = (picked: string, found: PickedFolder): string => keepfinFolderFor(picked, found.isKeepfinFolder)
