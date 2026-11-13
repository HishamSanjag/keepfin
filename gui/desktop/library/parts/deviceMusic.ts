// Music on this PC: lists the audio already on this computer, in place (nothing is copied or moved). The files are found in
// the person's chosen folders, read once (the cache in device-music.json keeps size, time and tags, so a restart reads nothing
// that did not change) and shown as entries of the `device` space. They never reach the synced library or the Keepfin folder.
import {watch as fsWatch, type FSWatcher} from 'node:fs'
import fs from 'node:fs/promises'
import path from 'node:path'
import {deviceMusicIdOf, deviceMusicIncludes} from '../../../shared/core/deviceMusic.js'
import {DEVICE_SPACE_ID, isDeviceId, type Collection, type DeviceMusicState} from '../../../shared/library.js'
import {Slicer} from '../../store/slices.js'
import type {LibraryEntry} from '../index.js'
import {readTags} from '../tags.js'
import type {LibraryCore} from './core.js'
import {DeviceStore, type DeviceItem} from './deviceState.js'
import {isInside, itemIdFor, keyOf, titleFromName} from './sanitize.js'
import {intermediate, transient, walkMedia} from './walk.js'

const STAT_GROUP = 64
const READERS = 4
const PUSH_EVERY = 100
// anything that passes the folder and file-type rules: the length is checked once the tags are read
const ANY_LENGTH_MS = 24 * 3_600_000

const entryOf = (item: DeviceItem): LibraryEntry => ({
  id: item.id,
  space: DEVICE_SPACE_ID,
  path: item.path,
  folder: '',
  kind: 'audio',
  title: item.title,
  artist: item.artist,
  album: item.album,
  albumArtist: item.albumArtist,
  track: item.track,
  year: item.year,
  duration: item.duration,
  size: item.size,
  modifiedAt: Math.round(item.mtimeMs),
  addedAt: Math.round(item.mtimeMs),
  hasArt: item.hasArt,
  managed: false,
  // loudness is never measured for these files
  analysed: true,
  sig: `${item.size}:${Math.round(item.mtimeMs)}`,
  favorite: item.fav || undefined,
  plays: item.plays,
  lastPlayedAt: item.lastPlayedAt,
  position: item.resume,
})

export class DeviceMusic {
  private readonly store: DeviceStore
  private running?: Promise<void>
  private again = false
  private scanning = false
  private found = 0
  private count = 0
  private readonly watchers = new Map<string, FSWatcher>()
  private watchTimer?: NodeJS.Timeout
  private readonly noArt = new Set<string>()

  constructor(private readonly host: LibraryCore) {
    const {options} = host
    this.store = new DeviceStore(options.device?.file ?? path.join(path.dirname(options.file), 'device-music.json'), options.saveDelay)
  }

  get enabled() {
    return this.store.data.enabled
  }

  /** Reads device-music.json; when Music on this PC is on, its songs are listed at once (from the cache) and a scan follows. */
  async load() {
    await this.store.load()
    if (!this.enabled) return
    await this.listCached()
    this.start()
  }

  /** True while a scan is running. */
  working() {
    return !!this.running
  }

  state(): DeviceMusicState {
    const {enabled, excluded} = this.store.data
    return {enabled, folders: this.folders(), excluded: [...excluded], scanning: this.scanning, found: this.found, count: this.count}
  }

  private folders(): string[] {
    return [...(this.store.data.folders ?? this.host.options.device?.folders?.() ?? [])]
  }

  /** The entry's favorite, plays and resume point are kept here (this device only), not in the synced library. */
  owns(entry: Pick<LibraryEntry, 'space'>) {
    return entry.space === DEVICE_SPACE_ID
  }

  async close() {
    this.stopWatchers()
    await this.store.flush()
  }

  // ------------------------------------------------------------------ settings

  async setEnabled(on: boolean) {
    if (on === this.enabled) return
    const data = this.store.data
    data.enabled = on
    if (on) {
      data.folders ??= this.host.options.device?.folders?.() ?? []
      await this.listCached()
      this.store.save()
      this.start()
    } else {
      this.stopWatchers()
      this.clear()
      this.store.save()
      this.host.changed(false)
    }
  }

  addFolder(folder: string) {
    const list = this.folders()
    if (list.some((entry) => keyOf(entry) === keyOf(folder))) return
    this.store.data.folders = [...list, path.normalize(folder)]
    this.store.save()
    this.syncWatchers()
    void this.scan()
  }

