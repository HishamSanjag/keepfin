// A paired device as one more transport for copies (spec 5; KEEPFIN-COPIES-SPEC.md section 9). The peer's Keepfin folder is the place:
// its inventory is read as a stream, its files come down by content id with Range (resuming a broken transfer), and every change is a
// request that the peer's own executor runs under its own place lock (peerExecutor.ts). This device never writes into the other
// device's folder directly.
//
// Capabilities (what is honest today): the SHA-256 content id is the server hash (checked on every download and by the peer on every
// upload); ids are stable (file ids of the peer's disk); replace and trash are compare-and-swap on the file's revision (size and
// modification time on the peer); create is no-replace; downloads resume with Range; an upload restarts from the beginning.
import fs from 'node:fs'
import fsp from 'node:fs/promises'
import {createHash} from 'node:crypto'
import readline from 'node:readline'
import type {CopyTransport, TransportCapabilities, TransportInventoryLine, TransportLock, TransportScanResult} from '../copies/transport.js'
import {PeerClient, PeerHttpError} from './client.js'

export class PeerTransportError extends Error {
  constructor(
    readonly kind: 'NotFound' | 'Conflict' | 'Changed' | 'Locked' | 'Denied' | 'Space' | 'Hash' | 'Other',
    message: string,
    readonly status = 0,
  ) {
    super(message)
    this.name = 'PeerTransportError'
  }
}

function mapError(error: unknown): never {
  if (error instanceof PeerHttpError) {
    const kind = error.status === 404 ? 'NotFound' : error.status === 409 && error.code === 'exists' ? 'Conflict' : error.status === 409 ? 'Locked' : error.status === 412 ? 'Changed' : error.status === 403 ? 'Denied' : error.status === 507 ? 'Space' : error.status === 422 ? 'Hash' : 'Other'
    throw new PeerTransportError(kind, error.message, error.status)
  }
  throw error
}

export interface PeerTransportOptions {
  client: PeerClient
  libraryId: string
  /** This device's name, shown to the peer in its lock. */
  device: string
  heartbeatMs?: number
}

export class PeerTransport implements CopyTransport {
  readonly kind = 'device'
  private token: string | null = null

  constructor(private readonly options: PeerTransportOptions) {}

  private get base() {
    return `/v1/libraries/${this.options.libraryId}`
  }

  capabilities(): TransportCapabilities {
    return {serverHash: 'sha256', stableIds: true, casMutation: true, createOnly: true, resumableUpload: false, rangeDownload: true}
  }

  async scan(_root: string, onLine: (line: TransportInventoryLine) => void, signal?: AbortSignal): Promise<TransportScanResult> {
    let response
    try {
      response = await this.options.client.request('GET', `${this.base}/inventory`, {timeoutMs: 10 * 60_000, signal})
    } catch (error) {
      return {count: 0, complete: false, unreadable: [String((error as Error).message)]}
    }
    if (response.status !== 200) {
      response.res.resume()
      return {count: 0, complete: false, unreadable: [`The device answered ${response.status}.`]}
    }
    let count = 0
    const lines = readline.createInterface({input: response.res, crlfDelay: Infinity})
    try {
      for await (const text of lines) {
        if (!text) continue
        const row = JSON.parse(text) as {path: string; size: number; mtimeMs: number; fileId?: string}
        onLine({path: row.path, size: row.size, mtimeMs: row.mtimeMs, fileId: row.fileId ?? null, revision: `${row.size}-${Math.floor(row.mtimeMs)}`})
        count++
      }
    } catch (error) {
      return {count, complete: false, unreadable: [String((error as Error).message)]}
    }
    // a response that was cut short is not a complete listing, whatever the header said
    return {count, complete: response.headers['x-keepfin-complete'] === '1' && response.res.complete, unreadable: []}
  }

