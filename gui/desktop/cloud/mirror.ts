// The local working image of a cloud copy. The copies runner works on folders (inventory, hashing, versions, trash, journal), so a
// cloud copy runs through the same runner over a device-local folder that mirrors the replica, and CloudTransport carries the
// bytes in both directions:
//   pull: before a run, the mirror is brought level with the replica (new and changed objects are downloaded and staged, then
//         published; a listing that is not complete changes nothing and removes nothing);
//   push: after a run, whatever the runner changed in the mirror is sent: new files are uploaded (create-only), changed files are
//         replaced by compare-and-swap (or the old bytes are preserved), and a file the runner moved (archived to the versions
//         folder, renamed) is a move on the cloud, never an upload plus a removal. Nothing is deleted in place.
// The state file records, for every path, what the mirror and the replica last agreed on (size, mtime, revision, content id), so
// work that was interrupted is picked up again and a change on either side is never mistaken for the other's.
// Cost: the mirror holds a full local copy of the replica on this computer (the planner and the hash checks need real bytes).
import crypto from 'node:crypto'
import fs from 'node:fs'
import fsp from 'node:fs/promises'
import path from 'node:path'
import {renameWithRetry} from '../fsutil.js'
import {readJson} from '../store/atomic.js'
import type {TransportInventoryLine} from '../copies/transport.js'
import {CloudError} from './http.js'
import type {CloudScanResult, CloudTransport} from './transport.js'

export interface SyncedFile {
  size: number
  mtimeMs: number
  revision: string | null
  contentId: string
}

export interface MirrorState {
  version: 1
  files: Record<string, SyncedFile>
}

export interface MirrorProgress {
  phase: 'listing' | 'downloading' | 'uploading' | 'finishing'
  done: number
  total: number
  doneBytes: number
  totalBytes: number
  current: string | null
}

/** What the mirror asks of a replica: a cloud account, or (dev-docs/plans/KEEPFIN-NETWORK-SPEC.md 5) a paired device's folder. */
export type MirrorTransport = Pick<CloudTransport, 'scan' | 'download' | 'uploadNew' | 'replace' | 'moveToTrash' | 'moveTo'> & Partial<Pick<CloudTransport, 'beginUploads' | 'endUploads' | 'prune'>>

export interface MirrorOptions {
  mirror: string
  /** The device-local state file (outside the mirror). */
  stateFile: string
  /** Staging folder for downloads (outside the mirror). */
  staging: string
  /** Library files: the replica's root. */
  media: MirrorTransport
  /** The replica's `.keepfin` folder, listed as a root of its own because the media listing skips it. */
  meta: MirrorTransport
  signal?: AbortSignal
  onProgress?: (p: MirrorProgress) => void
  parallelism?: number
  now?: () => number
  /**
   * Only the copy's own bookkeeping under `.keepfin` is synced (folder.json, names, catalogues, manifests, ops): the media are not
   * mirrored (copies run directly over the transport, copies/remote.ts), and neither are the bytes of earlier versions and trash entries.
   */
  metaOnly?: boolean
}

export interface PullReport {
  complete: boolean
  missingRoot: boolean
  downloaded: number
  bytes: number
  removed: number
  conflicts: number
  /** Remote names this computer cannot store as files (reserved characters); left alone. */
  unmirrored: string[]
  /** Removals were skipped because the listing looked wrong. */
  suspicious: boolean
  unreadable: string[]
  collisions: string[]
}

export interface PushFailure {
  kind: 'upload' | 'replace' | 'move' | 'trash'
  path: string
  reason: string
  cloudKind?: string
}

export interface PushReport {
  uploaded: number
  replaced: number
  moved: number
  trashed: number
  /** Redundant segments and manifests removed for good (not trash entries). */
  pruned?: number
  bytes: number
  failed: PushFailure[]
  /** Stopped early: the account is full or the sign-in is gone. */
  stopped: 'quota' | 'signin' | null
}

