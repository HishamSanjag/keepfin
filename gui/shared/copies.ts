// Copies on folders and drives: the contract between main and renderer (dev-docs/plans/KEEPFIN-COPIES-SPEC.md sections 2, 7, 8, 10).
// Types only. The renderer never sees file lists beyond the short samples a preview or a review row names, and never sends
// a path except the folder the user picked (main re-validates it).

export type CopyMode = 'twoWay' | 'backup' | 'mirror'

export type CopyHolds = {
  all: boolean
  /** Space ids. */
  spaces: string[]
  /** Playlist ids. */
  playlists: string[]
  favourites: boolean
  /** Smart space ids: a copy holds whatever the space's rules gather. Absent means none. */
  smart?: string[]
}

export type CopySchedule = {
  /** Also sync every n hours (null: only the triggers below). */
  everyHours: number | null
  /** "Sync by itself": when the drive appears and when the library changes. */
  auto: boolean
}

export type CopyStatus =
  /** Nothing waiting; the last run verified everything. */
  | 'upToDate'
  /** Something is waiting for the next sync. */
  | 'waiting'
  | 'syncing'
  | 'paused'
  /** The drive or folder is not there. */
  | 'disconnected'
  /** A plan is held for the user to confirm (the deletion guard). */
  | 'held'
  /** The first plan is waiting for the user to confirm its preview. */
  | 'preview'
  /** The copy cannot be used (nested, a link, not a Keepfin folder, a restored drive). */
  | 'refused'
  /** Items need a decision. */
  | 'review'
  /** Some files could not be copied: a problem with a cause (see problem), not a decision. Keepfin tries again at every sync. */
  | 'problem'
  /** Never synced yet. */
  | 'new'

export type CopyCell = 'kept' | 'waiting' | 'review' | 'notHeld'

/** A row of the Matrix: something a copy can hold. */
export type CopyRow = {key: string; kind: 'space' | 'playlist' | 'favourites' | 'smart'; id: string; name: string}

/** Derived files a copy on a folder or drive can carry besides the library's own files (spec 8). Never imported back. */
export type CopyExports = {playlistFiles: boolean; flat: boolean; mp3: boolean}

/** [data]: Keepfin's own bookkeeping is moving, not the person's files; the counts stay zero and the UI says so quietly. */
export type CopyProgress = {doneActions: number; totalActions: number; doneBytes: number; totalBytes: number; current: string | null; data?: boolean; dataStep?: 'saving' | 'tidying'; dataDone?: number; dataTotal?: number}

/** Why some files of a copy were not copied (words from the shared core: no codes, no temporary names). */
export type CopyProblem = {
  /** Files that failed at the last sync. */
  count: number
  /** The reasons, the most common first; each count is of the listed files. */
  reasons: {text: string; count: number}[]
  /** The failed files, relative to the copy (up to 50). */
  files: string[]
  /** Failed files not listed. */
  more: number
}

export type CopyPlaceView = {
  id: string
  name: string
  /** Where the copy is on this computer (the last path it was seen at). */
  path: string
  mode: CopyMode
  holds: CopyHolds
  schedule: CopySchedule
  /** Folder and drive copies only. */
  exports?: CopyExports
  /** Lyrics, subtitles and info files travel with their songs and videos (on unless this is false). */
  companions?: boolean
  /** Each song's music video travels with it. The effective value: the person's choice, else on for a copy that holds everything. */
  musicVideos?: boolean
  connected: boolean
  status: CopyStatus
  /** A plain sentence for the status when it needs one ("The drive is not connected"). */
  statusText?: string
  /** Remove copy… is running (what it is doing) or stopped (why; the copy is still here and can be removed again). */
  removal?: {state: 'removing' | 'failed'; text: string}
  progress?: CopyProgress
  lastVerifiedAt?: number
  /** Files verified on the copy / files still to copy / items that need a decision. */
  kept: number
  waiting: number
  review: number
  /** The person's files (songs, videos, photos, other; no sidecars, none of Keepfin's own data) the copy should hold / of those it has. Absent: only kept and waiting are known. */
  items?: number
  itemsKept?: number
  /** Files that could not be copied at the last sync, said in plain words. Absent when none failed. */
  problem?: CopyProblem
  freeBytes?: number
  totalBytes?: number
  /** Backups: earlier versions kept, and what is held for items deleted since. */
  versions?: {files: number; deletedFiles: number}
  /** The first plan has not been confirmed yet. */
  previewPending: boolean
  /** One cell per Matrix row, by row key. */
  cells: Record<string, CopyCell>
  /** Set for a copy in a cloud account: which account and folder, and whether the account needs a new sign-in. */
  cloud?: {provider: CloudProviderId; providerName: string; accountId: string; accountLabel: string; folder: string; needsSignIn: boolean; signedInHere: boolean}
  /** Set for a copy on a paired device: which device, and whether it answers right now. */
  device?: {deviceId: string; deviceName: string; online: boolean}
}