  removeFolder(folder: string) {
    this.store.data.folders = this.folders().filter((entry) => keyOf(entry) !== keyOf(folder))
    this.store.save()
    this.syncWatchers()
    void this.scan()
  }

  /** Songs removed from Keepfin come back (the next scan lists them again). */
  unhide() {
    this.store.data.hidden = []
    this.store.save()
    void this.scan()
  }

  /** "Remove from Keepfin": the files stay, the songs are hidden and stay hidden. */
  hide(ids: readonly string[]) {
    const gone = this.forget(ids)
    for (const key of gone) if (!this.store.data.hidden.includes(key)) this.store.data.hidden.push(key)
    return gone.length
  }

  /** Drops songs whose files were deleted. Returns their keys. */
  forget(ids: readonly string[]): string[] {
    if (!ids.length) return []
    const wanted = new Set(ids)
    const keys: string[] = []
    for (const [key, item] of Object.entries(this.store.data.items)) if (wanted.has(item.id)) keys.push(key)
    for (const key of keys) {
      const id = this.store.data.items[key].id
      delete this.store.data.items[key]
      this.unlist(key, id)
    }
    for (const [collection, list] of Object.entries(this.store.data.playlists)) this.store.data.playlists[collection] = list.filter((id) => !wanted.has(id))
    this.store.save()
    this.host.changed(false, ids, true)
    return keys
  }

  // ------------------------------------------------------------------ what the person does with a song

  /** Copies the entry's favorite, plays and resume point into this device's file, and tells the window. */
  keep(entry: LibraryEntry) {
    const item = this.store.data.items[keyOf(entry.path)]
    if (!item) return
    item.fav = entry.favorite || undefined
    item.plays = entry.plays
    item.lastPlayedAt = entry.lastPlayedAt
    item.resume = entry.position
    this.store.save()
    this.host.changed(false, [entry.id], false)
  }

  /** A playlist's songs from this device, after its synced ones (the synced playlist never names them). */
  overlay(collection: Collection): Collection {
    const extra = this.enabled ? (this.store.data.playlists[collection.id] ?? []).filter((id) => this.host.entries().has(id)) : []
    return extra.length ? {...collection, itemIds: [...collection.itemIds, ...extra]} : collection
  }

  /** This device's songs of a playlist become exactly these (a playlist saved with a new list). */
  replacePlaylist(collectionId: string, ids: readonly string[]) {
    const valid = [...new Set(ids)].filter((id) => isDeviceId(id) && this.host.entries().has(id))
    if (valid.join() !== (this.store.data.playlists[collectionId] ?? []).join()) this.setPlaylist(collectionId, valid)
  }

  addToPlaylist(collectionId: unknown, ids: unknown) {
    if (typeof collectionId !== 'string' || !Array.isArray(ids)) return
    const list = this.store.data.playlists[collectionId] ?? []
    const add = ids.filter((id) => isDeviceId(id) && this.host.entries().has(id) && !list.includes(id))
    if (add.length) this.setPlaylist(collectionId, [...list, ...add])
  }

  removeFromPlaylist(collectionId: unknown, ids: unknown) {
    if (typeof collectionId !== 'string' || !Array.isArray(ids)) return
    const gone = new Set(ids)
    const list = this.store.data.playlists[collectionId] ?? []
    if (list.some((id) => gone.has(id))) this.setPlaylist(collectionId, list.filter((id) => !gone.has(id)))
  }

  reorderPlaylist(collectionId: unknown, ids: unknown) {
    if (typeof collectionId !== 'string' || !Array.isArray(ids)) return
    const list = this.store.data.playlists[collectionId] ?? []
    const members = new Set(list)
    const ordered = ids.filter((id) => members.has(id))
    this.setPlaylist(collectionId, [...ordered, ...list.filter((id) => !ordered.includes(id))])
  }

  private setPlaylist(collectionId: string, list: string[]) {
    if (list.length) this.store.data.playlists[collectionId] = list
    else delete this.store.data.playlists[collectionId]
    this.store.save()
    this.host.changed(false, [], true)
  }

  /** The cover of a song, taken from the file the first time it is asked for. */
  async artPath(id: string): Promise<string | undefined> {
    const found = await this.host.art().find(id)
    if (found || this.noArt.has(id)) return found
    const entry = this.host.entries().get(id)
    if (!entry) return
    const picture = (await readTags(entry.path, true).catch(() => undefined))?.picture
    if (picture && (await this.host.art().put(id, picture.data, picture.format).catch(() => false))) return this.host.art().find(id)
    this.noArt.add(id)
    const item = this.store.data.items[keyOf(entry.path)]
    if (item) item.hasArt = false
    entry.hasArt = false
    this.store.save()
    this.host.changed(false, [id], false)
  }

