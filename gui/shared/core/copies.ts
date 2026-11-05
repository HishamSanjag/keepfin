// Adapter between the shared core (core/, Kotlin Multiplatform; built to build/core-js by `npm run core:build`) and the
// copies planner of dev-docs/plans/KEEPFIN-COPIES-SPEC.md section 4. This is the only file that imports the core's copies
// package. The planner is pure and streaming: it pulls JSON lines from the sources it is given (so inventories stay on
// disk) and answers with the hashes it still needs or with a plan, whose actions are read out in pages.
import {
  copiesCompanionCandidates,
  copiesCompanionCandidatesOf,
  copiesCompanionField,
  copiesCompanionPath,
  copiesCompanionsOf,
  copiesCompanionSuffix,
  copiesConflictPath,
  copiesCopyPath,
  copiesFailureReason,
  copiesKeepCompanions,
  copiesIsVideoCompanion,
  copiesVideoCompanionStem,
  copiesVideoCompanionSuffix,
  copiesNameKey,
  copiesProblemGroups,
  copiesProblemRetry,
  copiesProblemShort,
  copiesProblemTitle,
  copiesSyncReport,
  copiesPlan,
  type JsCopiesPlan,
} from '../../../build/core-js/keepfin-core.mjs'

export type CopyMode = 'twoWay' | 'backup' | 'mirror'
export type NameRulesId = 'exact' | 'ascii'
export type GenerationState = 'equal' | 'higher' | 'lower' | 'unknown'
export type Side = 'Main' | 'Copy'

export type ActionKind =
  | 'CopyTo'
  | 'MoveOnCopy'
  | 'RemoveFromCopy'
  | 'ImportNew'
  | 'ReviseFromCopy'
  | 'RestoreFromCopy'
  | 'RestoreEditedDeleted'
  | 'DeleteItem'
  | 'Converge'
  | 'ForgetBaseline'

export type ReviewKind =
  | 'MissingSource'
  | 'MissingEverywhere'
  | 'NameCollision'
  | 'UnknownFile'
  | 'KeptOnBackup'
  | 'CopyDeletedProposal'
  | 'Difference'
  | 'Suspended'
  | 'NotHeld'

/** `(relativePath, size, mtimeMs, fileId?)`; paths use `/`. */
export interface InvLine {
  path: string
  size: number
  mtimeMs: number
  fileId?: string | null
}

export interface Stat {
  size: number
  mtimeMs: number
  fileId?: string | null
}

export interface BaselineEntry {
  path: string
  itemId: string
  revisionOp: string | null
  contentId: string
  main: Stat
  copy: Stat
  scannedAtMs: number
  generation: number
}

export interface DesiredEntry {
  path: string
  itemId: string
  revisionOp: string | null
  contentId: string
  bytes: number
}

export interface PlacementMove {
  itemId: string
  from: string
  to: string
}

export interface PlanAction {
  kind: ActionKind
  path: string
  toPath: string | null
  itemId: string | null
  revisionOp: string | null
  contentId: string | null
  bytes: number
  displacedBytes: number
  expect: {contentId: string | null; fileId: string | null} | null
  archive: 'Versions' | 'Trash' | null
  reason: 'ItemDeleted' | 'NoLongerHeld' | 'ConfirmedDelete' | null
  review: boolean
}

export interface ReviewItem {
  kind: ReviewKind
  path: string
  itemId: string | null
  detail: string
  action: PlanAction | null
}

export interface HashRequest {
  side: Side
  path: string
  size: number
  mtimeMs: number
  why: string
}

export interface SpaceCheck {
  needed: number
  free: number | null
  enough: boolean | null
}

export interface PlanSummary {
  counts: Partial<Record<ActionKind, number>>
  bytes: Partial<Record<ActionKind, number>>
  destructive: number
  copyFiles: number
  mainSpace: SpaceCheck
  copySpace: SpaceCheck
}

/** Pulls the next JSON line, or null at the end. */
export type LineReader = () => string | null
/** Opens a stream; the planner may open each source more than once. */
export type LineSource = () => LineReader

export interface PlanSources {
  desired: LineSource
  main: LineSource
  copy: LineSource
  baseline: LineSource
}

export interface PlanOptions {
  mode: CopyMode
  rules: NameRulesId
  generation?: GenerationState
  mainComplete?: boolean
  copyComplete?: boolean
  previewConfirmed?: boolean
  audit?: boolean
  timestampGranularityMs?: number
  freeMain?: number
  freeCopy?: number
  deleteGuardPercent?: number
  /** The place carries lyrics, subtitles and info files (default true); off, those already on the copy stay. */
  companions?: boolean
  /** The place carries each song's music video (default true: always pass the place's effective value). */
  musicVideos?: boolean
  confirmedDeletes?: string[]
  /** Item ids an `item.delete` removed. */
  deleted?: string[]
  placementMoves?: PlacementMove[]
  caps?: {completeEnumeration?: boolean; stableIds?: boolean; conditionalMutation?: boolean}
}

