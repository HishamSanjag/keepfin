// Typed operations over one cloud account: the core describes each request and reads each answer, CloudHttp sends it. Failures
// throw CloudError (kind NeedsSignIn, RateLimited, QuotaFull, NotFound, Conflict, Transient, Fatal). Nothing here decides what a
// copy does with the answer: that is the transport (transport.ts) and the copies runner.
import crypto from 'node:crypto'
import fs from 'node:fs/promises'
import type {FileHandle} from 'node:fs/promises'
import {cloud, DROPBOX_BLOCK, dropboxCombine, quickXorHasher} from '../../shared/core/cloud.js'
import type {Capabilities, CloudEntry, CloudProvider, CloudRequest, Failure, Ref, Unsupported, UploadSession} from '../../shared/core/cloud.js'
import {CloudError} from './http.js'
import type {CloudHttp, SendIo} from './http.js'
import {MemoryJournal} from './journal.js'
import type {UploadJournal} from './journal.js'

export interface ClientConfig {
  /** WebDAV: the account's root collection URL. */
  base?: string
  now?: () => number
}

export interface ListResult {
  entries: CloudEntry[]
  /** False when any page failed, a page was torn or the provider flagged it (incompleteSearch, a 207 resource failure). */
  complete: boolean
  failed: string[]
}

export class ContentMismatchError extends Error {
  constructor(readonly expected: string, readonly got: string) {
    super(`The bytes do not match the pinned content (${got.slice(0, 15)}).`)
    this.name = 'ContentMismatchError'
  }
}

type Parsed = {ok: true} & Record<string, unknown>

/** Hashes a stream of buffers the way a provider reports it (sha256 / md5 / quickXor / Dropbox blocks), plus Keepfin's SHA-256. */
export class ProviderHasher {
  readonly sha256 = crypto.createHash('sha256')
  private md5 = crypto.createHash('md5')
  private quick = quickXorHasher()
  private blockDigests: string[] = []
  private block: Buffer[] = []
  private blockLen = 0
  constructor(private readonly want: 'sha256' | 'md5' | 'quickXor' | 'dropbox' | null) {}

  update(buf: Buffer): void {
    this.sha256.update(buf)
    if (this.want === 'md5') this.md5.update(buf)
    else if (this.want === 'quickXor') this.quick.update(buf)
    else if (this.want === 'dropbox') {
      let at = 0
      while (at < buf.length) {
        const n = Math.min(DROPBOX_BLOCK - this.blockLen, buf.length - at)
        this.block.push(buf.subarray(at, at + n))
        this.blockLen += n
        at += n
        if (this.blockLen === DROPBOX_BLOCK) this.flushBlock()
      }
    }
  }

  private flushBlock(): void {
    if (this.blockLen === 0) return
    this.blockDigests.push(crypto.createHash('sha256').update(Buffer.concat(this.block)).digest('hex'))
    this.block = []
    this.blockLen = 0
  }

  /** `sha256:<hex>`, the content id. Call once. */
  contentId(): string {
    return 'sha256:' + this.sha256.digest('hex')
  }

  /** The provider's own hash, or null when none is wanted. Call once, after the last update. */
  providerHash(): string | null {
    switch (this.want) {
      case 'md5':
        return this.md5.digest('hex')
      case 'quickXor':
        return this.quick.digest()
      case 'dropbox':
        this.flushBlock()
        return dropboxCombine(this.blockDigests)
      default:
        return null
    }
  }
}

export interface UploadOptions {
  file: string
  contentId: string
  parent?: Ref
  name?: string
  replace?: {ref: Ref; etag: string}
  signal?: AbortSignal
  onBytes?: (n: number) => void
}

export class CloudClient {
  readonly provider: CloudProvider
  private caps: Capabilities
  private casVerified = false
  private rangeVerified = false
  private createOnlyVerified = false
  private noReplaceMoveVerified = false
  private readonly now: () => number

