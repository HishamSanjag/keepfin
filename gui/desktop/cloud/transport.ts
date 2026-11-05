// The cloud transport for copies (dev-docs/plans/KEEPFIN-COPIES-SPEC.md section 9, KEEPFIN-CLOUD-SPEC.md 7.1). It implements the
// CopyTransport seam on top of CloudClient:
//   - scan streams an inventory folder by folder and says whether every folder was listed to the end (a torn page, an
//     incompleteSearch or a failed 207 resource makes it incomplete; a name collision is reported, never merged);
//   - download stages and verifies Keepfin's SHA-256 against the pinned content id; upload hashes the bytes it sends;
//   - replace overwrites only by compare-and-swap; where that does not exist the displaced bytes move to `.keepfin/trash` by id
//     and nothing is overwritten or deleted in place;
//   - the lock object is advisory, generations are append-only records.
import {cloud} from '../../shared/core/cloud.js'
import type {CloudEntry, Ref} from '../../shared/core/cloud.js'
import type {CopyTransport, TransportCapabilities, TransportInventoryLine, TransportLock, TransportScanResult} from '../copies/transport.js'
import {CloudClient} from './client.js'
import {CloudError} from './http.js'

export interface CloudTransportOptions {
  client: CloudClient
  /** The replica's folder on the account, relative to the app area (`Keepfin`, or `Keepfin/Library` for WebDAV). Library paths live under it. */
  rootPath: string
  now?: () => number
  /** A lock whose heartbeat is older than this is stale (spec 5: five minutes). */
  staleLockMs?: number
  randomId?: () => string
}

export interface CloudScanResult extends TransportScanResult {
  /** Two names that the provider cannot tell apart (case, Unicode form) or duplicates on Drive: listed for Needs review, never merged. */
  collisions: string[]
  missingRoot: boolean
}

export const KEEPFIN_DIR = '.keepfin'

interface Located {
  /** Where to address it. */
  ref: Ref
  parent: Ref
  /** The stored (escaped) name. */
  name: string
  entry?: CloudEntry
}

export class CloudTransport implements CopyTransport {
  readonly kind: string
  private readonly client: CloudClient
  private readonly now: () => number
  private readonly encodeCache = new Map<string, string>()
  /** Folder library path -> Ref (ids on Drive). */
  private readonly folders = new Map<string, Ref>()
  private readonly rootSegments: string[]

  constructor(private readonly options: CloudTransportOptions) {
    this.client = options.client
    this.kind = 'cloud:' + this.client.provider
    this.now = options.now ?? Date.now
    this.rootSegments = options.rootPath.split('/').filter(Boolean)
  }

  capabilities(): TransportCapabilities {
    const c = this.client.capabilities()
    return {
      serverHash: c.serverHash.algorithm,
      stableIds: c.stableIds,
      casMutation: c.cas.replace && c.cas.delete,
      createOnly: c.createOnly.byName,
      resumableUpload: c.upload.resumable,
      rangeDownload: c.rangeDownload,
    }
  }

  private get provider() {
    return this.client.provider
  }

  private enc(name: string): string {
    let v = this.encodeCache.get(name)
    if (v === undefined) {
      v = cloud<{encoded: string}>(this.provider, 'nameEncode', {name}).encoded
      this.encodeCache.set(name, v)
    }
    return v
  }

  private dec(stored: string): string {
    return cloud<{name: string}>(this.provider, 'nameDecode', {stored}).name
  }

  private pathRef(segments: string[]): Ref {
    return {path: [...this.rootSegments, ...segments.map((s) => this.enc(s))].join('/')}
  }

  // ------------------------------------------------------------------------------------------------------- addressing

  /** The folder at [segments] under the root; with [create] the missing ones are made. Drive resolves ids by listing. */
  private async folderRef(segments: string[], create: boolean): Promise<Ref | null> {
    if (this.provider !== 'drive') {
      if (create) await this.ensurePath(segments)
      return this.pathRef(segments)
    }
    const all = [...this.rootSegments, ...segments]
    let ref: Ref = {id: 'root'}
    let key = ''
    for (const raw of all) {
      key += '/' + raw
      const cached = this.folders.get(key)
      if (cached) {
        ref = cached
        continue
      }
      const stored = this.enc(raw)
      const found = await this.client.list(ref, stored)
      const folders = found.entries.filter((e) => e.folder && e.name === stored)
      if (folders.length > 1) throw new CloudError({kind: 'Conflict', status: 0, code: 'duplicate-names', message: `Two folders named ${raw} exist; resolve them by hand.`}, 'resolve')
      if (folders.length === 0) {
        if (!create) return null
        const made = await this.client.createFolder(ref, stored)
        ref = {id: made.id}
      } else {
        ref = {id: folders[0]!.id}
      }
      this.folders.set(key, ref)
    }
    return ref
  }

