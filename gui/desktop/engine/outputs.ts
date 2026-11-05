// Finding the file a download really wrote.
//
// The download engine reports the saved path on its output. If that text is ever damaged (the engine drops every
// character its console encoding cannot hold: Arabic letters, emoji, ...), the reported path no longer exists. This
// module is the safety net: it checks the reported files, falls back to the name the job asked for, and finally to a
// damage-tolerant match in the same folder. It is also the matcher the library uses to relink items already damaged.
import fs from 'node:fs/promises'
import path from 'node:path'

/** A name with every non-ASCII character and every whitespace or control character removed, lowercased. */
export function damageKey(name: string): string {
  let key = ''
  for (const char of name) {
    const code = char.codePointAt(0)!
    if (code > 0x20 && code < 0x7f) key += char
  }
  return key.toLowerCase()
}

/** File name without its last extension. */
const stemOf = (name: string) => name.slice(0, name.length - path.extname(name).length)

/**
 * The one file in `names` that a damaged name can stand for: same extension (case-insensitive), same ASCII skeleton, not
 * owned by something else. `wanted` lists the stems to try in order of trust; the first that matches anything decides, and
 * two matches are ambiguous, so nothing is chosen.
 */
export function matchDamaged(wanted: {stems: readonly string[]; ext?: string}, names: readonly string[], owned: (name: string) => boolean = () => false): string | undefined {
  const ext = wanted.ext?.toLowerCase()
  const pool = names.filter((name) => (ext === undefined || path.extname(name).toLowerCase() === ext) && !owned(name))
  for (const stem of wanted.stems) {
    const key = damageKey(stem)
    // A name that was almost all non-ASCII leaves too little to tell files apart (an empty key matches every such file).
    if (key.length < 3) continue
    const hits = pool.filter((name) => damageKey(stemOf(name)) === key)
    if (hits.length === 1) return hits[0]
    if (hits.length > 1) return undefined
  }
  return undefined
}

const pathKey = (file: string) => (process.platform === 'win32' ? path.normalize(file).toLowerCase() : path.normalize(file))
export const sameFile = (a: string, b: string) => pathKey(a) === pathKey(b)

const isFile = async (file: string) => !!(await fs.stat(file).catch(() => undefined))?.isFile()

/** `<stem>.%(ext)s` with a real extension (with its dot), or undefined when the template has another shape. */
export function fromTemplate(template: string | undefined, ext: string): string | undefined {
  if (!template || !template.endsWith('.%(ext)s') || !ext) return undefined
  return template.slice(0, -'%(ext)s'.length) + ext.replace(/^\./, '')
}

export type OutputOptions = {
  /** The job's output template (`<folder>/<stem>.%(ext)s`); not for galleries, whose files are named by the gallery engine. */
  template?: string
  /** Files that belong to something else (lowercased on Windows) and are never picked. */
  owned?: (file: string) => boolean
  /** Read a folder's names; defaults to the file system. */
  list?: (folder: string) => Promise<string[]>
  log?: (message: string) => void
}

/** Where the file a path stands for really is, or undefined when it cannot be found with certainty. */
export async function locateFile(reported: string, options: OutputOptions = {}): Promise<string | undefined> {
  if (await isFile(reported)) return reported
  const ext = path.extname(reported)
  const named = fromTemplate(options.template, ext)
  if (named && (await isFile(named))) return named
  const list = options.list ?? ((folder: string) => fs.readdir(folder).catch(() => []))
  const folders = [path.dirname(reported)]
  if (options.template && !folders.some((folder) => sameFile(folder, path.dirname(options.template!)))) folders.push(path.dirname(options.template))
  const stems = [stemOf(path.basename(reported))]
  if (options.template) stems.unshift(stemOf(path.basename(options.template.replace(/%\(ext\)s$/, 'x'))).replace(/\.$/, ''))
  for (const folder of folders) {
    const found = matchDamaged({stems, ext}, await list(folder), (name) => !!options.owned?.(path.join(folder, name)))
    if (found) {
      const file = path.join(folder, found)
      options.log?.(`Resolved a reported file name that was damaged: ${reported} -> ${file}`)
      return file
    }
  }
  return undefined
}

export class OutputMissingError extends Error {
  constructor(readonly reported: string) {
    super('The download finished, but its file could not be found in the folder. Try the download again.')
  }
}

/**
 * Verifies a finished download's files. The main file must exist (it is resolved when its reported name was damaged, and
 * the job fails with OutputMissingError when it cannot be found); other files that cannot be found are left out.
 */
export async function settleOutputs(
  outcome: {file: string; files: readonly string[]},
  options: OutputOptions = {},
): Promise<{file: string; files: string[]; repaired: boolean}> {
  const file = await locateFile(outcome.file, options)
  if (!file) throw new OutputMissingError(outcome.file)
  const files: string[] = []
  for (const other of outcome.files) {
    // Sidecars (subtitles, thumbnails, ...) are named from the main file, which the template cannot give back.
    const found = await locateFile(other, {...options, template: undefined})
    if (found && !sameFile(found, file) && !files.some((known) => sameFile(known, found))) files.push(found)
  }
  const repaired = !sameFile(file, outcome.file) || files.length !== outcome.files.length || files.some((value, index) => !sameFile(value, outcome.files[index]))
  return {file, files, repaired}
}
