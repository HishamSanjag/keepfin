// The baseline L of one relationship (spec 4.1): a sorted JSON-lines file of BaselineEntry. Commits are journaled per
// action; folding them into the file is a streaming merge, so neither the baseline nor the commits are held in memory.
import fs from 'node:fs/promises'
import path from 'node:path'
import type {BaselineEntry, NameRulesId} from '../../shared/core/copies.js'
import {fastKey} from '../../shared/core/copies.js'
import {iterateJournal, type BaselineOp} from './journal.js'
import {ExternalSorter, LineWriter, lineReader, type SortRec} from './lines.js'

export const baselineFile = (stateDir: string) => path.join(stateDir, 'baseline.jsonl')

export interface FoldResult {
  applied: number
  entries: number
}

/**
 * Applies the `committed` records of [journalFile] to the baseline (atomically replacing it). [refresh] may adjust the
 * entries that survive untouched (used to record a fresh stat for a file whose hash proved it unchanged). Applying the
 * same journal twice gives the same file.
 */
export async function foldCommits(options: {
  stateDir: string
  journalFile: string
  rules: NameRulesId
  tmpDir: string
  refresh?: (entry: BaselineEntry) => BaselineEntry
  chunk?: number
}): Promise<FoldResult> {
  const sorter = new ExternalSorter(options.tmpDir, options.chunk ?? 50_000, 'fold')
  let seq = 0
  for (const rec of iterateJournal(options.journalFile)) {
    if (rec.t !== 'committed') continue
    for (const op of rec.ops as BaselineOp[]) {
      await sorter.add({key: fastKey(options.rules, op.path), path: op.path, seq: seq++, text: op.entry ? JSON.stringify(op.entry) : 'null'})
    }
  }
  const file = baselineFile(options.stateDir)
  await fs.mkdir(options.stateDir, {recursive: true})
  const tmp = `${file}.${process.pid}.tmp`
  const writer = await LineWriter.create(tmp)
  const base = lineReader(file)
  let entries = 0
  let applied = 0

  const nextBase = (): {key: string; entry: BaselineEntry} | null => {
    const line = base()
    if (line === null) return null
    const entry = JSON.parse(line) as BaselineEntry
    return {key: fastKey(options.rules, entry.path), entry}
  }
  const emit = async (entry: BaselineEntry | null, untouched: boolean) => {
    if (!entry) return
    const out = untouched && options.refresh ? options.refresh(entry) : entry
    await writer.write(JSON.stringify(out))
    entries++
  }

  try {
    let b = nextBase()
    const iter = sorter.sorted()[Symbol.asyncIterator]()
    let u = await iter.next()
    while (b || !u.done) {
      const bk = b?.key
      const uk = u.done ? undefined : u.value.key
      if (uk === undefined || (bk !== undefined && bk < uk)) {
        await emit(b!.entry, true)
        b = nextBase()
        continue
      }
      // update group at key uk (and possibly the baseline entry with the same key)
      const group: SortRec[] = []
      while (!u.done && u.value.key === uk) {
        group.push(u.value)
        u = await iter.next()
      }
      group.sort((x, y) => x.seq - y.seq)
      let current: BaselineEntry | null = null
      if (b && bk === uk) {
        current = b.entry
        b = nextBase()
        while (b && b.key === uk) b = nextBase() // one entry per name key
      }
      for (const g of group) {
        current = g.text === 'null' ? null : (JSON.parse(g.text) as BaselineEntry)
        applied++
      }
      await emit(current, false)
    }
    await writer.close()
  } catch (error) {
    await writer.close(false).catch(() => undefined)
    await fs.rm(tmp, {force: true}).catch(() => undefined)
    throw error
  } finally {
    base.close()
  }
  await fs.rename(tmp, file)
  return {applied, entries}
}

/**
 * Sorts the baseline file again under [rules] (the name rule changed since it was written). Entries are kept as they are; two that now
 * share a name key stay side by side, and the planner reports them as a name collision rather than merging them.
 */
export async function resortBaseline(options: {stateDir: string; rules: NameRulesId; tmpDir: string; chunk?: number}): Promise<number> {
  const file = baselineFile(options.stateDir)
  const read = lineReader(file)
  const sorter = new ExternalSorter(options.tmpDir, options.chunk ?? 50_000, 'resort')
  let seq = 0
  try {
    for (let line = read(); line !== null; line = read()) {
      const entry = JSON.parse(line) as BaselineEntry
      await sorter.add({key: fastKey(options.rules, entry.path), path: entry.path, seq: seq++, text: line})
    }
  } finally {
    read.close()
  }
  const tmp = `${file}.${process.pid}.resort.tmp`
  const writer = await LineWriter.create(tmp)
  for await (const rec of sorter.sorted()) await writer.write(rec.text)
  await writer.close()
  await fs.rename(tmp, file)
  return seq
}

/** Streams the baseline entries. */
export function* readBaseline(stateDir: string): Generator<BaselineEntry> {
  const read = lineReader(baselineFile(stateDir))
  try {
    for (let line = read(); line !== null; line = read()) yield JSON.parse(line) as BaselineEntry
  } finally {
    read.close()
  }
}

/**
 * Forgets the copy-side file ids of the baseline: they were ids of files in a folder on this computer, and a copy kept on an account
 * or a device is listed with ids of its own (the planner compares ids only when both sides have one). Size and time stay.
 */
export async function stripCopyFileIds(stateDir: string): Promise<number> {
  const file = baselineFile(stateDir)
  const read = lineReader(file)
  const tmp = `${file}.${process.pid}.strip.tmp`
  const writer = await LineWriter.create(tmp)
  let changed = 0
  try {
    for (let line = read(); line !== null; line = read()) {
      const entry = JSON.parse(line) as BaselineEntry
      if (entry.copy?.fileId !== undefined) {
        const {fileId: _dropped, ...rest} = entry.copy
        void _dropped
        entry.copy = rest
        changed++
      }
      await writer.write(JSON.stringify(entry))
    }
  } finally {
    read.close()
  }
  await writer.close()
  if (changed > 0) await fs.rename(tmp, file)
  else await fs.rm(tmp, {force: true})
  return changed
}
