// Adapter between the shared core (core/, Kotlin Multiplatform; built to build/core-js by `npm run core:build`) and the library
// merge planner of dev-docs/plans/LIBRARY-MERGE-CONTRACT.md (sections 3, 5 and 7). This is the only file that imports the core's merge
// package. The core performs no I/O: the callbacks it is given are synchronous and cursor-based, and exchange canonical JSON.
// The host (gui/desktop/merge) backs them with an on-disk scratch database and streams what the planner emits to files.
import {createHash} from 'node:crypto'
import {NetMergePlanner, NetMergeSeparatePlanner, netMergeRemainingDrafts} from '../../../build/core-js/keepfin-core.mjs'

export type Draft = {kind: string; body: Record<string, unknown>}

export type MergeConfig = {mergeId: string; actor: string; replicaId: string; firstSpaceOrder?: number}

/** One S item for the planner (README of the core merge package, "Desktop facade"). */
export type SourceItem = {
  id: string
  added: string
  revisionHash: string | null
  size: number | null
  mtimeHint: number | null
  placements: Record<string, string>
  fieldClocks: Record<string, string>
  fields: Record<string, unknown>
  plays: number
  favourite: boolean
  userTags: string[]
  pathOnB: string | null
  file: {readable: boolean; size?: number | null; mtime?: number | null; hash?: string | null}
  addActor: string
  addSeq: number
}

/** One T item for the planner (also what the hash index hands back). */
export type TargetItem = Omit<SourceItem, 'pathOnB' | 'file' | 'addActor' | 'addSeq'>

export type PageAnswer = {accepted: boolean; needsHash: {s: string; path: string; size: number | null; mtime: number | null}[]}

export type BatchOut = {index: number; drafts: Draft[]; draftsHash: string}
export type MapLine = {s: string; t: string; how: 'linked' | 'added' | 'kept-duplicate'}
export type ReviewLine = {kind: 'manyMatches' | 'duplicateInS' | 'differingField' | 'missingFile' | 'sameNamePlaylist' | 'sameNameSpace'; s: string; t?: string; related?: string; field?: string; sourceValue?: unknown; targetValue?: unknown}
export type ArtCopyLine = {ownerKind: 'item' | 'playlist' | 'space'; ownerId: string; source: string; target: string}

/** What the planner emits: the parsed value and its canonical JSON text, which a host that writes lines can use as it is. */
export type PlannerOut = {
  batch(batch: BatchOut, raw: string): void
  map(line: MapLine, raw: string): void
  review(line: ReviewLine, raw: string): void
  art(copy: ArtCopyLine, raw: string): void
}

/** What the planner asks of T and of the scratch storage (all synchronous, all cursor-based). */
export type PlannerStores = {
  /** The id of the next T item with this content after [after] (null: first), in (item.add HLC, id) order; null at the end. */
  nextMatch(hash: string, after: string | null): string | null
  target(id: string): TargetItem
  /** The id of the next T entity of [kind] ('playlist' or 'space') named exactly [name] after [after], in id order. */
  nextName(kind: string, name: string, after: string | null): string | null
  firstSource(hash: string): string | null
  rememberContent(hash: string, source: string): void
  mapped(source: string): MapLine | null
  record(line: MapLine): void
}

export type Metadata = {
  playlists?: {id: string; present: boolean; fields: Record<string, unknown>; songs: {item: string; entry?: string; key?: string}[]}[]
  spaces?: {id: string; present: boolean; fields: Record<string, unknown>}[]
  sets?: Record<string, string[]>
}

export type ArtSource = {ownerKind: 'item' | 'playlist' | 'space'; ownerId: string; source: string; name: string}

export interface MergePlan {
  items(page: SourceItem[]): PageAnswer
  reverseItems(page: SourceItem[], bActorInT: string, activationSeq: number): PageAnswer
  spaces(views: Metadata): void
  playlists(views: Metadata): void
  artwork(files: ArtSource[]): void
  finish(): void
}

