// File operations behind the library: import, move between spaces, rename and delete.
// Failures never lose the original: copies are verified before anything is removed.
import fs from 'node:fs/promises'
import path from 'node:path'
import {
  mediaKindOf,
  type LibraryDeleteResult,
  type LibraryImportResult,
  type LibraryMoveResult,
  type TrashEntry,
  type TrashResult,
  type UndoResult,
} from '../../shared/library.js'
import {fullHash} from './fingerprint.js'
import {findSidecars, musicVideoNames} from './tags.js'
import {isInside, titleFromName, type CollectionMembership, type LibraryEntry, type LibraryIndex} from './index.js'

/** What download history held for a file, so Undo of a delete can point it at the file again. */
export type FileRef = {jobId: string; file?: string; files?: string[]; fileSize?: number}

export type FileDeps = {
  /** shell.trashItem in the app; injected so tests can observe it. */
  trash: (file: string) => Promise<void>
  /** The open Keepfin folder's root, or undefined when there is none. Files inside it are deleted into its trash (Undo can bring them back). */
  keepfinRoot?: () => string | undefined
  /** Download history follows a delete and its Undo. */
  history?: {fileRefs(paths: string[]): FileRef[]; restoreFileRefs(refs: FileRef[]): void}
}

const MAX_IMPORT = 20_000
const windowsReserved = /^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i

// ---------------------------------------------------------------------------
// Names
// ---------------------------------------------------------------------------

async function exists(file: string) {
  return !!(await fs.lstat(file).catch(() => undefined))
}

/** `name.ext`, or `name (2).ext`, … — the first that does not exist (case-insensitively on Windows and macOS). */
/** A free name in [dir] for [name]; [companions] (suffixes such as `.lrc`, `.video.mp4`) must be free against the same stem too. */
export async function uniquePath(dir: string, name: string, companions: readonly string[] = []): Promise<string> {
  const ext = path.extname(name)
  const stem = path.basename(name, ext)
  const taken = new Set((await fs.readdir(dir).catch(() => [] as string[])).map((entry) => entry.toLowerCase()))
  for (let n = 1; ; n++) {
    const base = n === 1 ? stem : `${stem} (${n})`
    const candidate = base + ext
    if (companions.some((suffix) => taken.has((base + suffix).toLowerCase()))) continue
    if (!taken.has(candidate.toLowerCase()) && !(await exists(path.join(dir, candidate)))) return path.join(dir, candidate)
  }
}

/** The suffixes of [sidecars] against the media stem [stem] (`Song.en.srt` → `.en.srt`). */
const suffixesOf = (sidecars: readonly string[], stem: string) => sidecars.map((file) => path.basename(file).slice(stem.length))

/** The files beside [file] that come into the library with it: lyrics, subtitles, the download's metadata and, for a song, its music video. */
async function sidecarsBeside(file: string): Promise<string[]> {
  const found = await findSidecars(file)
  const video = mediaKindOf(path.basename(file)) === 'audio' ? found.video : undefined
  return [...found.subtitles.map((item) => item.path), found.lrc, found.info, video].filter((item): item is string => !!item)
}