  private async ensurePath(segments: string[]): Promise<void> {
    const all = [...this.rootSegments, ...segments.map((s) => this.enc(s))]
    for (let i = 1; i <= all.length; i++) {
      const p = all.slice(0, i).join('/')
      if (this.folders.has(p)) continue
      const parent: Ref = {path: all.slice(0, i - 1).join('/')}
      try {
        await this.client.createFolder(parent, all[i - 1]!)
      } catch (error) {
        // exists (or a racing writer made it): fine. Anything else is real.
        if (!(error instanceof CloudError && error.kind === 'Conflict')) throw error
      }
      this.folders.set(p, {path: p})
    }
  }

  /** Finds the file at library path [lib]. Null when it does not exist. */
  private async locate(lib: string, create = false): Promise<Located | null> {
    const parts = lib.split('/').filter(Boolean)
    const leaf = parts.pop()!
    const name = this.enc(leaf)
    const parent = await this.folderRef(parts, create)
    if (!parent) return null
    if (this.provider === 'drive') {
      const found = (await this.client.list(parent, name)).entries.filter((e) => e.name === name && !e.folder)
      if (found.length > 1) throw new CloudError({kind: 'Conflict', status: 0, code: 'duplicate-names', message: `Two files named ${leaf} exist.`}, 'resolve')
      if (found.length === 0) return {ref: {}, parent, name}
      return {ref: {id: found[0]!.id}, parent, name, entry: found[0]}
    }
    const ref: Ref = {path: [parent.path, name].filter(Boolean).join('/')}
    return {ref, parent, name}
  }

  // ------------------------------------------------------------------------------------------------------------ scan

  async scan(_root: string, onLine: (line: TransportInventoryLine) => void, signal?: AbortSignal): Promise<CloudScanResult> {
    let count = 0
    let complete = true
    const unreadable: string[] = []
    const collisions: string[] = []
    const rootRef = await this.folderRef([], false)
    if (!rootRef) return {count: 0, complete: true, unreadable, collisions, missingRoot: true}
    const stack: Array<{lib: string; ref: Ref}> = [{lib: '', ref: rootRef}]
    const rules = (name: string) => cloud<{key: string}>(this.provider, 'nameKey', {name}).key
    while (stack.length) {
      if (signal?.aborted) throw new Error('aborted')
      const {lib, ref} = stack.pop()!
      let res
      try {
        res = await this.client.list(ref)
      } catch (error) {
        if (error instanceof CloudError && lib === '' && error.kind === 'NotFound') return {count: 0, complete: true, unreadable, collisions, missingRoot: true}
        complete = false
        unreadable.push(lib || '/')
        continue
      }
      if (!res.complete) {
        complete = false
        unreadable.push(lib || '/')
        for (const f of res.failed) unreadable.push(f)
      }
      const seen = new Map<string, string>()
      for (const e of res.entries) {
        const name = this.dec(e.name)
        if (lib === '' && e.name === KEEPFIN_DIR) continue
        const path = lib ? lib + '/' + name : name
        const key = rules(name)
        const clash = seen.get(key)
        if (clash !== undefined && (this.provider === 'drive' ? true : clash !== name)) collisions.push(path)
        seen.set(key, name)
        if (e.folder) {
          stack.push({lib: path, ref: this.provider === 'drive' ? {id: e.id} : {path: [ref.path, e.name].filter(Boolean).join('/')}})
        } else {
          count++
          onLine({path, size: e.size ?? 0, mtimeMs: e.mtimeMs ?? 0, fileId: e.id ?? null, revision: e.etag ?? null})
        }
      }
    }
    return {count, complete, unreadable, collisions, missingRoot: false}
  }

