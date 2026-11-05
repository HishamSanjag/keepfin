// Undo of library actions (main process): each action sent with an undoId records how to reverse it from the state at that moment,
// and libraryUndo reverses exactly that on the state as it is then. The same cases the phone's undo tests cover.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {spawnSync} from 'node:child_process'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import ffmpegPath from 'ffmpeg-static'
import {defaultLibrarySettings, type LibraryDeleteResult, type LibraryMoveResult, type UndoResult} from '../shared/library.js'
import {LibraryIndex} from './library/index.js'
import {purgeKeepfinTrash} from './library/files.js'
import {handleLibraryRequest, type LibraryDeps} from './library/ops.js'
import {UndoShelf} from './library/undo.js'

async function setup() {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-undo-'))
  const settings = defaultLibrarySettings({downloads: path.join(dir, 'Downloads'), music: path.join(dir, 'Music'), videos: path.join(dir, 'Videos')})
  for (const space of settings.spaces.filter((item) => item.type === 'folder')) await fs.mkdir(space.folder, {recursive: true})
  const index = new LibraryIndex({file: path.join(dir, 'data', 'library.json'), artDir: path.join(dir, 'art'), ffmpeg: () => ffmpegPath as string, saveDelay: 10, pushDelay: 5, watchDelay: 30, watch: false, analyse: false})
  await index.init()
  index.configure(settings)
  const trashed: string[] = []
  // the folder that counts as the Keepfin folder is the one the test sets; files outside it go to the (fake) Recycle Bin
  const state = {root: dir as string | undefined}
  const deps = {
    index,
    manager: {fileRefs: () => [], restoreFileRefs: () => undefined},
    ffmpeg: () => ffmpegPath as string,
    trash: async (target: string) => {
      trashed.push(target)
      // Windows: a just-written file can be held for a moment (antivirus, the indexer): EBUSY under load, so retry like the Recycle Bin does
      await fs.rm(target, {force: true, maxRetries: 5, retryDelay: 100})
    },
    keepfinRoot: () => state.root,
    undo: new UndoShelf(),
  } as unknown as LibraryDeps
  let n = 0
  const send = async <T>(request: Record<string, unknown>, undo = true) => {
    const undoId = `undo-id-${++n}`
    const result = (await handleLibraryRequest({...request, ...(undo ? {undoId} : {})} as never, deps)) as T
    return {
      result,
      undo: () => handleLibraryRequest({op: 'libraryUndo', undoId} as never, deps) as Promise<UndoResult>,
      // the notice went without Undo
      gone: () => handleLibraryRequest({op: 'libraryEraseTrashed', undoId} as never, deps),
    }
  }
  return {dir, settings, index, deps, trashed, state, send, cleanup: async () => {
    await index.close()
    await fs.rm(dir, {recursive: true, force: true})
  }}
}
const write = async (file: string, text = 'x') => {
  await fs.mkdir(path.dirname(file), {recursive: true})
  await fs.writeFile(file, text)
  return file
}
const exists = (file: string) => fs.access(file).then(() => true, () => false)

test('Undo is one use, and an unknown id says so', async () => {
  const f = await setup()
  try {
    const a = (await f.index.addFile(await write(path.join(f.settings.spaces[1].folder, 'a.mp3')), {space: 'music', managed: true}))!
    const fav = await f.send({op: 'libraryFavorite', ids: [a.id], favorite: true})
    await fav.undo()
    await assert.rejects(() => fav.undo(), /can no longer be undone/)
    await assert.rejects(() => handleLibraryRequest({op: 'libraryUndo', undoId: 'never-seen-id'} as never, f.deps), /can no longer be undone/)
    await assert.rejects(() => handleLibraryRequest({op: 'libraryUndo'} as never, f.deps), /can no longer be undone/)
  } finally {
    await f.cleanup()
  }
})