  constructor(readonly http: CloudHttp, private readonly config: ClientConfig = {}, private readonly journal: UploadJournal = new MemoryJournal()) {
    this.provider = http.account.provider
    this.now = config.now ?? Date.now
    this.caps = cloud<Capabilities>(this.provider, 'capabilities', this.provider === 'webdav' ? {} : {})
  }

  capabilities(): Capabilities {
    return this.caps
  }

  /** After the WebDAV probe the capabilities include what the server proved. */
  private refreshCaps(): void {
    this.caps = cloud<Capabilities>(this.provider, 'capabilities', this.provider === 'webdav' ? {probe: {cas: this.casVerified, range: this.rangeVerified, createOnly: this.createOnlyVerified, noReplaceMove: this.noReplaceMoveVerified}} : {})
  }

  private input<T extends object>(extra: T): T & {base?: string; cas?: boolean} {
    if (this.provider !== 'webdav') return extra
    return {base: this.config.base, cas: this.casVerified, ...extra}
  }

  /** Builds, sends and parses one operation; throws CloudError for a failed answer. */
  private async exec<T extends object = Parsed>(build: string, parse: string, input: object, parseExtra: object = {}, io: SendIo = {}, accept: number[] = []): Promise<T> {
    const built = cloud<{request: CloudRequest} | Unsupported>(this.provider, build, this.input(input))
    if ('unsupported' in built) throw new CloudError({kind: 'Fatal', status: 0, code: 'unsupported', message: built.reason}, build)
    const response = await this.http.send(built.request, io, accept)
    const parsed = cloud<Parsed | Failure>(this.provider, parse, this.input({response, ...parseExtra}))
    if (!parsed.ok) throw new CloudError((parsed as Failure).error, build)
    return parsed as unknown as T
  }

  // ----------------------------------------------------------------------------------------------------------- listing

  /** Lists one folder to the end, page by page. A failed page makes the result incomplete (and stops it); entries read so far stay. */
  async list(folder: Ref, name?: string): Promise<ListResult> {
    const entries: CloudEntry[] = []
    const failed: string[] = []
    let complete = true
    let cursor: string | null = null
    for (let pages = 0; pages < 100_000; pages++) {
      let page: {entries: CloudEntry[]; nextCursor: string | null; incomplete: boolean; failed?: string[]}
      try {
        page = await this.exec(cursor ? 'list' : 'list', 'parseList', {folder, cursor: cursor ?? undefined, name}, {...(cursor ? {cursor} : {}), ...(this.provider === 'webdav' ? {folder: folder.path ?? ''} : {})})
      } catch (error) {
        if (!(error instanceof CloudError)) throw error
        // A torn listing: keep what was read, say it is not complete. A missing folder is not a listing either.
        if (error.kind === 'NotFound' && pages === 0) throw error
        complete = false
        failed.push(folder.path ?? folder.id ?? '')
        break
      }
      entries.push(...page.entries)
      if (page.incomplete) complete = false
      if (page.failed?.length) failed.push(...page.failed)
      if (!page.nextCursor) break
      cursor = page.nextCursor
    }
    return {entries, complete, failed}
  }

  async stat(ref: Ref): Promise<CloudEntry | null> {
    try {
      const r = await this.exec<{entry: CloudEntry}>('stat', this.provider === 'webdav' ? 'parseStat' : 'parseEntry', {ref})
      return r.entry
    } catch (error) {
      if (error instanceof CloudError && error.kind === 'NotFound') return null
      throw error
    }
  }

  async createFolder(parent: Ref, name: string): Promise<CloudEntry> {
    const extra = this.provider === 'webdav' ? {name, kind: 'folder'} : {}
    return (await this.exec<{entry: CloudEntry}>('createFolder', 'parseEntry', {parent, name}, extra)).entry
  }

  /** Create-only small object (locks, generation records). Conflict means the name is taken (not for Drive: see the transport). */
  async createSmall(parent: Ref, name: string, content: string): Promise<CloudEntry> {
    if (this.provider === 'webdav' && !this.createOnlyVerified) return this.createViaTemp(parent, name, (temp) => this.rawCreateSmall(parent, temp, content))
    return this.rawCreateSmall(parent, name, content)
  }

