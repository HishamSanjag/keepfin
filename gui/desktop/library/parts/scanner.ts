// Scanning and watching: the full pass over the spaces, the reconcile of one space's folder, and the folder watchers.
import {watch as fsWatch, type FSWatcher} from 'node:fs'
import fs from 'node:fs/promises'
import path from 'node:path'
import {type Space} from '../../../shared/library.js'
import type {LibraryEntry} from '../index.js'
import type {LibraryCore} from './core.js'
import {keyOf} from './sanitize.js'
import {intermediate, transient, walkMedia} from './walk.js'


export class Scanner {
  scanning?: Promise<void>
  scannedAt?: number
  readonly problems = new Map<string, string>()
  // folder watching
  private readonly watchers = new Map<string, {folder: string; watcher: FSWatcher; timer?: NodeJS.Timeout}>()
  private deferred = new Set<string>()

  constructor(private readonly core: LibraryCore) {}

  /** Full pass: checks managed files, lists new files in "show other files" spaces and adopts history. */
  scan(): Promise<void> {
    if (this.scanning) return this.scanning
    if (!this.core.settings()?.enabled) return Promise.resolve()
    const run = (async () => {
      this.core.changed(false)
      try {
        await this.core.syncJobs()
        for (const space of this.core.spaces()) await this.reconcileSpace(space)
        this.scannedAt = this.core.now()
      } finally {
        this.scanning = undefined
        this.core.changed()
      }
    })()
    this.scanning = run
    this.core.changed(false)
    return run
  }

  /** Brings the index in line with one space's folder: missing/changed managed files, renames, new files. */
  private async reconcileSpace(space: Space) {
    const folderOk = !!(await fs.stat(space.folder).catch(() => undefined))?.isDirectory()
    const own = [...this.core.entries().values()].filter((entry) => entry.space === space.id)
    if (!folderOk) {
      if (own.some((entry) => entry.managed || space.scanFolder)) this.problems.set(space.id, `The folder for ${space.name} is not available.`)
      else this.problems.delete(space.id)
      this.core.changed(false)
      return
    }
    this.problems.delete(space.id)
    const missing: LibraryEntry[] = []
    for (const entry of own) {
      const stat = await fs.stat(entry.path).catch(() => undefined)
      if (!stat?.isFile()) {
        if (entry.managed) {
          if (!entry.missing) {
            entry.missing = true
            this.core.changed()
          }
          missing.push(entry)
        } else this.core.drop([entry.id])
        continue
      }
      if (entry.missing) {
        entry.missing = undefined
        this.core.changed()
      }
      // a folder moved by Change location keeps each file's size and time, but its lyrics and subtitles are found again beside it
      const dir = path.dirname(entry.path)
      const strayed = [entry.lrc, entry.infoJson, entry.videoPath, ...(entry.subPaths ?? [])].some((file) => file && path.dirname(file) !== dir)
      // a music video removed while Keepfin was closed: the song stops offering it
      const videoGone = !!entry.videoPath && !(await fs.stat(entry.videoPath).catch(() => undefined))
      // a video kept before its size and length were recorded is read again
      const videoUnread = !!entry.videoPath && entry.videoSize === undefined
      if (strayed || videoGone || videoUnread || entry.sig !== `${stat.size}:${Math.round(stat.mtimeMs)}`) this.core.queueEnrich(entry.id)
      else if (entry.kind === 'audio') this.core.queueLoudness(entry.id)
    }
    // Items saved by an older version under a damaged name (every non-Latin character dropped) point at nothing; the file is
    // in the folder under its real name.
    if (missing.length) {
      for (const entry of await this.core.relinkEntries(missing)) missing.splice(missing.indexOf(entry), 1)
    }
    if (!space.scanFolder && !missing.length) return
    const candidates: {path: string; size: number; mtimeMs: number}[] = []
    for (const file of await walkMedia(space.folder, {ignore: space.ignore})) {
      if (this.core.byPath().has(keyOf(file))) continue
      const stat = await fs.stat(file).catch(() => undefined)
      if (stat?.isFile()) candidates.push({path: file, size: stat.size, mtimeMs: stat.mtimeMs})
    }
    // A managed file that vanished and a new file with the same size and timestamp: it was moved or renamed.
    const unmatched: typeof candidates = []
    for (const candidate of candidates) {
      const owner = missing.find(
        (entry) => entry.size === candidate.size && Math.abs(entry.modifiedAt - candidate.mtimeMs) <= 2000 && path.extname(entry.path).toLowerCase() === path.extname(candidate.path).toLowerCase(),
      )
      if (owner) {
        missing.splice(missing.indexOf(owner), 1)
        this.core.relocate(owner.id, candidate.path, candidate)
      } else unmatched.push(candidate)
    }
    if (space.scanFolder) for (const candidate of unmatched) await this.core.addFile(candidate.path, {space: space.id, managed: false})
  }

  syncWatchers() {
    if (this.core.options.watch === false) return
    const wanted = new Map(this.core.spaces().map((space) => [space.id, space.folder]))
    for (const [id, current] of this.watchers)
      if (wanted.get(id) !== current.folder) {
        clearTimeout(current.timer)
        current.watcher.close()
        this.watchers.delete(id)
      }
    for (const [id, folder] of wanted) {
      if (this.watchers.has(id)) continue
      try {
        const watcher = fsWatch(folder, {recursive: true, persistent: false}, (_event, name) => {
          if (typeof name === 'string' && (transient.test(name) || intermediate.test(name))) return
          this.scheduleReconcile(id)
        })
        watcher.on('error', () => {
          watcher.close()
          this.watchers.delete(id)
        })
        this.watchers.set(id, {folder, watcher})
      } catch {
        // The folder may not exist yet (it is created with the first download); the next configure retries.
      }
    }
  }

  private scheduleReconcile(spaceId: string) {
    const watching = this.watchers.get(spaceId)
    if (!watching || this.core.closing()) return
    if (this.core.held()) {
      this.deferred.add(spaceId)
      return
    }
    if (watching.timer) return
    watching.timer = setTimeout(() => {
      watching.timer = undefined
      const space = this.core.space(spaceId)
      if (space && !this.core.held()) void this.reconcileSpace(space).catch(() => {})
      else this.deferred.add(spaceId)
    }, this.core.options.watchDelay ?? 800)
  }

  stopWatchers() {
    for (const current of this.watchers.values()) {
      clearTimeout(current.timer)
      current.watcher.close()
    }
    this.watchers.clear()
  }

  runDeferred() {
    const spaces = [...this.deferred]
    this.deferred = new Set()
    for (const id of spaces) this.scheduleReconcile(id)
  }
}
