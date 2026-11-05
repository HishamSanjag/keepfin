// One planning pass of S into T (dev-docs/plans/LIBRARY-MERGE-CONTRACT.md section 5): scans T and S into the scratch database, feeds the
// shared core's planner page by page in (item.add HLC, id) order, re-hashes the files it asks for (streaming, never loading a file
// whole), and streams what it emits to files. The dry run writes nothing but counts and the first review entries.
import {createHash} from 'node:crypto'
import fs from 'node:fs'
import fsp from 'node:fs/promises'
import path from 'node:path'
import {DatabaseSync} from 'node:sqlite'
import {createMergePlan, mergeEntityId, type ArtSource, type BatchOut, type MapLine, type Metadata, type ReviewLine, type SourceItem, type TargetItem} from '../../shared/core/merge.js'
import type {MergeMeta, ScanRow} from '../store/mergeScan.js'
import {Scratch} from './scratch.js'
import type {MergeDeps, MergeStore} from './types.js'

export const PAGE = 200
const REVIEW_KEEP = 200

export interface PlanResult {
  sItems: number
  /** Items in the other library (T), for the before → after picture. */
  tItems: number
  batches: number
  drafts: number
  linked: number
  added: number
  keptDuplicate: number
  missing: number
  differing: number
  playlists: number
  spaces: number
  review: (ReviewLine & {title?: string})[]
  reviewTotal: number
}

/** Hash results, kept across the dry run and the real run so a changed file is read once. */
export class HashCache {
  private readonly db: DatabaseSync

  constructor(file: string) {
    fs.mkdirSync(path.dirname(file), {recursive: true})
    this.db = new DatabaseSync(file)
    this.db.exec('PRAGMA journal_mode = WAL; PRAGMA synchronous = NORMAL; CREATE TABLE IF NOT EXISTS h (path TEXT NOT NULL PRIMARY KEY, size INTEGER NOT NULL, mtime INTEGER NOT NULL, hash TEXT NOT NULL) WITHOUT ROWID')
  }

  get(file: string, size: number, mtime: number): string | undefined {
    const row = this.db.prepare('SELECT size, mtime, hash FROM h WHERE path = ?').get(file) as {size: number; mtime: number; hash: string} | undefined
    return row && row.size === size && row.mtime === mtime ? row.hash : undefined
  }

  put(file: string, size: number, mtime: number, hash: string): void {
    this.db.prepare('INSERT OR REPLACE INTO h (path, size, mtime, hash) VALUES (?, ?, ?, ?)').run(file, size, mtime, hash)
  }

  close(): void {
    try {
      this.db.close()
    } catch {
      // already closed
    }
  }
}

/** The content id of a file, read as a stream. [size] is the size that was read (what the hash describes). */
export async function hashFile(file: string): Promise<{hash: string; size: number}> {
  const hash = createHash('sha256')
  let size = 0
  await new Promise<void>((resolve, reject) => {
    const stream = fs.createReadStream(file)
    stream.on('data', (chunk) => {
      hash.update(chunk as Buffer)
      size += (chunk as Buffer).length
    })
    stream.on('error', reject)
    stream.on('end', () => resolve())
  })
  return {hash: `sha256:${hash.digest('hex')}`, size}
}

export const canonical = (value: unknown): string =>
  value === null || typeof value !== 'object'
    ? JSON.stringify(value)
    : Array.isArray(value)
      ? `[${value.map(canonical).join(',')}]`
      : `{${Object.keys(value as object)
          .sort()
          .filter((k) => (value as Record<string, unknown>)[k] !== undefined)
          .map((k) => `${JSON.stringify(k)}:${canonical((value as Record<string, unknown>)[k])}`)
          .join(',')}}`

export const sha256Hex = (text: string) => createHash('sha256').update(text, 'utf8').digest('hex')

type Lines = {write(line: string): void; close(): void}

function lineFile(file: string): Lines {
  fs.mkdirSync(path.dirname(file), {recursive: true})
  const fd = fs.openSync(file, 'w')
  return {
    write: (line) => void fs.writeSync(fd, `${line}\n`),
    close: () => {
      fs.fsyncSync(fd)
      fs.closeSync(fd)
    },
  }
}