/** Known hashes: `sha256:<hex>` for the file as it is now, or null/undefined when not known. */
export type HashLookup = (side: Side, path: string, size: number, mtimeMs: number) => string | null | undefined

const PAGE = 2000

export class CopiesPlan {
  readonly actionCount: number
  readonly reviewCount: number
  readonly held: boolean
  readonly holdReason: string | null
  readonly additiveOnly: boolean
  readonly summary: PlanSummary
  constructor(private readonly plan: JsCopiesPlan) {
    this.actionCount = plan.actionCount
    this.reviewCount = plan.reviewCount
    this.held = plan.held
    this.holdReason = plan.holdReason ?? null
    this.additiveOnly = plan.additiveOnly
    this.summary = JSON.parse(plan.summaryJson()) as PlanSummary
  }

  /** The actions in plan order, read a page at a time. */
  *actions(): Generator<PlanAction> {
    for (let offset = 0; offset < this.actionCount; offset += PAGE) {
      for (const a of JSON.parse(this.plan.actionsPage(offset, PAGE)) as PlanAction[]) yield a
    }
  }

  *review(): Generator<ReviewItem> {
    for (let offset = 0; offset < this.reviewCount; offset += PAGE) {
      for (const r of JSON.parse(this.plan.reviewPage(offset, PAGE)) as ReviewItem[]) yield r
    }
  }

  get isEmpty(): boolean {
    return this.actionCount === 0
  }
}

export type PlanStep =
  | {ready: false; requests: HashRequest[]}
  | {ready: true; plan: CopiesPlan}

/**
 * Plans one relationship. When some decision needs the hash of a file on the copy that [hash] does not know, the answer is
 * `{ready: false, requests}`: hash those files and call again with the new hashes.
 */
export function planCopies(options: PlanOptions, sources: PlanSources, hash: HashLookup): PlanStep {
  const outcome = copiesPlan(
    JSON.stringify(options),
    sources.desired,
    sources.main,
    sources.copy,
    sources.baseline,
    (side, path, size, mtimeMs) => hash(side as Side, path, size, mtimeMs) ?? null,
  )
  if (outcome.ready) return {ready: true, plan: new CopiesPlan(outcome.plan as JsCopiesPlan)}
  const requests: HashRequest[] = []
  for (let offset = 0; offset < outcome.requestCount; offset += PAGE) {
    for (const r of JSON.parse(outcome.requestsPage(offset, PAGE)) as HashRequest[]) requests.push(r)
  }
  return {ready: false, requests}
}

/** The name key inventories are sorted by (together with the path). */
export function nameKey(rules: NameRulesId, path: string): string {
  return copiesNameKey(rules, path)
}

/** `name (conflict 1a2b3c4d).ext`: where a second version of a file lives. */
export function conflictPath(path: string, contentId: string): string {
  return copiesConflictPath(path, contentId)
}

/** `name (copy 1a2b3c4d).ext`: where a different file that arrived under an occupied name lives. */
export function copyNamePath(path: string, contentId: string): string {
  return copiesCopyPath(path, contentId)
}

/** The same key as [nameKey], computed in JavaScript (sorting calls it millions of times; a test checks they agree). */
export function fastKey(rules: NameRulesId, path: string): string {
  return rules === 'exact' ? path : path.replace(/[A-Z]/g, (c) => String.fromCharCode(c.charCodeAt(0) + 32))
}

// ---- companions: the lyrics, subtitles and info file of an item (core/.../copies/Companions.kt) ----

/** The item field prefix of a companion record (`companion:.lrc`). */
export const COMPANION_FIELD = 'companion:'

/** The companion suffix of the file [name] beside the media file [mediaName] (`.lrc`, `.en.srt`, `.info.json`), or null. */
export const companionSuffix = (mediaName: string, name: string): string | null => copiesCompanionSuffix(mediaName, name) ?? null

/** The item field that records the companion [suffix]. */
export const companionField = (suffix: string): string => copiesCompanionField(suffix)

/** Where the companion [suffix] of the media file at [mediaPath] lives (`Music/Song.mp3` and `.lrc` give `Music/Song.lrc`). */
export const companionPath = (mediaPath: string, suffix: string): string => copiesCompanionPath(mediaPath, suffix)

export type Companion = {suffix: string; content: string; size: number}

/** The companions an item's fields record ([fields] may hold only the `companion:` ones). */
export const companionsOf = (fields: Record<string, unknown>): Companion[] =>
  copiesCompanionsOf(JSON.stringify(fields)).map((c) => ({suffix: c.suffix, content: c.content, size: c.size}))