  /**
   * One file of the peer's folder as its listing shows it (size and time, which are also its revision), or null when it is not there. Read
   * from the headers of the by-name download (the ETag is `size-mtime`): one byte is requested, or none for an empty file.
   */
  async stat(remotePath: string): Promise<TransportInventoryLine | null> {
    const target = `${this.base}/copy/file?path=${encodeURIComponent(remotePath)}`
    for (const range of ['bytes=0-0', null]) {
      let response
      try {
        response = await this.options.client.request('GET', target, {headers: range ? {range} : {}, timeoutMs: 30_000})
      } catch (error) {
        return mapError(error)
      }
      if (response.status === 416 && range) {
        response.res.resume() // an empty file has no byte to ask for: ask again for the headers alone
        continue
      }
      if (response.status === 200 || response.status === 206) {
        const etag = String(response.headers.etag ?? '')
        const m = /^"(\d+)-(\d+)"$/.exec(etag)
        response.res.destroy()
        if (!m) throw new PeerTransportError('Other', 'The device did not say how big the file is.')
        return {path: remotePath, size: Number(m[1]), mtimeMs: Number(m[2]), fileId: null, revision: `${m[1]}-${m[2]}`}
      }
      const body = await collectText(response.res)
      if (response.status === 404) return null
      return mapError(new PeerHttpError(response.status, safeCode(body), safeMessage(body)))
    }
    return null
  }

  /** Downloads by content id, resuming a partial [stagedFile]; the bytes must hash to [expectedContentId]. */
  async download(remotePath: string, stagedFile: string, expectedContentId: string, signal?: AbortSignal): Promise<string> {
    // `*` pins nothing (a copy being pulled by name: the bytes are hashed on arrival and the caller learns the content id)
    const byName = expectedContentId === '*'
    const m = /^sha256:([0-9a-f]{64})$/.exec(expectedContentId)
    if (!m && !byName) throw new PeerTransportError('Other', 'A device can only send a file whose content id is known.')
    const have = await fsp.stat(stagedFile).then((s) => s.size, () => 0)
    let offset = have
    const target = byName ? `${this.base}/copy/file?path=${encodeURIComponent(remotePath)}` : `/v1/revisions/${m![1]}?library=${encodeURIComponent(this.options.libraryId)}`
    for (let attempt = 0; attempt < 2; attempt++) {
      let response
      try {
        response = await this.options.client.request('GET', target, {headers: offset > 0 ? {range: `bytes=${offset}-`} : {}, timeoutMs: 120_000, signal})
      } catch (error) {
        return mapError(error)
      }
      if (response.status === 416) {
        // the partial file is longer than the file or not a prefix: start over
        response.res.resume()
        await fsp.rm(stagedFile, {force: true})
        offset = 0
        continue
      }
      if (response.status !== 200 && response.status !== 206) {
        const body = await collectText(response.res)
        return mapError(new PeerHttpError(response.status, safeCode(body), safeMessage(body)))
      }
      // a peer that ignored Range sends the whole file: never append that to a partial one
      const resumed = response.status === 206 && offset > 0
      if (!resumed && offset > 0) await fsp.rm(stagedFile, {force: true})
      const out = fs.createWriteStream(stagedFile, {flags: resumed ? 'a' : 'w'})
      await new Promise<void>((resolve, reject) => {
        response.res.on('error', reject)
        out.on('error', reject)
        out.on('finish', resolve)
        response.res.pipe(out)
      })
      break
    }
    const got = await hashOf(stagedFile)
    if (!byName && got !== expectedContentId) {
      await fsp.rm(stagedFile, {force: true})
      throw new PeerTransportError('Hash', 'The file that arrived does not match its content id.')
    }
    return got
  }

  async uploadNew(sourceFile: string, remotePath: string, expectedContentId: string, signal?: AbortSignal): Promise<{revision: string | null}> {
    const result = await this.put(sourceFile, remotePath, expectedContentId, 'new', null, signal)
    return {revision: result.revision}
  }

  async replace(sourceFile: string, remotePath: string, expectedContentId: string, revision: string | null, signal?: AbortSignal): Promise<{revision: string | null; preservedAs: string | null}> {
    return this.put(sourceFile, remotePath, expectedContentId, 'replace', revision, signal)
  }

  private async put(sourceFile: string, remotePath: string, contentId: string, mode: 'new' | 'replace', revision: string | null, signal?: AbortSignal) {
    const token = this.requireToken()
    const size = (await fsp.stat(sourceFile)).size
    const query = new URLSearchParams({token, path: remotePath, content: contentId, mode})
    // a replace is conditional on the content the caller saw (HTTP If-Match on the content id); size and time remain for callers that know no better
    const ifMatch = revision !== null && isContentId(revision) ? revision : null
    if (revision !== null && !ifMatch) query.set('revision', revision)
    try {
      const response = await this.options.client.request('PUT', `${this.base}/copy/file?${query}`, {
        body: fs.createReadStream(sourceFile),
        contentLength: size,
        headers: ifMatch ? {'if-match': `"${ifMatch}"`} : {},
        timeoutMs: 10 * 60_000,
        signal,
      })
      const text = await collectText(response.res)
      if (response.status >= 400) throw new PeerHttpError(response.status, safeCode(text), safeMessage(text))
      const body = JSON.parse(text) as {revision: string; preservedAs: string | null}
      return {revision: body.revision, preservedAs: body.preservedAs ?? null}
    } catch (error) {
      return mapError(error)
    }
  }

