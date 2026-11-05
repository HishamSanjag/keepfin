// Files whose saved name was damaged: an item that points at nothing is relinked to the one file that matches by its real name.
import fs from 'node:fs/promises'
import path from 'node:path'
import {mediaKindOf} from '../../../shared/library.js'
import {matchDamaged} from '../../engine/outputs.js'
import type {LibraryEntry} from '../index.js'
import type {LibraryCore} from './core.js'
import {keyOf} from './sanitize.js'

export class Relinker {
  constructor(private readonly core: LibraryCore) {}

  /** Where an item's file may be, and the names it may have: from its saved path, the job that made it, and its title. */
  private relinkHints(entry: LibraryEntry): {folders: string[]; stems: string[]; ext?: string; jobFile?: string} {
    const unplaced = /[\\/]\.keepfin[\\/]unplaced[\\/]/i.test(entry.path)
    const job = entry.jobId ? this.core.options.jobs?.().find((item) => item.id === entry.jobId) : undefined
    const template = job?.outputTemplate
    const stemOf = (file: string) => path.basename(file, path.extname(file))
    const stems: string[] = []
    const addStem = (value: string | undefined) => {
      if (value && !stems.includes(value)) stems.push(value)
    }
    if (!unplaced) addStem(stemOf(entry.path))
    if (template) addStem(path.basename(template).replace(/\.%\(ext\)s$/, ''))
    if (job?.file) addStem(stemOf(job.file))
    addStem(entry.title)
    const folders: string[] = []
    const addFolder = (folder: string | undefined) => {
      if (folder && !folders.some((known) => keyOf(known) === keyOf(folder))) folders.push(folder)
    }
    if (!unplaced) addFolder(path.dirname(entry.path))
    const space = this.core.space(entry.space)
    if (space) {
      if (entry.folder) addFolder(path.join(space.folder, ...entry.folder.split('/')))
      // The job's own folder, below the space it was saved into, is where the file lives now (the folder moved, the layout stayed).
      for (const source of [template, job?.file]) {
        if (!source) continue
        const parts = path.dirname(source).split(/[\\/]+/).filter(Boolean)
        for (let take = 0; take <= Math.min(3, parts.length); take++) addFolder(path.join(space.folder, ...parts.slice(parts.length - take)))
      }
    }
    const ext = !unplaced ? path.extname(entry.path) : job?.file ? path.extname(job.file) : undefined
    return {folders, stems, ext: ext || undefined, jobFile: job?.file}
  }

  /** A file that a managed item owns. A file only listed by a folder scan (not managed) is free: the relinked item takes its place. */
  private ownedByItem(file: string): boolean {
    const id = this.core.byPath().get(keyOf(file))
    const owner = id ? this.core.entries().get(id) : undefined
    return !!owner && owner.managed
  }

  /** The one file in the hinted folders that a missing item stands for, or undefined (none, or more than one). */
  private async relinkTarget(entry: LibraryEntry, listings: Map<string, string[]>): Promise<string | undefined> {
    const hints = this.relinkHints(entry)
    const found = new Map<string, string>()
    for (const folder of hints.folders) {
      let names = listings.get(keyOf(folder))
      if (!names) {
        names = await fs.readdir(folder).catch(() => [] as string[])
        listings.set(keyOf(folder), names)
      }
      const pool = hints.ext === undefined ? names.filter((name) => mediaKindOf(name) === entry.kind) : names
      const name = matchDamaged({stems: hints.stems, ext: hints.ext}, pool, (candidate) => this.ownedByItem(path.join(folder, candidate)))
      if (name) found.set(keyOf(path.join(folder, name)), path.join(folder, name))
    }
    return found.size === 1 ? [...found.values()][0] : undefined
  }

  /**
   * Relinks items whose file is missing to the one file in their folder that matches after dropping non-ASCII characters and
   * whitespace from both names (the same extension, not owned by another item). Anything unclear stays missing. Returns the
   * entries that were relinked.
   */
  async relinkEntries(entries: readonly LibraryEntry[]): Promise<LibraryEntry[]> {
    const listings = new Map<string, string[]>()
    const targets = new Map<LibraryEntry, string>()
    for (const entry of entries) {
      const target = await this.relinkTarget(entry, listings)
      if (target) targets.set(entry, target)
    }
    // A file two missing items could both be is not guessed.
    const claims = new Map<string, number>()
    for (const target of targets.values()) claims.set(keyOf(target), (claims.get(keyOf(target)) ?? 0) + 1)
    const relinked: LibraryEntry[] = []
    for (const [entry, target] of targets) {
      if (claims.get(keyOf(target)) !== 1 || this.ownedByItem(target)) continue
      const stat = await fs.stat(target).catch(() => undefined)
      if (!stat?.isFile()) continue
      const from = entry.path
      const jobFile = this.relinkHints(entry).jobFile
      this.core.relocate(entry.id, target, stat)
      // History follows: the job still carries the old (damaged) path.
      if (jobFile && keyOf(jobFile) !== keyOf(from)) this.core.hooks().relocated(jobFile, target)
      console.warn(`[library] relinked a missing item to its file: "${entry.title}" ${from} -> ${target}`)
      relinked.push(entry)
    }
    return relinked
  }

  /** An item that opens as missing: its file is looked for under its real name once, before the user is told it is gone. */
  async relinkItem(id: unknown): Promise<LibraryEntry | undefined> {
    const entry = this.core.item(id)
    if ((await fs.stat(entry.path).catch(() => undefined))?.isFile()) return undefined
    return (await this.relinkEntries([entry]))[0]
  }
}
