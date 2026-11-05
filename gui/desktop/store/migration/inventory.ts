// Section 5.1: a read-only inventory of what a device holds today. Nothing here writes or moves anything.
import fs from 'node:fs/promises'
import path from 'node:path'
import {readJson} from '../atomic.js'
import {fromTemplate, matchDamaged} from '../../engine/outputs.js'
import {lp, pathKey, statOrUndefined, toSlash} from './io.js'
import type {Counts, InvCollection, InvFile, InvItem, InvSpace, Inventory} from './types.js'

const audioExt = new Set(['mp3', 'm4a', 'aac', 'flac', 'opus', 'ogg', 'oga', 'wav', 'wma', 'aiff', 'aif', 'alac', 'mka'])
const videoExt = new Set(['mp4', 'mkv', 'webm', 'mov', 'avi', 'm4v', 'wmv', 'flv', 'ts', 'mpg', 'mpeg', '3gp'])
const imageExt = new Set(['jpg', 'jpeg', 'png', 'webp', 'gif', 'bmp', 'avif', 'heic'])
const coverExt = new Set(['jpg', 'png', 'webp'])
const sidecarPattern = /\.(?:lrc|srt|vtt|ass|nfo|info\.json|jpg|png|webp)$/i
const coverName = /^(?:cover|folder)\.(?:jpg|png|webp)$/i
const transientPattern = /\.(?:part|ytdl|tmp|temp|crdownload|download|kfmove|kfedit|kftmp)$/i
const intermediatePattern = /\.f\d+(?:-[\w]+)?\.[a-z0-9]+$|\.(?:tagging|temp|cover|kfmove|kfedit)\.[a-z0-9]+$/i

const knownItemFields = new Set([
  'id', 'space', 'path', 'folder', 'kind', 'title', 'artist', 'album', 'albumArtist', 'track', 'year', 'duration', 'size', 'modifiedAt', 'addedAt',
  'sourceUrl', 'sourceId', 'jobId', 'hasArt', 'width', 'height', 'position', 'plays', 'lastPlayedAt', 'subtitles', 'favorite', 'watched', 'hasLyrics',
  'gain', 'disc', 'genre', 'chapters', 'tags', 'userTags', 'missing',
  // internal fields the library index keeps
  'managed', 'sig', 'subPaths', 'lrc', 'infoJson', 'analysed', 'hash', 'colIndex', 'tagsPending', 'collection',
])
const knownCollectionFields = new Set([
  'id', 'name', 'kind', 'space', 'itemIds', 'coverItemId', 'source', 'follow', 'seen', 'newItemIds', 'removedItemIds', 'createdAt', 'updatedAt',
])
const knownLibraryFields = new Set(['version', 'items', 'collections', 'playlists'])

const isObject = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
const extOf = (name: string) => path.extname(name).slice(1).toLowerCase()

function extraOf(raw: Record<string, unknown>, known: Set<string>): Record<string, unknown> {
  const extra: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(raw)) if (!known.has(key)) extra[key] = value
  return extra
}

export const builtinDestination: Record<string, string> = {music: 'Music', videos: 'Videos', downloads: 'Downloads'}

export type InventoryOptions = {
  /** Used when queue.json has no library settings. */
  spaces?: InvSpace[]
  signal?: AbortSignal
}

type Walked = {abs: string; name: string; size: number; mtimeMs: number}

async function walk(space: InvSpace, others: string[], skipped: string[], signal?: AbortSignal): Promise<Walked[]> {
  const result: Walked[] = []
  const otherKeys = new Set(others.map(pathKey))
  const ignore = (space.ignore ?? []).map((item) => item.replace(/\\/g, '/').replace(/^\/+|\/+$/g, '').toLowerCase()).filter(Boolean)
  const stack = [space.folder]
  while (stack.length) {
    if (signal?.aborted) throw new DOMException('Aborted', 'AbortError')
    const directory = stack.pop() as string
    let entries
    try {
      entries = await fs.readdir(lp(directory), {withFileTypes: true})
    } catch (error) {
      const code = (error as NodeJS.ErrnoException).code
      if (code === 'ENOENT' || code === 'ENOTDIR') continue
      throw error
    }
    if (entries.some((entry) => entry.name === '.nomedia')) continue
    for (const entry of entries) {
      const abs = path.join(directory, entry.name)
      if (entry.isDirectory()) {
        if (entry.name === '.keepfin' || otherKeys.has(pathKey(abs))) continue
        const rel = toSlash(path.relative(space.folder, abs)).toLowerCase()
        if (ignore.some((item) => rel === item || rel.startsWith(`${item}/`))) continue
        stack.push(abs)
      } else if (entry.isFile()) {
        if (transientPattern.test(entry.name) || intermediatePattern.test(entry.name)) {
          skipped.push(abs)
          continue
        }
        const stat = await fs.stat(lp(abs)).catch(() => undefined)
        if (stat) result.push({abs, name: entry.name, size: stat.size, mtimeMs: stat.mtimeMs})
      }
    }
  }
  return result.sort((a, b) => (a.abs < b.abs ? -1 : a.abs > b.abs ? 1 : 0))
}

