// The Keepfin folder contract between main and renderer (dev-docs/plans/KEEPFIN-FOLDER-DESKTOP-SPEC.md).
// Types only. The renderer never sees absolute media paths; the folder path itself is shown and chosen by the user.

/** Where the app is in the storage lifecycle. */
export type StoragePhase =
  /** No profile yet and nothing to bring over: show first start. */
  | 'firstStart'
  /** An earlier version's library or downloads exist: show the setup window. */
  | 'needsSetup'
  /** A setup run was interrupted: the setup window resumes it. */
  | 'resumeSetup'
  /** Storage › Change location was confirmed: the folder moves before the library opens (the move window). */
  | 'moveFolder'
  | 'ready'

/** A requested move of the Keepfin folder (move.json in the device folder). */
export type FolderMove = {
  from: string
  to: string
  /** Keep the old place as a backup copy (Storage › Copies) instead of sending it to the Recycle Bin. */
  keepOld?: boolean
}

export type MoveProgress = {
  phase: 'copying' | 'switching' | 'done' | 'failed'
  done: number
  total: number
  bytesDone: number
  bytesTotal: number
  current?: string
  /** Done, with something to tell (the old folder could not go to the Recycle Bin). */
  note?: string
  error?: string
}

/** Whether a folder can take the Keepfin folder: its size, the room there, and whether it is on the same drive (a rename, unless the old place is kept). */
export type MoveCheck = {problem?: string; bytes: number; freeBytes?: number; sameDrive: boolean}

export type CloudApp = 'google-drive' | 'onedrive' | 'dropbox' | 'icloud'

export type StorageState = {
  phase: StoragePhase
  /** The suggested folder for first start and setup (%USERPROFILE%\Keepfin). */
  defaultPath: string
  /** The open Keepfin folder, when ready. */
  folder?: {path: string; libraryId: string}
  /** A setup run to resume (phase 'resumeSetup'). */
  runId?: string
  /** The move to run (phase 'moveFolder'). */
  move?: FolderMove
}

/** What the folder picker found at a path (first-start "Where" step and setup "Where" step). */
export type FolderChoice = {
  path: string
  exists: boolean
  empty: boolean
  /** The path already holds a Keepfin folder: offer to open it instead. */
  isKeepfinFolder: boolean
  /** A Keepfin folder made by a newer app version: it can only be opened read-only. */
  newerFormat?: boolean
  writable: boolean
  freeBytes?: number
  /** Inside a cloud app's folder: show the tip (rule 3, decision D12), never block. */
  insideCloudApp: CloudApp | null
  /** A short reason it cannot be used, in plain words. */
  problem?: string
}

export type LibraryMode = 'downloader' | 'library'

// ---------------------------------------------------------------------------
// Setup window (existing users)
// ---------------------------------------------------------------------------

export type SourceChoice = 'move' | 'copy' | 'leave'

/** One place today's files live (a space folder, or the downloads folder). */
export type SetupSource = {
  id: string
  /** The space or folder name as the user knows it ("Music", "Downloads"). */
  name: string
  path: string
  files: number
  bytes: number
  /** On the same drive as the chosen Keepfin folder (a move is instant). */
  sameDrive?: boolean
  /** The suggested choice: move on the same drive, copy across drives. */
  suggested: SourceChoice
}

export type SetupInventory = {
  sources: SetupSource[]
  counts: SetupCounts
  /** Files in space folders the library did not know about. */
  unindexed: number
  /** Library entries whose file is missing today (they come over as missing, nothing is lost). */
  missing: number
}

export type SetupCounts = {
  media: number
  sidecars: number
  history: number
  playlists: number
  collections: number
  favourites: number
  userTags: number
  art: number
}

export type SetupPlan = {
  runId: string
  target: string
  choices: Record<string, SourceChoice>
  /** Bytes to copy (moves on the same drive copy nothing). */
  bytesToCopy: number
  /** Free space at the target, and whether the plan fits. */
  freeBytes?: number
  fits: boolean
  files: number
  /** Short plain-language lines for the review screen ("Move 1,204 songs from Music (same drive, instant)"). */
  lines: string[]
}

export type SetupPhase = 'preparing' | 'copying' | 'verifying' | 'saving' | 'cleaning' | 'done' | 'failed' | 'stoppedDiskFull'

export type SetupProgress = {
  runId: string
  phase: SetupPhase
  done: number
  total: number
  bytesDone: number
  bytesTotal: number
  /** The file being worked on, as a name only. */
  current?: string
  failed: number
}

export type SetupAttention = {
  name: string
  reason: string
  /** What the user can do. */
  actions: Array<'retry' | 'locate' | 'leave'>
}

export type SetupReport = {
  runId: string
  before: SetupCounts
  after: SetupCounts
  attention: SetupAttention[]
  /** The plain-text version for "Save report". */
  text: string
}

// ---------------------------------------------------------------------------
// Settings → Storage → Overview
// ---------------------------------------------------------------------------

export type StorageOverview = {
  path: string
  drive: string
  freeBytes?: number
  totalBytes?: number
  /** Size of everything in the Keepfin folder. */
  bytes: number
  spaces: Array<{id: string; name: string; files: number; bytes: number}>
  /** Bytes of the music videos kept beside songs (they sit beside the songs, not among the files counted per space). */
  musicVideoBytes?: number
  health: {missing: number; checking: boolean; lastCheckedAt?: number; mismatched?: number}
  insideCloudApp: CloudApp | null
  musicShortcut: boolean
}

/** Arguments of the storage* and setup* operations, carried in Request.storage. */
export type StorageArgs = {
  path?: string
  mode?: LibraryMode
  musicShortcut?: boolean
  choices?: Record<string, SourceChoice>
  runId?: string
  acceptFailures?: boolean
  enabled?: boolean
  /** Change location: keep the old place as a backup copy. */
  keepOld?: boolean
}