/** A file name stem from user input. Rejects instead of silently altering what the user typed. */
export function validateStem(input: unknown): string {
  const stem = typeof input === 'string' ? input.trim() : ''
  if (!stem) throw new Error('Type a file name.')
  if (stem.length > 180) throw new Error('That file name is too long.')
  // eslint-disable-next-line no-control-regex
  if (/[<>:"/\\|?*\x00-\x1f]/.test(stem)) throw new Error('File names cannot contain < > : " / \\ | ? *')
  if (/[. ]$/.test(stem) || windowsReserved.test(stem)) throw new Error('That file name is not allowed.')
  return stem
}

/** Files that travel with an item: subtitles, lyrics, the download's metadata and a song's music video. */
function sidecarsOf(entry: LibraryEntry): string[] {
  return [...(entry.subPaths ?? []), entry.lrc, entry.infoJson, entry.videoPath].filter((file): file is string => !!file)
}

/** Renames/moves a sidecar so it keeps its suffix (language tag, .lrc, .info.json) against the new stem. */
function sidecarTarget(sidecar: string, oldStem: string, newDir: string, newStem: string): string {
  const name = path.basename(sidecar)
  return path.join(newDir, newStem + name.slice(oldStem.length))
}

// ---------------------------------------------------------------------------
// Moving bytes
// ---------------------------------------------------------------------------

const crossVolume = (error: unknown) => (error as NodeJS.ErrnoException).code === 'EXDEV'

async function freeBytes(dir: string): Promise<number | undefined> {
  try {
    const stats = await fs.statfs(dir)
    return Number(stats.bavail) * Number(stats.bsize)
  } catch {
    return
  }
}

/** Copy to a temporary name, verify size and content, then publish. The source is not touched. */
export async function copyVerified(from: string, to: string, verify = true) {
  const size = (await fs.stat(from)).size
  const free = await freeBytes(path.dirname(to))
  if (free !== undefined && free < size + 16 * 1024 * 1024) throw new Error('There is not enough free space for this file.')
  const temporary = `${to}.kfmove`
  try {
    await fs.copyFile(from, temporary, fs.constants.COPYFILE_EXCL)
    if ((await fs.stat(temporary)).size !== size) throw new Error('The copy did not match the original, so nothing was changed.')
    if (verify && (await fullHash(from)) !== (await fullHash(temporary)))
      throw new Error('The copy did not match the original, so nothing was changed.')
    await fs.rename(temporary, to)
  } finally {
    await fs.rm(temporary, {force: true})
  }
}

/**
 * Same volume: rename. Different volume: verified copy, then the original goes to the Recycle Bin.
 * Returns a note when the original could not be removed.
 */
export async function moveFile(from: string, to: string, deps: FileDeps): Promise<string | undefined> {
  await fs.mkdir(path.dirname(to), {recursive: true})
  try {
    await fs.rename(from, to)
    return
  } catch (error) {
    if (!crossVolume(error)) throw error
  }
  await copyVerified(from, to)
  try {
    await deps.trash(from)
  } catch {
    return `${path.basename(from)} was copied, but the original could not be sent to the Recycle Bin.`
  }
}

const friendly = (error: unknown) => {
  const code = (error as NodeJS.ErrnoException).code
  if (code === 'ENOENT') return 'The file is no longer at its saved location.'
  if (code === 'EACCES' || code === 'EPERM' || code === 'EBUSY') return 'The file is in use or protected. Close it and try again.'
  if (code === 'ENOSPC') return 'There is not enough free space.'
  return error instanceof Error ? error.message : String(error)
}

// ---------------------------------------------------------------------------
// Import
// ---------------------------------------------------------------------------

type Source = {file: string; relative: string}

/** Validates picked or dropped paths and expands folders. Anything that is not an absolute media file or folder is rejected. */
export async function expandPaths(paths: unknown): Promise<{sources: Source[]; failed: {path: string; message: string}[]}> {
  if (!Array.isArray(paths) || !paths.length || paths.length > 5000) throw new Error('Choose files or folders to add.')
  const sources: Source[] = []
  const failed: {path: string; message: string}[] = []
  const seen = new Set<string>()
  const add = (file: string, relative: string) => {
    const key = process.platform === 'win32' ? file.toLowerCase() : file
    if (seen.has(key) || sources.length >= MAX_IMPORT) return
    seen.add(key)
    sources.push({file, relative})
  }
  for (const raw of paths) {
    if (typeof raw !== 'string' || !path.isAbsolute(raw) || raw.length > 1000) {
      failed.push({path: String(raw).slice(0, 200), message: 'That is not a file Keepfin can add.'})
      continue
    }
    const stat = await fs.stat(raw).catch(() => undefined)
    if (stat?.isFile()) {
      if (mediaKindOf(raw)) add(path.normalize(raw), '')
      else failed.push({path: raw, message: 'That file type cannot be added to the library.'})
    } else if (stat?.isDirectory()) {
      const root = path.normalize(raw)
      const base = path.basename(root)
      const pending = [root]
      while (pending.length && sources.length < MAX_IMPORT) {
        const dir = pending.pop()!
        const dirents = await fs.readdir(dir, {withFileTypes: true}).catch(() => [])
        if (dirents.some((dirent) => dirent.name === '.nomedia')) continue
        const videos = musicVideoNames(dirents.filter((dirent) => dirent.isFile()).map((dirent) => dirent.name))
        for (const dirent of dirents) {
          const full = path.join(dir, dirent.name)
          if (dirent.isDirectory()) {
            if (!dirent.name.startsWith('.')) pending.push(full)
          } else if (dirent.isFile() && mediaKindOf(dirent.name) && !videos.has(dirent.name.toLowerCase()))
            add(full, path.join(base, path.relative(root, path.dirname(full))))
        }
      }
    } else failed.push({path: raw, message: 'That file or folder was not found.'})
  }
  if (!sources.length && !failed.length) throw new Error('No media files were found.')
  return {sources, failed}
}

export async function importFiles(
  index: LibraryIndex,
  input: {paths: unknown; space: unknown; mode: unknown},
  deps: FileDeps,
): Promise<LibraryImportResult> {
  const space = index.space(typeof input.space === 'string' ? input.space : undefined)
  if (!space) throw new Error('Choose a space to add these files to.')
  const mode = input.mode === 'move' ? 'move' : 'copy'
  const {sources, failed} = await expandPaths(input.paths)
  const result: LibraryImportResult = {added: [], duplicates: [], failed, cancelled: false}
  await fs.mkdir(space.folder, {recursive: true})
  const operation = index.beginOperation('import', sources.length)
  try {
    for (const source of sources) {
      if (operation.signal.aborted) {
        result.cancelled = true
        break
      }
      operation.step(path.basename(source.file))
      try {
        const stat = await fs.stat(source.file)
        const inside = isInside(space.folder, source.file)
        if (inside) {
          const entry = await index.addFile(source.file, {space: space.id, managed: true})
          if (entry) result.added.push(entry.id)
          continue
        }
        const duplicate = await index.findByContent(space.id, source.file, stat.size)
        if (duplicate?.itemId) {
          result.duplicates.push({path: source.file, itemId: duplicate.itemId})
          continue
        }
        const oldStem = path.basename(source.file, path.extname(source.file))
        const sidecars = await sidecarsBeside(source.file)
        const target = await uniquePath(path.join(space.folder, source.relative), path.basename(source.file), suffixesOf(sidecars, oldStem))
        await fs.mkdir(path.dirname(target), {recursive: true})
        if (mode === 'move') {
          const note = await moveFile(source.file, target, deps)
          if (note) result.failed.push({path: source.file, message: note})
        } else await copyVerified(source.file, target, false)
        const newStem = path.basename(target, path.extname(target))
        for (const sidecar of sidecars) {
          const to = sidecarTarget(sidecar, oldStem, path.dirname(target), newStem)
          await (mode === 'move' ? moveFile(sidecar, to, deps) : copyVerified(sidecar, to, false)).catch(() => undefined)
        }
        const entry = await index.addFile(target, {space: space.id, managed: true})
        if (entry) result.added.push(entry.id)
      } catch (error) {
        result.failed.push({path: source.file, message: friendly(error)})
      } finally {
        operation.advance()
      }
    }
  } finally {
    operation.end()
  }
  return result
}

// ---------------------------------------------------------------------------
// Move between spaces
// ---------------------------------------------------------------------------

export async function moveItems(
  index: LibraryIndex,
  /** `copy`: the item moves to a checked copy and the original stays where it was (Storage › Bring them in › Copy in). */
  input: {ids: unknown; space: unknown; copy?: boolean},
  deps: FileDeps,
  /** Told for each item that moved: its id now and the space it came from (for Undo). */
  onMoved?: (moved: {id: string; from: string}) => void,
): Promise<LibraryMoveResult> {
  const space = index.space(typeof input.space === 'string' ? input.space : undefined)
  if (!space) throw new Error('Choose a space to move these to.')
  if (!Array.isArray(input.ids) || !input.ids.length || input.ids.length > 20_000) throw new Error('Choose items to move.')
  const entries = [...new Set(input.ids)].map((id) => index.item(id))
  const result: LibraryMoveResult = {moved: [], failed: [], notes: [], cancelled: false}
  await fs.mkdir(space.folder, {recursive: true})
  const operation = index.beginOperation('move', entries.length)
  try {
    for (const entry of entries) {
      if (operation.signal.aborted) {
        result.cancelled = true
        break
      }
      operation.step(path.basename(entry.path))
      try {
        if (entry.missing || !(await exists(entry.path))) throw Object.assign(new Error(), {code: 'ENOENT'})
        const directory = path.join(space.folder, ...entry.folder.split('/').filter(Boolean))
        if (path.dirname(entry.path).toLowerCase() === directory.toLowerCase() && entry.space === space.id) continue
        await fs.mkdir(directory, {recursive: true})
        const oldStem = path.basename(entry.path, path.extname(entry.path))
        const sidecars = sidecarsOf(entry)
        const target = await uniquePath(directory, path.basename(entry.path), suffixesOf(sidecars, oldStem))
        const newStem = path.basename(target, path.extname(target))
        const from = entry.space
        const note = input.copy ? await copyVerified(entry.path, target) : await moveFile(entry.path, target, deps)
        if (note) result.notes.push(note)
        for (const sidecar of sidecars) {
          const to = sidecarTarget(sidecar, oldStem, directory, newStem)
          await (input.copy ? copyVerified(sidecar, to, false) : moveFile(sidecar, to, deps)).catch(() => undefined)
        }
        const moved = index.relocate(entry.id, target, await fs.stat(target))
        moved.space = space.id
        moved.managed = true
        result.moved.push(moved.id)
        onMoved?.({id: moved.id, from})
      } catch (error) {
        result.failed.push({id: entry.id, message: friendly(error)})
      } finally {
        operation.advance()
      }
    }
  } finally {
    operation.end()
  }
  return result
}

/** Duplicates items into another space. The originals stay; the copies are new items with the same tags. */
export async function copyItems(index: LibraryIndex, input: {ids: unknown; space: unknown}): Promise<LibraryMoveResult> {
  const space = index.space(typeof input.space === 'string' ? input.space : undefined)
  if (!space) throw new Error('Choose a space to copy these to.')
  if (!Array.isArray(input.ids) || !input.ids.length || input.ids.length > 20_000) throw new Error('Choose items to copy.')
  const entries = [...new Set(input.ids)].map((id) => index.item(id))
  const result: LibraryMoveResult = {moved: [], failed: [], notes: [], cancelled: false}
  await fs.mkdir(space.folder, {recursive: true})
  const operation = index.beginOperation('copy', entries.length)
  try {
    for (const entry of entries) {
      if (operation.signal.aborted) {
        result.cancelled = true
        break
      }
      operation.step(path.basename(entry.path))
      try {
        if (entry.missing || !(await exists(entry.path))) throw Object.assign(new Error(), {code: 'ENOENT'})
        // Already there: copying into the space the item lives in would only make a second file.
        if (entry.space === space.id) continue
        const directory = path.join(space.folder, ...entry.folder.split('/').filter(Boolean))
        await fs.mkdir(directory, {recursive: true})
        const oldStem = path.basename(entry.path, path.extname(entry.path))
        const target = await uniquePath(directory, path.basename(entry.path), suffixesOf(sidecarsOf(entry), oldStem))
        const newStem = path.basename(target, path.extname(target))
        await copyVerified(entry.path, target, false)
        for (const sidecar of sidecarsOf(entry))
          await copyVerified(sidecar, sidecarTarget(sidecar, oldStem, directory, newStem), false).catch(() => undefined)
        const created = await index.addFile(target, {space: space.id, managed: true, sourceUrl: entry.sourceUrl})
        if (!created) throw new Error('The copy could not be added to the library.')
        created.tags = entry.tags && [...entry.tags]
        created.userTags = entry.userTags && [...entry.userTags]
        created.favorite = entry.favorite
        index.changed()
        result.moved.push(created.id)
      } catch (error) {
        result.failed.push({id: entry.id, message: friendly(error)})
      } finally {
        operation.advance()
      }
    }
  } finally {
    operation.end()
  }
  return result
}

// ---------------------------------------------------------------------------
// Rename and delete
// ---------------------------------------------------------------------------

export async function renameItem(index: LibraryIndex, id: unknown, name: unknown): Promise<LibraryEntry> {
  const entry = index.item(id)
  const stem = validateStem(name)
  const release = index.hold()
  try {
    if (entry.missing || !(await exists(entry.path))) throw new Error(friendly(Object.assign(new Error(), {code: 'ENOENT'})))
    const ext = path.extname(entry.path)
    const directory = path.dirname(entry.path)
    const target = path.join(directory, stem + ext)
    if (target === entry.path) return entry
    const oldStem = path.basename(entry.path, ext)
    // A different case of the same name is the same file on Windows and macOS; anything else existing is a clash.
    const sameFile = target.toLowerCase() === entry.path.toLowerCase()
    if (!sameFile && (await exists(target))) throw new Error('A file with that name already exists.')
    const followsName = entry.title === titleFromName(entry.path)
    const sidecars = sidecarsOf(entry)
    // the lyrics, subtitles or music video would take another file's name: the same clash
    for (const sidecar of sidecars) {
      const next = sidecarTarget(sidecar, oldStem, directory, stem)
      if (next.toLowerCase() !== sidecar.toLowerCase() && (await exists(next))) throw new Error('A file with that name already exists.')
    }
    await fs.rename(entry.path, target)
    for (const sidecar of sidecars) {
      const next = sidecarTarget(sidecar, oldStem, directory, stem)
      if (!(await exists(next))) await fs.rename(sidecar, next).catch(() => undefined)
    }
    const renamed = index.relocate(entry.id, target, await fs.stat(target))
    if (followsName) renamed.title = titleFromName(target)
    return renamed
  } catch (error) {
    throw new Error(friendly(error))
  } finally {
    release()
  }
}

/** The Keepfin folder's trash: a deleted item's files wait in `.keepfin/trash/<item id>/` so Undo can put them back. */
export const keepfinTrashDir = (root: string) => path.join(root, '.keepfin', 'trash')

const artTypes: Record<string, string> = {'.jpg': 'image/jpeg', '.png': 'image/png', '.webp': 'image/webp'}

/** One item whose files went to the Keepfin trash: the entry as it was, and where each file went from and to. */
export type TrashedItem = {
  entry: LibraryEntry
  dir: string
  /** The media file first, then the sidecars that moved with it. */
  moved: {from: string; to: string}[]
  art?: {file: string; mime: string}
}

/** What Undo of a delete needs: the trashed items, where they stood in collections, and what download history held for their files. */
export type TrashRecord = {items: TrashedItem[]; memberships: CollectionMembership[]; refs: FileRef[]}

/** Renames inside the Keepfin folder; across volumes (a linked folder on another drive) it copies, verifies and removes the original. */
async function relocateFile(from: string, to: string) {
  try {
    await fs.rename(from, to)
  } catch (error) {
    if (!crossVolume(error)) throw error
    await copyVerified(from, to)
    await fs.rm(from, {force: true})
  }
}

/** In `trash/<item id>/`: the delete was Delete for good, so the folder is erased once its Undo is given up (or at the next purge). */
export const ERASE_MARK = '.erase'

/** Moves an item's files into `.keepfin/trash/<id>/` (the media file must move; a sidecar that cannot move stays where it is). */
async function toKeepfinTrash(index: LibraryIndex, entry: LibraryEntry, root: string, forGood = false, memberships: CollectionMembership[] = []): Promise<TrashedItem> {
  const dir = await uniquePath(keepfinTrashDir(root), entry.id)
  await fs.mkdir(dir, {recursive: true})
  // written first: if the app stops part-way, the next purge still erases what this delete put here
  if (forGood) await fs.writeFile(path.join(dir, ERASE_MARK), '')
  const item: TrashedItem = {entry: structuredClone(entry), dir, moved: []}
  try {
    const target = path.join(dir, path.basename(entry.path))
    await relocateFile(entry.path, target)
    item.moved.push({from: entry.path, to: target})
  } catch (error) {
    await fs.rm(dir, {recursive: true, force: true}).catch(() => undefined)
    throw error
  }
  for (const sidecar of sidecarsOf(entry)) {
    if (!(await exists(sidecar))) continue
    const target = path.join(dir, path.basename(sidecar))
    if (await exists(target)) continue
    try {
      await relocateFile(sidecar, target)
      item.moved.push({from: sidecar, to: target})
    } catch {
      // the sidecar stays beside where the file was; the item still goes
    }
  }
  // The cover is dropped with the entry: keep a copy beside the files so Undo brings it back
  const art = await index.artPath(entry.id).catch(() => undefined)
  const mime = art ? artTypes[path.extname(art).toLowerCase()] : undefined
  if (art && mime) {
    const copy = path.join(dir, `art${path.extname(art).toLowerCase()}`)
    if (await fs.copyFile(art, copy).then(() => true, () => false)) item.art = {file: copy, mime}
  }
  // Everything a restore needs after a restart, with the places relative to the Keepfin folder so they follow it if it moves
  const mine = memberships.filter((m) => m.itemIds.includes(entry.id) || m.newItemIds?.includes(entry.id) || m.removedItemIds?.includes(entry.id))
  const file: TrashFile = {
    version: 1,
    id: entry.id,
    title: entry.title,
    path: entry.path,
    relPath: path.relative(root, entry.path),
    kind: entry.kind,
    space: entry.space,
    size: entry.size,
    deletedAt: Date.now(),
    entry,
    moved: item.moved.map((m) => ({from: path.relative(root, m.from), to: path.basename(m.to)})),
    memberships: mine,
    art: item.art ? path.basename(item.art.file) : undefined,
  }
  await fs.writeFile(path.join(dir, TRASH_FILE), JSON.stringify(file, null, 2)).catch(() => undefined)
  return item
}

const TRASH_FILE = 'item.json'

/** What `item.json` in a trash folder holds. Every field may be missing or wrong in a file from elsewhere; readers check before use. */
type TrashFile = {
  version: 1
  id: string
  title: string
  path: string
  relPath: string
  kind: LibraryEntry['kind']
  space: string
  size: number
  deletedAt: number
  entry: LibraryEntry
  moved: {from: string; to: string}[]
  memberships: CollectionMembership[]
  art?: string
}

/**
 * Deletes items from the library. A file inside the Keepfin folder goes to the Keepfin trash (and `record` is told, so Undo can bring it
 * back); any other file goes to the Recycle Bin for good. An item whose file is already gone is just dropped. [forGood]: the Keepfin
 * trash folders are marked for erasing ([eraseTrashed] when the Undo is given up, or the next purge), and other files are removed from
 * the disk instead of going to the Recycle Bin.
 */
export async function deleteItems(index: LibraryIndex, ids: unknown, deps: FileDeps, record?: (trash: TrashRecord) => void, forGood = false): Promise<LibraryDeleteResult> {
  if (!Array.isArray(ids) || !ids.length || ids.length > 20_000) throw new Error('Choose items to delete.')
  // Ids that already left the library (a second delete, a stale selection) have nothing left to do.
  const entries = [...new Set(ids)].map((id) => (typeof id === 'string' ? index.lookup(id) : undefined)).filter((entry): entry is LibraryEntry => !!entry)
  const result: LibraryDeleteResult = {deleted: [], failed: []}
  if (!entries.length) return result
  const root = deps.keepfinRoot?.()
  const trashed: TrashedItem[] = []
  // taken before anything is dropped: the collections and the download history change as items leave
  const memberships = root ? index.memberships(entries.map((entry) => entry.id)) : []
  const refs = root && deps.history ? deps.history.fileRefs(entries.flatMap((entry) => [entry.path, ...sidecarsOf(entry)])) : []
  const release = index.hold()
  const operation = index.beginOperation('delete', entries.length)
  // Entries leave the library in small batches, so the views and the history follow along without one
  // write per file, and a crash part-way leaves only files that really are still there.
  let pending: string[] = []
  const flush = () => {
    if (pending.length) index.drop(pending, {forget: true})
    pending = []
  }
  try {
    for (const entry of entries) {
      if (operation.signal.aborted) break
      operation.step(path.basename(entry.path))
      try {
        if (root && isInside(root, entry.path) && !isInside(path.join(root, '.keepfin'), entry.path) && (await exists(entry.path))) {
          trashed.push(await toKeepfinTrash(index, entry, root, forGood, memberships))
        } else {
          for (const file of [entry.path, ...sidecarsOf(entry)]) {
            if (!(await exists(file))) continue
            if (forGood) await fs.rm(file, {force: true})
            else await deps.trash(file)
          }
        }
        pending.push(entry.id)
        result.deleted.push(entry.id)
        if (pending.length >= 25) flush()
      } catch (error) {
        result.failed.push({id: entry.id, message: friendly(error)})
      } finally {
        operation.advance()
      }
    }
  } finally {
    flush()
    operation.end()
    release()
  }
  if (trashed.length) {
    result.undoable = trashed.map((item) => item.entry.id)
    record?.({items: trashed, memberships, refs})
  }
  return result
}

/** Sends a song's music video to the Recycle Bin and leaves the song as it was. */
export async function removeMusicVideo(index: LibraryIndex, id: unknown, deps: FileDeps): Promise<void> {
  const entry = index.item(id)
  const video = entry.videoPath
  if (!video) throw new Error('This song has no music video.')
  if (await exists(video)) {
    try {
      await deps.trash(video)
    } catch (error) {
      throw new Error(friendly(error))
    }
  }
  await index.refresh(entry.id)
}

const TRASH_DAYS = 30
// item ids: UUIDs, or the path hashes of files a scan found
const trashFolder = /^(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{20})(?: \(\d+\))?$/

/**
 * Removes what has waited in the Keepfin trash for 30 days: the folders a library delete made, named by item id. A folder is judged by its
 * own modified time. Anything else in .keepfin/trash (the copies' own entries and catalogue) is left to the code that wrote it, and
 * so is a folder an Undo still holds. Returns how many went.
 */
export async function purgeKeepfinTrash(root: string, now: number, held: ReadonlySet<string> = new Set(), days = TRASH_DAYS): Promise<number> {
  const base = keepfinTrashDir(root)
  let purged = 0
  for (const entry of await fs.readdir(base, {withFileTypes: true}).catch(() => [])) {
    if (!entry.isDirectory()) continue
    // a Delete for good whose notice never ended (the app closed first) goes now, whatever its age (only a delete writes the mark)
    const marked = await exists(path.join(base, entry.name, ERASE_MARK))
    if (!marked && !trashFolder.test(entry.name)) continue
    if ([...held].some((id) => entry.name === id || entry.name.startsWith(`${id} (`))) continue
    const modified = (await fs.stat(path.join(base, entry.name)).catch(() => undefined))?.mtimeMs ?? 0
    const old = modified > 0 && now - modified >= days * 86_400_000
    if (!old && !marked) continue
    await fs.rm(path.join(base, entry.name), {recursive: true, force: true}).then(() => void purged++, () => undefined)
  }
  return purged
}

/** Delete for good: the Undo was given up, so the Keepfin trash folders of [trash] are erased now. Returns how many went. */
export async function eraseTrashed(trash: TrashRecord): Promise<number> {
  let erased = 0
  for (const item of trash.items) await fs.rm(item.dir, {recursive: true, force: true}).then(() => void erased++, () => undefined)
  return erased
}
/**
 * Undo of a delete: each item's files go back to their old place, and the item comes back with the same id, tags, favourite, plays and
 * places in its collections. A file is never put over another: a new file that took the name, or a trash entry that is gone, is
 * reported and the item stays out.
 */
export async function restoreTrashed(index: LibraryIndex, trash: TrashRecord, deps: FileDeps): Promise<UndoResult> {
  const release = index.hold()
  let done = 0
  const problems: string[] = []
  try {
    for (const item of trash.items) {
      const title = item.entry.title
      try {
        const [main, ...sidecars] = item.moved
        if (index.entryAt(main.from) || index.lookup(item.entry.id)) {
          problems.push(`“${title}” is already back in your library.`)
          continue
        }
        if (!(await exists(main.to))) {
          problems.push(`“${title}” is no longer in the Keepfin trash.`)
          continue
        }
        if (await exists(main.from)) {
          problems.push(`A new file has the name of “${title}”, so it stays in the Keepfin trash.`)
          continue
        }
        await fs.mkdir(path.dirname(main.from), {recursive: true})
        await relocateFile(main.to, main.from)
        let kept = false
        for (const sidecar of sidecars) {
          if (!(await exists(sidecar.to))) continue
          // a new file took its name: it goes to the Recycle Bin rather than being erased with the trash folder
          const back = !(await exists(sidecar.from)) && (await relocateFile(sidecar.to, sidecar.from).then(() => true, () => false))
          if (!back && !(await deps.trash(sidecar.to).then(() => true, () => false))) kept = true
        }
        const restored = index.restoreEntry(item.entry, trash.memberships, await fs.stat(main.from))
        if (item.art) {
          const bytes = await fs.readFile(item.art.file).catch(() => undefined)
          if (bytes) await index.setArt(restored.id, bytes, item.art.mime).catch(() => undefined)
        }
        if (!kept) await fs.rm(item.dir, {recursive: true, force: true}).catch(() => undefined)
        done++
      } catch (error) {
        problems.push(`“${title}”: ${friendly(error)}`)
      }
    }
  } finally {
    release()
  }
  if (done && trash.refs.length) deps.history?.restoreFileRefs(trash.refs)
  const total = trash.items.length
  const rest = problems.length > 1 ? ` ${problems.length - 1} more could not be brought back.` : ''
  const message = problems.length
    ? `${done ? `Brought back ${done} of ${total} files. ` : ''}${problems[0]}${rest}`
    : `Brought back ${total === 1 ? `“${trash.items[0].entry.title}”` : `${done} files`}`
  return {done, failed: problems.length, message}
}

// ---------------------------------------------------------------------------
// The Trash page: what waits in .keepfin/trash, read from the folders themselves (no memory needed, so it works after a restart)
// ---------------------------------------------------------------------------

type LoadedTrash = {
  /** The trash folder's name: the id the page uses. */
  name: string
  /** The library item id the folder belongs to (the Undo shelf holds items by it). */
  itemId: string
  title: string
  fileName: string
  space: string
  kind: TrashEntry['kind']
  size: number
  deletedAt: number
  /** Present when `item.json` could be read: the full entry, to restore with the same id, tags, favourite, plays and places. */
  item?: TrashedItem
  memberships: CollectionMembership[]
  /** The media file in the folder, for a restore that has no entry to go by. */
  mediaFile: string
}

const isObject = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)

/** Reads one trash folder. Missing or damaged data never hides the entry: it is described by what the folder holds. Undefined when it holds no media file. */
async function loadTrash(root: string, name: string): Promise<LoadedTrash | undefined> {
  const dir = path.join(keepfinTrashDir(root), name)
  const names = await fs.readdir(dir).catch(() => [] as string[])
  const videos = musicVideoNames(names)
  const mediaName = (preferred?: string) =>
    preferred && names.includes(preferred) ? preferred : names.find((n) => !videos.has(n.toLowerCase()) && n !== TRASH_FILE && n !== ERASE_MARK && !/^art\.\w+$/.test(n) && mediaKindOf(n))
  let parsed: Partial<TrashFile> | undefined
  try {
    const raw: unknown = JSON.parse(await fs.readFile(path.join(dir, TRASH_FILE), 'utf8'))
    if (isObject(raw)) parsed = raw as Partial<TrashFile>
  } catch {
    parsed = undefined
  }
  const modified = (await fs.stat(dir).catch(() => undefined))?.mtimeMs ?? Date.now()
  const entry = isObject(parsed?.entry) && typeof parsed.entry.id === 'string' && typeof parsed.entry.path === 'string' ? (parsed.entry as LibraryEntry) : undefined
  const moved = Array.isArray(parsed?.moved) ? parsed.moved.filter((m) => isObject(m) && typeof m.from === 'string' && typeof m.to === 'string') : []
  const mediaFile = mediaName(moved[0] && path.basename(moved[0].to))
  if (!mediaFile) return undefined
  const stat = await fs.stat(path.join(dir, mediaFile)).catch(() => undefined)
  const deletedAt = typeof parsed?.deletedAt === 'number' && Number.isFinite(parsed.deletedAt) ? parsed.deletedAt : modified
  const bare = name.replace(/ \(\d+\)$/, '')
  let item: TrashedItem | undefined
  // a record from elsewhere (a synced copy) never sends a file outside the Keepfin folder
  const inRoot = (file: string) => isInside(root, file) && !isInside(path.join(root, '.keepfin'), file)
  const abs = (rel: string) => path.join(root, rel)
  const home = typeof parsed?.relPath === 'string' && parsed.relPath ? abs(parsed.relPath) : entry?.path
  if (entry && home && inRoot(home) && moved.length && moved[0].to === mediaFile && moved.every((m) => inRoot(abs(m.from)))) {
    item = {
      entry: {...entry, path: home},
      dir,
      moved: moved.map((m) => ({from: abs(m.from), to: path.join(dir, path.basename(m.to))})).filter((m) => names.includes(path.basename(m.to))),
      art: typeof parsed?.art === 'string' && names.includes(parsed.art) ? {file: path.join(dir, parsed.art), mime: artTypes[path.extname(parsed.art).toLowerCase()] ?? 'image/jpeg'} : undefined,
    }
    if (!item.moved.length) item = undefined
  }
  return {
    name,
    itemId: item?.entry.id ?? (typeof parsed?.id === 'string' ? parsed.id : bare),
    title: item?.entry.title ?? (typeof parsed?.title === 'string' && parsed.title ? parsed.title : titleFromName(mediaFile)),
    fileName: mediaFile,
    space: item?.entry.space ?? (typeof parsed?.space === 'string' ? parsed.space : ''),
    kind: item?.entry.kind ?? mediaKindOf(mediaFile) ?? 'other',
    size: stat?.size ?? (typeof parsed?.size === 'number' ? parsed.size : 0),
    deletedAt,
    item,
    memberships: item && Array.isArray(parsed?.memberships) ? parsed.memberships.filter((m) => isObject(m) && typeof m.id === 'string' && Array.isArray(m.itemIds)) : [],
    mediaFile,
  }
}

/** The names of the trash folders a library delete made, leaving out the copies' own entries and anything marked Delete for good. */
async function trashFolders(root: string): Promise<string[]> {
  const base = keepfinTrashDir(root)
  const out: string[] = []
  for (const entry of await fs.readdir(base, {withFileTypes: true}).catch(() => [])) {
    if (!entry.isDirectory() || !trashFolder.test(entry.name)) continue
    if (await exists(path.join(base, entry.name, ERASE_MARK))) continue
    out.push(entry.name)
  }
  return out
}

const daysLeftOf = (deletedAt: number, now: number) => Math.max(0, TRASH_DAYS - Math.max(0, Math.floor((now - deletedAt) / 86_400_000)))

/** What waits in the Keepfin trash, newest deletion first. */
export async function listTrash(root: string, now = Date.now()): Promise<TrashEntry[]> {
  const out: TrashEntry[] = []
  for (const name of await trashFolders(root)) {
    const found = await loadTrash(root, name)
    if (found) out.push({id: name, title: found.title, fileName: found.fileName, space: found.space, kind: found.kind, size: found.size, deletedAt: found.deletedAt, daysLeft: daysLeftOf(found.deletedAt, now)})
  }
  return out.sort((a, b) => b.deletedAt - a.deletedAt)
}

const validTrashName = (name: unknown): name is string => typeof name === 'string' && trashFolder.test(name)

/** A trash page answer, plus the library item ids it affected (the Undo shelf drops what it holds for them). */
export type TrashOutcome = TrashResult & {itemIds: string[]}

/**
 * Puts trash entries back: to their old place, with the same item id, tags, favourite, plays and collection places. A file is never put
 * over another: that entry stays in the trash and the message says so.
 */
export async function restoreFromTrash(index: LibraryIndex, root: string, names: unknown, deps: FileDeps): Promise<TrashOutcome> {
  if (!Array.isArray(names) || !names.length || names.length > 20_000) throw new Error('Choose files to restore.')
  const ids: string[] = []
  const itemIds: string[] = []
  const problems: string[] = []
  const titles: string[] = []
  for (const name of [...new Set(names)]) {
    if (!validTrashName(name)) continue
    const found = await loadTrash(root, name)
    if (!found) {
      problems.push('That file is no longer in the trash.')
      continue
    }
    try {
      if (found.item) {
        const home = found.item.moved[0].from
        if (await exists(home)) {
          problems.push(`“${found.title}”: a new file has its name now`)
          continue
        }
        const result = await restoreTrashed(index, {items: [found.item], memberships: found.memberships, refs: []}, deps)
        if (!result.done) {
          problems.push(result.message ?? `“${found.title}” could not be restored.`)
          continue
        }
      } else {
        // no readable record: the file comes back as a fresh import into the first folder space inside the Keepfin folder
        const usable = index.spaces().filter((s) => s.type === 'folder' && s.folder && isInside(root, s.folder))
        const space = usable.find((s) => s.id === (found.kind === 'audio' ? 'music' : found.kind === 'video' ? 'videos' : '')) ?? usable[0]
        if (!space) {
          problems.push(`“${found.title}”: there is no space to put it in`)
          continue
        }
        const target = path.join(space.folder, found.fileName)
        if (await exists(target)) {
          problems.push(`“${found.title}”: a new file has its name now`)
          continue
        }
        await fs.mkdir(space.folder, {recursive: true})
        await relocateFile(path.join(keepfinTrashDir(root), name, found.mediaFile), target)
        await fs.rm(path.join(keepfinTrashDir(root), name), {recursive: true, force: true}).catch(() => undefined)
        await index.addFile(target, {space: space.id, managed: true})
      }
      ids.push(name)
      itemIds.push(found.itemId)
      titles.push(found.title)
    } catch (error) {
      problems.push(`“${found.title}”: ${friendly(error)}`)
    }
  }
  const done = ids.length
  const rest = problems.length > 1 ? ` ${problems.length - 1} more could not be restored.` : ''
  const restored = done === 1 ? `Restored “${titles[0]}”` : `Restored ${done} files`
  const message = problems.length ? (done ? `${restored}. ${problems[0]}${rest}` : `${problems[0]}${rest}`) : restored
  return {done, failed: problems.length, message, ids, itemIds}
}

/** Delete for good, now: these trash folders (and nothing else in .keepfin/trash) are erased. */
export async function eraseFromTrash(root: string, names: unknown): Promise<TrashOutcome> {
  if (!Array.isArray(names)) throw new Error('Choose files to delete.')
  const ids: string[] = []
  const itemIds: string[] = []
  let failed = 0
  for (const name of [...new Set(names)]) {
    if (!validTrashName(name)) continue
    const dir = path.join(keepfinTrashDir(root), name)
    if (!(await exists(dir))) continue
    const found = await loadTrash(root, name)
    const gone = await fs.rm(dir, {recursive: true, force: true}).then(() => true, () => false)
    if (gone) {
      ids.push(name)
      itemIds.push(found?.itemId ?? name.replace(/ \(\d+\)$/, ''))
    } else failed++
  }
  const message = failed
    ? `${ids.length ? `Deleted ${ids.length} for good. ` : ''}${failed} could not be deleted because they are in use.`
    : ids.length === 1
      ? 'Deleted for good'
      : `Deleted ${ids.length} files for good`
  return {done: ids.length, failed, message, ids, itemIds}
}

/** Erases everything the Trash page lists. */
export async function emptyKeepfinTrash(root: string): Promise<TrashOutcome> {
  return eraseFromTrash(root, await trashFolders(root))
}
