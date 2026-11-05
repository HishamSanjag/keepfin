// JSON-lines files on disk: the way inventories, the baseline and run state are kept so that nothing holds a whole
// inventory in memory (spec section 3). Readers are synchronous because the planner pulls lines synchronously; the
// work runs off the main thread (the store worker), so a 64 KiB read per few hundred lines is fine there.
import fs from 'node:fs'
import fsp from 'node:fs/promises'
import path from 'node:path'
import os from 'node:os'
import type {NameRulesId, LineReader} from '../../shared/core/copies.js'
import {compareNames, fastKey} from '../../shared/core/copies.js'

export interface ClosableReader {
  (): string | null
  close(): void
}

const CHUNK = 64 * 1024

/** Reads a file line by line, synchronously. A missing file reads as empty. */
export function lineReader(file: string): ClosableReader {
  let fd: number | null = null
  try {
    fd = fs.openSync(file, 'r')
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error
  }
  const buf = Buffer.allocUnsafe(CHUNK)
  let carry: Buffer[] = []
  let queue: string[] = []
  let qi = 0
  let eof = fd === null
  const close = () => {
    if (fd !== null) {
      try {
        fs.closeSync(fd)
      } catch {
        // already closed
      }
      fd = null
    }
    eof = true
  }
  const read = (): string | null => {
    for (;;) {
      if (qi < queue.length) return queue[qi++]
      if (eof) {
        if (carry.length > 0) {
          const last = Buffer.concat(carry).toString('utf8')
          carry = []
          return last.length > 0 ? last : null
        }
        return null
      }
      const n = fs.readSync(fd!, buf, 0, CHUNK, null)
      if (n === 0) {
        close()
        continue
      }
      let start = 0
      const lines: string[] = []
      for (let i = 0; i < n; i++) {
        if (buf[i] === 10) {
          carry.push(Buffer.from(buf.subarray(start, i)))
          let line = Buffer.concat(carry).toString('utf8')
          carry = []
          if (line.endsWith('\r')) line = line.slice(0, -1)
          if (line.length > 0) lines.push(line)
          start = i + 1
        }
      }
      if (start < n) carry.push(Buffer.from(buf.subarray(start, n)))
      queue = lines
      qi = 0
    }
  }
  return Object.assign(read, {close})
}

/** Keeps track of the readers a planning round opened so they can all be closed afterwards. */
export class ReaderSet {
  private readonly open = new Set<ClosableReader>()

  source(file: string): () => LineReader {
    return () => {
      const r = lineReader(file)
      this.open.add(r)
      return r
    }
  }

  closeAll(): void {
    for (const r of this.open) r.close()
    this.open.clear()
  }
}

/** Appends lines to a file through one handle, in big writes; [close] fsyncs. */
export class LineWriter {
  private handle: fsp.FileHandle | null = null
  private buffer: string[] = []
  private size = 0
  count = 0

  private constructor(readonly file: string) {}

  static async create(file: string): Promise<LineWriter> {
    await fsp.mkdir(path.dirname(file), {recursive: true})
    const w = new LineWriter(file)
    w.handle = await fsp.open(file, 'w')
    return w
  }

  async write(line: string): Promise<void> {
    this.buffer.push(line)
    this.size += line.length + 1
    this.count++
    if (this.size >= 256 * 1024) await this.flush()
  }

  async flush(): Promise<void> {
    if (this.buffer.length === 0 || !this.handle) return
    const text = this.buffer.join('\n') + '\n'
    this.buffer = []
    this.size = 0
    await this.handle.write(text)
  }

  async close(sync = true): Promise<void> {
    if (!this.handle) return
    await this.flush()
    if (sync) await this.handle.sync()
    await this.handle.close()
    this.handle = null
  }
}

/** A record being sorted: ordered by (key, path, seq). [text] is what is written out. */
export interface SortRec {
  key: string
  path: string
  seq: number
  text: string
}

export const compareRec = (a: SortRec, b: SortRec): number => {
  if (a.key !== b.key) return a.key < b.key ? -1 : 1
  if (a.path !== b.path) return a.path < b.path ? -1 : 1
  return a.seq - b.seq
}

/**
 * Sorts any number of records with bounded memory: chunks are sorted and written as runs, then merged. Used for the
 * inventories (a directory walk does not come out in name-key order) and for folding journal commits into the baseline.
 */
export class ExternalSorter {
  private chunk: SortRec[] = []
  private readonly runs: string[] = []
  private done = false

  constructor(private readonly dir: string, private readonly chunkSize = 50_000, private readonly tag = 'run') {}

  async add(rec: SortRec): Promise<void> {
    this.chunk.push(rec)
    if (this.chunk.length >= this.chunkSize) await this.spill()
  }

  private async spill(): Promise<void> {
    if (this.chunk.length === 0) return
    this.chunk.sort(compareRec)
    const file = path.join(this.dir, `${this.tag}-${this.runs.length}.sorted`)
    const w = await LineWriter.create(file)
    for (const r of this.chunk) await w.write(JSON.stringify([r.key, r.path, r.seq, r.text]))
    await w.close(false)
    this.runs.push(file)
    this.chunk = []
  }

  /** The records in order. Cleans up the run files when finished or abandoned. */
  async *sorted(): AsyncGenerator<SortRec> {
    if (this.done) throw new Error('already consumed')
    this.done = true
    try {
      if (this.runs.length === 0) {
        this.chunk.sort(compareRec)
        for (const r of this.chunk) yield r
        this.chunk = []
        return
      }
      await this.spill()
      const readers = this.runs.map((f) => lineReader(f))
      try {
        const heads: (SortRec | null)[] = readers.map((r) => parseRec(r()))
        for (;;) {
          let best = -1
          for (let i = 0; i < heads.length; i++) {
            const h = heads[i]
            if (h && (best < 0 || compareRec(h, heads[best]!) < 0)) best = i
          }
          if (best < 0) return
          const out = heads[best]!
          heads[best] = parseRec(readers[best]())
          yield out
        }
      } finally {
        for (const r of readers) r.close()
      }
    } finally {
      for (const f of this.runs) await fsp.rm(f, {force: true}).catch(() => undefined)
    }
  }
}

function parseRec(line: string | null): SortRec | null {
  if (line === null) return null
  const [key, p, seq, text] = JSON.parse(line) as [string, string, number, string]
  return {key, path: p, seq, text}
}

export async function tempDir(prefix: string): Promise<string> {
  return fsp.mkdtemp(path.join(os.tmpdir(), prefix))
}

export {compareNames, fastKey}
export type {NameRulesId}