/** A paired device that a copy can be put on, and whether it lets this device do that (its own switch "Keep a copy on this device"). */
export type CopyDeviceChoice = {
  deviceId: string
  name: string
  online: boolean
  allowed: boolean
  /** Why not, in plain words, when it is not allowed. */
  why?: string
}

export type CopyReviewKind =
  | 'conflict'
  | 'copyDeleted'
  | 'missingSource'
  | 'missingEverywhere'
  | 'nameCollision'
  | 'unknownFile'
  | 'keptOnBackup'
  | 'difference'
  | 'suspended'
  | 'notHeld'
  /** A file that is only in the cloud: left alone. */
  | 'onlineOnly'
  /** A file the copy's drive cannot hold (FAT, 4 GB): not copied. */
  | 'tooLarge'
  | 'held'
  | 'restoredDrive'
  /** A cloud account whose sign-in was refused or withdrawn. */
  | 'signIn'

export type CopyReviewAction = 'keepBoth' | 'takeMain' | 'takeCopy' | 'confirmDelete' | 'restore' | 'sameDrive' | 'separateCopy' | 'confirmHeld' | 'dismiss' | 'signIn'

export type CopyReviewItem = {
  id: string
  placeId: string
  placeName: string
  kind: CopyReviewKind
  /** What happened, in a short sentence naming the file. */
  title: string
  /** Why, and what each action does. */
  detail: string
  /** The library path (a name only, never a folder of the user's disk). */
  path?: string
  /** The library item it is about, when it has one. */
  itemId?: string
  actions: CopyReviewAction[]
}

export type CopiesState = {
  /** False while the library is not open (first start, setup): the screen shows nothing to manage. */
  available: boolean
  places: CopyPlaceView[]
  rows: CopyRow[]
  review: CopyReviewItem[]
  /** Review items beyond the ones listed. */
  reviewMore: number
  /** Something is running. */
  syncing: boolean
  /** What the last Sync all or Sync now did, once every copy in it finished (words from the shared core). */
  report?: {title: string; detail: string; warn: boolean; at: number}
  /** A drive that holds a Keepfin copy of this library was just seen but is not one of the copies yet. */
  found?: {path: string; name: string}[]
  /** Cloud accounts: present only when this build can offer them. */
  cloud?: {providers: CloudProviderChoice[]; accounts: CloudAccountView[]}
  /** Paired devices a copy can be put on ("Add a copy → A paired device"): present only when devices are available. */
  devices?: CopyDeviceChoice[]
}

// ---------------------------------------------------------------------------
// Add a copy
// ---------------------------------------------------------------------------

export type CopyFolderCheck = {
  path: string
  ok: boolean
  /** Why not, in plain words. */
  problem?: string
  /** The folder is a Keepfin folder of this library already (a copy being re-added). */
  isCopy?: boolean
  /** The folder holds a Keepfin folder of any library (`.keepfin/folder.json`): the wizard uses it as it is, not a Keepfin folder inside it. */
  keepfinFolder?: boolean
  /** The folder does not exist yet: adding the copy makes it. */
  willCreate?: boolean
  /** The folder already has files of its own (shallow count, capped). */
  existingFiles?: number
  freeBytes?: number
  totalBytes?: number
  /** A name to suggest ("Backup drive (E:)"). */
  suggestedName: string
  insideCloudApp?: string | null
}

