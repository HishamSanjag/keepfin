// Music on this PC: what this device keeps in <userData>/device-music.json (never in the Keepfin folder, never synced): the
// switch, the folders, hidden songs, the cache of what the scan read (size, time, tags), and the songs' favorites, plays,
// resume points and playlist entries.
import fs from 'node:fs/promises'
import path from 'node:path'
import {renameWithRetry} from '../../fsutil.js'

export type DeviceItem = {
  /** The file as the scan found it (the key is its case-folded form). */
  path: string
  id: string
  size: number
  mtimeMs: number
  title: string
  artist?: string
  album?: string
  albumArtist?: string
  duration: number
  track?: number
  year?: number
  hasArt: boolean
  /** Read, but not music (shorter than 30 seconds): remembered so the file is not read again. */
  skipped?: boolean
  fav?: boolean
  plays?: number
  lastPlayedAt?: number
  resume?: number
}

export type DeviceFile = {
  version: 1
  enabled: boolean
  /** Undefined until first switched on: then the host's Music folder. */
  folders?: string[]
  excluded: string[]
  hidden: string[]
  items: Record<string, DeviceItem>
  playlists: Record<string, string[]>
}

const empty = (): DeviceFile => ({version: 1, enabled: false, excluded: [], hidden: [], items: {}, playlists: {}})
const strings = (value: unknown) => (Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === 'string') : [])
const number = (value: unknown) => (typeof value === 'number' && Number.isFinite(value) ? value : undefined)

function sanitizeItem(raw: unknown): DeviceItem | undefined {
  if (!raw || typeof raw !== 'object') return
  const item = raw as DeviceItem
  if (typeof item.path !== 'string' || !path.isAbsolute(item.path) || typeof item.id !== 'string' || !/^dm-[A-Za-z0-9_-]+$/.test(item.id)) return
  if (typeof item.title !== 'string' || number(item.size) === undefined || number(item.mtimeMs) === undefined) return
  return item
}

export class DeviceStore {
  data: DeviceFile = empty()
  private timer?: NodeJS.Timeout
  private chain: Promise<void> = Promise.resolve()
  private counter = 0

  constructor(private readonly file: string, private readonly delay = 1000) {}

  async load() {
    let raw: Partial<DeviceFile> = {}
    try {
      raw = JSON.parse(await fs.readFile(this.file, 'utf8'))
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'ENOENT') await fs.copyFile(this.file, `${this.file}.corrupt-${Date.now()}`).catch(() => {})
    }
    const data = empty()
    data.enabled = raw.enabled === true
    if (Array.isArray(raw.folders)) data.folders = strings(raw.folders).filter((folder) => path.isAbsolute(folder))
    data.excluded = strings(raw.excluded)
    data.hidden = strings(raw.hidden)
    for (const [key, value] of Object.entries(raw.items && typeof raw.items === 'object' ? raw.items : {})) {
      const item = sanitizeItem(value)
      if (item) data.items[key] = item
    }
    for (const [id, list] of Object.entries(raw.playlists && typeof raw.playlists === 'object' ? raw.playlists : {})) data.playlists[id] = strings(list)
    this.data = data
  }

  /** One debounced atomic write. */
  save() {
    if (this.timer) return
    this.timer = setTimeout(() => {
      this.timer = undefined
      void this.flush().catch((error) => console.error('Could not save Music on this PC:', error))
    }, this.delay)
  }

  /** Writes now (and waits for a write in flight). */
  flush(): Promise<void> {
    clearTimeout(this.timer)
    this.timer = undefined
    const text = JSON.stringify(this.data)
    const job = this.chain.then(async () => {
      await fs.mkdir(path.dirname(this.file), {recursive: true})
      const temporary = `${this.file}.${process.pid}.${++this.counter}.tmp`
      await fs.writeFile(temporary, text)
      await renameWithRetry(temporary, this.file)
    })
    this.chain = job.catch(() => {})
    return job
  }
}