  private async rawCreateSmall(parent: Ref, name: string, content: string): Promise<CloudEntry> {
    return (await this.exec<{entry: CloudEntry}>('createSmall', 'parseEntry', {parent, name, content}, {name})).entry
  }

  /** Compare-and-swap replace of a small object; refused (Fatal 'unsupported') where the provider has none. */
  async replaceSmall(ref: Ref, etag: string, content: string, advisory = false): Promise<CloudEntry> {
    return (await this.exec<{entry: CloudEntry}>('replaceSmall', 'parseEntry', {ref, etag, content, advisory}, {name: ref.path?.split('/').pop()})).entry
  }

  async deleteCas(ref: Ref, etag: string): Promise<void> {
    await this.exec('deleteCas', 'parseDone', {ref, etag})
  }

  async move(ref: Ref, to: {newParentId?: string; fromParentId?: string; newName?: string; toPath?: string; etag?: string}): Promise<CloudEntry> {
    const extra = this.provider === 'webdav' ? {name: to.toPath?.split('/').pop(), kind: 'file'} : {}
    return (await this.exec<{entry: CloudEntry}>('move', 'parseEntry', {ref, ...to}, extra)).entry
  }

  async trash(ref: Ref, etag?: string): Promise<void> {
    await this.exec('trash', 'parseDone', {ref, etag})
  }

  async quota(): Promise<{usedBytes?: number; totalBytes?: number; freeBytes?: number; account?: string}> {
    const q = await this.exec<{usedBytes?: number; totalBytes?: number; freeBytes?: number; account?: string}>('quota', 'parseQuota', {})
    return {usedBytes: q.usedBytes, totalBytes: q.totalBytes, freeBytes: q.freeBytes, ...(typeof q.account === 'string' ? {account: q.account} : {})}
  }

  // ------------------------------------------------------------------------------------------------------------ WebDAV

  /**
   * The If-Match probe (7.1 #1, #9): on a scratch object, a wrong If-Match must be refused and the right one accepted, the tag
   * must be strong, and a ranged GET must answer 206. A server that ignores If-Match never gets compare-and-swap rights.
   */
  async probeWebdav(scratchPath: string): Promise<{cas: boolean; range: boolean; createOnly: boolean; noReplaceMove: boolean; reason: string}> {
    if (this.provider !== 'webdav') throw new Error('only WebDAV is probed')
    const second = scratchPath + '-2'
    const any = [200, 201, 204, 206, 304, 400, 403, 405, 409, 412, 415, 501]
    const send = async (op: string, extra: object = {}) => {
      const built = cloud<{request: CloudRequest}>('webdav', op, this.input({path: scratchPath, path2: second, ...extra}))
      return this.http.send(built.request, {}, any)
    }
    const put = await send('probePut')
    const etag = put.headers['etag'] ?? (await this.stat({path: scratchPath}))?.etag
    const range = await send('probeRange')
    const wrong = await send('probeWrong')
    const right = etag ? await send('probeRight', {etag}) : {status: 0}
    const none = await send('probeNoneMatch')
    await this.http.send(cloud<{request: CloudRequest}>('webdav', 'probeNoneMatch', this.input({path: second})).request, {}, any)
    const move = await send('probeMoveOver')
    const verdict = cloud<{cas: boolean; range: boolean; createOnly: boolean; noReplaceMove: boolean; reason: string}>('webdav', 'probeEvaluate', {
      put: {status: put.status, etag},
      wrongMatch: {status: wrong.status},
      rightMatch: {status: right.status},
      range: {status: range.status},
      noneMatch: {status: none.status},
      moveOver: {status: move.status},
    })
    for (const p of [scratchPath, second]) await this.http.send(cloud<{request: CloudRequest}>('webdav', 'trash', this.input({ref: {path: p}})).request, {}, [404]).catch(() => undefined)
    this.casVerified = verdict.cas
    this.rangeVerified = verdict.range
    this.createOnlyVerified = verdict.createOnly
    this.noReplaceMoveVerified = verdict.noReplaceMove
    this.refreshCaps()
    return verdict
  }

