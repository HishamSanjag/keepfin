// Shared types of the existing-user migration (dev-docs/plans/KEEPFIN-FOLDER-DESKTOP-SPEC.md section 5).

export type FileKind = 'media' | 'sidecar' | 'art'
export type Choice = 'move' | 'copy' | 'leave'

/** Per-file journal states (section 5.3). `planned` is implicit: every plan file starts there without a journal line. */
export type FileState = 'planned' | 'copied' | 'verified' | 'committed' | 'removable' | 'done' | 'failed'

export type InvFile = {
  /** Absolute path at inventory time. */
  path: string
  size: number
  mtimeMs: number
  kind: FileKind
  /** Space id the file belongs to ('' for saved art). */
  space: string
  /** Path below the space folder, '/'-separated (for art: the file name). */
  rel: string
  /** Old library item id (media in the library, and saved art). */
  itemId?: string
  /** For a sidecar: the media file it belongs to. */
  owner?: string
  /** A cover that serves a whole folder (cover.jpg, folder.jpg) rather than one file. */
  shared?: boolean
  /** Media only: false for files in a space folder that the library does not list. */
  indexed?: boolean
  /** Media only: the library lists it from outside its space folder. */
  outside?: boolean
}

export type InvSpace = {
  id: string
  name: string
  /** Absolute folder; '' for smart spaces. */
  folder: string
  type: 'folder' | 'smart'
  ignore?: string[]
}

export type InvItem = {
  id: string
  path: string
  space: string
  /** The entry exactly as it was in library.json. */
  raw: Record<string, unknown>
  /** Fields the current app does not know, kept verbatim. */
  extra: Record<string, unknown>
}

export type InvCollection = {
  id: string
  raw: Record<string, unknown>
  extra: Record<string, unknown>
}

export type Counts = {
  media: number
  sidecars: number
  history: number
  playlists: number
  collections: number
  favourites: number
  userTags: number
  art: number
}

export type Inventory = {
  version: 1
  deviceDir: string
  spaces: InvSpace[]
  library: {
    present: boolean
    version?: number
    /** Unknown top-level fields of library.json. */
    extra: Record<string, unknown>
    items: InvItem[]
    collections: InvCollection[]
  }
  queuePresent: boolean
  media: InvFile[]
  sidecars: InvFile[]
  art: InvFile[]
  /** Paths of media in space folders that the library does not list. */
  unindexed: string[]
  /** Files the library lists that are not on disk. */
  missing: {itemId: string; path: string; space: string}[]
  /** Jobs that have a file. */
  jobs: {id: string; file: string; exists: boolean}[]
  /** Saved art without a library entry. */
  orphanArt: string[]
  /** Files in space folders that Keepfin does not manage (left alone). */
  other: string[]
  /** Unfinished downloads and temporary files (left alone). */
  skipped: string[]
  counts: Counts
}

export type PlanFile = {
  id: string
  kind: FileKind
  space: string
  source: string
  /** Absolute destination, resolved once in the plan. */
  dest: string
  /** Destination relative to the Keepfin folder, '/'-separated. */
  rel: string
  /** Art is always a copy so the old library stays restorable. */
  action: 'move' | 'copy'
  size: number
  mtimeMs: number
  itemId?: string
  /** Plan file id of the media this belongs to. */
  owner?: string
}

export type PlanSpace = {
  id: string
  name: string
  folder: string
  choice: Choice
  /** Destination folder relative to the Keepfin folder; null for a space that is left in place. */
  destRel: string | null
}

export type Plan = {
  version: 1
  runId: string
  createdAt: number
  target: string
  choices: Record<string, Choice>
  spaces: PlanSpace[]
  files: PlanFile[]
  /** Files that stay where they are (Leave). */
  left: {path: string; kind: FileKind; space: string; itemId?: string}[]
  mapping: {
    /** Old item id to new UUID. */
    items: Record<string, string>
    /** Old collection id to new UUID. */
    collections: Record<string, string>
    /** Unindexed file path to the new UUID it gets when added. */
    unindexed: Record<string, string>
    /** Old absolute path to the new path relative to the Keepfin folder. */
    paths: Record<string, string>
    /** Spaces that stay outside the Keepfin folder: space id to its absolute folder. */
    linkedSpaces: Record<string, string>
  }
  /** Unindexed files the user chose to ignore: they still travel, but get no library entry. */
  ignored: string[]
  inventory: Inventory
}

export type RunStatus = 'completed' | 'needs-decision' | 'no-space' | 'paused' | 'abandoned'

export type FailedFile = {id: string; source: string; kind: FileKind; reason: string; code?: string; state: FileState}

export type RunResult = {
  status: RunStatus
  failed: FailedFile[]
  /** True once the new library was written (the switch point passed). */
  committed: boolean
}

export type Progress = {
  phase: 'copying' | 'committing' | 'cleaning' | 'done'
  done: number
  total: number
  bytesDone: number
  bytesTotal: number
  current?: string
}
