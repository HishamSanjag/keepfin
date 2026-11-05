// Combining two libraries (dev-docs/plans/LIBRARY-MERGE.md, dev-docs/plans/LIBRARY-MERGE-CONTRACT.md): the shapes the renderer and the main
// process exchange. The renderer sends structured intent only (a device id, which library's settings stay, what to keep); every
// answer is the current CombineState, so a page needs no other call. The Windows main process builds these in gui/desktop/merge.

export type CombinePhase =
  | 'idle' // nothing is being combined
  | 'joining' // taking the other library's records in beside this one (this library stays fully usable)
  | 'reviewing' // the dry run is being made
  | 'ready' // the review is ready: nothing has changed yet
  | 'planning' // describing this library's files (hashing the ones that changed)
  | 'applying' // writing the batches into the other library
  | 'verifying'
  | 'switching'
  | 'done' // combined; Separate again is available until `combined.until`
  | 'paused' // waiting (offline, the folder is not there, or the person paused)
  | 'failed'
  | 'separating'

export type CombineReviewKind = 'manyMatches' | 'duplicateInS' | 'differingField' | 'missingFile' | 'sameNamePlaylist' | 'sameNameSpace'

export interface CombineReviewLine {
  kind: CombineReviewKind
  /** The item, playlist or space on this device. */
  s: string
  /** What it became or matched in the other library. */
  t?: string
  related?: string
  field?: string
  /** The item's title, or the playlist's or space's name, on this device (what the review shows instead of an id). */
  title?: string
}

/** The dry run (nothing has changed yet). */
export interface CombinePreview {
  deviceId: string
  deviceName: string
  libraryName: string
  /** Items in this device's library. */
  items: number
  /** Items in the other library, when known (the dry run reads it). */
  otherItems?: number
  /** New content: added to the other library. */
  added: number
  /** The same file is already in the other library: counted once, this device's file becomes its copy here. */
  linked: number
  /** The same content twice here: both kept. */
  keptDuplicates: number
  missingFiles: number
  /** A linked item whose title or other detail differs: the other library's value stays and the difference is listed. */
  differing: number
  playlists: number
  spaces: number
  review: CombineReviewLine[]
  reviewTotal: number
  /** How many days Separate again stays available. */
  separateDays: number
}

export interface CombineProgress {
  done: number
  total: number
  label?: string
}

export interface CombineSeparatePreview {
  /** Items this device brought into the other library (the ones Separate again takes back out of it). */
  brought: number
  /** Brought items the other library's devices already hold a file of: "Keep them there" or "Remove them". */
  heldElsewhere: {id: string; title?: string}[]
  heldElsewhereTotal: number
  /** Items added here since combining: they come back to this device's own library. */
  addedSince: number
  deviceName: string
  /** The other device cannot be reached: separating anyway leaves it everything this device brought. */
  unreachable: boolean
}

export interface CombineState {
  available: boolean
  unavailable?: string
  phase: CombinePhase
  mergeId?: string
  deviceId?: string
  deviceName?: string
  libraryName?: string
  progress?: CombineProgress
  preview?: CombinePreview
  /** Plain reasons a combine cannot start right now (a copy is running, a review is open). */
  blockers?: string[]
  /** Why it is waiting, or what went wrong. */
  paused?: string
  error?: string
  combined?: {since: string; until: string; libraryName: string; canSeparate: boolean}
  separate?: CombineSeparatePreview
}

export interface CombineArgs {
  deviceId?: string
  /** Whose name, spaces and settings stay: this device's ('mine') or the other library's ('theirs'). */
  keep?: 'mine' | 'theirs'
  /** combineSeparate: which brought items the other library keeps ('all', 'none' or a list of item ids). */
  keepOnPeer?: string[] | 'all' | 'none'
  /** combineSeparate: go ahead although the other device cannot be reached. */
  anyway?: boolean
}

/** The combine* operations of the request contract (gui/shared/contracts.ts). */
export const COMBINE_OPS = ['combineState', 'combinePreview', 'combineStart', 'combinePause', 'combineResume', 'combineCancel', 'combineSeparatePreview', 'combineSeparate', 'combineKeep'] as const

export const emptyCombineState = (why?: string): CombineState => ({available: !why, ...(why ? {unavailable: why} : {}), phase: 'idle'})
