// The Trash page (main process): what a delete writes into the Keepfin trash is enough to list and restore it after a restart.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import ffmpegPath from 'ffmpeg-static'
import {defaultLibrarySettings, type LibraryDeleteResult, type TrashList, type TrashResult, type UndoResult} from '../shared/library.js'
import {LibraryIndex} from './library/index.js'
import {keepfinTrashDir, ERASE_MARK} from './library/files.js'
import {handleLibraryRequest, type LibraryDeps} from './library/ops.js'
import {UndoShelf} from './library/undo.js'

const DAY = 86_400_000

async function setup() {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-trash-'))
  const settings = defaultLibrarySettings({downloads: path.join(dir, 'Downloads'), music: path.join(dir, 'Music'), videos: path.join(dir, 'Videos')})
  for (const space of settings.spaces.filter((item) => item.type === 'folder')) await fs.mkdir(space.folder, {recursive: true})
  const open = async () => {
    const index = new LibraryIndex({file: path.join(dir, 'data', 'library.json'), artDir: path.join(dir, 'art'), ffmpeg: () => ffmpegPath as string, saveDelay: 10, pushDelay: 5, watchDelay: 30, watch: false, analyse: false})
    await index.init()
    index.configure(settings)
    return index
  }
  let index = await open()
  const state = {root: dir as string | undefined}
  const shelf = new UndoShelf()
  const deps = () =>
    ({
      index,
      manager: {fileRefs: () => [], restoreFileRefs: () => undefined},
      ffmpeg: () => ffmpegPath as string,
      trash: async (target: string) => void (await fs.rm(target, {force: true})),
      keepfinRoot: () => state.root,
      undo: shelf,
    }) as unknown as LibraryDeps
  const send = <T>(request: Record<string, unknown>) => handleLibraryRequest(request as never, deps()) as Promise<T>
  return {
    dir,
    settings,
    state,
    shelf,
    send,
    get index() {
      return index
    },
    // an app restart: the index is closed and opened again from its saved file, the Undo shelf is gone
    restart: async () => {
      await index.close()
      index = await open()
      shelf['records'].clear()
    },
    cleanup: async () => {
      await index.close()
      await fs.rm(dir, {recursive: true, force: true})
    },
  }
}
const write = async (file: string, text = 'x') => {
  await fs.mkdir(path.dirname(file), {recursive: true})
  await fs.writeFile(file, text)
  return file
}
const exists = (file: string) => fs.access(file).then(() => true, () => false)

/** The index of these tests makes path-hash ids; the app's store makes UUIDs. Names the trash folder as the store would: returns the new folder name. */
let counter = 0
async function asUuid(root: string, id: string) {
  const name = `00000000-0000-4000-8000-${String(++counter).padStart(12, '0')}`
  await fs.rename(path.join(keepfinTrashDir(root), id), path.join(keepfinTrashDir(root), name))
  return name
}

test('a delete leaves enough in the trash to list and restore it after a restart (tags, favourite, plays, collection place)', async () => {
  const f = await setup()
  try {
    const music = f.settings.spaces[1].folder
    const a = (await f.index.addFile(await write(path.join(music, 'a.mp3'), 'aaa'), {space: 'music', managed: true}))!
    const b = (await f.index.addFile(await write(path.join(music, 'b.mp3'), 'bbb'), {space: 'music', managed: true}))!
    f.index.setUserTags([a.id], ['rock'], [])
    f.index.setFavorite([a.id], true)
    const collection = (await f.send<{id: string}>({op: 'collectionSave', collection: {name: 'Mix', itemIds: [b.id, a.id]}})) as {id: string}
    const deleted = await f.send<LibraryDeleteResult>({op: 'libraryDeleteFile', ids: [a.id], undoId: 'undo-trash-1'})
    assert.deepEqual(deleted.undoable, [a.id])
    // the JSON is there
    const folders = [await asUuid(f.dir, a.id)]
    assert.deepEqual(await fs.readdir(keepfinTrashDir(f.dir)), folders)
    const saved = JSON.parse(await fs.readFile(path.join(keepfinTrashDir(f.dir), folders[0], 'item.json'), 'utf8'))
    assert.equal(saved.id, a.id)
    assert.equal(saved.relPath, path.join('Music', 'a.mp3'))
    assert.equal(saved.entry.favorite, true)

    await f.restart()
    const list = await f.send<TrashList>({op: 'libraryTrashList'})
    assert.equal(list.hasFolder, true)
    assert.equal(list.entries.length, 1)
    assert.equal(list.entries[0].title, a.title)
    assert.equal(list.entries[0].fileName, 'a.mp3')
    assert.equal(list.entries[0].space, 'music')
    assert.equal(list.entries[0].size, 3)
    assert.equal(list.entries[0].daysLeft, 30)

    const restored = await f.send<TrashResult>({op: 'libraryTrashRestore', ids: [list.entries[0].id]})
    assert.equal(restored.done, 1)
    assert.match(restored.message ?? '', /^Restored “/)
    assert.equal(await exists(path.join(music, 'a.mp3')), true)
    assert.equal(await exists(path.join(keepfinTrashDir(f.dir), folders[0])), false)
    const back = f.index.item(a.id)
    assert.deepEqual(back.userTags, ['rock'])
    assert.equal(back.favorite, true)
    assert.deepEqual(f.index.collection(collection.id).itemIds, [b.id, a.id], 'a is back at its place in the collection')
  } finally {
    await f.cleanup()
  }
})

