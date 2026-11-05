// The seam between the copies runner and a transport that is not a folder on this computer (dev-docs/plans/KEEPFIN-COPIES-SPEC.md
// section 9; cloud: KEEPFIN-CLOUD-SPEC.md). Types only: the runner keeps working on folders and does not import this yet.
// A transport declares what it can do and the runner chooses a protocol those capabilities allow; a missing guarantee makes
// it conservative (spec 9, "If missing").

export interface TransportCapabilities {
  /** The kind of server hash, or null: then every file is downloaded and hashed before it counts as verified. */
  serverHash: 'sha256' | 'md5' | 'quickXor' | 'dropbox' | null
  stableIds: boolean
  /** Overwrite or delete an existing object only if it still has the revision we saw. Without it nothing is overwritten in place. */
  casMutation: boolean
  /** Create a new object only if the name is free (lock objects, generation records, no-replace publish). */
  createOnly: boolean
  resumableUpload: boolean
  rangeDownload: boolean
}

/** One line of a streamed remote inventory (same shape the local scan writes). */
export interface TransportInventoryLine {
  path: string
  size: number
  mtimeMs: number
  fileId: string | null
  /** The provider's revision of the object (eTag / rev / version); the precondition of a later overwrite. */
  revision: string | null
}

export interface TransportScanResult {
  count: number
  /** Names the provider cannot tell apart (case, Unicode form) or duplicates on Drive: for Needs review, never merged. */
  collisions?: string[]
  /** Every folder was listed to the end with no torn page. Anything else is principle 5: no destructive action. */
  complete: boolean
  unreadable: string[]
}

export interface TransportLock {
  /** Advisory: it never replaces the append-only authority (7.1 #1). */
  held: boolean
  token: string
  heartbeat(): Promise<void>
  release(): Promise<void>
}

export interface CopyTransport {
  readonly kind: string
  capabilities(): TransportCapabilities
  /** Streams the inventory of [root] to [onLine] in any order (the caller sorts it like a local scan); a listing that tears or fails is reported as incomplete. */
  scan(root: string, onLine: (line: TransportInventoryLine) => void, signal?: AbortSignal): Promise<TransportScanResult>
  /** Downloads into the staged file [stagedFile] and verifies Keepfin's SHA-256 against the pinned content id before returning it (* pins nothing). */
  download(remotePath: string, stagedFile: string, expectedContentId: string, signal?: AbortSignal): Promise<string>
  /** Uploads [sourceFile], pinned to [expectedContentId], to a name that must not exist yet; returns the new revision. */
  uploadNew(sourceFile: string, remotePath: string, expectedContentId: string, signal?: AbortSignal): Promise<{revision: string | null}>
  /** Replaces an object only when it is still at [revision] (compare-and-swap), else preserves the displaced bytes in the trash first. */
  replace(sourceFile: string, remotePath: string, expectedContentId: string, revision: string | null, signal?: AbortSignal): Promise<{revision: string | null; preservedAs: string | null}>
  /** Moves an object into the Keepfin trash by id (never deletes in place). */
  moveToTrash(remotePath: string, revision: string | null): Promise<{trashedAs: string}>
  acquireLock(owner: {device: string; run: string}, nowMs: number): Promise<TransportLock>
  /** The highest generation record, and an append of a new one (append-only: `.keepfin/generations/<n>-<replica>.json`). */
  readGeneration(): Promise<number>
  appendGeneration(generation: number, replicaId: string): Promise<boolean>
}