export function targetItemOf(row: ScanRow): TargetItem {
  return {
    id: row.id,
    added: row.added,
    revisionHash: row.head?.content ?? null,
    size: row.head?.size ?? null,
    mtimeHint: null,
    placements: row.placements,
    fieldClocks: row.clocks,
    fields: row.fields,
    plays: row.plays,
    favourite: row.favourite,
    userTags: row.tags,
  } as TargetItem
}

export interface PlanInput {
  deps: MergeDeps
  mergeId: string
  /** B's new actor and replica in T. */
  actor: string
  replicaId: string
  /** B's replica in S (the placements that are "on this device"). */
  sReplica: string
  root: string
  s: MergeStore
  t: MergeStore
  keep: 'mine' | 'theirs'
  /** Where plan files go; undefined for the dry run. */
  planDir?: string
  work: string
  onProgress?(label: string, done: number, total: number): void
  /** The S art folder (records root's art/) to turn into copy instructions, when there is one. */
  sArtDir?: string
  /** The reverse direction (Separate): [s] is T, only B's additions after the activation are described, into [t] (S). */
  reverse?: {bActorInT: string; activationSeq: number}
}

function placementFile(input: PlanInput, placement: string): string | undefined {
  return input.deps.files?.resolve(placement) ?? path.join(input.root, ...placement.split('/'))
}

