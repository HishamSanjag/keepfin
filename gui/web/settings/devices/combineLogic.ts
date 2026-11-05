import type {CombinePreview, CombineReviewLine, CombineState} from '../../../shared/combine'
import {plural} from '../../format'

/** The pure words of combining two libraries: the Combine step, the review, the card line, the Separate dialog. */

/** The preview may also say how big the other library is (`otherItems`); until it does, the choice cannot follow size. */
export type PreviewSizes = CombinePreview & {otherItems?: number}

/** Which library's name, spaces and settings stay: the one with more items. Without the other library's size, the other device's. */
export function defaultKeep(preview: PreviewSizes | undefined): 'mine' | 'theirs' {
  // Without the other library's size, keep this PC's: a PC usually holds the bigger library (a phone keeps a part)
  if (!preview || preview.otherItems === undefined) return 'mine'
  return preview.items > preview.otherItems ? 'mine' : 'theirs'
}

/** "Galaxy S24 has 1,326 items. This PC has 210 items. Combine them into one library that both keep up to date?" */
export function combineQuestion(preview: PreviewSizes, name: string): string {
  const mine = `This PC has ${plural(preview.items, 'item')}.`
  const theirs = preview.otherItems !== undefined ? `${name} has ${plural(preview.otherItems, 'item')}.` : `${name} and this PC each have their own library.`
  return `${theirs} ${mine} Combine them into one library that both keep up to date?`
}

const are = (count: number, noun: string, tail: string) => `${plural(count, noun)} ${count === 1 ? 'is' : 'are'} ${tail}`

/** The review's lines, in order; a line whose count is zero is left out. */
export function reviewFacts(preview: CombinePreview, name: string): string[] {
  const lines: string[] = []
  if (preview.added > 0) lines.push(are(preview.added, 'item', 'added'))
  if (preview.linked > 0) lines.push(`${are(preview.linked, 'item', `already in ${name}’s library`)} (same file): counted once`)
  if (preview.keptDuplicates > 0) lines.push(`${plural(preview.keptDuplicates, 'item')} ${preview.keptDuplicates === 1 ? 'is' : 'are'} the same file twice here: both are kept`)
  if (preview.differing > 0) lines.push(`${plural(preview.differing, 'item')} ${preview.differing === 1 ? 'has' : 'have'} a different title or detail: ${name}’s stays`)
  if (preview.missingFiles > 0) lines.push(`${plural(preview.missingFiles, 'file')} could not be found on this PC: ${preview.missingFiles === 1 ? 'it comes' : 'they come'} along without a file`)
  if (preview.playlists > 0) lines.push(`${plural(preview.playlists, 'playlist')} come${preview.playlists === 1 ? 's' : ''} along`)
  if (preview.spaces > 0) lines.push(`${plural(preview.spaces, 'space')} come${preview.spaces === 1 ? 's' : ''} along`)
  lines.push('Favourites and play counts come along')
  return lines
}

export interface ReviewTile {
  key: 'added' | 'linked' | 'twice'
  icon: string
  count: number
  label: string
  /** The whole sentence, for screen readers and the tooltip. */
  sentence: string
}

/** The review as a picture (owner's choice A, 2026-10-21): this PC's and the other library's sizes flowing into one, tiles for what
 * happens to this PC's items, what comes along, and what to check. Zero counts are left out. */
export function reviewPicture(preview: PreviewSizes, name: string) {
  const facts = reviewFacts(preview, name)
  const sentence = (prefix: string) => facts.find((f) => f.includes(prefix)) ?? ''
  const tiles: ReviewTile[] = []
  if (preview.added > 0) tiles.push({key: 'added', icon: 'plus', count: preview.added, label: 'added', sentence: sentence('added')})
  if (preview.linked > 0) tiles.push({key: 'linked', icon: 'check', count: preview.linked, label: 'already there', sentence: sentence('counted once')})
  if (preview.keptDuplicates > 0) tiles.push({key: 'twice', icon: 'copy', count: preview.keptDuplicates, label: 'kept twice', sentence: sentence('twice here')})
  const along: {icon: string; text: string}[] = []
  if (preview.playlists > 0) along.push({icon: 'list', text: plural(preview.playlists, 'playlist')})
  if (preview.spaces > 0) along.push({icon: 'layers', text: plural(preview.spaces, 'space')})
  along.push({icon: 'heart', text: 'favourites'}, {icon: 'play', text: 'play counts'})
  const check: string[] = []
  if (preview.differing > 0) check.push(sentence('different title'))
  if (preview.missingFiles > 0) check.push(sentence('could not be found'))
  // the combined library: the other library plus what is new here (linked items are counted once)
  const total = preview.otherItems !== undefined ? preview.otherItems + preview.added + preview.keptDuplicates : undefined
  return {total, tiles, along, check}
}

