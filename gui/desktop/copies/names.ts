// The durable name map of a copy (spec 5.4). A library path that the copy's file system cannot spell as it is (reserved
// names, rejected characters, trailing dots and spaces, over-long components, a file competing with a folder) gets a
// physical name, appended to `.keepfin/names.v1.jsonl` BEFORE it is used. Every reader of the copy applies the map, so a
// standalone open of the copy still finds its files, and the inventory of the copy translates physical names back.
import fs from 'node:fs/promises'
import path from 'node:path'
import {appendLineDurable} from '../store/atomic.js'
import {exists, withCounter} from './util.js'

const RESERVED = /^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\..*)?$/i
const MAX_COMPONENT = 200

/** One component spelled so that Windows, FAT and exFAT accept it. Returns the component itself when it is fine. */
export function safeComponent(name: string): string {
  let out = ''
  for (const ch of name) {
    const c = ch.codePointAt(0)!
    out += c < 32 || '<>:"|?*\\'.includes(ch) ? '_' : ch
  }
  if (RESERVED.test(out)) out = `_${out}`
  // trailing dots and spaces are dropped by Windows: replace them so the name stays distinct
  const m = /[. ]+$/.exec(out)
  if (m) out = out.slice(0, m.index) + '_'.repeat(m[0].length)
  if (out.length > MAX_COMPONENT) {
    const dot = out.lastIndexOf('.')
    const ext = dot > 0 && out.length - dot <= 16 ? out.slice(dot) : ''
    out = out.slice(0, MAX_COMPONENT - ext.length) + ext
  }
  return out
}

export interface NameEntry {
  lib: string
  phys: string
}

export const namesFile = (copyRoot: string) => path.join(copyRoot, '.keepfin', 'names.v1.jsonl')

export class NameMap {
  /** library prefix -> physical prefix */
  private readonly forward = new Map<string, string>()
  /** physical prefix (lowercase) -> library prefix */
  private readonly backward = new Map<string, string>()

  private constructor(private readonly copyRoot: string, private readonly same = false) {}

  /** A copy that is not a folder here (an account, a device): names are the library's own, nothing is mapped. */
  static identity(copyRoot: string): NameMap {
    return new NameMap(copyRoot, true)
  }

  static async load(copyRoot: string): Promise<NameMap> {
    const map = new NameMap(copyRoot)
    let text = ''
    try {
      text = await fs.readFile(namesFile(copyRoot), 'utf8')
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error
    }
    for (const line of text.split('\n')) {
      if (!line.trim()) continue
      try {
        const e = JSON.parse(line) as NameEntry
        if (typeof e.lib === 'string' && typeof e.phys === 'string') map.remember(e)
      } catch {
        // a torn last line
      }
    }
    return map
  }

  private remember(e: NameEntry): void {
    this.forward.set(e.lib, e.phys)
    this.backward.set(e.phys.toLowerCase(), e.lib)
  }

  get size(): number {
    return this.forward.size
  }

  /** The physical relative path of [lib] as far as the map knows (no file system access, nothing is added). */
  physical(lib: string): string {
    if (this.same) return lib
    const parts = lib.split('/')
    let prefix = ''
    const out: string[] = []
    for (const part of parts) {
      prefix = prefix ? `${prefix}/${part}` : part
      const mapped = this.forward.get(prefix)
      if (mapped !== undefined) {
        out.length = 0
        out.push(...mapped.split('/'))
      } else {
        out.push(part)
      }
    }
    return out.join('/')
  }

  /** The library path of a physical relative path (the reverse of [physical]). */
  library(phys: string): string {
    if (this.same) return phys
    if (this.backward.size === 0) return phys
    const parts = phys.split('/')
    let prefix = ''
    let libPrefix = ''
    for (const part of parts) {
      prefix = prefix ? `${prefix}/${part}` : part
      const hit = this.backward.get(prefix.toLowerCase())
      if (hit !== undefined) {
        libPrefix = hit
      } else {
        libPrefix = libPrefix ? `${libPrefix}/${part}` : part
      }
    }
    return libPrefix
  }

  /**
   * The physical path for [lib] on this copy, mapping the names the file system cannot take (no file system access). New
   * mappings are appended to the durable map before this returns.
   */
  async resolve(lib: string): Promise<string> {
    if (this.same) return lib
    const parts = lib.split('/')
    let libPrefix = ''
    let physPrefix = ''
    for (const part of parts) {
      libPrefix = libPrefix ? `${libPrefix}/${part}` : part
      const known = this.forward.get(libPrefix)
      if (known !== undefined) {
        physPrefix = known
        continue
      }
      const safe = safeComponent(part)
      const candidate = physPrefix ? `${physPrefix}/${safe}` : safe
      if (safe !== part) await this.add(libPrefix, candidate)
      physPrefix = candidate
    }
    return physPrefix
  }

  /**
   * A file and a folder competed for one physical name ([failed] is the physical prefix that could not be used for the
   * library prefix reached by [lib]): give that component another name, with a counter, and remember it.
   */
  async relocate(lib: string, failed: string): Promise<string> {
    const libParts = lib.split('/')
    const physParts = this.physical(lib).split('/')
    const failedParts = failed.split('/')
    const depth = failedParts.length
    const libPrefix = libParts.slice(0, depth).join('/')
    const base = failedParts.join('/')
    let n = 2
    let candidate = withCounter(base, n)
    while (this.backward.has(candidate.toLowerCase()) || (await exists(path.join(this.copyRoot, ...candidate.split('/'))))) {
      n++
      candidate = withCounter(base, n)
    }
    await this.add(libPrefix, candidate)
    return [candidate, ...physParts.slice(depth)].join('/')
  }

  private async add(lib: string, phys: string): Promise<void> {
    const entry: NameEntry = {lib, phys}
    await appendLineDurable(namesFile(this.copyRoot), JSON.stringify(entry))
    this.remember(entry)
  }
}