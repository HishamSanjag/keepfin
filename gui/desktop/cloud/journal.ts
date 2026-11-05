// Upload-session journal (dev-docs/plans/KEEPFIN-CLOUD-SPEC.md section 4): the session handle and the acknowledged offset of every
// resumable upload, so a restart continues instead of starting again. A session handle carries no token (the Authorization
// header is added per request), so the file holds nothing secret. Writes are atomic (temporary file, then rename).
import fs from 'node:fs/promises'
import path from 'node:path'
import type {UploadSession} from '../../shared/core/cloud.js'

export interface JournalEntry {
  session: UploadSession
  offset: number
  total: number
  contentId: string
  createdAtMs: number
  expiresAtMs?: number
}

export interface UploadJournal {
  get(key: string): Promise<JournalEntry | null>
  put(key: string, entry: JournalEntry): Promise<void>
  update(key: string, offset: number): Promise<void>
  remove(key: string): Promise<void>
}

export class MemoryJournal implements UploadJournal {
  readonly entries = new Map<string, JournalEntry>()
  async get(key: string) {
    return this.entries.get(key) ?? null
  }
  async put(key: string, entry: JournalEntry) {
    this.entries.set(key, entry)
  }
  async update(key: string, offset: number) {
    const e = this.entries.get(key)
    if (e) e.offset = offset
  }
  async remove(key: string) {
    this.entries.delete(key)
  }
}

export class FileJournal implements UploadJournal {
  private cache: Record<string, JournalEntry> | null = null
  private chain: Promise<unknown> = Promise.resolve()
  constructor(private readonly file: string) {}

  private async load(): Promise<Record<string, JournalEntry>> {
    if (this.cache) return this.cache
    try {
      this.cache = JSON.parse(await fs.readFile(this.file, 'utf8')) as Record<string, JournalEntry>
    } catch {
      this.cache = {}
    }
    return this.cache
  }

  private save(): Promise<void> {
    const job = this.chain.then(async () => {
      const tmp = `${this.file}.tmp-${process.pid}`
      await fs.mkdir(path.dirname(this.file), {recursive: true})
      await fs.writeFile(tmp, JSON.stringify(this.cache), 'utf8')
      await fs.rename(tmp, this.file)
    })
    this.chain = job.catch(() => undefined)
    return job
  }

  async get(key: string) {
    return (await this.load())[key] ?? null
  }
  async put(key: string, entry: JournalEntry) {
    ;(await this.load())[key] = entry
    await this.save()
  }
  async update(key: string, offset: number) {
    const all = await this.load()
    if (all[key]) {
      all[key].offset = offset
      await this.save()
    }
  }
  async remove(key: string) {
    const all = await this.load()
    if (key in all) {
      delete all[key]
      await this.save()
    }
  }
}