/** One review entry in plain words. */
export function reviewEntry(line: CombineReviewLine, name: string): string {
  // Names, never ids: an entry without a title says what it is instead
  const s = line.title ? `“${line.title}”` : line.kind === 'sameNamePlaylist' || line.kind === 'sameNameSpace' ? 'with the same name' : 'One item'
  switch (line.kind) {
    case 'manyMatches':
      return `${s} matches several items in ${name}’s library. It is counted once, with the oldest.`
    case 'duplicateInS':
      return `${s} is the same file twice on this PC. Both are kept.`
    case 'differingField':
      return `${s}: the ${line.field ?? 'detail'} is different. ${name}’s stays.`
    case 'missingFile':
      return `${s} could not be found on this PC. It comes along without a file.`
    case 'sameNamePlaylist':
      return line.title ? `A playlist called ${s} is on both. Both are kept.` : 'A playlist with the same name is on both. Both are kept.'
    case 'sameNameSpace':
      return line.title ? `A space called ${s} is on both. Both are kept.` : 'A space with the same name is on both. Both are kept.'
  }
}

/** Phases in which the work itself is running (after the person pressed Combine). */
export const RUNNING = new Set<CombineState['phase']>(['planning', 'applying', 'verifying', 'switching'])
/** Phases of the dry run (before Combine is pressed). */
export const LOOKING = new Set<CombineState['phase']>(['joining', 'reviewing'])

export const forDevice = (state: CombineState | undefined, deviceId: string): CombineState | undefined => (state && state.available && state.deviceId === deviceId && state.phase !== 'idle' ? state : undefined)

export const longDate = (iso: string) => {
  const at = new Date(iso)
  return Number.isNaN(at.getTime()) ? '' : at.toLocaleDateString(undefined, {day: 'numeric', month: 'long', year: 'numeric'})
}

/** The percentage of a progress, or null when the total is not known yet. */
export const percentOf = (state: CombineState): number | null => (state.progress && state.progress.total > 0 ? Math.round((state.progress.done / state.progress.total) * 100) : null)

/** The card's sentence: "Combining libraries · 1,230 of 1,450", "Combining paused · Laptop is not reachable", "Combined · Separate again until 20 November 2026". */
export function combineLine(state: CombineState): string {
  const p = state.progress
  const count = p && p.total > 0 ? ` · ${p.done.toLocaleString()} of ${p.total.toLocaleString()}` : p?.label ? ` · ${p.label}` : ''
  switch (state.phase) {
    case 'joining':
    case 'reviewing':
      return `Looking at both libraries${count}`
    case 'ready':
      return 'Ready to combine · nothing has changed yet'
    case 'planning':
    case 'applying':
    case 'verifying':
    case 'switching':
      return `Combining libraries${count}`
    case 'paused':
      return `Combining paused${state.paused ? ` · ${state.paused}` : ''}`
    case 'failed':
      return `Combining stopped · ${state.error ?? 'Something went wrong.'}`
    case 'separating':
      return 'Separating libraries…'
    case 'done': {
      const c = state.combined
      if (c && c.canSeparate && Date.parse(c.until) > Date.now()) return `Combined · Separate again until ${longDate(c.until)}`
      return 'Combined'
    }
    default:
      return ''
  }
}

/** Whether Separate again is still on offer for this combine. */
export const canSeparate = (state: CombineState | undefined) => !!state && state.phase === 'done' && !!state.combined?.canSeparate && Date.parse(state.combined.until) > Date.now()

/** The Separate dialog's sentences, from the preview of what goes back. */
export function separateFacts(state: CombineState, thisPc = 'this PC') {
  const sep = state.separate
  if (!sep) return null
  const name = sep.deviceName || state.deviceName || 'The other device'
  return {
    name,
    goesBack: `${plural(sep.brought, 'item')} ${thisPc} brought ${sep.brought === 1 ? 'is' : 'are'} taken out of ${name}’s library. ${thisPc[0].toUpperCase()}${thisPc.slice(1)} goes back to its own library.`,
    addedSince: sep.addedSince > 0 ? `${plural(sep.addedSince, 'item')} added here since combining ${sep.addedSince === 1 ? 'comes' : 'come'} back to ${thisPc}’s own library.` : null,
    held: sep.heldElsewhereTotal > 0 ? `${name} has copies of ${plural(sep.heldElsewhereTotal, 'item')} ${thisPc} brought.` : null,
    unreachable: sep.unreachable ? `${name} is not reachable. Separate anyway: ${name} keeps everything ${thisPc} brought.` : null,
  }
}