  async moveToTrash(remotePath: string, revision: string | null): Promise<{trashedAs: string}> {
    const token = this.requireToken()
    const ifMatch = revision !== null && isContentId(revision) ? revision : null
    try {
      return await this.options.client.json<{trashedAs: string}>('POST', `${this.base}/copy/trash`, {json: {token, path: remotePath, revision: ifMatch ? null : revision, ifMatch}})
    } catch (error) {
      return mapError(error)
    }
  }

  /** Renames a file inside the peer's folder, only while it still has the content the caller saw. */
  async moveTo(from: string, to: string, revision: string | null): Promise<{revision: string | null}> {
    const token = this.requireToken()
    const ifMatch = revision !== null && isContentId(revision) ? revision : null
    try {
      return await this.options.client.json<{revision: string}>('POST', `${this.base}/copy/move`, {json: {token, from, to, revision: ifMatch ? null : revision, ifMatch}})
    } catch (error) {
      return mapError(error)
    }
  }

  async acquireLock(owner: {device: string; run: string}, _nowMs: number): Promise<TransportLock> {
    let answer: {ok: boolean; token?: string; holder?: string}
    try {
      answer = await this.options.client.json('POST', `${this.base}/copy/lock`, {json: {device: owner.device, run: owner.run}})
    } catch (error) {
      return mapError(error)
    }
    if (!answer.ok || !answer.token) return {held: false, token: '', heartbeat: async () => undefined, release: async () => undefined}
    const token = answer.token
    this.token = token
    const beat = setInterval(() => void this.options.client.json('POST', `${this.base}/copy/heartbeat`, {json: {token}}).catch(() => undefined), this.options.heartbeatMs ?? 15_000)
    beat.unref?.()
    return {
      held: true,
      token,
      heartbeat: async () => {
        await this.options.client.json('POST', `${this.base}/copy/heartbeat`, {json: {token}})
      },
      release: async () => {
        clearInterval(beat)
        if (this.token === token) this.token = null
        await this.options.client.json('POST', `${this.base}/copy/unlock`, {json: {token}}).catch(() => undefined)
      },
    }
  }

  async readGeneration(): Promise<number> {
    try {
      const state = await this.options.client.json<{generation: number | null}>('GET', `${this.base}/copy/state`)
      return state.generation ?? 0
    } catch (error) {
      return mapError(error)
    }
  }

  async appendGeneration(generation: number, replicaId: string): Promise<boolean> {
    const token = this.requireToken()
    try {
      const answer = await this.options.client.json<{ok: boolean}>('POST', `${this.base}/copy/generation`, {json: {token, generation, replica: replicaId}})
      return answer.ok
    } catch (error) {
      return mapError(error)
    }
  }

  private requireToken(): string {
    if (!this.token) throw new PeerTransportError('Locked', 'The copy must be locked before it is changed.')
    return this.token
  }
}

export const isContentId = (value: string) => /^sha256:[0-9a-f]{64}$/.test(value)

async function hashOf(file: string): Promise<string> {
  const hash = createHash('sha256')
  for await (const chunk of fs.createReadStream(file)) hash.update(chunk as Buffer)
  return `sha256:${hash.digest('hex')}`
}

async function collectText(stream: AsyncIterable<unknown>): Promise<string> {
  const chunks: Buffer[] = []
  for await (const chunk of stream) chunks.push(chunk as Buffer)
  return Buffer.concat(chunks).toString('utf8')
}

const safeCode = (text: string) => {
  try {
    return String((JSON.parse(text) as {error?: string}).error ?? 'error')
  } catch {
    return 'error'
  }
}
const safeMessage = (text: string) => {
  try {
    return String((JSON.parse(text) as {message?: string}).message ?? 'The device refused.')
  } catch {
    return 'The device refused.'
  }
}