export async function runPlan(input: PlanInput, hashes: HashCache): Promise<PlanResult> {
  const {deps} = input
  const scratch = new Scratch(path.join(input.work, `scratch-${input.planDir ? 'plan' : 'dry'}.db`))
  const result: PlanResult = {sItems: 0, tItems: 0, batches: 0, drafts: 0, linked: 0, added: 0, keptDuplicate: 0, missing: 0, differing: 0, playlists: 0, spaces: 0, review: [], reviewTotal: 0}
  const files = input.planDir ? {batches: lineFile(path.join(input.planDir, 'batches.jsonl')), map: lineFile(path.join(input.planDir, 'map.jsonl')), review: lineFile(path.join(input.planDir, 'review.jsonl')), art: lineFile(path.join(input.planDir, 'art.jsonl')), paths: lineFile(path.join(input.planDir, 'paths.jsonl'))} : undefined
  try {
    // ---- T: the hash index and the entities' names
    input.onProgress?.('Reading the other library', 0, 0)
    let tCount = 0
    await input.t.scan((rows) => {
      scratch.tx(() => {
        for (const row of rows) scratch.addTarget(targetItemOf(row))
      })
      tCount += rows.length
      input.onProgress?.('Reading the other library', tCount, 0)
    })
    const tMeta = await input.t.meta()
    for (const space of tMeta.spaces) if (typeof space.fields.name === 'string') scratch.addName('space', space.fields.name, space.id)
    for (const playlist of tMeta.playlists) if (typeof playlist.fields.name === 'string') scratch.addName('playlist', playlist.fields.name, playlist.id)
    const orders = tMeta.spaces.map((s) => (typeof s.fields.order === 'number' ? s.fields.order : -1))
    const firstSpaceOrder = Math.max(tMeta.spaces.length - 1, ...orders) + 1

    // ---- S: every item, in planning order
    input.onProgress?.('Reading this library', 0, 0)
    let sCount = 0
    await input.s.scan((rows) => {
      scratch.tx(() => {
        for (const row of rows) {
          if (input.reverse && !(row.addActor === input.reverse.bActorInT && row.addSeq > input.reverse.activationSeq)) continue
          const path_ = row.placements[input.sReplica] ?? null
          const hint = deps.files?.hint(row.id)
          scratch.addSource({
            id: row.id,
            added: row.added,
            revisionHash: row.head?.content ?? null,
            size: row.head?.size ?? null,
            mtimeHint: hint?.modifiedAt ?? null,
            placements: row.placements,
            fieldClocks: row.clocks,
            fields: row.fields,
            plays: row.plays,
            favourite: row.favourite,
            userTags: row.tags,
            pathOnB: path_,
            file: {readable: false},
            addActor: row.addActor,
            addSeq: row.addSeq,
          })
          sCount++
        }
      })
      input.onProgress?.('Reading this library', sCount, 0)
    })
    result.sItems = sCount
    result.tItems = tCount
    const sMeta = input.reverse ? ({spaces: [], playlists: [], sets: {}, routes: null} as MergeMeta) : await input.s.meta()

    // ---- the planner
    const stores = scratch.stores()
    const counts = {linked: 0, added: 0, keptDuplicate: 0}
    // The review shows names, not ids: the titles of the page being planned, and S's playlist and space names
    let pageTitles = new Map<string, string>()
    const nameOf = (id: string): string | undefined => {
      const named = (fields: Record<string, unknown>) => (typeof fields.title === 'string' ? fields.title : typeof fields.name === 'string' ? fields.name : undefined)
      if (pageTitles.has(id)) return pageTitles.get(id)
      const entity = sMeta.playlists.find((p) => p.id === id) ?? sMeta.spaces.find((s) => s.id === id)
      return entity ? named(entity.fields) : undefined
    }
    const plan = createMergePlan(
      {mergeId: input.mergeId, actor: input.actor, replicaId: input.replicaId, firstSpaceOrder},
      stores,
      {
        batch(batch: BatchOut, raw: string) {
          result.batches++
          result.drafts += batch.drafts.length
          files?.batches.write(raw)
        },
        map(line: MapLine, raw: string) {
          if (line.how === 'linked') counts.linked++
          else if (line.how === 'added') counts.added++
          else counts.keptDuplicate++
          files?.map.write(raw)
        },
        review(line: ReviewLine, raw: string) {
          result.reviewTotal++
          if (line.kind === 'missingFile') result.missing++
          if (line.kind === 'differingField') result.differing++
          if (result.review.length < REVIEW_KEEP) {
            const title = nameOf(line.s)
            result.review.push(title ? {...line, title} : line)
          }
          files?.review.write(raw)
        },
        art(_copy, raw) {
          files?.art.write(raw)
        },
      },
    )

    let after: {added: string; id: string} | null = null
    let done = 0
    const spent = {page: 0, observe: 0, planner: 0, rehash: 0}
    const lap = (key: keyof typeof spent, since: number) => void (spent[key] += performance.now() - since)
    const pageSize = deps.pageSize ?? PAGE
    for (;;) {
      let t0 = performance.now()
      const page = scratch.sourcePage(after, pageSize)
      lap('page', t0)
      if (!page.length) break
      t0 = performance.now()
      await Promise.all(page.map((item) => observe(input, item)))
      lap('observe', t0)
      pageTitles = new Map(page.flatMap((item) => (typeof item.fields?.title === 'string' ? [[item.id, item.fields.title] as [string, string]] : [])))
      for (let attempt = 0; ; attempt++) {
        t0 = performance.now()
        // one transaction per page: the ledger writes (first source, map line) are not a disk write each
        const answer = scratch.tx(() => (input.reverse ? plan.reverseItems(page, input.reverse.bActorInT, input.reverse.activationSeq) : plan.items(page)))
        lap('planner', t0)
        if (answer.accepted) break
        if (attempt > 3) throw new Error('A file kept changing while it was read.')
        for (const need of answer.needsHash) {
          const row = page.find((r) => r.id === need.s)!
          await rehash(input, row, hashes)
        }
      }
      for (const item of page) if (files && item.pathOnB && item.file.readable) files.paths.write(JSON.stringify({s: item.id, path: item.pathOnB}))
      done += page.length
      after = {added: page[page.length - 1].added, id: page[page.length - 1].id}
      input.onProgress?.('Describing this library', done, sCount)
    }
    if (process.env.KEEPFIN_MERGE_TRACE) console.log(`plan time: page ${Math.round(spent.page)} ms, observe ${Math.round(spent.observe)} ms, planner ${Math.round(spent.planner)} ms`)

    // ---- spaces, playlists, artwork
    if (input.reverse) {
      plan.finish()
      result.linked = counts.linked
      result.added = counts.added
      result.keptDuplicate = counts.keptDuplicate
      return result
    }
    const metadata = metadataOf(sMeta)
    result.spaces = metadata.spaces?.length ?? 0
    result.playlists = metadata.playlists?.length ?? 0
    if (metadata.spaces?.length) plan.spaces({spaces: metadata.spaces, sets: {}})
    const lists = metadata.playlists ?? []
    for (let i = 0; i < lists.length; i += 25) {
      const slice = lists.slice(i, i + 25)
      const sets: Record<string, string[]> = {}
      for (const list of slice) for (const prefix of ['seen:', 'new:', 'removed:']) if (metadata.sets?.[prefix + list.id]) sets[prefix + list.id] = metadata.sets[prefix + list.id]
      plan.playlists({playlists: slice, sets})
    }
    if (input.sArtDir) {
      const playlistIds = new Set(sMeta.playlists.map((p) => p.id))
      const spaceIds = new Set(sMeta.spaces.map((s) => s.id))
      const art = await artFiles(input.sArtDir, (id) => scratch.hasSource(id), playlistIds, spaceIds)
      if (art.length) plan.artwork(art)
    }
    plan.finish()
    result.linked = counts.linked
    result.added = counts.added
    result.keptDuplicate = counts.keptDuplicate
    if (files && input.keep === 'mine') {
      // "Keep my settings": T's routes follow this library's, pointed at the spaces that came across
      const wire = (v: unknown) => (typeof v === 'string' ? mergeEntityId(input.mergeId, 'space', v) : undefined)
      const routes = {music: wire(sMeta.routes?.music), other: wire(sMeta.routes?.other)}
      if (routes.music || routes.other) {
        const drafts = [{kind: 'profile.set', body: {field: 'routes', value: Object.fromEntries(Object.entries(routes).filter(([, v]) => v))}}]
        const batch: BatchOut = {index: result.batches, drafts, draftsHash: sha256Hex(canonical(drafts.map((d) => ({body: d.body, kind: d.kind}))))}
        result.batches++
        result.drafts += drafts.length
        files.batches.write(JSON.stringify(batch))
      }
    }
    return result
  } finally {
    if (files) for (const f of Object.values(files)) f.close()
    scratch.close()
  }
}

