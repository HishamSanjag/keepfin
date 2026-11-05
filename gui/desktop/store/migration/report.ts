// Section 5.7: the data behind "Everything is in your Keepfin folder" and its plain-text rendering.
import path from 'node:path'
import type {Fold} from './journal.js'
import type {Counts, FileKind, FileState, Plan} from './types.js'

export type AttentionAction = 'retry' | 'locate' | 'leave'

export type AttentionItem = {
  path: string
  kind: FileKind | 'missing'
  reason: string
  actions: AttentionAction[]
}

export type Report = {
  /** complete: everything arrived; attention: some files need a decision; unfinished: the run has not switched yet. */
  status: 'complete' | 'attention' | 'unfinished'
  before: Counts
  after: Counts
  attention: AttentionItem[]
}

const arrived = new Set<FileState>(['verified', 'committed', 'removable', 'done'])

export type ReportOptions = {
  /** Counts read back from the new library by the caller. When absent, the library counts are the planned ones once committed. */
  libraryAfter?: Partial<Pick<Counts, 'history' | 'playlists' | 'collections' | 'favourites' | 'userTags'>>
}

export function buildReport(plan: Plan, fold: Fold, options: ReportOptions = {}): Report {
  const before = plan.inventory.counts
  const committed = fold.run.committed
  let media = plan.left.filter((file) => file.kind === 'media').length
  let sidecars = plan.left.filter((file) => file.kind === 'sidecar').length
  let art = 0
  const attention: AttentionItem[] = []
  for (const file of plan.files) {
    const state = fold.files.get(file.id)
    if (state && arrived.has(state.state)) {
      if (file.kind === 'media') media++
      else if (file.kind === 'sidecar') sidecars++
      else art++
    } else if (state?.state === 'failed') {
      attention.push({path: file.source, kind: file.kind, reason: state.reason ?? 'The file could not be brought over.', actions: ['retry', 'locate', 'leave']})
    }
  }
  for (const missing of plan.inventory.missing)
    attention.push({path: missing.path, kind: 'missing', reason: 'The file is not on this PC any more.', actions: ['locate', 'leave']})

  const library = options.libraryAfter ?? {}
  const keep = (key: keyof NonNullable<ReportOptions['libraryAfter']>) => library[key] ?? (committed ? before[key] : 0)
  const after: Counts = {
    media,
    sidecars,
    art,
    history: keep('history'),
    playlists: keep('playlists'),
    collections: keep('collections'),
    favourites: keep('favourites'),
    userTags: keep('userTags'),
  }
  const status = !committed ? 'unfinished' : attention.length ? 'attention' : 'complete'
  return {status, before, after, attention}
}

const rows: [keyof Counts, string][] = [
  ['media', 'Songs and videos'],
  ['sidecars', 'Subtitles, lyrics and covers next to them'],
  ['art', 'Saved artwork'],
  ['history', 'Download history'],
  ['playlists', 'Playlists'],
  ['collections', 'Followed and downloaded collections'],
  ['favourites', 'Favourites'],
  ['userTags', 'Your tags'],
]

/** The report as plain text, in plain language. */
export function renderReport(report: Report): string {
  const lines: string[] = []
  lines.push(
    report.status === 'complete'
      ? 'Everything is in your Keepfin folder.'
      : report.status === 'attention'
        ? 'Almost everything is in your Keepfin folder. A few files need your attention.'
        : 'Keepfin has not finished bringing your library over. Nothing has been switched yet, and your old library still works as before.',
  )
  lines.push('')
  const width = Math.max(...rows.map(([, label]) => label.length))
  lines.push(`${'What'.padEnd(width)}  Before  After`)
  for (const [key, label] of rows) lines.push(`${label.padEnd(width)}  ${String(report.before[key]).padStart(6)}  ${String(report.after[key]).padStart(5)}`)
  if (report.attention.length) {
    lines.push('')
    lines.push('Needs your attention')
    for (const item of report.attention) {
      const choices = item.actions.map((action) => (action === 'retry' ? 'Retry' : action === 'locate' ? 'Locate' : 'Leave it')).join(', ')
      lines.push(`- ${path.basename(item.path)} (${item.path}): ${item.reason} Options: ${choices}.`)
    }
  }
  return `${lines.join('\n')}\n`
}