export async function takeInventory(deviceDir: string, options: InventoryOptions = {}): Promise<Inventory> {
  const libraryRaw = await readJson<unknown>(path.join(deviceDir, 'library.json'))
  const queueRaw = await readJson<unknown>(path.join(deviceDir, 'queue.json'))
  const library = isObject(libraryRaw) ? libraryRaw : undefined
  const queue = isObject(queueRaw) ? queueRaw : undefined

  const spaces = readSpaces(queue) ?? options.spaces ?? []

  // Library entries, verbatim.
  const items: InvItem[] = []
  if (library && Array.isArray(library.items)) {
    for (const raw of library.items) {
      if (!isObject(raw) || typeof raw.id !== 'string' || typeof raw.path !== 'string') continue
      items.push({id: raw.id, path: raw.path, space: typeof raw.space === 'string' ? raw.space : '', raw, extra: extraOf(raw, knownItemFields)})
    }
  }
  const collections: InvCollection[] = []
  const collectionSource = library && Array.isArray(library.collections) ? library.collections : library && Array.isArray(library.playlists) ? library.playlists : []
  for (const raw of collectionSource) {
    if (!isObject(raw) || typeof raw.id !== 'string') continue
    collections.push({id: raw.id, raw, extra: extraOf(raw, knownCollectionFields)})
  }

  const folderSpaces = spaces.filter((space) => space.type === 'folder' && space.folder)
  const skipped: string[] = []
  const walked: {space: InvSpace; files: Walked[]}[] = []
  for (const space of folderSpaces) {
    walked.push({
      space,
      files: await walk(space, folderSpaces.filter((other) => other !== space && isInside(space.folder, other.folder)).map((other) => other.folder), skipped, options.signal),
    })
  }

  await relinkDamagedItems(items, walked.flatMap((entry) => entry.files), queue)

  const itemByPath = new Map(items.map((item) => [pathKey(item.path), item]))
  const media: InvFile[] = []
  const sidecars: InvFile[] = []
  const other: string[] = []
  const seenPaths = new Set<string>()

  for (const {space, files} of walked) {
    const byDirectory = new Map<string, Walked[]>()
    for (const file of files) {
      const list = byDirectory.get(path.dirname(file.abs)) ?? []
      list.push(file)
      byDirectory.set(path.dirname(file.abs), list)
    }
    for (const [, group] of [...byDirectory.entries()].sort((a, b) => (a[0] < b[0] ? -1 : 1))) {
      const mediaHere: {file: Walked; item?: InvItem}[] = []
      const rest: Walked[] = []
      for (const file of group) {
        const item = itemByPath.get(pathKey(file.abs))
        const ext = extOf(file.name)
        if (item || audioExt.has(ext) || videoExt.has(ext)) mediaHere.push({file, item})
        else rest.push(file)
      }
      const stems = mediaHere.map(({file}) => ({stem: path.basename(file.name, path.extname(file.name)).toLowerCase(), file})).sort((a, b) => b.stem.length - a.stem.length)
      const firstMedia = mediaHere.map(({file}) => file).sort((a, b) => (a.name < b.name ? -1 : 1))[0]
      const toRel = (file: Walked) => toSlash(path.relative(space.folder, file.abs))
      for (const {file, item} of mediaHere) {
        seenPaths.add(pathKey(file.abs))
        media.push({
          path: file.abs, size: file.size, mtimeMs: file.mtimeMs, kind: 'media', space: item?.space || space.id, rel: toRel(file),
          itemId: item?.id, indexed: !!item,
        })
      }
      for (const file of rest) {
        const lower = file.name.toLowerCase()
        const ext = extOf(file.name)
        if (sidecarPattern.test(file.name)) {
          const owner = stems.find(({stem}) => lower.startsWith(`${stem}.`))
          if (owner) {
            sidecars.push({path: file.abs, size: file.size, mtimeMs: file.mtimeMs, kind: 'sidecar', space: space.id, rel: toRel(file), owner: owner.file.abs})
            continue
          }
          if (coverName.test(file.name) && firstMedia) {
            sidecars.push({path: file.abs, size: file.size, mtimeMs: file.mtimeMs, kind: 'sidecar', space: space.id, rel: toRel(file), owner: firstMedia.abs, shared: true})
            continue
          }
        }
        if (imageExt.has(ext)) {
          media.push({path: file.abs, size: file.size, mtimeMs: file.mtimeMs, kind: 'media', space: space.id, rel: toRel(file), indexed: false})
          continue
        }
        other.push(file.abs)
      }
    }
  }

  // Library entries that were not found inside a space folder: present on disk (outside) or missing.
  const missing: Inventory['missing'] = []
  for (const item of items) {
    if (seenPaths.has(pathKey(item.path))) continue
    const stat = await statOrUndefined(item.path)
    if (stat?.isFile()) {
      media.push({
        path: item.path, size: stat.size, mtimeMs: stat.mtimeMs, kind: 'media', space: item.space, rel: path.basename(item.path),
        itemId: item.id, indexed: true, outside: true,
      })
    } else {
      missing.push({itemId: item.id, path: item.path, space: item.space})
    }
  }
  media.sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0))

  // Saved art, keyed by the old item id.
  const art: InvFile[] = []
  const orphanArt: string[] = []
  const itemIds = new Set(items.map((item) => item.id))
  const artDir = path.join(deviceDir, 'library-art')
  const artEntries = await fs.readdir(lp(artDir), {withFileTypes: true}).catch(() => [])
  for (const entry of artEntries.sort((a, b) => (a.name < b.name ? -1 : 1))) {
    if (!entry.isFile() || !coverExt.has(extOf(entry.name))) continue
    const abs = path.join(artDir, entry.name)
    const id = path.basename(entry.name, path.extname(entry.name))
    if (!itemIds.has(id)) {
      orphanArt.push(abs)
      continue
    }
    const stat = await fs.stat(lp(abs)).catch(() => undefined)
    if (stat) art.push({path: abs, size: stat.size, mtimeMs: stat.mtimeMs, kind: 'art', space: '', rel: entry.name, itemId: id})
  }

  // Jobs that have a file.
  const jobs: Inventory['jobs'] = []
  const jobList = queue && Array.isArray(queue.jobs) ? queue.jobs : []
  for (const job of jobList) {
    if (!isObject(job) || typeof job.id !== 'string' || typeof job.file !== 'string' || !job.file) continue
    let file = job.file
    let exists = !!(await statOrUndefined(file))?.isFile()
    // A damaged saved name: the name the job asked for is the better witness.
    const named = !exists && typeof job.outputTemplate === 'string' ? fromTemplate(job.outputTemplate, path.extname(file)) : undefined
    if (named && (await statOrUndefined(named))?.isFile()) {
      file = named
      exists = true
    }
    jobs.push({id: job.id, file, exists})
  }

  const counts: Counts = {
    media: media.length,
    sidecars: sidecars.length,
    history: jobList.length,
    playlists: collections.filter((item) => item.raw.kind === 'own').length,
    collections: collections.filter((item) => item.raw.kind !== 'own').length,
    favourites: items.filter((item) => item.raw.favorite === true).length,
    userTags: items.reduce((sum, item) => sum + (Array.isArray(item.raw.userTags) ? item.raw.userTags.length : 0), 0),
    art: art.length,
  }

  return {
    version: 1,
    deviceDir,
    spaces,
    library: {
      present: !!library,
      version: typeof library?.version === 'number' ? library.version : undefined,
      extra: library ? extraOf(library, knownLibraryFields) : {},
      items,
      collections,
    },
    queuePresent: !!queue,
    media,
    sidecars,
    art,
    unindexed: media.filter((file) => file.indexed === false).map((file) => file.path),
    missing,
    jobs,
    orphanArt,
    other,
    skipped,
    counts,
  }
}

