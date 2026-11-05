// A cloud account as the media of a copy (copies/remote.ts): the runner plans over the account's streamed listing and the executor sends
// and moves objects through CloudTransport, so this computer keeps no copy of the replica. Displaced bytes are moved to the replica's own
// `.keepfin/versions` or `.keepfin/trash` path before anything is replaced or removed, the layout every copy uses.
import type {RemoteBase, RemoteErrorKind, RemoteMedia, RemoteScan, RemoteStopState} from '../copies/remote.js'
import type {TransportInventoryLine} from '../copies/transport.js'
import {ContentMismatchError} from './client.js'
import {CloudError} from './http.js'
import type {CloudScanResult, CloudTransport} from './transport.js'

/** What the person should be told for an error that ends the run without having done any harm, or null for any other error. */
export function cloudStop(error: unknown): {state: RemoteStopState; message: string} | null {
  if (!(error instanceof CloudError)) return null
  switch (error.kind) {
    case 'NeedsSignIn':
      return {state: 'needsSignIn', message: 'The sign-in for this account has expired or was withdrawn. Sign in again to continue.'}
    case 'QuotaFull':
      return {state: 'quota', message: 'The account is full. Free some space on it or choose a different account.'}
    case 'Transient':
    case 'RateLimited':
      return {state: 'offline', message: 'The account could not be reached. Keepfin will try again.'}
    case 'Conflict':
      if (error.info.code === 'duplicate-names') return {state: 'incomplete', message: error.info.message ?? 'Two items with the same name are on the account; resolve them by hand.'}
      return null
    default:
      return null
  }
}

export class CloudRemote implements RemoteMedia {
  readonly kind = 'cloud'
  readonly archive = 'move' as const

  constructor(readonly media: CloudTransport) {}

  identity(line: TransportInventoryLine): string | null {
    return line.fileId || line.revision ? `${line.fileId ?? ''}@${line.revision ?? ''}` : null
  }

  async scan(onLine: (line: TransportInventoryLine) => void, signal?: AbortSignal): Promise<RemoteScan> {
    const result = (await this.media.scan('', onLine, signal)) as CloudScanResult
    return {count: result.count, complete: result.complete, unreadable: result.unreadable, collisions: result.collisions, missingRoot: result.missingRoot}
  }

  stat(remotePath: string) {
    return this.media.stat(remotePath)
  }

  download(remotePath: string, stagedFile: string, expectedContentId: string, signal?: AbortSignal) {
    return this.media.download(remotePath, stagedFile, expectedContentId, signal)
  }

  uploadNew(sourceFile: string, remotePath: string, contentId: string, signal?: AbortSignal) {
    return this.media.uploadNew(sourceFile, remotePath, contentId, signal)
  }

  async replace(sourceFile: string, remotePath: string, contentId: string, base: RemoteBase, signal?: AbortSignal) {
    const out = await this.media.replace(sourceFile, remotePath, contentId, base.revision, signal)
    return {revision: out.revision}
  }

  async moveToTrash(remotePath: string, base: RemoteBase): Promise<void> {
    await this.media.moveToTrash(remotePath, base.revision)
  }

  moveTo(from: string, to: string, base: RemoteBase) {
    return this.media.moveTo(from, to, base.revision)
  }

  classify(error: unknown): RemoteErrorKind | null {
    if (error instanceof ContentMismatchError) return 'mismatch'
    if (!(error instanceof CloudError)) return null
    if (error.kind === 'NotFound') return 'gone'
    if (error.kind === 'Conflict') return error.info.code === 'exists' ? 'exists' : 'changed'
    return null
  }

  stop(error: unknown) {
    return cloudStop(error)
  }
}
