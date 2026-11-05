import fs from 'node:fs/promises'
import path from 'node:path'
import {createHash} from 'node:crypto'

export type ThumbnailImage = {mime: string; bytes: Buffer}
const imageTypes = new Set(['image/jpeg', 'image/png', 'image/webp'])
const maxImageBytes = 2_000_000
type Source = {url?: string; file?: string}

/** Job artwork persists with history. Only temporary inspection artwork is evictable. */
export class ThumbnailStore {
  private requests = new Map<string, Promise<string | undefined>>()
  private running = 0
  private waiting: (() => void)[] = []
  private retained?: Set<string>
  private removed = new Set<string>()
  /** Artwork of removed history entries that waits for an Undo (job id → the timer that lets it go). */
  private deferred = new Map<string, ReturnType<typeof setTimeout>>()
  private pruning = Promise.resolve()
  constructor(
    private directory: string,
    private options: {
      fetch?: typeof fetch
      resize?: (image: ThumbnailImage) => ThumbnailImage | undefined
      frame?: (file: string) => Promise<ThumbnailImage | undefined>
      temporaryLimitBytes?: number
      temporaryTargetBytes?: number
    } = {},
  ) {}

  private hash(value: string) {
    return createHash('sha256').update(value).digest('hex')
  }
  private name(key: string) {
    return `${key.startsWith('job:') ? 'job' : 'url'}-${this.hash(key)}.json`
  }
  private file(key: string) {
    return path.join(this.directory, this.name(key))
  }
  private kept(key: string) {
    return (
      !this.removed.has(key) &&
      (!key.startsWith('job:') ||
        !this.retained ||
        this.retained.has(this.name(key)))
    )
  }
  private async read(key: string): Promise<ThumbnailImage | undefined> {
    try {
      const file = this.file(key)
      if ((await fs.stat(file)).size > 2_800_000) return
      const item = JSON.parse(await fs.readFile(file, 'utf8'))
      if (
        !imageTypes.has(item.mime) ||
        typeof item.data !== 'string' ||
        item.data.length > 2_700_000 ||
        !/^[A-Za-z0-9+/]*={0,2}$/.test(item.data)
      )
        return
      const bytes = Buffer.from(item.data, 'base64')
      if (!bytes.length || bytes.length > maxImageBytes) return
      return {mime: item.mime, bytes}
    } catch {
      return
    }
  }
  private async write(key: string, image: ThumbnailImage) {
    if (!this.kept(key)) return
    await fs.mkdir(this.directory, {recursive: true})
    const target = this.file(key)
    const temporary = target + '.tmp'
    try {
      await fs.writeFile(
        temporary,
        JSON.stringify({
          mime: image.mime,
          data: image.bytes.toString('base64'),
        }),
      )
      if (!this.kept(key)) return
      await fs.rename(temporary, target)
      if (!this.kept(key)) await fs.rm(target, {force: true})
    } finally {
      await fs.rm(temporary, {force: true})
    }
  }
  private async fetchImage(url: string): Promise<ThumbnailImage | undefined> {
    const source = new URL(url)
    if (
      !['https:', 'http:'].includes(source.protocol) ||
      source.username ||
      source.password ||
      url.length > 4096
    )
      return
    const response = await (this.options.fetch || fetch)(url, {
      signal: AbortSignal.timeout(10_000),
      headers: {Accept: 'image/jpeg,image/png,image/webp'},
    })
    const mime =
      response.headers
        .get('content-type')
        ?.split(';')[0]
        .trim()
        .toLowerCase() || ''
    if (
      !response.ok ||
      !imageTypes.has(mime) ||
      Number(response.headers.get('content-length')) > maxImageBytes
    ) {
      await response.body?.cancel()
      return
    }
    const reader = response.body?.getReader()
    if (!reader) return
    const chunks: Buffer[] = []
    let size = 0
    try {
      while (true) {
        const result = await reader.read()
        if (result.done) break
        size += result.value.byteLength
        if (size > maxImageBytes) {
          await reader.cancel()
          return
        }
        chunks.push(Buffer.from(result.value))
      }
    } finally {
      reader.releaseLock()
    }
    return {mime, bytes: Buffer.concat(chunks)}
  }
  private async acquire() {
    if (this.running < 2) {
      this.running++
      return
    }
    // Release transfers ownership of its occupied slot directly to this waiter.
    await new Promise<void>((resolve) => this.waiting.push(resolve))
  }
  private release() {
    const next = this.waiting.shift()
    if (next) next()
    else this.running--
  }
  async get(source: Source, jobId?: string): Promise<string | undefined> {
    if (!jobId && !source.url) return
    const key = jobId ? 'job:' + jobId : 'url:' + source.url
    if (!this.kept(key)) return
    const pending = this.requests.get(key)
    if (pending) return pending
    const request = this.load(key, source, jobId)
      .catch(() => undefined)
      .finally(() => this.requests.delete(key))
    this.requests.set(key, request)
    return request
  }
  private async load(key: string, source: Source, jobId?: string) {
    let image = await this.read(key)
    if (image)
      return `data:${image.mime};base64,${image.bytes.toString('base64')}`
    if (source.url) {
      if (jobId) await this.requests.get('url:' + source.url)
      image = await this.read('url:' + source.url)
    }
    await this.acquire()
    try {
      if (!this.kept(key)) return
      // Inspection artwork is promoted before trying local media or expiring provider URLs.
      if (!image && source.file)
        image = await this.options.frame?.(source.file).catch(() => undefined)
      if (!image && source.url)
        image = await this.fetchImage(source.url).catch(() => undefined)
      if (
        !image ||
        !image.bytes.length ||
        image.bytes.length > maxImageBytes ||
        !imageTypes.has(image.mime)
      )
        return
      if (this.options.resize) image = this.options.resize(image)
      if (
        !image ||
        !image.bytes.length ||
        image.bytes.length > maxImageBytes ||
        !imageTypes.has(image.mime)
      )
        return
      await this.write(key, image).catch(() => {})
      if (!jobId) await this.prune().catch(() => {})
      if (!this.kept(key)) return
      return `data:${image.mime};base64,${image.bytes.toString('base64')}`
    } finally {
      this.release()
    }
  }
  async remove(jobId: string) {
    const key = 'job:' + jobId
    this.removed.add(key)
    await fs.rm(this.file(key), {force: true})
  }
  /**
   * Removed history entries keep their artwork for `ms` so Undo can bring them back whole; then it goes like any removed entry's.
   * Call before the entries leave the list: the next retain() leaves these alone.
   */
  defer(jobIds: string[], ms: number) {
    for (const id of jobIds) {
      clearTimeout(this.deferred.get(id))
      const timer = setTimeout(() => {
        this.deferred.delete(id)
        void this.remove(id).catch(() => {})
      }, ms)
      timer.unref?.()
      this.deferred.set(id, timer)
    }
  }
  /** Undo brought these entries back: their artwork stays for good. */
  keep(jobIds: string[]) {
    for (const id of jobIds) {
      clearTimeout(this.deferred.get(id))
      this.deferred.delete(id)
      this.removed.delete('job:' + id)
    }
    this.retained = new Set([...(this.retained ?? []), ...jobIds.map((id) => this.name('job:' + id))])
  }
  async retain(jobIds: string[]) {
    this.retained = new Set([...jobIds, ...this.deferred.keys()].map((id) => this.name('job:' + id)))
    await fs.mkdir(this.directory, {recursive: true})
    const names = await fs.readdir(this.directory)
    await Promise.all(
      names
        .filter(
          (name) =>
            /^job-[a-f0-9]{64}\.json$/.test(name) && !this.retained!.has(name),
        )
        .map((name) => fs.rm(path.join(this.directory, name), {force: true})),
    )
  }
  private prune() {
    this.pruning = this.pruning
      .catch(() => {})
      .then(async () => {
        const names = await fs.readdir(this.directory)
        const entries = (
          await Promise.all(
            names
              .filter((name) => /^url-[a-f0-9]{64}\.json$/.test(name))
              .map(async (name) => {
                try {
                  return {
                    name,
                    stat: await fs.stat(path.join(this.directory, name)),
                  }
                } catch {
                  return undefined
                }
              }),
          )
        ).filter((entry) => entry !== undefined)
        let size = entries.reduce((total, item) => total + item.stat.size, 0)
        const limit = this.options.temporaryLimitBytes ?? 64_000_000
        const target = Math.min(
          this.options.temporaryTargetBytes ?? 48_000_000,
          limit,
        )
        if (size <= limit) return
        for (const entry of entries.sort(
          (a, b) => a.stat.mtimeMs - b.stat.mtimeMs,
        )) {
          if (size <= target) break
          await fs.rm(path.join(this.directory, entry.name), {force: true})
          size -= entry.stat.size
        }
      })
    return this.pruning
  }
}