  // ------------------------------------------------------------------ the scan

  /** A pass over the folders: lists new songs, reads only files that changed, drops songs whose files are gone. */
  scan(): Promise<void> {
    if (!this.enabled || this.host.closing()) return Promise.resolve()
    if (this.running) {
      this.again = true
      return this.running
    }
    const run = this.pass().finally(() => {
      this.running = undefined
      if (this.again && !this.host.closing()) {
        this.again = false
        void this.scan()
      }
    })
    this.running = run
    return run
  }

  /** Lists the songs of the cache, in slices that give the thread back. */
  private async listCached() {
    const slicer = new Slicer()
    const ids: string[] = []
    for (const item of Object.values(this.store.data.items)) {
      if (!item.skipped) this.put(item, ids)
      if (ids.length >= PUSH_EVERY) this.flushIds(ids)
      await slicer.tick()
    }
    this.flushIds(ids)
  }

  private start() {
    this.syncWatchers()
    void this.scan()
  }

  private async pass() {
    this.scanning = true
    this.found = 0
    this.host.changed(false)
    try {
      const live: string[] = []
      const unavailable: string[] = []
      for (const folder of this.folders()) ((await fs.stat(folder).catch(() => undefined))?.isDirectory() ? live : unavailable).push(folder)
      const seen = new Set<string>()
      for (const folder of live) {
        if (this.host.closing()) return
        await this.scanFolder(folder, seen)
      }
      this.reconcile(live, unavailable, seen)
    } finally {
      this.scanning = false
      this.store.save()
      this.host.changed(false)
    }
  }

  private isLeftOut(folder: string) {
    return this.store.data.excluded.some((entry) => keyOf(entry) === keyOf(folder) || isInside(entry, folder))
  }

  /** Folders Keepfin owns (every space, the Keepfin folder): files there are Keepfin's, never listed here. */
  private owned() {
    return [...this.host.spaces().map((space) => space.folder), this.host.options.device?.keepfinRoot?.() ?? '']
  }

  private isOwned(file: string) {
    return this.owned().some((folder) => !!folder && (isInside(folder, file) || keyOf(folder) === keyOf(file)))
  }

  private async scanFolder(root: string, seen: Set<string>) {
    const hidden = new Set(this.store.data.hidden)
    const wanted: {file: string; key: string}[] = []
    for (const file of await walkMedia(root, {kind: 'audio', skip: (folder) => this.isLeftOut(folder) || this.isOwned(folder)})) {
      const key = keyOf(file)
      if (seen.has(key) || hidden.has(key) || this.isOwned(file)) continue
      const known = this.host.byPath().get(key)
      if (known && !isDeviceId(known)) continue
      // the folder and file-type rules (the shared core); the length is checked once the tags are read
      const relative = path.relative(root, path.dirname(file)).split(path.sep).filter(Boolean).join('/')
      if (!deviceMusicIncludes({relativePath: relative ? `${relative}/` : '', displayName: path.basename(file), durationMs: ANY_LENGTH_MS, mime: ''})) continue
      seen.add(key)
      wanted.push({file, key})
    }
    const fresh: {file: string; key: string; size: number; mtimeMs: number}[] = []
    const ids: string[] = []
    const slicer = new Slicer()
    for (let from = 0; from < wanted.length; from += STAT_GROUP) {
      const group = wanted.slice(from, from + STAT_GROUP)
      const stats = await Promise.all(group.map(({file}) => fs.stat(file).catch(() => undefined)))
      group.forEach(({file, key}, at) => {
        const stat = stats[at]
        if (!stat?.isFile()) return seen.delete(key)
        const cached = this.store.data.items[key]
        if (cached && cached.size === stat.size && Math.round(cached.mtimeMs) === Math.round(stat.mtimeMs)) {
          if (!cached.skipped) this.put(cached, ids)
        } else fresh.push({file, key, size: stat.size, mtimeMs: stat.mtimeMs})
      })
      await slicer.tick()
    }
    this.flushIds(ids)
    let next = 0
    const reader = async () => {
      while (next < fresh.length && !this.host.closing()) {
        const {file, key, size, mtimeMs} = fresh[next++]
        const tags = await readTags(file, false)
        const item: DeviceItem = {path: file, id: deviceMusicIdOf('pc', itemIdFor(file)), size, mtimeMs, title: tags.title ?? titleFromName(file), duration: tags.duration ?? 0, hasArt: true}
        if (tags.artist) item.artist = tags.artist
        if (tags.album) item.album = tags.album
        if (tags.albumArtist) item.albumArtist = tags.albumArtist
        if (tags.track) item.track = tags.track
        if (tags.year) item.year = tags.year
        // fav, plays and resume survive a changed file
        const before = this.store.data.items[key]
        if (before) Object.assign(item, {fav: before.fav, plays: before.plays, lastPlayedAt: before.lastPlayedAt, resume: before.resume})
        if (item.duration * 1000 < 30_000) item.skipped = true
        this.store.data.items[key] = item
        if (item.skipped) this.unlist(key, item.id, ids)
        else this.put(item, ids)
        if (ids.length >= PUSH_EVERY) this.flushIds(ids)
      }
    }
    await Promise.all(Array.from({length: READERS}, reader))
    this.flushIds(ids)
  }