  /**
   * WebDAV without a proven create-only: write a temporary name, then MOVE it onto the real name with Overwrite: F. A server that
   * honours that refuses a taken name (412); one that does not leaves a small race that the advisory lock narrows (it cannot close it).
   */
  private async createViaTemp(parent: Ref, name: string, write: (tempName: string) => Promise<CloudEntry>): Promise<CloudEntry> {
    const dir = parent.path ?? ''
    const finalPath = [dir, name].filter(Boolean).join('/')
    if (await this.stat({path: finalPath})) throw new CloudError({kind: 'Conflict', status: 412, code: 'precondition', message: 'exists'}, 'create')
    const temp = `.keepfin-tmp-${this.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
    const tempPath = [dir, temp].filter(Boolean).join('/')
    const entry = await write(temp)
    try {
      const moved = await this.move({path: tempPath}, {toPath: finalPath})
      return {...entry, ...moved, name}
    } catch (error) {
      await this.trash({path: tempPath}).catch(() => undefined)
      throw error
    }
  }
  // -------------------------------------------------------------------------------------------------------------- upload

  /**
   * Uploads [file] (pinned to [contentId]) as a new object `name` under [parent], or as a new revision of [replace]. The bytes
   * that go out are hashed on the way; the last chunk is held back until the whole file hashed to the pinned content id, so a
   * file that changed underneath is never committed. A restart resumes the journaled session.
   */
  async upload(opts: UploadOptions): Promise<CloudEntry> {
    for (let round = 0; ; round++) {
      try {
        return await this.uploadOnce(opts)
      } catch (error) {
        // "resume" is raised when the provider's offset differs from ours: the journal holds the right one, hash again from the start.
        if (error instanceof CloudError && error.info.code === 'resume' && round < 4) continue
        throw error
      }
    }
  }

  private async uploadOnce(opts: UploadOptions): Promise<CloudEntry> {
    if (this.provider === 'webdav' && !opts.replace && !this.createOnlyVerified) {
      return this.createViaTemp(opts.parent!, opts.name!, (temp) => this.uploadDirect({...opts, name: temp}))
    }
    return this.uploadDirect(opts)
  }

  private async uploadDirect(opts: UploadOptions): Promise<CloudEntry> {
    const fh = await fs.open(opts.file, 'r')
    try {
      const size = (await fh.stat()).size
      if (size === 0 && this.provider === 'graph') {
        if (opts.contentId !== 'sha256:' + crypto.createHash('sha256').digest('hex')) throw new ContentMismatchError(opts.contentId, 'empty')
        return opts.replace ? this.replaceSmall(opts.replace.ref, opts.replace.etag, '') : this.createSmall(opts.parent!, opts.name!, '')
      }
      const key = `${this.http.account.id}|${opts.replace ? 'r:' + (opts.replace.ref.id ?? opts.replace.ref.path) : (opts.parent?.id ?? opts.parent?.path ?? '') + '/' + opts.name}|${opts.contentId}`
      const algo = this.caps.serverHash.algorithm
      const hasher = new ProviderHasher(algo === 'sha256' ? null : algo)
      let session: UploadSession | null = null
      let offset = 0
      const saved = await this.journal.get(key)
      if (saved && (!saved.expiresAtMs || saved.expiresAtMs > this.now())) {
        const q = await this.resume(saved.session, saved.offset, size)
        if (q !== null) {
          session = saved.session
          offset = q
        }
      }
      if (!session) {
        const started = await this.startSession(opts, size)
        session = started.session
        await this.journal.put(key, {session, offset: 0, total: size, contentId: opts.contentId, expiresAtMs: started.expiresAtMs, createdAtMs: this.now()})
      }
      // Re-hash what an earlier run already sent (reading only), so the whole-file check still covers every byte.
      await this.hashPrefix(fh, offset, hasher, opts.signal)
      const preferred = this.caps.upload.chunkPreferred || size || 1
      let entry: CloudEntry | null = null
      let stalls = 0
      while (!entry) {
        if (opts.signal?.aborted) throw new CloudError({kind: 'Transient', status: 0, code: 'paused'}, 'upload')
        const length = Math.min(preferred, size - offset)
        const buf = Buffer.alloc(length)
        if (length > 0) await readFully(fh, buf, offset)
        const last = offset + length >= size
        // Hash this chunk once, whatever happens to the request (a retry sends the same buffer).
        hasher.update(buf)
        if (last) {
          const got = hasher.sha256.copy().digest('hex')
          if ('sha256:' + got !== opts.contentId) throw new ContentMismatchError(opts.contentId, 'sha256:' + got)
        }
        const built = cloud<{request: CloudRequest} | Unsupported>(this.provider, 'uploadAppend', this.input({session, offset, length, total: size, mode: opts.replace ? 'replace' : undefined, parent: opts.parent, name: opts.name, ref: opts.replace?.ref, etag: opts.replace?.etag}))
        if ('unsupported' in built) throw new CloudError({kind: 'Fatal', status: 0, code: 'unsupported', message: built.reason}, 'upload')
        const response = await this.http.send(built.request, {readRange: async () => buf, signal: opts.signal, context: 'upload'}, [308])
        const parsed = cloud<Parsed | Failure>(this.provider, 'parseUploadAppend', this.input({response, offset, length, total: size, name: opts.name}))
        if (!parsed.ok) {
          const err = (parsed as Failure).error
          if (err.code === 'incorrect_offset' && err.correctOffset !== undefined && stalls++ < 3) {
            // The provider holds a different amount than we think: continue from its offset (hash again from the start).
            await this.journal.update(key, err.correctOffset)
            throw new CloudError({kind: 'Transient', status: 0, code: 'resume'}, 'upload')
          }
          if (err.code === 'ResumeNeeded' && stalls++ < 3) {
            const q = await this.resume(session, offset, size)
            if (q === null) throw new CloudError({kind: 'NotFound', status: 404, code: 'SessionExpired'}, 'upload')
            await this.journal.update(key, q)
            throw new CloudError({kind: 'Transient', status: 0, code: 'resume'}, 'upload')
          }
          if (err.code === 'SessionExpired') await this.journal.remove(key)
          throw new CloudError(err, 'upload')
        }
        const done = parsed as unknown as {done: boolean; nextOffset?: number; entry?: CloudEntry}
        if (done.done) {
          entry = done.entry!
        } else {
          opts.onBytes?.(length)
          const next = done.nextOffset ?? offset + length
          await this.journal.update(key, next)
          if (next !== offset + length) throw new CloudError({kind: 'Transient', status: 0, code: 'resume'}, 'upload')
          offset = next
          if (offset >= size) throw new CloudError({kind: 'Fatal', status: 0, code: 'not-committed'}, 'upload')
        }
      }
      const reported = algo ? (algo === 'sha256' ? entry.hashes.sha256 : algo === 'md5' ? entry.hashes.md5 : algo === 'quickXor' ? entry.hashes.quickXor : entry.hashes.dropbox) : undefined
      if (algo === 'sha256' && reported && 'sha256:' + reported !== opts.contentId) throw new CloudError({kind: 'Fatal', status: 0, code: 'hash-mismatch', message: 'The provider stored different bytes.'}, 'upload')
      if (algo && algo !== 'sha256' && reported) {
        const local = hasher.providerHash()
        if (local !== reported) throw new CloudError({kind: 'Fatal', status: 0, code: 'hash-mismatch', message: 'The provider stored different bytes.'}, 'upload')
      }
      await this.journal.remove(key)
      return entry
    } finally {
      await fh.close().catch(() => undefined)
    }
  }

  private async hashPrefix(fh: FileHandle, upTo: number, hasher: ProviderHasher, signal?: AbortSignal): Promise<void> {
    let at = 0
    const buf = Buffer.alloc(1024 * 1024)
    while (at < upTo) {
      if (signal?.aborted) throw new CloudError({kind: 'Transient', status: 0, code: 'paused'}, 'upload')
      const n = Math.min(buf.length, upTo - at)
      const got = await readFully(fh, buf.subarray(0, n), at)
      hasher.update(Buffer.from(buf.subarray(0, got)))
      at += got
      if (got === 0) break
    }
  }

  private async startSession(opts: {parent?: Ref; name?: string; replace?: {ref: Ref; etag: string}}, size: number): Promise<{session: UploadSession; expiresAtMs?: number}> {
    const input = {parent: opts.parent, name: opts.name, size, mode: opts.replace ? 'replace' : undefined, ref: opts.replace?.ref, etag: opts.replace?.etag}
    const built = cloud<{request: CloudRequest | null; commit?: unknown; session?: UploadSession} | Unsupported>(this.provider, 'uploadStart', this.input(input))
    if ('unsupported' in built) throw new CloudError({kind: 'Fatal', status: 0, code: 'unsupported', message: built.reason}, 'upload')
    if (!built.request) return {session: built.session!}
    const response = await this.http.send(built.request, {context: 'upload'})
    const parsed = cloud<Parsed | Failure>(this.provider, 'parseUploadStart', this.input({response, nowMs: this.now(), commit: built.commit}))
    if (!parsed.ok) throw new CloudError((parsed as Failure).error, 'upload')
    const p = parsed as unknown as {session: UploadSession; expiresAtMs?: number}
    return {session: p.session, expiresAtMs: p.expiresAtMs}
  }

  /** Asks where an old session stands. Null when it is gone (the caller starts again). */
  private async resume(session: UploadSession, offset: number, total: number): Promise<number | null> {
    if (session.single) return 0
    const built = cloud<{request: CloudRequest}>(this.provider, 'uploadQuery', this.input({session, total, offset}))
    const response = await this.http.send(built.request, {context: 'upload'}, [308])
    const parsed = cloud<Parsed | Failure>(this.provider, 'parseUploadQuery', this.input({response, offset}))
    if (!parsed.ok) {
      const e = (parsed as Failure).error
      if (e.kind === 'NotFound') return null
      throw new CloudError(e, 'upload')
    }
    const p = parsed as unknown as {done: boolean; nextOffset?: number}
    return p.done ? null : (p.nextOffset ?? 0)
  }

  // ------------------------------------------------------------------------------------------------------------ download

  /**
   * Downloads [ref] into [dest] (a staging file this call owns) and verifies Keepfin's SHA-256 against [contentId]. A partial
   * [dest] from an earlier attempt is continued with a Range request only if the server honours it: a 200 to a ranged request is
   * never appended; the file restarts from zero.
   */
  async download(ref: Ref, dest: string, contentId: string, opts: {size?: number; signal?: AbortSignal; onBytes?: (n: number) => void; resume?: boolean} = {}): Promise<string> {
    const sha = crypto.createHash('sha256')
    let have = 0
    if (opts.resume) {
      try {
        const st = await fs.stat(dest)
        if (st.size > 0 && (opts.size === undefined || st.size <= opts.size)) {
          have = st.size
          const rh = await fs.open(dest, 'r')
          const buf = Buffer.alloc(1024 * 1024)
          let at = 0
          while (at < have) {
            const got = await readFully(rh, buf.subarray(0, Math.min(buf.length, have - at)), at)
            sha.update(buf.subarray(0, got))
            at += got
            if (!got) break
          }
          await rh.close()
        }
      } catch {
        have = 0
      }
    }
    if (have === 0) await fs.rm(dest, {force: true})
    let url: string | undefined
    let restarts = 0
    for (let attempt = 0; attempt < 6; attempt++) {
      if (opts.size !== undefined && have >= opts.size && have > 0) break
      if (this.provider === 'graph' && !url) {
        const r = await this.exec<{url: string}>('downloadResolve', 'parseDownloadResolve', {ref})
        url = r.url
      }
      const built = cloud<{request: CloudRequest}>(this.provider, 'download', this.input({ref, start: have, url}))
      let res: Response
      try {
        res = await this.http.stream(built.request, {signal: opts.signal, context: 'download'})
      } catch (error) {
        if (error instanceof CloudError && this.provider === 'graph' && url && (error.info.status === 401 || error.info.status === 403 || error.info.status === 404) && restarts++ < 2) {
          url = undefined
          continue
        }
        throw error
      }
      const headers: Record<string, string> = {}
      res.headers.forEach((v, k) => (headers[k.toLowerCase()] = v))
      const verdict = cloud<{ok: boolean; ignoredRange?: boolean; error?: {code?: string}; total?: number}>(this.provider, 'parseDownload', this.input({response: {status: res.status, headers, body: ''}, start: have}))
      if (!verdict.ok) {
        await res.body?.cancel().catch(() => undefined)
        if (verdict.ignoredRange) {
          // Never append this body to a partial file: start over once, without a Range header.
          if (restarts++ >= 2) throw new CloudError({kind: 'Fatal', status: res.status, code: 'range-ignored'}, 'download')
          sha.update(Buffer.alloc(0))
          return this.downloadFresh(ref, dest, contentId, opts)
        }
        if (verdict.error?.code === 'range-not-satisfiable' && opts.size === have) break
        throw new CloudError({kind: 'Fatal', status: res.status, code: verdict.error?.code}, 'download')
      }
      const out = await fs.open(dest, have > 0 ? 'a' : 'w')
      try {
        const reader = res.body!.getReader()
        for (;;) {
          if (opts.signal?.aborted) {
            await reader.cancel().catch(() => undefined)
            throw new CloudError({kind: 'Transient', status: 0, code: 'paused'}, 'download')
          }
          let chunk: ReadableStreamReadResult<Uint8Array>
          try {
            chunk = await reader.read()
          } catch {
            break // the connection broke: continue from what we hold with a Range request
          }
          if (chunk.done) break
          const buf = Buffer.from(chunk.value)
          await out.write(buf)
          sha.update(buf)
          have += buf.length
          opts.onBytes?.(buf.length)
        }
        await out.sync()
      } finally {
        await out.close()
      }
      if (opts.size === undefined || have >= opts.size) break
    }
    const got = 'sha256:' + sha.digest('hex')
    // '*' means the bytes are not pinned yet (a file another device put there): the caller reads the id from the return value.
    if (contentId !== '*' && got !== contentId) {
      await fs.rm(dest, {force: true})
      throw new ContentMismatchError(contentId, got)
    }
    return got
  }

  private async downloadFresh(ref: Ref, dest: string, contentId: string, opts: Parameters<CloudClient['download']>[3] = {}): Promise<string> {
    await fs.rm(dest, {force: true})
    return this.download(ref, dest, contentId, {...opts, resume: false})
  }

  /** The text of a small object (locks, generation records). */
  async readText(ref: Ref, maxBytes = 1_000_000): Promise<string> {
    let url: string | undefined
    if (this.provider === 'graph') url = (await this.exec<{url: string}>('downloadResolve', 'parseDownloadResolve', {ref})).url
    const built = cloud<{request: CloudRequest}>(this.provider, 'download', this.input({ref, start: 0, url}))
    const res = await this.http.stream(built.request, {context: 'download'})
    const text = await res.text()
    if (text.length > maxBytes) throw new CloudError({kind: 'Fatal', status: 0, code: 'too-large'}, 'download')
    return text
  }
}

async function readFully(fh: FileHandle, buf: Buffer, position: number): Promise<number> {
  let total = 0
  while (total < buf.length) {
    const {bytesRead} = await fh.read(buf, total, buf.length - total, position + total)
    if (bytesRead === 0) break
    total += bytesRead
  }
  return total
}