test('Undo of favorite changes back only the items the action changed', async () => {
  const f = await setup()
  try {
    const music = f.settings.spaces[1].folder
    const a = (await f.index.addFile(await write(path.join(music, 'a.mp3')), {space: 'music', managed: true}))!
    const b = (await f.index.addFile(await write(path.join(music, 'b.mp3')), {space: 'music', managed: true}))!
    f.index.setFavorite([a.id], true)
    const action = await f.send({op: 'libraryFavorite', ids: [a.id, b.id], favorite: true})
    assert.equal(f.index.item(b.id).favorite, true)
    const back = await action.undo()
    assert.equal(back.done, 1)
    assert.equal(f.index.item(a.id).favorite, true, 'already a favorite before the action: stays')
    assert.notEqual(f.index.item(b.id).favorite, true)
    // the opposite action
    const off = await f.send({op: 'libraryFavorite', id: a.id, ids: [a.id], favorite: false})
    assert.notEqual(f.index.item(a.id).favorite, true)
    await off.undo()
    assert.equal(f.index.item(a.id).favorite, true)
  } finally {
    await f.cleanup()
  }
})

test('Undo of tags removes the tags the action added and puts back the ones it removed, per item, keeping later changes', async () => {
  const f = await setup()
  try {
    const music = f.settings.spaces[1].folder
    const a = (await f.index.addFile(await write(path.join(music, 'a.mp3')), {space: 'music', managed: true}))!
    const b = (await f.index.addFile(await write(path.join(music, 'b.mp3')), {space: 'music', managed: true}))!
    f.index.setUserTags([a.id], ['rock', 'old'], [])
    // a already has rock, b does not: "add rock, remove old" changes them differently
    const action = await f.send({op: 'libraryTag', ids: [a.id, b.id], tags: {add: ['rock', 'new'], remove: ['old']}})
    assert.deepEqual(f.index.item(a.id).userTags, ['rock', 'new'])
    assert.deepEqual(f.index.item(b.id).userTags, ['rock', 'new'])
    // a tag added after the action stays
    f.index.setUserTags([b.id], ['later'], [])
    const back = await action.undo()
    assert.equal(back.done, 2)
    assert.deepEqual(f.index.item(a.id).userTags, ['rock', 'old'])
    assert.deepEqual(f.index.item(b.id).userTags, ['later'])
  } finally {
    await f.cleanup()
  }
})

test('Undo of add to a collection takes out only the songs the action added', async () => {
  const f = await setup()
  try {
    const music = f.settings.spaces[1].folder
    const [a, b, c] = await Promise.all(['a', 'b', 'c'].map(async (name) => (await f.index.addFile(await write(path.join(music, `${name}.mp3`)), {space: 'music', managed: true}))!))
    const collection = f.index.saveCollection({name: 'Mix', itemIds: [a.id]})
    const action = await f.send({op: 'collectionAdd', id: collection.id, ids: [a.id, b.id]})
    f.index.addToCollection(collection.id, [c.id])
    const back = await action.undo()
    assert.equal(back.done, 1)
    assert.deepEqual(f.index.collection(collection.id).itemIds, [a.id, c.id], 'a was there before, c came after')
    // adding only songs already there records nothing to undo
    const nothing = await f.send({op: 'collectionAdd', id: collection.id, ids: [a.id]})
    await assert.rejects(() => nothing.undo(), /can no longer be undone/)
  } finally {
    await f.cleanup()
  }
})

test('Undo of creating, renaming and deleting a collection', async () => {
  const f = await setup()
  try {
    const music = f.settings.spaces[1].folder
    const [a, b, c] = await Promise.all(['a', 'b', 'c'].map(async (name) => (await f.index.addFile(await write(path.join(music, `${name}.mp3`)), {space: 'music', managed: true}))!))
    const made = await f.send<{id: string}>({op: 'collectionSave', collection: {name: 'New mix', itemIds: [a.id, b.id]}})
    assert.equal(f.index.collection(made.result.id).name, 'New mix')
    await made.undo()
    assert.throws(() => f.index.collection(made.result.id), /no longer exists/)

    const collection = f.index.saveCollection({name: 'Road', itemIds: [c.id, a.id, b.id]})
    const renamed = await f.send({op: 'collectionSave', collection: {id: collection.id, name: 'Road trip'}})
    assert.equal(f.index.collection(collection.id).name, 'Road trip')
    await renamed.undo()
    assert.equal(f.index.collection(collection.id).name, 'Road')

    const space = f.index.collection(collection.id).space
    const deleted = await f.send({op: 'collectionDelete', id: collection.id})
    assert.throws(() => f.index.collection(collection.id), /no longer exists/)
    // a song that left the library meanwhile is not brought back with it
    f.index.drop([b.id])
    const back = await deleted.undo()
    assert.equal(back.done, 1)
    const restored = f.index.collection(collection.id)
    assert.equal(restored.name, 'Road')
    assert.equal(restored.space, space)
    assert.deepEqual(restored.itemIds, [c.id, a.id])
  } finally {
    await f.cleanup()
  }
})

