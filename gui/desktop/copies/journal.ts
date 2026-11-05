// The run journal (spec 5.1): `journal.jsonl`, append-only, one JSON object per line, torn-tail tolerant. Each action
// writes `intent`, `staged`, (`preserved`), `published`, `committed` (or `skipped` / `failed`). Appends are group-
// committed: lines written together share one fsync, and a step that asks for durability resolves only after the fsync.
import fs from 'node:fs/promises'
import type {FileHandle} from 'node:fs/promises'
import path from 'node:path'
import type {BaselineEntry, PlanAction} from '../../shared/core/copies.js'
import {CrashError} from './util.js'
import {lineReader} from './lines.js'

export interface BaselineOp {
  path: string
  /** null removes the entry */
  entry: BaselineEntry | null
}

export type JournalRecord =
  | {t: 'run'; run: string; at: number; generation: number}
  | {t: 'intent'; run: string; n: number; action: PlanAction; tmp: string | null; dest: string | null}
  | {t: 'staged'; run: string; n: number; tmp: string; contentId: string}
  | {t: 'preserved'; run: string; n: number; from: string; archive: string; catalogueId: string; store: 'versions' | 'trash'}
  | {t: 'published'; run: string; n: number}
  | {t: 'committed'; run: string; n: number; ops: BaselineOp[]}
  | {t: 'skipped'; run: string; n: number; reason: string}
  | {t: 'failed'; run: string; n: number; reason: string}
  | {t: 'end'; run: string}

/** Fault injection for tests: called at every named step; throwing CrashError there stops the "process" dead. */
export type FaultHook = (step: string, info: {n: number; action: PlanAction}) => void | Promise<void>

export class Journal {
  private handle: FileHandle | null = null
  private pending: {line: string; durable: boolean; resolve: () => void; reject: (e: unknown) => void}[] = []
  private flushing: Promise<void> | null = null
  private dead = false

  private constructor(readonly file: string) {}

  static async open(file: string): Promise<Journal> {
    await fs.mkdir(path.dirname(file), {recursive: true})
    const j = new Journal(file)
    j.handle = await fs.open(file, 'a')
    return j
  }

  /** Simulates a killed process: every later append throws and nothing more reaches the disk. */
  kill(): void {
    this.dead = true
  }

  get isDead(): boolean {
    return this.dead
  }

  append(rec: JournalRecord, durable: boolean): Promise<void> {
    if (this.dead) return Promise.reject(new CrashError('journal-dead'))
    return new Promise<void>((resolve, reject) => {
      this.pending.push({line: JSON.stringify(rec), durable, resolve, reject})
      if (!this.flushing) this.flushing = this.flush()
    })
  }

  private async flush(): Promise<void> {
    // let other workers add their lines to the same batch
    await new Promise((r) => setImmediate(r))
    while (this.pending.length > 0) {
      const batch = this.pending
      this.pending = []
      try {
        if (this.dead || !this.handle) throw new CrashError('journal-dead')
        await this.handle.write(batch.map((b) => b.line).join('\n') + '\n')
        if (batch.some((b) => b.durable)) await this.handle.sync()
        for (const b of batch) b.resolve()
      } catch (error) {
        for (const b of batch) b.reject(error)
      }
    }
    // no await between the last emptiness check and here: an append can never be stranded
    this.flushing = null
  }

  async close(): Promise<void> {
    if (this.flushing) await this.flushing
    if (this.handle) {
      await this.handle.close().catch(() => undefined)
      this.handle = null
    }
  }
}

/** Streams a journal record by record. A torn last line (a crash while writing) and any invalid line are skipped. */
export function* iterateJournal(file: string, stats?: {torn: number}): Generator<JournalRecord> {
  const read = lineReader(file)
  try {
    for (let line = read(); line !== null; line = read()) {
      try {
        const r = JSON.parse(line) as JournalRecord
        if (r && typeof r === 'object' && typeof (r as {t?: unknown}).t === 'string') yield r
        else if (stats) stats.torn++
      } catch {
        if (stats) stats.torn++
      }
    }
  } finally {
    read.close()
  }
}