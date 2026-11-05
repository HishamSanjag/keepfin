// A copy carries only items whose content is recorded. An item placed without one (a download added since setup, a file setup
// could not match) gets it from its file: names with emoji, curly quotes and Arabic included.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {createHash, randomUUID} from 'node:crypto'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {DownloadManager} from '../manager.js'
import {FakeEngine} from '../fake-engine.js'
import {LibraryIndex} from '../library/index.js'
import {createKeepfinFolder, dataDir} from './keepfinFolder.js'
import {BUILTIN_SPACE_IDS} from './mapping.js'
import {StoreLibrary} from './persistence.js'

const sha = (text: string) => `sha256:${createHash('sha256').update(text).digest('hex')}`

test('items placed without a recorded content get it from their files: one from before opening, one added after', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-fill-'))
  const actor = randomUUID()
  const folder = await createKeepfinFolder(path.join(dir, 'Keepfin'), {libraryId: randomUUID(), replicaId: randomUUID(), now: new Date().toISOString(), hide: () => undefined})
  const deps = {deviceDir: path.join(dir, 'AppData'), newId: randomUUID, now: Date.now, store: {watch: false, flushIdleMs: 20}, contentFillDelayMs: 50}
  // before: an item placed with no content, the way setup left the songs it could not match
  const first = new StoreLibrary(deps)
  await first.create(folder, actor)
  await first.open(folder, actor)
  const old = randomUUID()
  const oldName = 'They Don’t Care About Us — Gothic 🖤.mp3'
  await fs.mkdir(path.join(folder.root, 'Music', 'rr'), {recursive: true})
  await fs.writeFile(path.join(folder.root, 'Music', 'rr', oldName), 'OLD SONG')
  await first.persistence.store.apply([
    {kind: 'item.add', body: {item: old, fields: {media: 'audio', space: BUILTIN_SPACE_IDS.music, title: 'They Don’t Care About Us'}}},
    {kind: 'item.place', body: {item: old, place: folder.replicaId, path: `Music/rr/${oldName}`}},
  ])
  await first.close()

  const library = new StoreLibrary(deps)
  await library.open(folder, actor)
  const index = new LibraryIndex({
    file: path.join(dir, 'unused', 'library.json'), artDir: path.join(dataDir(folder.root), 'art'), ffmpeg: () => undefined,
    persistence: library.persistence, newId: randomUUID, saveDelay: 10, pushDelay: 5, watch: false, analyse: false,
  })
  await index.init()
  const manager = new DownloadManager({store: path.join(dir, 'AppData', 'queue.json'), defaultOutDir: path.join(dir, 'Downloads'), engine: new FakeEngine(), library: index, snapshotDelay: 5, progressInterval: 20})
  try {
    await manager.init()
    // after: a new download in the Downloads space
    const downloads = manager.getSettings().library.spaces.find((s) => s.id === 'downloads')!.folder
    const newFile = path.join(downloads, 'YouTube', 'نهاية كرت انفيديا ٥٠٩٠؟ 🚀.mp4')
    await fs.mkdir(path.dirname(newFile), {recursive: true})
    await fs.writeFile(newFile, 'NEW VIDEO')
    const added = await index.addFile(newFile, {space: 'downloads', managed: true})
    assert.ok(added, 'the download is in the library')

    const contentOf = async (id: string) => (await library.persistence.store.views()).items.find((i) => i.id === id)?.heads[0]?.content
    let oldContent: string | undefined
    let newContent: string | undefined
    for (let i = 0; i < 400 && !(oldContent && newContent); i++) {
      await new Promise((resolve) => setTimeout(resolve, 25))
      oldContent = await contentOf(old)
      newContent = await contentOf(added!.id)
    }
    assert.equal(oldContent, sha('OLD SONG'), 'the item from before opening has its content')
    assert.equal(newContent, sha('NEW VIDEO'), 'the new download has its content')

    // the files beside a download get their records, so copies carry them: subtitles and the download's details here
    await fs.writeFile(path.join(downloads, 'YouTube', 'clip.mp4'), 'CLIP')
    await fs.writeFile(path.join(downloads, 'YouTube', 'clip.en.srt'), '1\n00:00:01,000 --> 00:00:02,000\nhi\n')
    await fs.writeFile(path.join(downloads, 'YouTube', 'clip.info.json'), '{"id":"x"}')
    const clip = await index.addFile(path.join(downloads, 'YouTube', 'clip.mp4'), {space: 'downloads', managed: true})
    const fieldsOf = async (id: string) => (await library.persistence.store.views()).items.find((i) => i.id === id)?.fields ?? {}
    const until = async (ok: (f: Record<string, unknown>) => boolean) => {
      for (let i = 0; i < 400; i++) {
        const f = await fieldsOf(clip!.id)
        if (ok(f)) return f
        await new Promise((resolve) => setTimeout(resolve, 25))
      }
      return fieldsOf(clip!.id)
    }
    const recorded = await until((f) => !!f['companion:.en.srt'] && !!f['companion:.info.json'])
    assert.deepEqual(recorded['companion:.en.srt'], {content: sha('1\n00:00:01,000 --> 00:00:02,000\nhi\n'), size: 35})
    assert.deepEqual(recorded['companion:.info.json'], {content: sha('{"id":"x"}'), size: 10})
    // the subtitles deleted here: their record is cleared, the details stay
    await fs.rm(path.join(downloads, 'YouTube', 'clip.en.srt'))
    await index.refresh(clip!.id)
    const after = await until((f) => f['companion:.en.srt'] === null)
    assert.equal(after['companion:.en.srt'], null)
    assert.ok(after['companion:.info.json'])
  } finally {
    await manager.close()
    await index.close()
    await library.close()
    await fs.rm(dir, {recursive: true, force: true})
  }
})
