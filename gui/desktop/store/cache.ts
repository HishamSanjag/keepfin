// The projection cache (dev-docs/plans/KEEPFIN-FOLDER-DESKTOP-SPEC.md section 10): the library as the app shows it, kept in
// the profile's device folder so that opening it does not need the store. `meta.json` carries the key (the operations the
// cache reflects); `base.ndjson` holds every entry and collection, `journal.ndjson` the changes since, appended as they
// are made. A cache whose key differs from the store's is ignored, never repaired.
//
//   journal line:  {"e": <entry>}  {"d": "<item id>"}  {"c": <collection>}  {"dc": "<collection id>"}
import fs from 'node:fs/promises'
import {StringDecoder} from 'node:string_decoder'
import path from 'node:path'
import type {SyncJson, SyncSpace} from '../../shared/core/sync.js'
import type {Collection, Space} from '../../shared/library.js'
import type {LibraryEntry} from '../library/index.js'
import {DEVICE_ITEM_KEYS} from './device.js'
import type {Routes} from './mapping.js'
import {writeJsonAtomic, readJson} from './atomic.js'

export const CACHE_FORMAT = 1

export type CacheMeta = {
  format: typeof CACHE_FORMAT
  libraryId: string
  replicaId: string
  /** The Keepfin folder the cached paths are under. */
  root?: string
  /** The operations the cache reflects, as the store's listing key. */
  key: string
  /** Which base and journal files belong together (the meta is what switches generations, atomically). */
  gen: number
  spaces: Space[]
  routes: Routes
  /** The space views and profile the projection used (a later partial projection needs them). */
  spaceViews: SyncSpace[]
  profile: Record<string, SyncJson>
  /** This actor's own play counters. */
  own: Record<string, number>
  baseItems: number
  baseCollections: number
  /** Bytes of the journal this meta covers. */
  journalBytes: number
  journalLines: number
}

export type CacheRead = {meta: CacheMeta; items: LibraryEntry[]; collections: Collection[]}

/** Entries are cached without the device-only keys that live in placement-state (those are laid over on load). */
const STRIP = new Set<string>(DEVICE_ITEM_KEYS.filter((key) => key !== 'size' && key !== 'modifiedAt' && key !== 'missing'))

export const entryLine = (entry: LibraryEntry): string => {
  const copy: Record<string, unknown> = {}
  for (const key of Object.keys(entry)) if (!STRIP.has(key)) copy[key] = (entry as Record<string, unknown>)[key]
  return JSON.stringify({e: copy})
}
export const collectionLine = (collection: Collection) => JSON.stringify({c: collection})
export const removedItemLine = (id: string) => JSON.stringify({d: id})
export const removedCollectionLine = (id: string) => JSON.stringify({dc: id})

const yieldNow = () => new Promise<void>((resolve) => setImmediate(resolve))

export class ProjectionCache {
  private pending: string[] = []
  private timer?: NodeJS.Timeout
  private writing: Promise<void> = Promise.resolve()
  private meta?: CacheMeta
  private metaSource?: () => CacheMeta

  constructor(
    private readonly dir: string,
    private readonly delay = 3000,
  ) {}

  private file(name: string) {
    return path.join(this.dir, name)
  }

  /** The cache when it reflects exactly `key`, read in slices that give the thread back in between. */
  async read(expected: {key: string; libraryId: string; replicaId: string; root?: string}, options: {chunk?: number; onProgress?(done: number, total: number): void} = {}): Promise<CacheRead | undefined> {
    const meta = await readJson<CacheMeta>(this.file('meta.json')).catch(() => undefined)
    if (!meta || meta.format !== CACHE_FORMAT || meta.libraryId !== expected.libraryId || meta.replicaId !== expected.replicaId || meta.key !== expected.key) return undefined
    // the paths in it are under the folder where it was written: after Change location the store is read again
    if (expected.root !== undefined && meta.root !== expected.root) return undefined
    const chunk = options.chunk ?? 2000
    try {
      const items = new Map<string, LibraryEntry>()
      const collections = new Map<string, Collection>()
      let done = 0
      const total = meta.baseItems + meta.baseCollections + meta.journalLines
      type CacheRecord = {e?: LibraryEntry; d?: string; c?: Collection; dc?: string}
      const take = (record: CacheRecord) => {
        if (record.e) items.set(record.e.id, record.e)
        else if (record.d) items.delete(record.d)
        else if (record.c) collections.set(record.c.id, record.c)
        else if (record.dc) collections.delete(record.dc)
      }
      // One parse per line: parsing a whole piece as one array was measured no faster at 200,000 items and blocked longer.
      const feed = async (text: string) => {
        let from = 0
        while (from < text.length) {
          let to = text.indexOf('\n', from)
          if (to < 0) break // a torn last line
          const line = text.slice(from, to)
          from = to + 1
          if (!line) continue
          take(JSON.parse(line) as CacheRecord)
          if (++done % chunk === 0) {
            options.onProgress?.(done, total)
            await yieldNow()
          }
        }
      }
      // A file is read in pieces of about a megabyte (never as one huge string), each cut at its last complete line.
      const readLines = async (file: string, limit: number | undefined): Promise<boolean> => {
        const handle = await fs.open(file, 'r')
        try {
          const total = limit ?? (await handle.stat()).size
          const decoder = new StringDecoder('utf8')
          const piece = Buffer.alloc(1 << 20)
          let carry = ''
          for (let at = 0; at < total; ) {
            const {bytesRead} = await handle.read(piece, 0, Math.min(piece.length, total - at), at)
            if (bytesRead === 0) return false
            at += bytesRead
            const text = carry + decoder.write(piece.subarray(0, bytesRead))
            const cut = text.lastIndexOf('\n') + 1
            carry = text.slice(cut)
            await feed(text.slice(0, cut))
          }
          return true
        } finally {
          await handle.close()
        }
      }
      if (!(await readLines(this.file(`base.${meta.gen}.ndjson`), undefined))) return undefined
      if (meta.journalBytes > 0 && !(await readLines(this.file(`journal.${meta.gen}.ndjson`), meta.journalBytes))) return undefined
      options.onProgress?.(total, total)
      this.meta = meta
      return {meta, items: [...items.values()], collections: [...collections.values()]}
    } catch {
      return undefined
    }
  }