const emitTo = (out: PlannerOut) => (kind: string, json: string) => {
  const value = JSON.parse(json)
  if (kind === 'batch') out.batch(value as BatchOut, json)
  else if (kind === 'map') out.map(value as MapLine, json)
  else if (kind === 'review') out.review(value as ReviewLine, json)
  else if (kind === 'art') out.art(value as ArtCopyLine, json)
  else throw new Error(`The merge planner emitted an unknown ${kind}.`)
}

/** The planner for S into T (or, with `reverseItems`, T's new items back into S). One pass; never reuse it. */
export function createMergePlan(config: MergeConfig, stores: PlannerStores, out: PlannerOut): MergePlan {
  const planner = new NetMergePlanner(
    JSON.stringify({mergeId: config.mergeId, actor: config.actor, replicaId: config.replicaId, firstSpaceOrder: config.firstSpaceOrder ?? 0}),
    (hash, after) => stores.nextMatch(hash, after ?? null),
    (id) => JSON.stringify(stores.target(id)),
    (kind, name, after) => stores.nextName(kind, name, after ?? null),
    (hash) => stores.firstSource(hash),
    (hash, source) => stores.rememberContent(hash, source),
    (source) => {
      const line = stores.mapped(source)
      return line ? JSON.stringify(line) : null
    },
    (json) => stores.record(JSON.parse(json) as MapLine),
    emitTo(out),
  )
  return {
    items: (page) => JSON.parse(planner.itemsJson(JSON.stringify(page))) as PageAnswer,
    reverseItems: (page, bActorInT, activationSeq) => JSON.parse(planner.reverseItemsJson(JSON.stringify(page), bActorInT, activationSeq)) as PageAnswer,
    spaces: (views) => planner.spacesJson(JSON.stringify(views)),
    playlists: (views) => planner.playlistsJson(JSON.stringify(views)),
    artwork: (files) => planner.artworkJson(JSON.stringify(files)),
    finish: () => planner.finish(),
  }
}

export type SeparateChoice = 'Keep' | 'Remove'

export interface SeparatePlan {
  items(mapPage: MapLine[], choices: Record<string, SeparateChoice>): void
  finish(): void
}

/** Section 7.2 only: the metadata drafts that go into T. [observed] answers what a delete must observe for a T item. */
export function createSeparatePlan(replicaId: string, target: (id: string) => TargetItem, observed: (id: string) => {tags: string[]; heads: string[]}, out: Pick<PlannerOut, 'batch'>): SeparatePlan {
  const planner = new NetMergeSeparatePlanner(replicaId, (id) => JSON.stringify(target(id)), (id) => JSON.stringify(observed(id)), emitTo({...out, map() {}, review() {}, art() {}}))
  return {
    items: (mapPage, choices) => planner.itemsJson(JSON.stringify(mapPage), JSON.stringify(choices)),
    finish: () => planner.finish(),
  }
}

/** The drafts of [batch] that a restart still has to apply when [applied] of them are already in T's records (the receipt protocol). */
export function remainingDrafts(batch: BatchOut, applied: number): Draft[] {
  return JSON.parse(netMergeRemainingDrafts(JSON.stringify(batch), applied)) as Draft[]
}

/**
 * The id the planner gives an entity it creates in T (section 5): domain-separated and derived from the merge id and the S id, so a
 * rerun produces the same id and a remap needs no stored table for spaces and playlists. `kind` is 'item', 'space' or 'playlist';
 * for a space [source] is its wire id.
 */
export function mergeEntityId(mergeId: string, kind: 'item' | 'space' | 'playlist', source: string): string {
  const h = createHash('sha256').update(JSON.stringify(['keepfin-merge-v1', mergeId, kind, source])).digest('hex')
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-5${h.slice(13, 16)}-8${h.slice(17, 20)}-${h.slice(20, 32)}`
}