const META = '.keepfin'
/** What the account keeps for itself under `.keepfin`: the advisory lock, the generation records, and the flat trash entries the transport moves displaced files to. */
const skipRemoteMeta = (p: string) => p === 'lock.json' || p.startsWith('generations/') || /^trash\/\d{10,}-[a-z0-9]{3,}-[^/]*$/.test(p)
const localSkip = (rel: string) => rel === `${META}/lock` || rel.startsWith(`${META}/staging/`) || rel.endsWith('.tmp') || rel.includes('.kftmp')
/** Under `.keepfin`, the bytes of earlier versions and trash entries (their catalogues are bookkeeping and are synced). */
export const isArchiveBytes = (rel: string) => (rel.startsWith(`${META}/versions/`) && rel !== `${META}/versions/catalogue.jsonl`) || (rel.startsWith(`${META}/trash/`) && rel !== `${META}/trash/index.jsonl`)
const inScope = (o: {metaOnly?: boolean}, rel: string) => !o.metaOnly || (rel.startsWith(`${META}/`) && !isArchiveBytes(rel))
const WIN_BAD =/[<>:"|?*\\\u0000-\u001f]/
const WIN_RESERVED = /^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\.|$)/i

/** True when every segment of a remote path can be a file name here and cannot climb out of the mirror. */
export function safeRelative(rel: string): boolean {
  if (!rel || rel.length > 900) return false
  for (const seg of rel.split('/')) {
    if (!seg || seg === '.' || seg === '..' || seg.includes('\0')) return false
    if (process.platform === 'win32' && (WIN_BAD.test(seg) || /[. ]$/.test(seg) || WIN_RESERVED.test(seg))) return false
  }
  return true
}

export async function pool<T>(items: T[], size: number, fn: (item: T) => Promise<void>, signal?: AbortSignal): Promise<void> {
  let next = 0
  let failure: unknown
  const worker = async () => {
    while (failure === undefined && next < items.length) {
      if (signal?.aborted) return
      const item = items[next++]!
      try {
        await fn(item)
      } catch (error) {
        failure ??= error
      }
    }
  }
  await Promise.all(Array.from({length: Math.max(1, Math.min(size, items.length))}, worker))
  if (failure !== undefined) throw failure
}

export async function sha256File(file: string): Promise<string> {
  const hash = crypto.createHash('sha256')
  await new Promise<void>((resolve, reject) => {
    const s = fs.createReadStream(file)
    s.on('data', (c) => hash.update(c))
    s.on('error', reject)
    s.on('end', () => resolve())
  })
  return 'sha256:' + hash.digest('hex')
}

const same = (s: SyncedFile, st: {size: number; mtimeMs: number}) => st.size === s.size && Math.abs(st.mtimeMs - s.mtimeMs) < 1

export async function readState(file: string): Promise<MirrorState> {
  const saved = await readJson<MirrorState>(file).catch(() => undefined)
  return saved?.version === 1 && saved.files ? saved : {version: 1, files: {}}
}

/** Compact (a library can have hundreds of thousands of files) and atomic. */
export async function saveState(file: string, state: MirrorState): Promise<void> {
  await fsp.mkdir(path.dirname(file), {recursive: true})
  const temp = `${file}.${process.pid}.${Math.random().toString(36).slice(2)}.tmp`
  await fsp.writeFile(temp, JSON.stringify(state), 'utf8')
  try {
    await renameWithRetry(temp, file)
  } catch (error) {
    await fsp.rm(temp, {force: true}).catch(() => undefined)
    throw error
  }
}

const abs = (root: string, rel: string) => path.join(root, ...rel.split('/'))

const OPS_FILE = /^\.keepfin\/ops\/([^/]+)\/(\d{16})-(\d{16})\.seg$/
const MANIFEST_FILE = /^(\.keepfin\/places\/[^/]+)\/manifest-(\d+)\.jsonl$/
/** How many manifests a place keeps (the same number as copies/manifest.ts, which writes them). */
const MANIFESTS_KEPT = 3

/**
 * Which of [candidates] a file in [held] makes redundant: a segment whose range a larger segment of the same actor holds (the
 * larger one decided by the store that merged them, op for op), or a manifest with at least three newer ones. Only these two
 * kinds under `.keepfin` are ever listed; everything else a mirror loses stays an ordinary removal
 * (dev-docs/plans/KEEPFIN-DATA-COMPACTION.md R6).
 */