/** The suffix a desired entry is the companion of (its revision is `companion:<suffix>`), or null for the media file. */
export const companionOfEntry = (revisionOp: string | null | undefined): string | null =>
  revisionOp?.startsWith(COMPANION_FIELD) ? revisionOp.slice(COMPANION_FIELD.length) : null

/** True for a music video's companion suffix (.video.mp4). */
export const isVideoCompanion = (suffix: string): boolean => copiesIsVideoCompanion(suffix)

/** The companion suffix a music video with the extension [ext] (mp4) has (.video.mp4), or null when the container is not supported. */
export const videoCompanionSuffix = (ext: string): string | null => copiesVideoCompanionSuffix(ext) ?? null

/** The song's stem for a music video's file name (Song.video.mp4 gives Song), or null for any other name. */
export const videoCompanionStem = (name: string): string | null => copiesVideoCompanionStem(name) ?? null

/** The desired entries the companion fields of one media entry give ([companionsJson] is a JSON object of those fields). */
export const companionCandidates = (itemId: string, mediaPath: string, heads: number, companionsJson: string, videos = true, others = true): DesiredEntry[] =>
  copiesCompanionCandidates(itemId, mediaPath, heads, companionsJson, videos, others).map((e) => ({path: e.path, itemId: e.itemId, revisionOp: e.revisionOp, contentId: e.contentId, bytes: e.bytes}))

/**
 * [companionCandidates] for many items in one call to the core: each with its media path, head count and fields (only the
 * `companion:` ones are read; the JSON is parsed here, natively, which costs a tenth of parsing it in the core).
 */
export function companionCandidatesOf(items: readonly {itemId: string; path: string; heads: number; fields: Record<string, unknown>}[], videos = true, others = true): DesiredEntry[] {
  const ids: string[] = []
  const paths: string[] = []
  const heads: number[] = []
  const names: string[] = []
  const contents: string[] = []
  const sizes: number[] = []
  for (const item of items) {
    for (const [name, value] of Object.entries(item.fields)) {
      if (!name.startsWith(COMPANION_FIELD)) continue
      const v = value && typeof value === 'object' ? (value as {content?: unknown; size?: unknown}) : {}
      ids.push(item.itemId)
      paths.push(item.path)
      heads.push(item.heads)
      names.push(name)
      contents.push(typeof v.content === 'string' ? v.content : '')
      sizes.push(typeof v.size === 'number' && Number.isSafeInteger(v.size) ? v.size : -1)
    }
  }
  if (!ids.length) return []
  return copiesCompanionCandidatesOf(ids, paths, Int32Array.from(heads), names, contents, Float64Array.from(sizes), videos, others).map((e) => ({path: e.path, itemId: e.itemId, revisionOp: e.revisionOp, contentId: e.contentId, bytes: e.bytes}))
}

/** The candidates a desired set keeps: one per name, never a media file's ([isMediaKey] takes `fastKey('ascii', path)`). */
export function keepCompanions(candidates: readonly DesiredEntry[], isMediaKey: (key: string) => boolean): DesiredEntry[] {
  if (!candidates.length) return []
  const kept = copiesKeepCompanions(candidates.map((e) => e.path), candidates.map((e) => e.itemId), isMediaKey)
  return Array.from(kept, (i) => candidates[i])
}

/** The order the planner requires: by name key, then by path (UTF-16 code units, as the core compares). */
export function compareNames(rules: NameRulesId, a: string, b: string): number {
  const ka = fastKey(rules, a)
  const kb = fastKey(rules, b)
  if (ka !== kb) return ka < kb ? -1 : 1
  return a === b ? 0 : a < b ? -1 : 1
}

// ---- a copy that could not copy some files (core/.../copies/CopyProblems.kt) ----

export type ProblemGroup = {text: string; count: number}

/** The plain sentence for one failure: no code, no file name. */
export const failureReason = (reason: string): string => copiesFailureReason(reason)

/** The failures grouped by plain reason, the most common first. */
export const problemGroups = (reasons: readonly string[]): ProblemGroup[] => copiesProblemGroups([...reasons]).map((g) => ({text: g.text, count: g.count}))

/** "50 files could not be copied". */
export const problemTitle = (count: number): string => copiesProblemTitle(count)

/** "50 files not copied". */
export const problemShort = (count: number): string => copiesProblemShort(count)

/** "Keepfin tries again at every sync." */
export const problemRetry = (): string => copiesProblemRetry()

// ---- what a sync the person started did (core/.../copies/SyncReport.kt) ----

export type SyncReport = {title: string; detail: string; warn: boolean}

/** Said once when every copy of a Sync all or Sync now has finished, even when there was nothing to do. */
export function syncReport(synced: number, changed: number, problems: number, notReached: readonly string[], needOk: readonly string[]): SyncReport {
  const r = copiesSyncReport(synced, changed, problems, [...notReached], [...needOk])
  return {title: r.title, detail: r.detail, warn: r.warn}
}