test('Undo of a move puts each file back in the space it came from, grouped by origin', async () => {
  const f = await setup()
  try {
    const downloads = f.settings.spaces.find((space) => space.id === 'downloads')!
    const music = f.settings.spaces.find((space) => space.id === 'music')!
    const videos = f.settings.spaces.find((space) => space.id === 'videos')!
    const a = (await f.index.addFile(await write(path.join(downloads.folder, 'a.mp3')), {space: 'downloads', managed: true}))!
    const b = (await f.index.addFile(await write(path.join(music.folder, 'b.mp3')), {space: 'music', managed: true}))!
    const c = (await f.index.addFile(await write(path.join(music.folder, 'c.mp3')), {space: 'music', managed: true}))!
    const moved = await f.send<LibraryMoveResult>({op: 'libraryMove', ids: [a.id, b.id, c.id], space: 'videos'})
    assert.equal(moved.result.moved.length, 3)
    assert.ok(await exists(path.join(videos.folder, 'a.mp3')))
    const back = await moved.undo()
    assert.equal(back.done, 3)
    assert.equal(back.failed, 0)
    assert.ok(await exists(path.join(downloads.folder, 'a.mp3')))
    assert.ok(await exists(path.join(music.folder, 'b.mp3')))
    assert.ok(await exists(path.join(music.folder, 'c.mp3')))
    assert.ok(!(await exists(path.join(videos.folder, 'a.mp3'))))
    assert.deepEqual(f.index.all().map((entry) => entry.space).sort(), ['downloads', 'music', 'music'])
  } finally {
    await f.cleanup()
  }
})

test('Undo of renaming a file gives it its old name back', async () => {
  const f = await setup()
  try {
    const music = f.settings.spaces[1].folder
    const a = (await f.index.addFile(await write(path.join(music, 'before.mp3')), {space: 'music', managed: true}))!
    const renamed = await f.send<{id: string}>({op: 'libraryRename', id: a.id, text: 'after'})
    assert.ok(await exists(path.join(music, 'after.mp3')))
    await renamed.undo()
    assert.ok(await exists(path.join(music, 'before.mp3')))
    assert.ok(!(await exists(path.join(music, 'after.mp3'))))
    // renaming to the same name changes nothing and records nothing
    const same = await f.send({op: 'libraryRename', id: f.index.all()[0].id, text: 'before'})
    await assert.rejects(() => same.undo(), /can no longer be undone/)
  } finally {
    await f.cleanup()
  }
})

