// What the network layer needs from a library and from the clock; the host (netHost.ts) fills it from the real store, the tests
// from in-process stores. Free of Electron imports.
import type {SyncMembership} from '../../shared/core/sync.js'
import type {InventoryResult} from '../copies/inventory.js'

export interface NetLibrary {
  libraryId: string
  /** What people call it (the profile name). */
  name: string
  /** The Keepfin folder of this device's replica: segments, media and the copy root all live below it. */
  root: string
  /** The records root below .keepfin/ ('' or undefined: .keepfin/ itself; a combined folder names another one). */
  records?: string
  /** This device's actor in the library right now (it can rotate). */
  actor(): string
  /** Writes pending operations into segments, so a peer asking now gets them. */
  flush(): Promise<void>
  /** Reads segments that arrived in the folder and admits them. */
  rescan(): Promise<void>
  /** A change in the library (own or remote): the peers are told. */
  onChange(listener: () => void): () => void
  membership(): Promise<SyncMembership>
  /** Starts enforcement: signs a `member.genesis` that freezes the unsigned history. A no-op when one exists. */
  ensureMembership(grant?: {actor: string; spkiBase64: string; perms: string[]}): Promise<void>
  /** `member.grant` for [actor] with [spkiBase64] as its key. Needs admin. */
  grantMember(actor: string, spkiBase64: string, perms: string[]): Promise<void>
  /** `member.revoke`: [actor]'s ops above [cutoffSeq] are set aside from now on. Needs admin. */
  revokeMember(actor: string, cutoffSeq: number): Promise<void>
  /** The absolute path of the file whose content is [contentId] (`sha256:<hex>`) in this library, or null. */
  findContent(contentId: string): Promise<string | null>
  /** The sorted inventory of the folder as JSON lines in [outFile] (the copies scan). */
  scanInventory(outFile: string, tmpDir: string): Promise<InventoryResult>
}

export interface NetClock {
  now(): number
}

export interface NetLog {
  (message: string): void
}
