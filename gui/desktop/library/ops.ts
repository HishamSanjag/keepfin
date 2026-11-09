// IPC operations for the library. main.ts forwards anything it does not know to handleLibraryRequest;
// every input is validated here, and paths only ever come from the index or a native dialog.
import fs from 'node:fs/promises'
import path from 'node:path'
import type {Request} from '../../shared/contracts.js'
import {buildM3u8, notOnThisPc, type MusicVideoFound, type UndoResult} from '../../shared/library.js'
import {lookupMusicVideo} from '../engine/music.js'
import type {DownloadManager} from '../manager.js'
import {editInfo, validateInfo, type EditInfo} from './edit.js'
import {
  copyItems,
  deleteItems,
  emptyKeepfinTrash,
  eraseFromTrash,
  eraseTrashed,
  importFiles,
  listTrash,
  moveItems,
  removeMusicVideo,
  renameItem,
  restoreFromTrash,
  restoreTrashed,
  validateStem,
  type FileDeps,
} from './files.js'
import type {FollowEngine} from './follow.js'
import type {ArtistFollowing} from './artists.js'
import type {LibraryIndex} from './index.js'
import type {PlayCache} from './prepare.js'
import type {UndoShelf} from './undo.js'

export type LibraryDeps = {
  index: LibraryIndex
  follow: FollowEngine
  artists: ArtistFollowing
  manager: DownloadManager
  cache: PlayCache
  ffmpeg: () => string | undefined
  trash: (file: string) => Promise<void>
  /** The open Keepfin folder's root (a file inside it is deleted into its trash, which Undo can bring back), or undefined when there is none. */
  keepfinRoot?: () => string | undefined
  /** Where Undo is remembered: one shelf for the whole app (download history uses it too). */
  undo: UndoShelf
  /** Native dialogs, opened by the main process on behalf of the renderer. */
  pickFiles: (kind: 'files' | 'folder') => Promise<string[] | undefined>
  savePlaylist: (defaultName: string) => Promise<string | undefined>
  openPath: (file: string) => Promise<string>
  showInFolder: (file: string) => void
  startDrag: (file: string, art: string | undefined) => void
  /**
   * Play from a paired device (dev-docs/plans/KEEPFIN-NETWORK-SPEC.md 6): a media URL for an item whose file is not on this computer but is on a device
   * that lets this one play its files, or undefined. Nothing is saved.
   */
  playFrom?: (itemId: string, fileName: string) => Promise<PlayFromOutcome | undefined>
}

/** What playing from a paired device gives: the media URL and the device's name, or the device that has it but cannot be reached. */
export type PlayFromOutcome = {url: string; deviceName: string} | {unreachable: {device: string; lastSeen: number | null}}

export const libraryOperations = new Set<string>([
  'libraryState', 'libraryResync', 'libraryScan', 'libraryImport', 'libraryMove', 'libraryCancel', 'libraryRename', 'libraryEditInfo',
  'libraryDeleteFile', 'libraryOpen', 'libraryReveal', 'libraryDrag', 'libraryPlayback', 'libraryFavorite',
  'libraryLyrics', 'libraryFindLyrics', 'songOfLink', 'libraryFindMusicVideo', 'libraryGetMusicVideo', 'libraryRemoveMusicVideo', 'librarySearchLyrics', 'libraryPrepare', 'libraryMatch', 'downloadAnyway', 'spaceSave',
  'spaceDelete', 'spaceReorder', 'libraryTag', 'libraryAddFrom', 'collectionSave', 'collectionDelete', 'collectionExport',
  'collectionAdd', 'collectionRemove', 'collectionReorder', 'collectionFollow', 'collectionCheck', 'collectionReview',
  'collectionOpened', 'followingState', 'followArtist', 'unfollowArtist', 'followingCheck', 'keepRelease', 'playlistSave', 'playlistDelete', 'playlistExport', 'libraryUndo', 'libraryEraseTrashed',
  'libraryTrashList', 'libraryTrashRestore', 'libraryTrashErase', 'libraryTrashEmpty',
])