  /** The file at library path [lib] as a listing would show it (id, revision, size, time), or null when it is not there. */
  async stat(lib: string): Promise<TransportInventoryLine | null> {
    const loc = await this.locate(lib)
    if (!loc) return null
    let entry: CloudEntry | null | undefined
    if (this.provider === 'drive') entry = loc.entry
    else {
      try {
        entry = await this.client.stat(loc.ref)
      } catch (error) {
        if (error instanceof CloudError && error.kind === 'NotFound') return null
        throw error
      }
    }
    if (!entry || entry.folder) return null
    return {path: lib, size: entry.size ?? 0, mtimeMs: entry.mtimeMs ?? 0, fileId: entry.id ?? null, revision: entry.etag ?? null}
  }
  // ----------------------------------------------------------------------------------------------------------- bytes

  /** [expectedContentId] * accepts bytes nobody pinned yet (a file another device put there); the SHA-256 found is returned either way. */
  async download(remotePath: string, stagedFile: string, expectedContentId: string, signal?: AbortSignal): Promise<string> {
    const loc = await this.locate(remotePath)
    if (!loc || (this.provider === 'drive' && !loc.entry)) throw new CloudError({kind: 'NotFound', status: 404}, 'download')
    return this.client.http.transfer(() => this.client.download(loc.ref, stagedFile, expectedContentId, {signal, resume: true, size: loc.entry?.size}))
  }

  async uploadNew(sourceFile: string, remotePath: string, expectedContentId: string, signal?: AbortSignal): Promise<{revision: string | null}> {
    const loc = await this.locate(remotePath, true)
    if (!loc) throw new CloudError({kind: 'NotFound', status: 404}, 'upload')
    if (this.provider === 'drive' && loc.entry) throw new CloudError({kind: 'Conflict', status: 0, code: 'exists'}, 'upload')
    const entry = await this.client.http.transfer(() => this.client.upload({file: sourceFile, contentId: expectedContentId, parent: loc.parent, name: loc.name, signal}))
    if (this.client.capabilities().duplicateNames) {
      // Drive has no create-only: if a racing writer made the same name, the earliest object keeps it and ours goes to the trash.
      const mine = (await this.client.list(loc.parent, loc.name)).entries.filter((e) => e.name === loc.name && !e.folder).sort((a, b) => (a.createdMs ?? 0) - (b.createdMs ?? 0) || a.id!.localeCompare(b.id!))
      if (mine.length > 1 && mine[0]!.id !== entry.id) {
        await this.client.trash({id: entry.id})
        throw new CloudError({kind: 'Conflict', status: 0, code: 'exists'}, 'upload')
      }
    }
    return {revision: entry.etag ?? null}
  }

  async replace(sourceFile: string, remotePath: string, expectedContentId: string, revision: string | null, signal?: AbortSignal): Promise<{revision: string | null; preservedAs: string | null}> {
    const caps = this.capabilities()
    const loc = await this.locate(remotePath)
    if (caps.casMutation && revision && loc && (this.provider !== 'drive' || loc.entry)) {
      const current = this.provider === 'webdav' ? null : await this.client.stat(loc.ref)
      if (this.provider !== 'webdav' && !current) throw new CloudError({kind: 'NotFound', status: 404}, 'replace')
      const ref: Ref = current?.id ? {id: current.id} : loc.ref
      const entry = await this.client.http.transfer(() => this.client.upload({file: sourceFile, contentId: expectedContentId, replace: {ref, etag: revision}, signal}))
      return {revision: entry.etag ?? null, preservedAs: null}
    }
    // No compare-and-swap (or no revision to swap against): preserve the displaced bytes, then create anew. Never overwrite in place.
    const trashed = loc && (loc.entry || this.provider !== 'drive') ? await this.moveToTrash(remotePath, revision).catch((error) => {
      if (error instanceof CloudError && error.kind === 'NotFound') return null
      throw error
    }) : null
    const up = await this.uploadNew(sourceFile, remotePath, expectedContentId, signal)
    return {revision: up.revision, preservedAs: trashed?.trashedAs ?? null}
  }