export function redundantIn(candidates: Iterable<string>, held: Iterable<string>): Set<string> {
  const ranges = new Map<string, [number, number][]>()
  const numbers = new Map<string, number[]>()
  for (const p of held) {
    const s = OPS_FILE.exec(p)
    if (s) ranges.set(s[1]!, [...(ranges.get(s[1]!) ?? []), [Number(s[2]), Number(s[3])]])
    const m = MANIFEST_FILE.exec(p)
    if (m) numbers.set(m[1]!, [...(numbers.get(m[1]!) ?? []), Number(m[2])])
  }
  const out = new Set<string>()
  for (const p of candidates) {
    const s = OPS_FILE.exec(p)
    if (s) {
      const first = Number(s[2])
      const last = Number(s[3])
      if ((ranges.get(s[1]!) ?? []).some(([f, l]) => f <= first && l >= last && (f !== first || l !== last))) out.add(p)
      continue
    }
    const m = MANIFEST_FILE.exec(p)
    if (m && (numbers.get(m[1]!) ?? []).filter((n) => n > Number(m[2])).length >= MANIFESTS_KEPT) out.add(p)
  }
  return out
}

export function friendlyCloudError(error: unknown): string {
  if (error instanceof CloudError) {
    switch (error.kind) {
      case 'NeedsSignIn':
        return 'The sign-in has expired.'
      case 'QuotaFull':
        return 'The account is full.'
      case 'RateLimited':
        return 'The provider asked Keepfin to slow down.'
      case 'NotFound':
        return 'It was not found on the account.'
      case 'Conflict':
        return error.info.code === 'exists' || error.info.code === 'duplicate-names' ? 'Something with this name is already on the account.' : 'It changed on the account in the meantime.'
      case 'Transient':
        return 'The connection failed.'
      default:
        return error.info.message ? `The provider refused it (${error.info.message.slice(0, 120)}).` : 'The provider refused it.'
    }
  }
  return error instanceof Error ? error.message : String(error)
}

// ------------------------------------------------------------------------------------------------------------------ pull