const idsOf = (request: Request): unknown[] => (request.ids !== undefined ? (Array.isArray(request.ids) ? request.ids : []) : [request.id])

async function playable(deps: LibraryDeps, id: unknown) {
  const entry = deps.index.item(id)
  if (!entry.missing && (await fs.stat(entry.path).catch(() => undefined))?.isFile()) return entry
  // A name that an older version saved damaged is found again under its real name before the file is called gone.
  const relinked = await Promise.resolve()
    .then(() => deps.index.relinkItem(entry.id))
    .catch(() => undefined)
  if (relinked && (await fs.stat(relinked.path).catch(() => undefined))?.isFile()) return relinked
  throw new Error('The file is no longer at its saved location.')
}

const done = (count: number, message?: string): UndoResult => ({done: count, failed: 0, message})

/** The names of the tags that differ: what `after` has that `before` did not, and what `before` had that `after` lost. */
const tagChange = (before: readonly string[], after: readonly string[]) => ({
  added: after.filter((tag) => !before.includes(tag)),
  removed: before.filter((tag) => !after.includes(tag)),
})

/** The editable tags an item has now, in the shape an edit takes (a cleared value is an empty string or 0). */
function infoOf(entry: ReturnType<LibraryIndex['item']>, keys: (keyof EditInfo)[]): EditInfo {
  const info: EditInfo = {}
  for (const key of keys) {
    if (key === 'cover') continue
    if (key === 'title') info.title = entry.title
    else if (key === 'artist' || key === 'album' || key === 'albumArtist' || key === 'genre') info[key] = entry[key] ?? ''
    else info[key] = entry[key] ?? 0
  }
  return info
}

/** Moves items to a space; Undo moves each one back to the space it came from (grouped by origin), whatever else changed since. */
async function moveWithUndo(index: LibraryIndex, input: {ids: unknown; space: unknown}, files: FileDeps, shelf: UndoShelf, undoId: unknown) {
  const origins = new Map<string, string>()
  const result = await moveItems(index, input, files, ({id, from}) => origins.set(id, from))
  if (origins.size)
    shelf.record(undoId, async () => {
      const bySpace = new Map<string, string[]>()
      for (const [id, from] of origins) {
        if (!index.lookup(id)) continue
        bySpace.set(from, [...(bySpace.get(from) ?? []), id])
      }
      let moved = 0
      const failures: string[] = []
      for (const [space, ids] of bySpace) {
        const back = await moveItems(index, {ids, space}, files).catch((error: unknown) => ({moved: [] as string[], failed: ids.map((id) => ({id, message: error instanceof Error ? error.message : String(error)})), notes: [], cancelled: false}))
        moved += back.moved.length
        failures.push(...back.failed.map((failure) => failure.message))
      }
      return {done: moved, failed: failures.length, message: failures.length ? failures[0] : undefined}
    })
  return result
}

