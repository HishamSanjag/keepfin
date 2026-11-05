// Section 5.3: the append-only journal. One durable line per transition, written before the next action.
import fs from 'node:fs/promises'
import path from 'node:path'
import {appendLineDurable} from '../atomic.js'
import type {FileState} from './types.js'

export const journalFile = (runDir: string) => path.join(runDir, 'journal.jsonl')

const allowed: Record<FileState, FileState[]> = {
  planned: ['copied', 'failed'],
  copied: ['verified', 'failed'],
  verified: ['committed', 'failed'],
  committed: ['removable', 'done', 'failed'],
  removable: ['done', 'failed'],
  done: [],
  // A failed file only leaves this state through an explicit retry record.
  failed: [],
}

export function canTransition(from: FileState, to: FileState): boolean {
  return allowed[from].includes(to)
}

/** An illegal transition is a programming error. */
export function assertTransition(from: FileState, to: FileState): void {
  if (!canTransition(from, to)) throw new Error(`Illegal migration transition ${from} -> ${to}`)
}

export type FileData = {
  reason?: string
  code?: string
  mode?: 'copy' | 'rename'
  srcHash?: string
  destHash?: string
  size?: number
  mtimeMs?: number
  reinventoried?: boolean
}

export type JournalRecord =
  | ({k: 'file'; id: string; state: FileState} & FileData & {at?: number})
  | {k: 'intent'; id: string; op: 'rename' | 'copy'; from: string; to: string; size: number; mtimeMs: number; at?: number}
  | {k: 'retry'; id: string; at?: number}
  | {k: 'run'; event: 'started' | 'committed' | 'abandoning' | 'abandoned' | 'done' | 'stopped'; reason?: string; at?: number}

export type FileFold = {
  state: FileState
  /** State the file was in when it failed; a retry returns there. */
  failedFrom?: FileState
  reason?: string
  code?: string
  mode?: 'copy' | 'rename'
  srcHash?: string
  destHash?: string
  size?: number
  mtimeMs?: number
  reinventoried?: boolean
  intent?: {op: 'rename' | 'copy'; from: string; to: string; size: number; mtimeMs: number}
}

export type RunFold = {
  started: boolean
  committed: boolean
  abandoning: boolean
  abandoned: boolean
  done: boolean
  /** Why the run last stopped before finishing (for example no space); cleared by the next run event. */
  stopped?: string
}

export type Fold = {files: Map<string, FileFold>; run: RunFold; torn: boolean; corrupt: number; lines: number}

export const emptyFold = (): Fold => ({
  files: new Map(),
  run: {started: false, committed: false, abandoning: false, abandoned: false, done: false},
  torn: false,
  corrupt: 0,
  lines: 0,
})

/** Applies one record to a fold. */
export function applyRecord(fold: Fold, record: JournalRecord): void {
  const {files, run} = fold
  if (record.k === 'run') {
    if (record.event === 'started') run.started = true
    else if (record.event === 'committed') run.committed = true
    else if (record.event === 'abandoning') run.abandoning = true
    else if (record.event === 'abandoned') run.abandoned = true
    else if (record.event === 'done') run.done = true
    run.stopped = record.event === 'stopped' ? (record.reason ?? 'stopped') : undefined
    return
  }
  const file = files.get(record.id) ?? {state: 'planned' as FileState}
  files.set(record.id, file)
  if (record.k === 'intent') {
    file.intent = {op: record.op, from: record.from, to: record.to, size: record.size, mtimeMs: record.mtimeMs}
    return
  }
  if (record.k === 'retry') {
    if (file.state === 'failed') {
      file.state = file.failedFrom ?? 'planned'
      file.failedFrom = undefined
      file.reason = undefined
      file.code = undefined
    }
    return
  }
  if (record.state === 'failed') {
    if (file.state !== 'failed') file.failedFrom = file.state
    file.state = 'failed'
    file.reason = record.reason
    file.code = record.code
    return
  }
  file.state = record.state
  for (const field of ['mode', 'srcHash', 'destHash', 'size', 'mtimeMs', 'reinventoried'] as const)
    if (record[field] !== undefined) (file as Record<string, unknown>)[field] = record[field]
}

/** Folds journal text into each file's last state. A torn (incomplete) last line is ignored, never an error. */
export function foldJournal(text: string): Fold {
  const fold = emptyFold()
  const endsClean = text.length === 0 || text.endsWith('\n')
  const lines = text.split('\n')
  if (lines[lines.length - 1] === '') lines.pop()
  lines.forEach((line, index) => {
    if (!line.trim()) return
    let record: JournalRecord
    try {
      record = JSON.parse(line) as JournalRecord
      if (!record || typeof record !== 'object' || typeof (record as {k?: unknown}).k !== 'string') throw new Error('shape')
    } catch {
      if (index === lines.length - 1 && !endsClean) fold.torn = true
      else fold.corrupt++
      return
    }
    fold.lines++
    applyRecord(fold, record)
  })
  return fold
}

export async function readJournal(runDir: string): Promise<Fold> {
  let text = ''
  try {
    text = await fs.readFile(journalFile(runDir), 'utf8')
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error
  }
  return foldJournal(text)
}

/** Thrown by the crash hook so tests can stop a run exactly after (or before) a journal write. */
export class InjectedCrash extends Error {
  constructor(message = 'Injected crash') {
    super(message)
    this.name = 'InjectedCrash'
  }
}

export type CrashPlan = {after?: number; before?: number}

export class Journal {
  writes = 0
  private chain: Promise<void> = Promise.resolve()

  private constructor(
    private readonly file: string,
    readonly fold: Fold,
    private readonly crash?: CrashPlan,
    private readonly now: () => number = Date.now,
  ) {}

  /** Opens for appending and folds what is there. A torn last line from a crash is closed off first so new lines never join it. */
  static async open(runDir: string, crash?: CrashPlan, now?: () => number): Promise<Journal> {
    const file = journalFile(runDir)
    await fs.mkdir(runDir, {recursive: true})
    let text = ''
    try {
      text = await fs.readFile(file, 'utf8')
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error
    }
    const fold = foldJournal(text)
    if (text.length > 0 && !text.endsWith('\n')) await appendLineDurable(file, '')
    return new Journal(file, fold, crash, now)
  }

  /** Appends one or more records in a single durable write, validating transitions first and folding them after. */
  append(records: JournalRecord | JournalRecord[]): Promise<void> {
    const list = Array.isArray(records) ? records : [records]
    const step = this.chain.then(async () => {
      if (!list.length) return
      for (const record of list) {
        if (record.k !== 'file') continue
        const current = this.fold.files.get(record.id)?.state ?? 'planned'
        assertTransition(current, record.state)
      }
      this.writes++
      if (this.crash?.before !== undefined && this.writes === this.crash.before) throw new InjectedCrash(`before write ${this.writes}`)
      const at = this.now()
      await appendLineDurable(this.file, list.map((record) => JSON.stringify({...record, at})).join('\n'))
      for (const record of list) applyRecord(this.fold, record)
      if (this.crash?.after !== undefined && this.writes === this.crash.after) throw new InjectedCrash(`after write ${this.writes}`)
    })
    this.chain = step.catch(() => undefined)
    return step
  }
}