export async function pull(o: MirrorOptions): Promise<PullReport> {
  const report: PullReport = {complete: true, missingRoot: false, downloaded: 0, bytes: 0, removed: 0, conflicts: 0, unmirrored: [], suspicious: false, unreadable: [], collisions: []}
  const progress = (p: Partial<MirrorProgress>) => o.onProgress?.({phase: 'listing', done: 0, total: 0, doneBytes: 0, totalBytes: 0, current: null, ...p})
  progress({})
  const remote = new Map<string, TransportInventoryLine>()
  const mediaScan = o.metaOnly ? ({count: 0, complete: true, unreadable: [], collisions: [], missingRoot: true} as CloudScanResult) : ((await o.media.scan('', (l) => remote.set(l.path, l), o.signal)) as CloudScanResult)
  const metaScan = (await o.meta.scan('', (l) => {
    const rel = `${META}/${l.path}`
    if (!skipRemoteMeta(l.path) && inScope(o, rel)) remote.set(rel, {...l, path: rel})
  }, o.signal)) as CloudScanResult
  report.complete = mediaScan.complete && metaScan.complete
  report.missingRoot = o.metaOnly ? metaScan.missingRoot : mediaScan.missingRoot && metaScan.missingRoot
  report.unreadable = [...mediaScan.unreadable, ...metaScan.unreadable]
  report.collisions = [...mediaScan.collisions, ...metaScan.collisions]

  const state = await readState(o.stateFile)
  await fsp.mkdir(o.mirror, {recursive: true})
  await fsp.mkdir(o.staging, {recursive: true})

  type Job = {line: TransportInventoryLine; conflict: boolean}
  const jobs: Job[] = []
  for (const [rel, line] of remote) {
    if (!safeRelative(rel)) {
      report.unmirrored.push(rel)
      continue
    }
    const synced = state.files[rel]
    const st = await fsp.lstat(abs(o.mirror, rel)).catch(() => null)
    if (st && !st.isFile()) {
      report.unmirrored.push(rel)
      continue
    }
    if (synced) {
      const localSame = st !== null && same(synced, st)
      const remoteSame = line.revision !== null && line.revision === synced.revision
      if (remoteSame && localSame) continue
      if (remoteSame) continue // changed here only (or removed here): the push sends it
      if (line.sha256 && line.size === synced.size && synced.contentId === `sha256:${line.sha256.toLowerCase()}`) {
        // a new revision with the same bytes (Drive bumps `version` for changes that are not content): note it, download nothing
        state.files[rel] = {...synced, revision: line.revision}
        continue
      }
      // the replica changed
      if (st === null || localSame) {
        jobs.push({line, conflict: false})
      } else jobs.push({line, conflict: true}) // changed on both sides
    } else if (st === null) {
      jobs.push({line, conflict: false})
    } else {
      // here and on the replica, never reconciled (an earlier run stopped, or a folder that was already there): compare the bytes
      jobs.push({line, conflict: true})
    }
  }

  const totalBytes = jobs.reduce((n, j) => n + j.line.size, 0)
  let done = 0
  let doneBytes = 0
  let n = 0
  await pool(
    jobs,
    o.parallelism ?? 4,
    async ({line, conflict}) => {
      const rel = line.path
      const part = path.join(o.staging, `p${n++}.part`)
      progress({phase: 'downloading', done, total: jobs.length, doneBytes, totalBytes, current: rel})
      const transport = rel.startsWith(META + '/') ? o.meta : o.media
      const remotePath = rel.startsWith(META + '/') ? rel.slice(META.length + 1) : rel
      const contentId = await transport.download(remotePath, part, '*', o.signal)
      const target = abs(o.mirror, rel)
      const here = await fsp.lstat(target).catch(() => null)
      if (conflict && here) {
        if ((await sha256File(target)) === contentId) {
          // the same bytes: nothing to keep apart
          await fsp.rm(part, {force: true})
          state.files[rel] = {size: here.size, mtimeMs: here.mtimeMs, revision: line.revision, contentId}
          done++
          return
        }
        // both changed: the local bytes are kept under .keepfin/conflicts and travel to the replica as a file of their own
        const keep = abs(o.mirror, `${META}/conflicts/${(o.now ?? Date.now)()}-${path.basename(rel)}`)
        await fsp.mkdir(path.dirname(keep), {recursive: true})
        await fsp.rename(target, keep)
        report.conflicts++
      }
      await fsp.mkdir(path.dirname(target), {recursive: true})
      if (line.mtimeMs > 0) await fsp.utimes(part, new Date(), new Date(line.mtimeMs)).catch(() => undefined)
      await fsp.rename(part, target)
      const st = await fsp.stat(target)
      state.files[rel] = {size: st.size, mtimeMs: st.mtimeMs, revision: line.revision, contentId}
      report.downloaded++
      report.bytes += st.size
      done++
      doneBytes += st.size
    },
    o.signal,
  ).finally(() => saveState(o.stateFile, state).catch(() => undefined))

  // what disappeared from the replica: only from a listing that was complete, and not a listing that suddenly shows nothing
  if (report.complete && !report.missingRoot) {
    const known = Object.keys(state.files).filter((p) => inScope(o, p))
    const allGone = known.filter((p) => !remote.has(p) && safeRelative(p))
    // files another device pruned because a larger one covers them are gone on purpose: they do not count as a mass removal
    const pruned = redundantIn(allGone, remote.keys())
    const gone = allGone.filter((p) => !pruned.has(p))
    for (const rel of pruned) {
      const synced = state.files[rel]!
      const st = await fsp.lstat(abs(o.mirror, rel)).catch(() => null)
      if (st && st.isFile() && same(synced, st)) {
        await fsp.rm(abs(o.mirror, rel), {force: true})
        report.removed++
      }
      delete state.files[rel]
    }
    if (gone.length > 0 && (remote.size === 0 || (gone.length > 100 && gone.length > known.length / 2))) {
      report.suspicious = true
    } else {
      for (const rel of gone) {
        const synced = state.files[rel]!
        const st = await fsp.lstat(abs(o.mirror, rel)).catch(() => null)
        if (st && st.isFile() && same(synced, st)) {
          await fsp.rm(abs(o.mirror, rel), {force: true})
          report.removed++
        }
        delete state.files[rel]
      }
    }
  }
  await saveState(o.stateFile, state)
  return report
}

// ------------------------------------------------------------------------------------------------------------------ push