/**
 * Items saved by an older version under a damaged file name (every Arabic letter and emoji dropped) point at nothing while
 * the real file sits in the folder under its full name; left alone, the file would travel as an unlisted file and the item
 * would come over as missing. The name the job asked for (queue.json `outputTemplate`) is trusted over the damaged path;
 * otherwise a name that matches once non-ASCII characters and whitespace are ignored. Only a unique, unowned file with the
 * same extension is taken. The item's path is corrected in place; nothing on disk changes.
 */
async function relinkDamagedItems(items: InvItem[], walked: readonly Walked[], queue: Record<string, unknown> | undefined) {
  const jobs = new Map<string, Record<string, unknown>>()
  for (const job of queue && Array.isArray(queue.jobs) ? queue.jobs : []) if (isObject(job) && typeof job.id === 'string') jobs.set(job.id, job)
  const byFolder = new Map<string, Walked[]>()
  for (const file of walked) {
    const key = pathKey(path.dirname(file.abs))
    byFolder.set(key, [...(byFolder.get(key) ?? []), file])
  }
  const walkedKeys = new Set(walked.map((file) => pathKey(file.abs)))
  const itemKeys = new Set(items.map((item) => pathKey(item.path)))
  const targets = new Map<InvItem, Walked>()
  for (const item of items) {
    if (walkedKeys.has(pathKey(item.path)) || (await statOrUndefined(item.path))?.isFile()) continue
    const job = typeof item.raw.jobId === 'string' ? jobs.get(item.raw.jobId) : undefined
    const template = job && typeof job.outputTemplate === 'string' ? job.outputTemplate : undefined
    const jobFile = job && typeof job.file === 'string' ? job.file : undefined
    const ext = path.extname(item.path) || (jobFile ? path.extname(jobFile) : '')
    if (!ext) continue
    const free = (file: Walked) => !itemKeys.has(pathKey(file.abs))
    // The name the job asked for, with the real extension, wins outright.
    const named = fromTemplate(template, ext)
    const exact = named ? walked.find((file) => pathKey(file.abs) === pathKey(named)) : undefined
    if (exact && free(exact)) {
      targets.set(item, exact)
      continue
    }
    const stems = [template && path.basename(template).replace(/\.%\(ext\)s$/, ''), path.basename(item.path, path.extname(item.path)), jobFile && path.basename(jobFile, path.extname(jobFile))].filter((value): value is string => !!value)
    const found = new Map<string, Walked>()
    for (const folder of new Set([pathKey(path.dirname(item.path)), ...(template ? [pathKey(path.dirname(template))] : [])])) {
      const files = byFolder.get(folder) ?? []
      const name = matchDamaged({stems, ext}, files.map((file) => file.name), (candidate) => files.some((file) => file.name === candidate && !free(file)))
      const file = name ? files.find((candidate) => candidate.name === name) : undefined
      if (file) found.set(pathKey(file.abs), file)
    }
    if (found.size === 1) targets.set(item, [...found.values()][0])
  }
  // A file two items could both be is not guessed.
  const claims = new Map<string, number>()
  for (const file of targets.values()) claims.set(pathKey(file.abs), (claims.get(pathKey(file.abs)) ?? 0) + 1)
  for (const [item, file] of targets) {
    if (claims.get(pathKey(file.abs)) !== 1) continue
    console.warn(`[migration] relinked a library item to its real file: "${String(item.raw.title ?? '')}" ${item.path} -> ${file.abs}`)
    item.path = file.abs
  }
}

function isInside(folder: string, file: string): boolean {
  const relative = path.relative(folder, file)
  return !!relative && !relative.startsWith('..') && !path.isAbsolute(relative)
}

function readSpaces(queue: Record<string, unknown> | undefined): InvSpace[] | undefined {
  const settings = queue && isObject(queue.settings) ? queue.settings : undefined
  const library = settings && isObject(settings.library) ? settings.library : undefined
  if (!library || !Array.isArray(library.spaces)) return undefined
  const spaces: InvSpace[] = []
  for (const raw of library.spaces) {
    if (!isObject(raw) || typeof raw.id !== 'string') continue
    spaces.push({
      id: raw.id,
      name: typeof raw.name === 'string' ? raw.name : raw.id,
      folder: typeof raw.folder === 'string' ? raw.folder : '',
      type: raw.type === 'smart' ? 'smart' : 'folder',
      ignore: Array.isArray(raw.ignore) ? raw.ignore.filter((item): item is string => typeof item === 'string') : undefined,
    })
  }
  return spaces
}