export async function handleLibraryRequest(request: Request, deps: LibraryDeps): Promise<unknown> {
  const {index, manager} = deps
  const files: FileDeps = {
    trash: deps.trash,
    keepfinRoot: deps.keepfinRoot,
    history: {fileRefs: (paths) => manager.fileRefs?.(paths) ?? [], restoreFileRefs: (refs) => manager.restoreFileRefs?.(refs)},
  }
  switch (request.op) {
    case 'libraryUndo':
      return deps.undo.undo(request.undoId)
    case 'libraryEraseTrashed':
      await deps.undo.expire(request.undoId)
      return null
    case 'libraryTrashList': {
      const root = deps.keepfinRoot?.()
      return root ? {hasFolder: true, entries: await listTrash(root)} : {hasFolder: false, entries: []}
    }
    case 'libraryTrashRestore':
    case 'libraryTrashErase':
    case 'libraryTrashEmpty': {
      const root = deps.keepfinRoot?.()
      if (!root) throw new Error('The trash belongs to the Keepfin folder.')
      const outcome =
        request.op === 'libraryTrashRestore'
          ? await restoreFromTrash(index, root, request.ids, files)
          : request.op === 'libraryTrashErase'
            ? await eraseFromTrash(root, request.ids)
            : await emptyKeepfinTrash(root)
      // an Undo of the delete that put these here has nothing left to do
      deps.undo.forgetHolding(outcome.itemIds)
      const {itemIds: _itemIds, ...answer} = outcome
      return answer
    }
    case 'libraryState':
    case 'libraryResync':
      // A small library comes back whole; a big one as batches pushed to the window (and a marker here).
      return index.stateResponse()
    case 'libraryScan':
      void index.scan()
      return index.stateResponse()
    case 'spaceSave':
      return manager.saveSpace(request.spaceDef)
    case 'spaceDelete': {
      const settings = manager.getSettings().library
      const position = settings.spaces.findIndex((space) => space.id === request.id)
      const space = settings.spaces[position]
      const routes = {...settings.routes}
      const snapshot = await manager.deleteSpace(request.id)
      // Undo puts the same space back at its place, and the routes that pointed to it
      deps.undo.record(request.undoId, async () => {
        await manager.restoreSpace(space, position, routes)
        return done(1)
      })
      return snapshot
    }
    case 'spaceReorder':
      return manager.reorderSpaces(request.ids)
    case 'libraryTag': {
      const tags = request.tags
      if (!tags || typeof tags !== 'object') throw new Error('Type a tag to add or remove.')
      // what each item had before: Undo reverses what this action changed on each item, nothing else
      const before = new Map<string, string[]>()
      for (const id of Array.isArray(request.ids) ? new Set(request.ids) : []) before.set(id, [...(index.item(id).userTags ?? [])])
      index.setUserTags(request.ids, tags.add, tags.remove)
      const groups = new Map<string, {ids: string[]; added: string[]; removed: string[]}>()
      for (const [id, was] of before) {
        const change = tagChange(was, index.lookup(id)?.userTags ?? [])
        if (!change.added.length && !change.removed.length) continue
        const key = JSON.stringify([[...change.added].sort(), [...change.removed].sort()])
        const group = groups.get(key) ?? {ids: [], ...change}
        group.ids.push(id)
        groups.set(key, group)
      }
      if (groups.size)
        deps.undo.record(request.undoId, async () => {
          let items = 0
          for (const group of groups.values()) {
            const present = group.ids.filter((id) => index.lookup(id))
            if (!present.length) continue
            // the tags it added come off, the ones it removed go back
            index.setUserTags(present, group.removed, group.added)
            items += present.length
          }
          return done(items)
        })
      return null
    }
    case 'libraryAddFrom': {
      const ids = [...(Array.isArray(request.ids) ? request.ids : [])]
      for (const collectionId of Array.isArray(request.collectionIds) ? request.collectionIds : []) ids.push(...index.collection(collectionId).itemIds)
      const input = {ids: [...new Set(ids)], space: request.space}
      if (request.addMode === 'copy') return copyItems(index, input)
      return moveWithUndo(index, input, files, deps.undo, request.undoId)
    }
    case 'libraryImport': {
      let paths = request.paths
      if (paths === undefined) {
        paths = await deps.pickFiles(request.pick === 'folder' ? 'folder' : 'files')
        if (!paths?.length) return {added: [], duplicates: [], failed: [], cancelled: true}
      }
      return importFiles(index, {paths, space: request.space, mode: request.importMode}, files)
    }
    case 'libraryMove':
      // a copy leaves the original in place, so there is nothing to undo
      if ((request as {copy?: unknown}).copy === true) return moveItems(index, {ids: request.ids, space: request.space, copy: true}, files)
      return moveWithUndo(index, {ids: request.ids, space: request.space}, files, deps.undo, request.undoId)
    case 'libraryCancel':
      index.cancelOperation()
      return null
    case 'libraryRename': {
      const before = index.item(request.id)
      const oldPath = before.path
      const oldStem = path.basename(oldPath, path.extname(oldPath))
      const renamed = await renameItem(index, request.id, request.text)
      if (renamed.path !== oldPath)
        deps.undo.record(request.undoId, async () => {
          await renameItem(index, renamed.id, oldStem)
          return done(1)
        })
      return {id: renamed.id}
    }
    case 'libraryEditInfo': {
      // the values the edit replaces, read before it runs. A new cover cannot be put back exactly, so such an edit has no Undo.
      const entry = index.item(request.id)
      const info = validateInfo(request.info)
      const previous = info.cover ? undefined : infoOf(entry, Object.keys(info) as (keyof EditInfo)[])
      const id = entry.id
      await editInfo(index, id, request.info, deps.ffmpeg())
      if (previous)
        deps.undo.record(request.undoId, async () => {
          await editInfo(index, id, previous, deps.ffmpeg())
          return done(1)
        })
      return null
    }
    case 'libraryDeleteFile': {
      const forGood = request.forGood === true
      return deleteItems(
        index,
        idsOf(request),
        files,
        (trash) =>
          deps.undo.record(
            request.undoId,
            () => restoreTrashed(index, trash, files),
            trash.items.map((item) => item.entry.id),
            // Delete for good: once the Undo is given up the files leave the Keepfin trash
            forGood ? () => eraseTrashed(trash).then(() => undefined) : undefined,
          ),
        forGood,
      )
    }
    case 'libraryOpen': {
      const entry = await playable(deps, request.id)
      const failure = await deps.openPath(request.video && entry.videoPath ? entry.videoPath : entry.path)
      if (failure) throw new Error('This file could not be opened in another app.')
      return null
    }
    case 'libraryReveal': {
      const entry = await playable(deps, request.id)
      // `video`: the song's music video file, shown in its folder
      deps.showInFolder(request.video && entry.videoPath ? entry.videoPath : entry.path)
      return null
    }
    case 'libraryDrag': {
      const entry = await playable(deps, request.id)
      deps.startDrag(entry.path, await index.artPath(entry.id))
      return null
    }
    case 'libraryPlayback':
      index.recordPlayback(request.id, request.position, request.finished)
      return null
    case 'libraryFavorite': {
      const list = idsOf(request)
      // the items this action really changes (some already were as asked)
      const changed =
        typeof request.favorite === 'boolean'
          ? list.filter((id): id is string => typeof id === 'string' && !!index.lookup(id) && (index.lookup(id)!.favorite ?? false) !== request.favorite)
          : []
      index.setFavorite(list, request.favorite)
      if (changed.length)
        deps.undo.record(request.undoId, async () => {
          const present = changed.filter((id) => index.lookup(id))
          if (present.length) index.setFavorite(present, !request.favorite)
          return done(present.length)
        })
      return null
    }
    case 'libraryLyrics':
      return (await index.lyrics(request.id)) ?? null
    case 'libraryFindLyrics':
      return manager.findLyrics(request.id)
    case 'songOfLink':
      return manager.songOfInspected(request.url)
    case 'libraryFindMusicVideo': {
      const entry = index.item(request.id)
      const artist = entry.artist ?? entry.albumArtist
      if (entry.kind !== 'audio' || !artist) return {none: true} satisfies MusicVideoFound
      return lookupMusicVideo(artist, entry.title)
    }
    case 'libraryGetMusicVideo':
      await manager.getMusicVideo(request.id, request.videoId)
      return null
    case 'libraryRemoveMusicVideo':
      await removeMusicVideo(index, request.id, files)
      return null
    case 'librarySearchLyrics':
      return index.searchLyrics(request.text)
    case 'libraryPrepare':
      try {
        return await deps.cache.prepare(await playable(deps, request.id))
      } catch (error) {
        // not on this computer: a paired device that has it can play it (Range works, so seeking does)
        const entry = typeof request.id === 'string' ? index.lookup(request.id) : undefined
        const fileName = entry ? (entry.title ? entry.title.replace(/[<>:"/\\|?*\x00-\x1f]/g, '_') : path.basename(entry.path)) : ''
        const found = entry && deps.playFrom ? await deps.playFrom(entry.id, fileName).catch(() => undefined) : undefined
        if (found && 'url' in found) return {url: found.url, remuxed: false, from: found.deviceName}
        if (found) return {unreachable: found.unreachable}
        if (entry && notOnThisPc(entry)) throw new Error('This is not on this PC, and no device that lets you play its files has it right now.')
        throw error
      }
    case 'libraryMatch':
      return manager.libraryMatch(request.urls, request.space, request.inspectionId)
    case 'downloadAnyway':
      return manager.downloadAnyway(request.id)
    case 'collectionSave':
    case 'playlistSave': {
      const input = request.collection ?? request.playlist
      if (!input || typeof input !== 'object') throw new Error('Invalid collection.')
      const renaming = input.id ? index.collection(input.id) : undefined
      const oldName = renaming?.name
      const saved = index.saveCollection(input)
      if (!renaming)
        // Undo of "Made the collection": it goes again
        deps.undo.record(request.undoId, async () => {
          index.deleteCollection(saved.id)
          return done(1)
        })
      else if (oldName !== undefined && oldName !== saved.name)
        deps.undo.record(request.undoId, async () => {
          index.saveCollection({id: saved.id, name: oldName})
          return done(1)
        })
      return saved
    }
    case 'collectionDelete':
    case 'playlistDelete': {
      // the collection as it is now: Undo brings it back whole, with the same id
      const saved = structuredClone(index.collection(request.id))
      index.deleteCollection(request.id)
      deps.undo.record(request.undoId, async () => {
        index.restoreCollection(saved)
        return done(1)
      })
      return null
    }
    case 'collectionAdd': {
      const before = new Set(index.collection(request.id).itemIds)
      const collection = index.addToCollection(request.id, request.ids)
      const added = collection.itemIds.filter((id) => !before.has(id))
      // Undo takes out only the songs this action added, not ones that were there already
      if (added.length)
        deps.undo.record(request.undoId, async () => {
          index.removeFromCollection(request.id, added)
          return done(added.length)
        })
      return collection
    }
    case 'collectionRemove':
      return index.removeFromCollection(request.id, request.ids)
    case 'collectionReorder':
      return index.reorderCollection(request.id, request.ids)
    case 'collectionFollow':
      return index.setFollow(request.id, request.follow)
    case 'collectionCheck':
      if (typeof request.id === 'string') return [await deps.follow.check(request.id)]
      return deps.follow.checkDue()
    case 'collectionReview':
      if (typeof request.id !== 'string') throw new Error('Choose a collection to review.')
      return deps.follow.review(request.id)
    case 'followingState':
      return deps.artists.current()
    case 'followArtist':
      return deps.artists.follow(String(request.text ?? ''))
    case 'unfollowArtist':
      return deps.artists.unfollow(String(request.id))
    case 'followingCheck':
      return deps.artists.check(typeof request.id === 'string' ? request.id : undefined, true)
    case 'keepRelease':
      return deps.artists.keep(String(request.id))
    case 'collectionOpened':
      index.markOpened(request.id)
      return null
    case 'collectionExport':
    case 'playlistExport': {
      const playlist = index.collection(request.id)
      const defaultName = (() => {
        try {
          return validateStem(playlist.name.replace(/[<>:"/\\|?*\x00-\x1f]/g, '_').replace(/[. ]+$/, ''))
        } catch {
          return 'Playlist'
        }
      })()
      const chosen = await deps.savePlaylist(defaultName)
      if (!chosen) return null
      const target = /\.m3u8?$/i.test(chosen) ? chosen : `${chosen}.m3u8`
      const entries = playlist.itemIds
        .map((id) => index.lookup(id))
        .filter((entry): entry is NonNullable<typeof entry> => !!entry && !entry.missing)
        .map((entry) => ({
          path: path.relative(path.dirname(target), entry.path),
          title: entry.title,
          artist: entry.artist,
          duration: entry.duration,
        }))
      await fs.writeFile(target, buildM3u8(playlist.name, entries), 'utf8')
      return {path: target, count: entries.length}
    }
    default:
      throw new Error('Unknown operation.')
  }
}