test('Undo of Edit info writes the previous values back; an edit with a new cover has no Undo', async () => {
  const f = await setup()
  try {
    const file = path.join(f.settings.spaces[1].folder, 'song.mp3')
    await fs.mkdir(path.dirname(file), {recursive: true})
    const made = spawnSync(ffmpegPath as string, ['-hide_banner', '-nostdin', '-y', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=1', '-metadata', 'title=Old title', '-metadata', 'artist=Old artist', '-c:a', 'libmp3lame', '-id3v2_version', '3', file])
    assert.equal(made.status, 0, String(made.stderr))
    const entry = (await f.index.addFile(file, {space: 'music', managed: true}))!
    await f.index.refresh(entry.id)
    assert.equal(f.index.item(entry.id).title, 'Old title')
    const edit = await f.send({op: 'libraryEditInfo', id: entry.id, info: {title: 'New title', album: 'New album'}})
    assert.equal(f.index.item(entry.id).title, 'New title')
    assert.equal(f.index.item(entry.id).album, 'New album')
    await edit.undo()
    const item = f.index.item(entry.id)
    assert.equal(item.title, 'Old title')
    assert.equal(item.album, undefined, 'a tag the file did not have is cleared again')
    assert.equal(item.artist, 'Old artist', 'a tag the edit did not touch is left alone')
    // with a cover: no record, so no Undo
    const cover = path.join(f.dir, 'cover.png')
    const png = spawnSync(ffmpegPath as string, ['-hide_banner', '-nostdin', '-y', '-f', 'lavfi', '-i', 'color=c=red:s=64x64', '-frames:v', '1', cover])
    assert.equal(png.status, 0)
    const withCover = await f.send({op: 'libraryEditInfo', id: entry.id, info: {title: 'Covered', cover}})
    await assert.rejects(() => withCover.undo(), /can no longer be undone/)
  } finally {
    await f.cleanup()
  }
})

test('Delete: files in the Keepfin folder go to its trash and Undo puts them, and the item, back', async () => {
  const f = await setup()
  try {
    const music = f.settings.spaces[1].folder
    const a = (await f.index.addFile(await write(path.join(music, 'Album', 'a.mp3'), 'bytes-a'), {space: 'music', managed: true}))!
    const b = (await f.index.addFile(await write(path.join(music, 'b.mp3'), 'bytes-b'), {space: 'music', managed: true}))!
    f.index.setUserTags([a.id], ['keep'], [])
    f.index.setFavorite([a.id], true)
    const collection = f.index.saveCollection({name: 'Mix', itemIds: [b.id, a.id]})
    const deleted = await f.send<LibraryDeleteResult>({op: 'libraryDeleteFile', ids: [a.id]})
    assert.deepEqual(deleted.result.undoable, [a.id])
    assert.deepEqual(f.trashed, [], 'nothing went to the Recycle Bin')
    assert.ok(!(await exists(path.join(music, 'Album', 'a.mp3'))))
    assert.equal(await fs.readFile(path.join(f.dir, '.keepfin', 'trash', a.id, 'a.mp3'), 'utf8'), 'bytes-a')
    assert.equal(f.index.lookup(a.id), undefined)
    assert.deepEqual(f.index.collection(collection.id).itemIds, [b.id])
    // something changed in the collection since: it stays
    const c = (await f.index.addFile(await write(path.join(music, 'c.mp3')), {space: 'music', managed: true}))!
    f.index.addToCollection(collection.id, [c.id])
    const back = await deleted.undo()
    assert.equal(back.done, 1)
    assert.equal(back.failed, 0)
    assert.equal(await fs.readFile(path.join(music, 'Album', 'a.mp3'), 'utf8'), 'bytes-a')
    const restored = f.index.item(a.id)
    assert.deepEqual(restored.userTags, ['keep'])
    assert.equal(restored.favorite, true)
    assert.deepEqual(f.index.collection(collection.id).itemIds, [b.id, c.id, a.id], 'a goes back after b, and c (added since) stays')
    assert.ok(!(await exists(path.join(f.dir, '.keepfin', 'trash', a.id))), 'the trash entry is cleaned up')
  } finally {
    await f.cleanup()
  }
})

test('Delete for good: erased when its notice goes without Undo, at the next purge if the app closed first, and Undo still works meanwhile', async () => {
  const f = await setup()
  try {
    const music = f.settings.spaces[1].folder
    const videos = f.settings.spaces[2].folder
    f.state.root = music
    const add = async (folder: string, name: string, space: string) => (await f.index.addFile(await write(path.join(folder, name), name), {space, managed: true}))!
    const trashOf = (id: string) => path.join(music, '.keepfin', 'trash', id)

    // the notice ran out: the trash folder is erased; a file outside the folder skipped the Recycle Bin
    const a = await add(music, 'a.mp3', 'music')
    const out = await add(videos, 'out.mp4', 'videos')
    const first = await f.send<LibraryDeleteResult>({op: 'libraryDeleteFile', ids: [a.id, out.id], forGood: true})
    assert.deepEqual(first.result.undoable, [a.id])
    assert.deepEqual(f.trashed, [], 'nothing went to the Recycle Bin')
    assert.ok(!(await exists(path.join(videos, 'out.mp4'))))
    assert.ok(await exists(path.join(trashOf(a.id), 'a.mp3')), 'kept for Undo while the notice shows')
    await first.gone()
    assert.ok(!(await exists(trashOf(a.id))), 'erased once the notice went')
    await assert.rejects(first.undo(), /can no longer be undone/)

    // Undo while the notice shows: back, and nothing is left to erase
    const b = await add(music, 'b.mp3', 'music')
    const second = await f.send<LibraryDeleteResult>({op: 'libraryDeleteFile', ids: [b.id], forGood: true})
    assert.equal((await second.undo()).done, 1)
    assert.ok(await exists(path.join(music, 'b.mp3')))
    assert.ok(!(await exists(trashOf(b.id))))

    // the app closed before the notice ended: the next purge erases the marked folder, though it is new
    const c = await add(music, 'c.mp3', 'music')
    await f.send<LibraryDeleteResult>({op: 'libraryDeleteFile', ids: [c.id], forGood: true})
    const d = await add(music, 'd.mp3', 'music')
    await f.send<LibraryDeleteResult>({op: 'libraryDeleteFile', ids: [d.id]})
    assert.equal(await purgeKeepfinTrash(music, Date.now()), 1)
    assert.ok(!(await exists(trashOf(c.id))))
    assert.ok(await exists(path.join(trashOf(d.id), 'd.mp3')), 'an ordinary delete keeps its 30 days')
  } finally {
    await f.cleanup()
  }
})

test('Delete: a file outside the Keepfin folder goes to the Recycle Bin without Undo; a new file on the old name stops the restore honestly', async () => {
  const f = await setup()
  try {
    const music = f.settings.spaces[1].folder
    const videos = f.settings.spaces[2].folder
    f.state.root = music
    const inside = (await f.index.addFile(await write(path.join(music, 'in.mp3'), 'inside'), {space: 'music', managed: true}))!
    const outside = (await f.index.addFile(await write(path.join(videos, 'out.mp4'), 'outside'), {space: 'videos', managed: true}))!
    const deleted = await f.send<LibraryDeleteResult>({op: 'libraryDeleteFile', ids: [inside.id, outside.id]})
    assert.deepEqual(deleted.result.deleted.sort(), [inside.id, outside.id].sort())
    assert.deepEqual(deleted.result.undoable, [inside.id])
    assert.deepEqual(f.trashed, [path.join(videos, 'out.mp4')])
    // somebody puts a new file where it was
    await write(path.join(music, 'in.mp3'), 'a different file')
    const back = await deleted.undo()
    assert.equal(back.done, 0)
    assert.equal(back.failed, 1)
    assert.match(back.message ?? '', /new file has the name of “in”, so it stays in the Keepfin trash/)
    assert.equal(await fs.readFile(path.join(music, 'in.mp3'), 'utf8'), 'a different file', 'never put over another file')
    assert.equal(await fs.readFile(path.join(music, '.keepfin', 'trash', inside.id, 'in.mp3'), 'utf8'), 'inside', 'the original is still in the trash')
  } finally {
    await f.cleanup()
  }
})

test('Delete: a trash entry that is gone is reported, and the restore of the others goes on', async () => {
  const f = await setup()
  try {
    const music = f.settings.spaces[1].folder
    const a = (await f.index.addFile(await write(path.join(music, 'a.mp3'), 'a'), {space: 'music', managed: true}))!
    const b = (await f.index.addFile(await write(path.join(music, 'b.mp3'), 'b'), {space: 'music', managed: true}))!
    const deleted = await f.send<LibraryDeleteResult>({op: 'libraryDeleteFile', ids: [a.id, b.id]})
    await fs.rm(path.join(f.dir, '.keepfin', 'trash', a.id), {recursive: true, force: true})
    const back = await deleted.undo()
    assert.equal(back.done, 1)
    assert.equal(back.failed, 1)
    assert.match(back.message ?? '', /Brought back 1 of 2 files\. “a” is no longer in the Keepfin trash\./)
    assert.ok(f.index.lookup(b.id))
    assert.equal(f.index.lookup(a.id), undefined)
  } finally {
    await f.cleanup()
  }
})

test('Delete without a Keepfin folder (the earlier behaviour) goes to the Recycle Bin and offers no Undo', async () => {
  const f = await setup()
  try {
    f.state.root = undefined
    const a = (await f.index.addFile(await write(path.join(f.settings.spaces[1].folder, 'a.mp3')), {space: 'music', managed: true}))!
    const deleted = await f.send<LibraryDeleteResult>({op: 'libraryDeleteFile', ids: [a.id]})
    assert.equal(deleted.result.undoable, undefined)
    assert.equal(f.trashed.length, 1)
    await assert.rejects(() => deleted.undo(), /can no longer be undone/)
  } finally {
    await f.cleanup()
  }
})

test('the Keepfin trash is purged after 30 days: old item folders go; recent ones, held ones and the copies\' entries stay', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-purge-'))
  try {
    const trash = path.join(dir, '.keepfin', 'trash')
    const old = '11111111-1111-4111-8111-111111111111'
    const recent = '22222222-2222-4222-8222-222222222222'
    const held = '33333333-3333-4333-8333-333333333333'
    const day = 86_400_000
    const now = Date.now()
    for (const [id, age] of [[old, 31], [recent, 2], [held, 40]] as const) {
      await write(path.join(trash, id, 'song.mp3'), id)
      const when = new Date(now - age * day)
      await fs.utimes(path.join(trash, id), when, when)
    }
    await write(path.join(trash, 'copies', '2026-01-01', 'run-1', 'x.mp3'), 'copy trash')
    await write(path.join(trash, 'index.jsonl'), '{}')
    for (const entry of ['copies', 'index.jsonl']) await fs.utimes(path.join(trash, entry), new Date(now - 90 * day), new Date(now - 90 * day))
    assert.equal(await purgeKeepfinTrash(dir, now, new Set([held])), 1)
    assert.ok(!(await exists(path.join(trash, old))), 'the 31-day-old item folder went')
    assert.ok(await exists(path.join(trash, recent, 'song.mp3')), 'the 2-day-old one stays')
    assert.ok(await exists(path.join(trash, held, 'song.mp3')), 'one an Undo still holds stays')
    assert.ok(await exists(path.join(trash, 'copies', '2026-01-01', 'run-1', 'x.mp3')), 'the copies\' trash is not touched')
    assert.ok(await exists(path.join(trash, 'index.jsonl')))
    // a shelf reports the folders its records hold
    const shelf = new UndoShelf()
    shelf.record('record-for-held', async () => ({done: 0, failed: 0}), [held])
    assert.deepEqual([...shelf.held()], [held])
  } finally {
    await fs.rm(dir, {recursive: true, force: true})
  }
})
test('the shelf keeps a bounded number of records and forgets old ones', async () => {
  let now = 1_000
  const shelf = new UndoShelf(() => now)
  for (let n = 0; n < 150; n++) shelf.record(`record-number-${n}`, async () => ({done: n, failed: 0}))
  await assert.rejects(() => shelf.undo('record-number-0'), /can no longer be undone/)
  assert.equal((await shelf.undo('record-number-149')).done, 149)
  shelf.record('old-record-1', async () => ({done: 1, failed: 0}))
  now += 31 * 60_000
  shelf.record('new-record-1', async () => ({done: 2, failed: 0}))
  await assert.rejects(() => shelf.undo('old-record-1'), /can no longer be undone/)
  // a request without a usable id records nothing
  shelf.record(undefined, async () => ({done: 0, failed: 0}))
  shelf.record('short', async () => ({done: 0, failed: 0}))
  await assert.rejects(() => shelf.undo('short'), /can no longer be undone/)
})