  /** What was not seen: files that are gone leave; a folder that is not available keeps its songs (marked missing). */
  private reconcile(live: string[], unavailable: string[], seen: Set<string>) {
    const ids: string[] = []
    for (const [key, item] of Object.entries(this.store.data.items)) {
      const inside = (folders: string[]) => folders.some((folder) => isInside(folder, item.path))
      if (inside(live)) {
        if (!seen.has(key)) {
          delete this.store.data.items[key]
          this.unlist(key, item.id, ids)
        }
      } else if (inside(unavailable)) {
        const entry = this.host.entries().get(item.id)
        if (entry && !entry.missing) {
          entry.missing = true
          ids.push(item.id)
        }
      } else {
        delete this.store.data.items[key]
        this.unlist(key, item.id, ids)
      }
    }
    this.flushIds(ids)
  }

  // ------------------------------------------------------------------ entries

  /** Lists a song (a new entry, or the entry brought up to date); the id joins [ids] only when the window must hear of it. */
  private put(item: DeviceItem, ids: string[] = []) {
    const entries = this.host.entries()
    const key = keyOf(item.path)
    const old = entries.get(item.id)
    this.found++
    if (old && old.sig === `${item.size}:${Math.round(item.mtimeMs)}` && old.title === item.title && !old.missing) return
    entries.set(item.id, entryOf(item))
    this.host.byPath().set(key, item.id)
    if (!old) this.count++
    ids.push(item.id)
    this.noArt.delete(item.id)
  }

  private unlist(key: string, id: string, ids: string[] = []) {
    const entries = this.host.entries()
    if (entries.delete(id)) this.count--
    if (this.host.byPath().get(key) === id) this.host.byPath().delete(key)
    ids.push(id)
  }

  /** Every song leaves the library's lists (Music on this PC was switched off). */
  private clear() {
    const ids = [...this.host.entries().values()].filter((entry) => this.owns(entry)).map((entry) => entry.id)
    for (const id of ids) {
      const entry = this.host.entries().get(id)!
      this.host.entries().delete(id)
      this.host.byPath().delete(keyOf(entry.path))
    }
    this.count = 0
    this.found = 0
    this.host.changed(false, ids, true)
  }

  private flushIds(ids: string[]) {
    if (!ids.length) return
    this.host.changed(false, ids.splice(0), true)
  }

  // ------------------------------------------------------------------ watching

  private syncWatchers() {
    if (this.host.options.watch === false) return
    const wanted = new Set(this.folders().map((folder) => path.normalize(folder)))
    for (const [folder, watcher] of this.watchers)
      if (!wanted.has(folder)) {
        watcher.close()
        this.watchers.delete(folder)
      }
    for (const folder of wanted) {
      if (this.watchers.has(folder)) continue
      try {
        const watcher = fsWatch(folder, {recursive: true, persistent: false}, (_event, name) => {
          if (typeof name === 'string' && (transient.test(name) || intermediate.test(name))) return
          this.schedule()
        })
        watcher.on('error', () => {
          watcher.close()
          this.watchers.delete(folder)
        })
        this.watchers.set(folder, watcher)
      } catch {
        // The folder is not there (a drive that is out): the next scan marks its songs missing.
      }
    }
  }

  private schedule() {
    if (this.watchTimer || this.host.closing()) return
    this.watchTimer = setTimeout(() => {
      this.watchTimer = undefined
      void this.scan().catch(() => {})
    }, this.host.options.watchDelay ?? 800)
  }

  private stopWatchers() {
    clearTimeout(this.watchTimer)
    this.watchTimer = undefined
    for (const watcher of this.watchers.values()) watcher.close()
    this.watchers.clear()
  }
}