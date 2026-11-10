// Adapter between the shared core (core/, built to build/core-js by `npm run core:build`) and the cloud providers of
// dev-docs/plans/KEEPFIN-CLOUD-SPEC.md. This is the only file that imports the core's cloud package. The core describes requests and
// reads responses; it never touches the network. Everything crosses as JSON text.
import {cloudCall, cloudDropboxCombine, JsQuickXor} from '../../../build/core-js/keepfin-core.mjs'

export type CloudProvider = 'drive' | 'graph' | 'dropbox' | 'webdav'

/** A request the platform executes. `auth` says the account's Authorization header goes on it. `bodyRef` is a slice of the pinned file. */
export interface CloudRequest {
  method: string
  url: string
  headers: Record<string, string>
  auth: boolean
  body?: string
  bodyRef?: {offset: number; length: number}
  redirect?: 'manual'
  /** Sent one at a time per account (a Dropbox change locks the whole Dropbox). */
  serial?: boolean
}

export interface CloudResponse {
  status: number
  /** Header names lower-cased. */
  headers: Record<string, string>
  body: string
}

export interface CloudEntry {
  id?: string
  name: string
  folder: boolean
  size?: number
  mtimeMs?: number
  createdMs?: number
  etag?: string
  hashes: {sha256?: string; md5?: string; quickXor?: string; sha1?: string; dropbox?: string}
  parentId?: string
  path?: string
  trashed?: boolean
}

export type CloudErrorKind = 'NeedsSignIn' | 'RateLimited' | 'QuotaFull' | 'NotFound' | 'Conflict' | 'Transient' | 'Fatal'

export interface CloudErrorInfo {
  kind: CloudErrorKind
  status: number
  code?: string
  message?: string
  retryAfterMs?: number
  /** A 401 whose token may only have expired: refresh once before showing NeedsSignIn. */
  refresh?: boolean
  correctOffset?: number
}

export type Failure = {ok: false; error: CloudErrorInfo; ignoredRange?: boolean}
export type Unsupported = {unsupported: true; reason: string}

export interface Ref {
  id?: string
  path?: string
}

export interface UploadSession {
  kind: CloudProvider
  url?: string
  id?: string
  commit?: {path: string; mode: 'add' | 'update'; rev?: string}
  single?: boolean
}

export interface Capabilities {
  provider: CloudProvider
  stableIds: boolean
  identity: 'id' | 'path'
  duplicateNames: boolean
  serverHash: {algorithm: 'sha256' | 'md5' | 'quickXor' | 'dropbox' | null; fallback: string | null}
  createOnly: {byName: boolean; mechanism: string}
  cas: {replace: boolean; delete: boolean; probeNeeded: boolean; probed?: boolean}
  rangeDownload: boolean
  upload: {resumable: boolean; chunkMultiple: number; chunkPreferred: number; chunkMax: number; sessionTtlDays?: number; sequential: boolean}
  listing: {paged: boolean; incompleteFlag: string | null}
  unverified: string[]
}

/** One core call: `cloud('graph', 'list', {folder: {path: ''}})`. Throws when the core refuses the input (a rule, not a network error). */
export function cloud<T = unknown>(provider: CloudProvider, op: string, input: object = {}): T {
  return JSON.parse(cloudCall(provider, op, JSON.stringify(input))) as T
}

/** Streaming quickXorHash (OneDrive), Base64 like Graph writes it. */
export function quickXorHasher(): {update(chunk: Uint8Array): void; digest(): string} {
  const h = new JsQuickXor()
  return {
    update(chunk) {
      h.update(new Int8Array(chunk.buffer, chunk.byteOffset, chunk.byteLength))
    },
    digest: () => h.digest(),
  }
}

/** Dropbox content_hash from the hex SHA-256 digests of each 4 MiB block (the platform hashes the blocks natively). */
export function dropboxCombine(blockDigestsHex: string[]): string {
  return (JSON.parse(cloudDropboxCombine(blockDigestsHex)) as {hash: string}).hash
}

export const DROPBOX_BLOCK = 4 * 1024 * 1024