test('the list is newest first, counts days left, and skips Delete for good marks and the copies feature', async () => {
  const f = await setup()
  try {
    const music = f.settings.spaces[1].folder
    const ids: string[] = []
    for (const name of ['a', 'b', 'c']) ids.push((await f.index.addFile(await write(path.join(music, `${name}.mp3`)), {space: 'music', managed: true}))!.id)
    await f.send({op: 'libraryDeleteFile', ids: [ids[0]]})
    await f.send({op: 'libraryDeleteFile', ids: [ids[1]]})
    await f.send({op: 'libraryDeleteFile', ids: [ids[2]], forGood: true})
    const names = [await asUuid(f.dir, ids[0]), await asUuid(f.dir, ids[1]), await asUuid(f.dir, ids[2])]
    // the copies feature's own entries
    await write(path.join(keepfinTrashDir(f.dir), 'copies', 'x', 'song.mp3'))
    await write(path.join(keepfinTrashDir(f.dir), 'index.jsonl'), '{}')
    // age the first one by 12 days and the second by 29
    const rewrite = async (id: string, days: number) => {
      const file = path.join(keepfinTrashDir(f.dir), id, 'item.json')
      const json = JSON.parse(await fs.readFile(file, 'utf8'))
      json.deletedAt = Date.now() - days * DAY - 1000
      await fs.writeFile(file, JSON.stringify(json))
    }
    await rewrite(names[0], 12)
    await rewrite(names[1], 29)
    const list = await f.send<TrashList>({op: 'libraryTrashList'})
    const byId = new Map(list.entries.map((e) => [e.id, e]))
    assert.equal(list.entries.length, 2, 'the erase-marked folder and the copies entries are not listed')
    assert.equal(byId.get(names[0])!.daysLeft, 18)
    assert.equal(byId.get(names[1])!.daysLeft, 1)
    // newest deletion first = the one deleted 12 days ago before the one deleted 29 days ago
    assert.deepEqual(list.entries.map((e) => e.id), [names[0], names[1]])
    assert.equal(await exists(path.join(keepfinTrashDir(f.dir), names[2], ERASE_MARK)), true)
    // erasing and emptying never touch the copies' entries
    await f.send({op: 'libraryTrashEmpty'})
    assert.equal(await exists(path.join(keepfinTrashDir(f.dir), 'copies', 'x', 'song.mp3')), true)
    assert.equal(await exists(path.join(keepfinTrashDir(f.dir), 'index.jsonl')), true)
    assert.equal((await f.send<TrashList>({op: 'libraryTrashList'})).entries.length, 0)
  } finally {
    await f.cleanup()
  }
})

test('daysLeft never goes below 0', async () => {
  const f = await setup()
  try {
    const a = (await f.index.addFile(await write(path.join(f.settings.spaces[1].folder, 'old.mp3')), {space: 'music', managed: true}))!
    await f.send({op: 'libraryDeleteFile', ids: [a.id]})
    const file = path.join(keepfinTrashDir(f.dir), await asUuid(f.dir, a.id), 'item.json')
    const json = JSON.parse(await fs.readFile(file, 'utf8'))
    json.deletedAt = Date.now() - 45 * DAY
    await fs.writeFile(file, JSON.stringify(json))
    assert.equal((await f.send<TrashList>({op: 'libraryTrashList'})).entries[0].daysLeft, 0)
  } finally {
    await f.cleanup()
  }
})

test('restore never overwrites a new file with the name; the entry stays in the trash', async () => {
  const f = await setup()
  try {
    const music = f.settings.spaces[1].folder
    const a = (await f.index.addFile(await write(path.join(music, 'a.mp3'), 'old'), {space: 'music', managed: true}))!
    await f.send({op: 'libraryDeleteFile', ids: [a.id]})
    const name = await asUuid(f.dir, a.id)
    await write(path.join(music, 'a.mp3'), 'NEW')
    const result = await f.send<TrashResult>({op: 'libraryTrashRestore', ids: [name]})
    assert.equal(result.done, 0)
    assert.equal(result.failed, 1)
    assert.match(result.message ?? '', /a new file has its name now/)
    assert.equal(await fs.readFile(path.join(music, 'a.mp3'), 'utf8'), 'NEW')
    assert.equal((await f.send<TrashList>({op: 'libraryTrashList'})).entries.length, 1)
  } finally {
    await f.cleanup()
  }
})