/** Looks at the file now: readable, its size and modification time. */
async function observe(input: PlanInput, item: SourceItem): Promise<void> {
  if (!item.pathOnB) {
    item.file = {readable: false}
    return
  }
  const file = placementFile(input, item.pathOnB)
  try {
    const st = file ? await fsp.stat(file) : undefined
    item.file = st?.isFile() ? {readable: true, size: st.size, mtime: Math.round(st.mtimeMs)} : {readable: false}
  } catch {
    item.file = {readable: false}
  }
}

async function rehash(input: PlanInput, item: SourceItem, hashes: HashCache): Promise<void> {
  const file = item.pathOnB ? placementFile(input, item.pathOnB) : undefined
  if (!file) {
    item.file = {readable: false}
    return
  }
  try {
    const before = await fsp.stat(file)
    const mtime = Math.round(before.mtimeMs)
    const cached = hashes.get(file, before.size, mtime)
    if (cached) {
      item.file = {readable: true, size: before.size, mtime, hash: cached}
      return
    }
    const read = await hashFile(file)
    const after = await fsp.stat(file)
    if (read.size !== before.size || after.size !== before.size || Math.round(after.mtimeMs) !== mtime) {
      // changed while it was read: look again from the start
      await observe(input, item)
      return
    }
    hashes.put(file, before.size, mtime, read.hash)
    item.file = {readable: true, size: before.size, mtime, hash: read.hash}
  } catch {
    item.file = {readable: false}
  }
}

function metadataOf(meta: MergeMeta): Metadata {
  return {
    spaces: meta.spaces.map((s) => ({id: s.id, present: true, fields: s.fields})),
    playlists: meta.playlists.map((p) => ({id: p.id, present: true, fields: p.fields, songs: p.songs})),
    sets: meta.sets,
  }
}

const IMAGE = /\.(jpe?g|png|webp|gif)$/i

/** The flat `art/<id>.<ext>` files of a records root, by what they belong to. */
async function artFiles(dir: string, isItem: (id: string) => boolean, playlists: Set<string>, spaces: Set<string>): Promise<ArtSource[]> {
  const out: ArtSource[] = []
  for (const name of await fsp.readdir(dir).catch(() => [] as string[])) {
    if (!IMAGE.test(name)) continue
    const id = name.slice(0, name.lastIndexOf('.'))
    const ownerKind = isItem(id) ? 'item' : playlists.has(id) ? 'playlist' : spaces.has(id) ? 'space' : undefined
    if (ownerKind) out.push({ownerKind, ownerId: id, source: path.join(dir, name), name})
  }
  return out
}