async function walk(root: string, metaOnly = false): Promise<{rel: string; size: number; mtimeMs: number}[]> {
  const out: {rel: string; size: number; mtimeMs: number}[] = []
  const stack = [metaOnly ? META : '']
  while (stack.length) {
    const dir = stack.pop()!
    const entries = await fsp.readdir(abs(root, dir) || root, {withFileTypes: true}).catch(() => [])
    for (const e of entries) {
      const rel = dir ? `${dir}/${e.name}` : e.name
      if (localSkip(rel)) continue
      if (e.isDirectory()) stack.push(rel)
      else if (e.isFile()) {
        const st = await fsp.stat(abs(root, rel)).catch(() => null)
        if (st) out.push({rel, size: st.size, mtimeMs: st.mtimeMs})
      }
    }
  }
  return out
}

export async function push(o: MirrorOptions): Promise<PushReport> {
  const report: PushReport = {uploaded: 0, replaced: 0, moved: 0, trashed: 0, pruned: 0, bytes: 0, failed: [], stopped: null}
  const progress = (p: Partial<MirrorProgress>) => o.onProgress?.({phase: 'uploading', done: 0, total: 0, doneBytes: 0, totalBytes: 0, current: null, ...p})
  const state = await readState(o.stateFile)
  const files = (await walk(o.mirror, o.metaOnly)).filter((f) => inScope(o, f.rel))
  const onDisk = new Map(files.map((f) => [f.rel, f]))

  type Item = {rel: string; size: number; mtimeMs: number; contentId: string}
  const added: Item[] = []
  const changed: Item[] = []
  for (const f of files) {
    const synced = state.files[f.rel]
    if (synced && same(synced, f)) continue
    const contentId = await sha256File(abs(o.mirror, f.rel)).catch(() => null)
    if (!contentId) continue
    if (synced && synced.contentId === contentId) {
      state.files[f.rel] = {...synced, size: f.size, mtimeMs: f.mtimeMs}
      continue
    }
    ;(synced ? changed : added).push({rel: f.rel, size: f.size, mtimeMs: f.mtimeMs, contentId})
  }
  const removed = Object.keys(state.files).filter((p) => !onDisk.has(p) && inScope(o, p))

  // moves: a new file with the content that a removed or a changed file had is that file, moved (the runner archives the old bytes
  // next to the file it replaces; the account sees a move and then a new file, never the old bytes sent again)
  const byContent = new Map<string, string[]>()
  for (const p of [...removed, ...changed.map((c) => c.rel)]) {
    const key = `${state.files[p]!.contentId}|${state.files[p]!.size}`
    byContent.set(key, [...(byContent.get(key) ?? []), p])
  }
  const moves: {from: string; item: Item}[] = []
  const uploads: Item[] = []
  const replaced = new Map<string, Item>(changed.map((c) => [c.rel, c]))
  for (const item of added) {
    const list = byContent.get(`${item.contentId}|${item.size}`)
    const from = list?.shift()
    if (from) {
      moves.push({from, item})
      // a changed file that was moved away is a new file at its own path now
      const again = replaced.get(from)
      if (again) {
        replaced.delete(from)
        uploads.push(again)
      }
    } else uploads.push(item)
  }
  const moved = new Set(moves.map((m) => m.from))
  const trashes = removed.filter((p) => !moved.has(p))
  const replacing = [...replaced.values()]

  const total = moves.length + uploads.length + replacing.length + trashes.length
  const totalBytes = [...uploads, ...replacing].reduce((n, i) => n + i.size, 0)
  let done = 0
  let doneBytes = 0
  const tick = (current: string) => progress({done, total, doneBytes, totalBytes, current})
  const fail = (kind: PushFailure['kind'], rel: string, error: unknown) => {
    if (error instanceof CloudError && error.kind === 'NeedsSignIn') throw error
    if (error instanceof CloudError && error.kind === 'QuotaFull') report.stopped = 'quota'
    report.failed.push({kind, path: rel, reason: friendlyCloudError(error), ...(error instanceof CloudError ? {cloudKind: error.kind} : {})})
  }
  let sinceSave = 0
  const saved = async () => {
    if (++sinceSave >= 100) {
      sinceSave = 0
      await saveState(o.stateFile, state).catch(() => undefined)
    }
  }
  const remoteOf = (rel: string) => (rel.startsWith(META + '/') ? {t: o.meta, p: rel.slice(META.length + 1)} : {t: o.media, p: rel})

  try {
    // moves first, one at a time: a folder that must exist is created by the first one that needs it
    for (const m of moves) {
      if (o.signal?.aborted) break
      tick(m.item.rel)
      const from = state.files[m.from]!
      try {
        // the media transport addresses the whole replica, `.keepfin` included, so a file can move between the two areas
        const res = await o.media.moveTo(m.from, m.item.rel, from.revision)
        delete state.files[m.from]
        state.files[m.item.rel] = {size: m.item.size, mtimeMs: m.item.mtimeMs, revision: res.revision, contentId: m.item.contentId}
        report.moved++
      } catch (error) {
        if (error instanceof CloudError && error.kind === 'NotFound') {
          // gone from the replica already: send the bytes as a new file
          uploads.push(m.item)
          delete state.files[m.from]
        } else fail('move', m.item.rel, error)
      }
      done++
      await saved()
    }

    const upload = async (item: Item, replacing: boolean) => {
      if (o.signal?.aborted || report.stopped) return
      tick(item.rel)
      const r = remoteOf(item.rel)
      const file = abs(o.mirror, item.rel)
      try {
        const out = replacing ? await r.t.replace(file, r.p, item.contentId, state.files[item.rel]?.revision ?? null, o.signal) : await r.t.uploadNew(file, r.p, item.contentId, o.signal)
        const st = await fsp.stat(file)
        state.files[item.rel] = {size: item.size, mtimeMs: st.size === item.size ? st.mtimeMs : item.mtimeMs, revision: out.revision, contentId: item.contentId}
        if (replacing) report.replaced++
        else report.uploaded++
        report.bytes += item.size
        doneBytes += item.size
      } catch (error) {
        if (o.signal?.aborted) return
        fail(replacing ? 'replace' : 'upload', item.rel, error)
      }
      done++
      await saved()
    }
    // small files and the ones the metadata needs first are not special: a pool of four, as the account allows
    // Drive lists a folder once for the files going into it, and checks for racing duplicates once per folder at the end
    const sending = [...uploads, ...replacing]
    o.media.beginUploads?.(sending.filter((i) => !i.rel.startsWith(META + '/')).map((i) => i.rel))
    o.meta.beginUploads?.(sending.filter((i) => i.rel.startsWith(META + '/')).map((i) => i.rel.slice(META.length + 1)))
    try {
      await pool(uploads, o.parallelism ?? 4, (i) => upload(i, false), o.signal)
      await pool(replacing, o.parallelism ?? 4, (i) => upload(i, true), o.signal)
    } finally {
      const lost = [...((await o.media.endUploads?.()) ?? []), ...((await o.meta.endUploads?.()) ?? []).map((p) => META + '/' + p)]
      for (const rel of lost) {
        const item = uploads.find((u) => u.rel === rel)
        delete state.files[rel]
        if (item) {
          report.uploaded--
          report.bytes -= item.size
        }
        fail('upload', rel, new CloudError({kind: 'Conflict', status: 0, code: 'exists'}, 'upload'))
      }
    }

    // A segment a larger one covers, or an old manifest, is pruned for good, but only while the file that makes it redundant is already
    // on the replica; anything else the runner removed goes to the trash as before.
    const redundant = redundantIn(trashes, Object.keys(state.files).filter((p) => onDisk.has(p)))
    for (const rel of trashes) {
      if (o.signal?.aborted) break
      tick(rel)
      const r = remoteOf(rel)
      try {
        if (redundant.has(rel) && r.t.prune) {
          await r.t.prune(r.p, state.files[rel]!.revision)
          report.pruned = (report.pruned ?? 0) + 1
        } else {
          await r.t.moveToTrash(r.p, state.files[rel]!.revision)
          report.trashed++
        }
        delete state.files[rel]
      } catch (error) {
        if (error instanceof CloudError && error.kind === 'NotFound') delete state.files[rel]
        else fail('trash', rel, error)
      }
      done++
      await saved()
    }
  } catch (error) {
    if (error instanceof CloudError && error.kind === 'NeedsSignIn') report.stopped = 'signin'
    else throw error
  } finally {
    await saveState(o.stateFile, state).catch(() => undefined)
  }
  return report
}