  async moveToTrash(remotePath: string, revision: string | null): Promise<{trashedAs: string}> {
    const loc = await this.locate(remotePath)
    if (!loc || (this.provider === 'drive' && !loc.entry)) throw new CloudError({kind: 'NotFound', status: 404}, 'trash')
    const stamp = `${this.now()}-${this.options.randomId?.() ?? Math.random().toString(36).slice(2, 8)}`
    const trashName = `${stamp}-${loc.name}`
    const trashRef = (await this.folderRef([KEEPFIN_DIR, 'trash'], true))!
    const casEtag = this.capabilities().casMutation && revision ? revision : undefined
    if (this.provider === 'drive') await this.client.move(loc.ref, {newParentId: trashRef.id, fromParentId: loc.parent.id, newName: trashName})
    else if (this.provider === 'graph') {
      const t = await this.client.stat(trashRef)
      await this.client.move(loc.ref, {newParentId: t!.id, newName: trashName, etag: casEtag})
    } else if (this.provider === 'dropbox') await this.client.move(loc.ref, {toPath: [trashRef.path, trashName].join('/')})
    else await this.client.move(loc.ref, {toPath: [trashRef.path, trashName].join('/'), etag: casEtag})
    return {trashedAs: `${KEEPFIN_DIR}/trash/${trashName}`}
  }

  /**
   * Renames or moves an object by id to another library path (no bytes are sent). Used when a copy archives or renames a file:
   * the object keeps its content and the cloud sees a move, not an upload plus a removal. Never replaces anything at [toLib].
   */
  async moveTo(fromLib: string, toLib: string, revision: string | null): Promise<{revision: string | null}> {
    const loc = await this.locate(fromLib)
    if (!loc || (this.provider === 'drive' && !loc.entry)) throw new CloudError({kind: 'NotFound', status: 404}, 'move')
    const parts = toLib.split('/').filter(Boolean)
    const leaf = this.enc(parts.pop()!)
    const destParent = await this.folderRef(parts, true)
    if (!destParent) throw new CloudError({kind: 'NotFound', status: 404}, 'move')
    const casEtag = this.capabilities().casMutation && revision ? revision : undefined
    let moved: CloudEntry
    if (this.provider === 'drive') moved = await this.client.move(loc.ref, {newParentId: destParent.id, fromParentId: loc.parent.id, newName: leaf})
    else if (this.provider === 'graph') {
      const t = await this.client.stat(destParent)
      moved = await this.client.move(loc.ref, {newParentId: t!.id, newName: leaf, etag: casEtag})
    } else if (this.provider === 'dropbox') moved = await this.client.move(loc.ref, {toPath: [destParent.path, leaf].filter(Boolean).join('/')})
    else moved = await this.client.move(loc.ref, {toPath: [destParent.path, leaf].filter(Boolean).join('/'), etag: casEtag})
    return {revision: moved.etag ?? null}
  }
  // ------------------------------------------------------------------------------------------------- the replica itself

  /** Creates the replica's folder and its `.keepfin` folder when they are not there yet. */
  async ensureRoot(): Promise<void> {
    await this.folderRef([KEEPFIN_DIR], true)
  }

  /** The names of the folders directly under library path [lib] (decoded), or null when [lib] does not exist. */
  async listChildFolders(lib: string): Promise<string[] | null> {
    const ref = await this.folderRef(lib.split('/').filter(Boolean), false)
    if (!ref) return null
    let res
    try {
      res = await this.client.list(ref)
    } catch (error) {
      if (error instanceof CloudError && error.kind === 'NotFound') return null
      throw error
    }
    return res.entries.filter((e) => e.folder).map((e) => this.dec(e.name))
  }

  /** The text of a small file at library path [lib] (folder.json), or null when it does not exist. */
  async readSmall(lib: string): Promise<string | null> {
    let loc
    try {
      loc = await this.locate(lib)
    } catch (error) {
      if (error instanceof CloudError && error.kind === 'NotFound') return null
      throw error
    }
    if (!loc || (this.provider === 'drive' && !loc.entry)) return null
    try {
      return await this.client.readText(loc.ref)
    } catch (error) {
      if (error instanceof CloudError && error.kind === 'NotFound') return null
      throw error
    }
  }

  // ------------------------------------------------------------------------------------------------ lock, generations