test('an unreadable item.json still lists the entry by its file name, and restore adds it back as a fresh import', async () => {
  const f = await setup()
  try {
    const music = f.settings.spaces[1].folder
    const a = (await f.index.addFile(await write(path.join(music, 'Glass Harbour.mp3')), {space: 'music', managed: true}))!
    await f.send({op: 'libraryDeleteFile', ids: [a.id]})
    await fs.writeFile(path.join(keepfinTrashDir(f.dir), await asUuid(f.dir, a.id), 'item.json'), '{ not json')
    const list = await f.send<TrashList>({op: 'libraryTrashList'})
    assert.equal(list.entries.length, 1)
    assert.equal(list.entries[0].fileName, 'Glass Harbour.mp3')
    assert.ok(list.entries[0].title.length > 0)
    const result = await f.send<TrashResult>({op: 'libraryTrashRestore', ids: [list.entries[0].id]})
    assert.equal(result.done, 1)
    assert.equal((await f.send<TrashList>({op: 'libraryTrashList'})).entries.length, 0)
    assert.ok(f.index.entryAt(path.join(music, 'Glass Harbour.mp3')), 'the file is in the library again')
  } finally {
    await f.cleanup()
  }
})

test('a path-hash trash folder is listed, and an item.json pointing outside the Keepfin folder never puts the file there', async () => {
  const f = await setup()
  try {
    const music = f.settings.spaces[1].folder
    const a = (await f.index.addFile(await write(path.join(music, 'Far.mp3')), {space: 'music', managed: true}))!
    await f.send({op: 'libraryDeleteFile', ids: [a.id]})
    // the folder keeps the scan's own id (no rename)
    const file = path.join(keepfinTrashDir(f.dir), a.id, 'item.json')
    const json = JSON.parse(await fs.readFile(file, 'utf8'))
    json.relPath = path.join('..', 'outside', 'Far.mp3')
    json.moved[0].from = json.relPath
    await fs.writeFile(file, JSON.stringify(json))
    const list = await f.send<TrashList>({op: 'libraryTrashList'})
    assert.deepEqual(list.entries.map((e) => e.id), [a.id])
    const result = await f.send<TrashResult>({op: 'libraryTrashRestore', ids: [a.id]})
    assert.equal(result.done, 1)
    assert.equal(await exists(path.join(f.dir, '..', 'outside', 'Far.mp3')), false)
    assert.ok(f.index.entryAt(path.join(music, 'Far.mp3')), 'it came back into its space instead')
  } finally {
    await f.cleanup()
  }
})

test('erase deletes for good, and empty erases every listed entry', async () => {
  const f = await setup()
  try {
    const music = f.settings.spaces[1].folder
    const ids: string[] = []
    for (const name of ['a', 'b', 'c']) ids.push((await f.index.addFile(await write(path.join(music, `${name}.mp3`)), {space: 'music', managed: true}))!.id)
    await f.send({op: 'libraryDeleteFile', ids})
    const names = [await asUuid(f.dir, ids[0]), await asUuid(f.dir, ids[1]), await asUuid(f.dir, ids[2])]
    const erased = await f.send<TrashResult>({op: 'libraryTrashErase', ids: [names[0]]})
    assert.equal(erased.done, 1)
    assert.equal(await exists(path.join(keepfinTrashDir(f.dir), names[0])), false)
    // a name that is not a trash folder is ignored (no path tricks)
    const sneaky = await f.send<TrashResult>({op: 'libraryTrashErase', ids: ['../../Music', 'copies']})
    assert.equal(sneaky.done, 0)
    assert.equal(await exists(music), true)
    const emptied = await f.send<TrashResult>({op: 'libraryTrashEmpty'})
    assert.equal(emptied.done, 2)
    assert.equal((await f.send<TrashList>({op: 'libraryTrashList'})).entries.length, 0)
  } finally {
    await f.cleanup()
  }
})

test('restoring or erasing from the page drops the Undo record that held the item', async () => {
  const f = await setup()
  try {
    const music = f.settings.spaces[1].folder
    const a = (await f.index.addFile(await write(path.join(music, 'a.mp3')), {space: 'music', managed: true}))!
    const b = (await f.index.addFile(await write(path.join(music, 'b.mp3')), {space: 'music', managed: true}))!
    await f.send({op: 'libraryDeleteFile', ids: [a.id], undoId: 'undo-for-a'})
    await f.send({op: 'libraryDeleteFile', ids: [b.id], undoId: 'undo-for-b'})
    const names = [await asUuid(f.dir, a.id), await asUuid(f.dir, b.id)]
    await f.send({op: 'libraryTrashRestore', ids: [names[0]]})
    await assert.rejects(() => f.send<UndoResult>({op: 'libraryUndo', undoId: 'undo-for-a'}), /can no longer be undone/)
    await f.send({op: 'libraryTrashErase', ids: [names[1]]})
    await assert.rejects(() => f.send<UndoResult>({op: 'libraryUndo', undoId: 'undo-for-b'}), /can no longer be undone/)
    assert.equal(f.shelf.held().size, 0)
  } finally {
    await f.cleanup()
  }
})

test('without a Keepfin folder the page lists nothing and says so', async () => {
  const f = await setup()
  try {
    f.state.root = undefined
    assert.deepEqual(await f.send<TrashList>({op: 'libraryTrashList'}), {hasFolder: false, entries: []})
    await assert.rejects(() => f.send({op: 'libraryTrashRestore', ids: ['x']}), /belongs to the Keepfin folder/)
  } finally {
    await f.cleanup()
  }
})
