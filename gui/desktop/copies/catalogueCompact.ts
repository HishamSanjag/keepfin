// Compacts a copy's append-only catalogues (`.keepfin/versions/catalogue.jsonl`, `.keepfin/trash/index.jsonl`) once they pass about
// 2,000 lines (dev-docs/plans/KEEPFIN-DATA-COMPACTION.md). Voided and expired ("gone") entries and the lines that void them are
// dropped; every other line is kept as it was written, so a reader of any release sees exactly the entries it saw before. The file is
// replaced atomically: a temporary file beside it, fsynced, then renamed.
import fs from 'node:fs/promises'
import path from 'node:path'
import {randomBytes} from 'node:crypto'
import {renameWithRetry} from '../fsutil.js'
import {lineReader} from './lines.js'
import {catalogueFile, type Store} from './versions.js'

export const COMPACT_LINES = 2000
const TEMP = /^(catalogue|index)\.jsonl\.compact-[0-9a-f]+\.tmp$/

export interface CompactResult {
  before: number
  after: number
}

type Parsed = {kind: 'entry'; id: string; text: string} | {kind: 'dead'; id: string} | {kind: 'other'}

function parse(text: string): Parsed {
  try {
    const v = JSON.parse(text) as {id?: unknown; file?: unknown; void?: unknown; gone?: unknown} | null
    if (v && typeof v === 'object') {
      if (v.void) return {kind: 'dead', id: String(v.void)}
      if (v.gone) return {kind: 'dead', id: String(v.gone)}
      if (v.id && v.file) return {kind: 'entry', id: String(v.id), text}
    }
  } catch {
    // a torn line is skipped by every reader
  }
  return {kind: 'other'}
}

/** Rewrites one catalogue without its dead lines when it holds more than [threshold] lines. Null when nothing was done. */
export async function compactCatalogue(copyRoot: string, store: Store, options: {threshold?: number; beforeRename?: () => void | Promise<void>} = {}): Promise<CompactResult | null> {
  const file = catalogueFile(copyRoot, store)
  const dir = path.dirname(file)
  for (const name of await fs.readdir(dir).catch(() => [] as string[])) if (TEMP.test(name)) await fs.rm(path.join(dir, name), {force: true}).catch(() => undefined)
  const first = await fs.stat(file).catch(() => null)
  if (!first) return null

  const parsed: Parsed[] = []
  const read = lineReader(file)
  try {
    for (let line = read(); line !== null; line = read()) parsed.push(parse(line))
  } finally {
    read.close()
  }
  if (parsed.length <= (options.threshold ?? COMPACT_LINES)) return null

  const dead = new Set<string>()
  for (const p of parsed) if (p.kind === 'dead') dead.add(p.id)
  // an entry that is live is kept once per identical line (the newest); the rest of the live lines stay in their order
  const keep: string[] = []
  const seen = new Set<string>()
  for (let i = parsed.length - 1; i >= 0; i--) {
    const p = parsed[i]!
    if (p.kind !== 'entry' || dead.has(p.id) || seen.has(p.text)) continue
    seen.add(p.text)
    keep.push(p.text)
  }
  keep.reverse()
  if (keep.length === parsed.length) return null

  const temp = path.join(dir, `${path.basename(file)}.compact-${randomBytes(4).toString('hex')}.tmp`)
  try {
    const handle = await fs.open(temp, 'wx')
    try {
      await handle.writeFile(keep.length ? `${keep.join('\n')}\n` : '', 'utf8')
      await handle.sync()
    } finally {
      await handle.close()
    }
    await options.beforeRename?.()
    // a line appended since the file was read would be lost by the switch: leave the file for the next run
    const now = await fs.stat(file).catch(() => null)
    if (!now || now.size !== first.size || Math.floor(now.mtimeMs) !== Math.floor(first.mtimeMs)) return null
    await renameWithRetry(temp, file)
  } finally {
    await fs.rm(temp, {force: true}).catch(() => undefined)
  }
  return {before: parsed.length, after: keep.length}
}

/** Both catalogues of a copy. A failure of one never stops the other, and never harms the file. */
export async function compactCatalogues(copyRoot: string): Promise<number> {
  let done = 0
  for (const store of ['versions', 'trash'] as const) if (await compactCatalogue(copyRoot, store).catch(() => null)) done++
  return done
}