  async acquireLock(owner: {device: string; run: string}, nowMs: number): Promise<TransportLock> {
    const stale = this.options.staleLockMs ?? 5 * 60_000
    const dir = (await this.folderRef([KEEPFIN_DIR], true))!
    const name = this.enc('lock.json')
    const content = (beat: number) => JSON.stringify({device: owner.device, run: owner.run, heartbeatAt: beat})
    const token = `${owner.device}:${owner.run}`
    let mine: CloudEntry | null = null
    const tryCreate = async (): Promise<boolean> => {
      if (this.provider === 'drive') {
        const before = (await this.client.list(dir, name)).entries.filter((e) => e.name === name)
        if (before.length) return false
      }
      try {
        mine = await this.client.createSmall(dir, name, content(nowMs))
      } catch (error) {
        if (error instanceof CloudError && error.kind === 'Conflict') return false
        throw error
      }
      if (this.provider === 'drive') {
        // No create-only by name: list afterwards; the earliest createdTime wins, the loser withdraws.
        const all = (await this.client.list(dir, name)).entries.filter((e) => e.name === name).sort((a, b) => (a.createdMs ?? 0) - (b.createdMs ?? 0) || a.id!.localeCompare(b.id!))
        if (all[0]!.id !== mine!.id) {
          await this.client.trash({id: mine!.id})
          mine = null
          return false
        }
      }
      return true
    }
    let held = await tryCreate()
    if (!held) {
      // Is the existing lock stale? Then take it over: move it away by id (compare-and-swap where possible), then create again.
      const lockPath = KEEPFIN_DIR + '/lock.json'
      const loc = await this.locate(lockPath)
      if (loc && (loc.entry || this.provider !== 'drive')) {
        let beat = 0
        try {
          const text = await this.client.readText(loc.ref)
          beat = (JSON.parse(text) as {heartbeatAt?: number}).heartbeatAt ?? 0
        } catch {
          beat = 0
        }
        if (nowMs - beat > stale) {
          const st = this.provider === 'drive' ? loc.entry : await this.client.stat(loc.ref)
          try {
            await this.moveToTrash(lockPath, st?.etag ?? null)
            held = await tryCreate()
          } catch (error) {
            if (!(error instanceof CloudError)) throw error
            held = false
          }
        }
      }
    }
    const client = this.client
    const self = this
    const lock: TransportLock = {
      held,
      token,
      async heartbeat() {
        if (!held || !mine) return
        const text = content(self.now())
        const caps = client.capabilities()
        if (caps.cas.replace && mine.etag) {
          mine = await client.replaceSmall(mine.id ? {id: mine.id} : {path: [dir.path, name].join('/')}, mine.etag, text)
        } else if (self.provider === 'drive') {
          mine = await client.replaceSmall({id: mine.id}, mine.etag ?? '', text, true)
        } else {
          // Advisory object, own file: withdraw and create again; losing the race means the lock is lost.
          await self.moveToTrash(KEEPFIN_DIR + '/lock.json', null)
          mine = await client.createSmall(dir, name, text)
        }
      },
      async release() {
        if (!held || !mine) return
        held = false
        lock.held = false
        await self.moveToTrash(KEEPFIN_DIR + '/lock.json', client.capabilities().cas.replace ? mine.etag ?? null : null).catch((error) => {
          if (!(error instanceof CloudError && error.kind === 'NotFound')) throw error
        })
        mine = null
      },
    }
    return lock
  }

  async readGeneration(): Promise<number> {
    const dir = await this.folderRef([KEEPFIN_DIR, 'generations'], false)
    if (!dir) return 0
    let res
    try {
      res = await this.client.list(dir)
    } catch (error) {
      if (error instanceof CloudError && error.kind === 'NotFound') return 0
      throw error
    }
    if (!res.complete) throw new CloudError({kind: 'Transient', status: 0, code: 'incomplete-listing'}, 'generation')
    let max = 0
    for (const e of res.entries) {
      const n = Number(/^(\d+)-/.exec(this.dec(e.name))?.[1])
      if (Number.isFinite(n) && n > max) max = n
    }
    return max
  }

  async appendGeneration(generation: number, replicaId: string): Promise<boolean> {
    const dir = (await this.folderRef([KEEPFIN_DIR, 'generations'], true))!
    const name = this.enc(`${String(generation).padStart(10, '0')}-${replicaId}.json`)
    if (this.provider === 'drive' && (await this.client.list(dir, name)).entries.some((e) => e.name === name)) return false
    try {
      await this.client.createSmall(dir, name, JSON.stringify({generation, replicaId}))
      return true
    } catch (error) {
      if (error instanceof CloudError && error.kind === 'Conflict') return false
      throw error
    }
  }
}