export type CopyDraft = {
  /** A folder on this computer; empty for a copy in a cloud account (then cloud names it). */
  path: string
  name: string
  mode: CopyMode
  holds: CopyHolds
  schedule: CopySchedule
  /** Lyrics, subtitles and info files go with their items (the default); false leaves them out. */
  companions?: boolean
  /** Music videos go with their songs; absent follows the holds (on only for a copy that holds everything). */
  musicVideos?: boolean
  /** The account and the folder name on it (Keepfin/<folder>; for WebDAV the folder path on the server). */
  cloud?: {accountId: string; folder: string}
  /** A paired device: the copy is kept in its Keepfin folder, written by that device itself. */
  device?: {deviceId: string}
}

export type CopyPlanLine = {label: string; files: number; bytes: number}

export type CopyPreview = {
  /** The id of the copy being added: pass it to confirm or cancel. */
  placeId: string
  status: 'ready' | 'held' | 'refused' | 'disconnected' | 'busy'
  reason?: string
  /** Additive only: the first plan removes and replaces nothing until it is confirmed. */
  additiveOnly: boolean
  /** What a sync would do, in the planner's words. */
  lines: CopyPlanLine[]
  /** Files to put on the copy / bytes / files already there and equal. */
  copyFiles: number
  copyBytes: number
  /** Removals plus replacements. */
  destructive: number
  /** Differences the plan lists but will not act on yet. */
  differences: {path: string; detail: string}[]
  differencesMore: number
  freeBytes?: number
  enough: boolean | null
  /** Items on the library side this would add (files only the copy has). */
  importsToLibrary: number
}

export type CopiesArgs = {
  path?: string
  draft?: CopyDraft
  placeId?: string
  /** copiesEdit */
  name?: string
  mode?: CopyMode
  holds?: CopyHolds
  schedule?: CopySchedule
  exports?: CopyExports
  /** copiesEdit: lyrics, subtitles and info files go with their items. */
  companions?: boolean
  /** copiesEdit: music videos go with their songs. */
  musicVideos?: boolean
  /** copiesResolve */
  reviewId?: string
  action?: CopyReviewAction
  /** copiesRestore */
  entryId?: string
  /** copiesRemove: also move the copy's folder to the Recycle Bin (a folder or drive copy only). */
  deleteFiles?: boolean
  /** copiesAdopt: a found drive. */
  found?: string
  /** copiesCloud* */
  cloud?: CloudArgs
}

/** A version kept on a backup (Restore lists these). */
export type CopyVersion = {id: string; name: string; path: string; archivedAt: number; why: string; deleted: boolean}

// ---------------------------------------------------------------------------
// Cloud accounts (dev-docs/plans/KEEPFIN-CLOUD-SPEC.md section 6)
// ---------------------------------------------------------------------------

export type CloudProviderId = 'drive' | 'graph' | 'dropbox' | 'webdav'

export type CloudProviderChoice = {
  id: CloudProviderId
  name: string
  /** False when this build has no client id for the provider: the screen hides it. */
  available: boolean
  /** One plain line on what Keepfin can see there ("Only the files Keepfin creates"). */
  scope: string
}

export type CloudAccountView = {
  id: string
  provider: CloudProviderId
  providerName: string
  /** The address of the account when the provider tells it, else the provider's name; for WebDAV the user and server. */
  label: string
  needsSignIn: boolean
  freeBytes?: number
  totalBytes?: number
  /** Copies that use this account. */
  copies: number
  /** WebDAV: what the server was found to support when it was connected. */
  support?: string[]
}

export type CloudArgs = {
  provider?: CloudProviderId
  accountId?: string
  /** WebDAV */
  url?: string
  user?: string
  password?: string
  /** The folder name on the account (WebDAV: its path on the server). */
  folder?: string
}

/** A Keepfin replica of this library found on an account. */
export type CloudFoundReplica = {
  folder: string
  /** The copy it belongs to in this library, when the library already knows it (then it is attached, not added again). */
  placeName?: string
  placeId?: string
}

export type CloudSignInResult = {account: CloudAccountView; state: CopiesState}

export type CloudRevokeResult = {revoked: boolean; note: string; url?: string; state: CopiesState}