  /** Queues journal lines; they are written (with the meta) after the delay, or by flush(). */
  record(lines: readonly string[], meta: () => CacheMeta) {
    for (const line of lines) this.pending.push(line)
    this.metaSource = meta
    // A big change (every entry marked once at the first scan) is not held in memory for the delay: it is written in turns.
    if (this.pending.length >= 20_000 && this.meta) {
      void this.flush().catch((error) => console.error('Could not save the library cache:', error))
      return
    }
    if (this.timer || !this.pending.length) return
    this.timer = setTimeout(() => {
      this.timer = undefined
      void this.flush().catch((error) => console.error('Could not save the library cache:', error))
    }, this.delay)
    this.timer.unref?.()
  }

  get journalLines() {
    return (this.meta?.journalLines ?? 0) + this.pending.length
  }

  /** Appends the queued lines to the journal, then writes the meta that covers them. */
  flush(): Promise<void> {
    clearTimeout(this.timer)
    this.timer = undefined
    const run = this.writing.then(() => this.write())
    this.writing = run.catch(() => undefined)
    return run
  }

  private async write() {
    const source = this.metaSource
    // No base yet (it is still being written): the lines wait for it. Replaying a line the base already holds changes nothing.
    if (!source || !this.meta) return
    const lines = this.pending
    this.pending = []
    try {
      let bytes = this.meta.journalBytes
      if (lines.length) {
        const journal = this.file(`journal.${this.meta.gen}.ndjson`)
        // Anything past the covered length is a crashed append: cut it off first.
        await fs.truncate(journal, bytes).catch(() => undefined)
        const handle = await fs.open(journal, 'a')
        try {
          // In pieces, so that a journal of a whole library is neither one huge string nor one long block.
          for (let from = 0; from < lines.length; from += 2000) {
            const text = `${lines.slice(from, from + 2000).join('\n')}\n`
            await handle.write(text, null, 'utf8')
            bytes += Buffer.byteLength(text)
            if (lines.length > 2000) await yieldNow()
          }
          await handle.sync()
        } finally {
          await handle.close()
        }
      }
      const meta = {...source(), gen: this.meta.gen, baseItems: this.meta.baseItems, baseCollections: this.meta.baseCollections, journalBytes: bytes, journalLines: this.meta.journalLines + lines.length}
      await writeJsonAtomic(this.file('meta.json'), meta)
      this.meta = meta
    } catch (error) {
      this.pending = [...lines, ...this.pending]
      throw error
    }
  }

  /** Writes every entry and collection as the new base and empties the journal (cold open, compaction). */
  async writeBase(items: readonly LibraryEntry[], collections: readonly Collection[], meta: CacheMeta, options: {chunk?: number} = {}): Promise<void> {
    const chunk = options.chunk ?? 5000
    const run = this.writing.then(async () => {
      await fs.mkdir(this.dir, {recursive: true})
      const gen = (this.meta?.gen ?? 0) + 1
      const old = this.meta?.gen
      const temp = this.file(`base.${gen}.ndjson`)
      const handle = await fs.open(temp, 'w')
      try {
        let batch: string[] = []
        const flush = async () => {
          if (batch.length) await handle.write(`${batch.join('\n')}\n`)
          batch = []
        }
        for (let i = 0; i < items.length; i++) {
          batch.push(entryLine(items[i]))
          if ((i + 1) % chunk === 0) {
            await flush()
            await yieldNow()
          }
        }
        for (const collection of collections) batch.push(collectionLine(collection))
        await flush()
        await handle.sync()
      } finally {
        await handle.close()
      }
      // The new generation becomes the cache when its meta lands; until then the old files are the cache.
      const written = {...meta, gen, baseItems: items.length, baseCollections: collections.length, journalBytes: 0, journalLines: 0}
      await writeJsonAtomic(this.file('meta.json'), written)
      this.meta = written
      if (this.pending.length && this.metaSource) this.record([], this.metaSource)
      if (old !== undefined) {
        await fs.rm(this.file(`base.${old}.ndjson`), {force: true})
        await fs.rm(this.file(`journal.${old}.ndjson`), {force: true})
      }
    })
    this.writing = run.catch(() => undefined)
    return run
  }

  async close() {
    await this.flush().catch(() => undefined)
  }
}
