// Test fixtures for the migration engine: a generated device folder with a library, sidecars, art and jobs.
import {createHash} from 'node:crypto'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'

export const sha = (bytes: Buffer | string) => createHash('sha256').update(bytes).digest('hex')

export async function put(file: string, content: string | Buffer) {
  await fs.mkdir(path.dirname(file), {recursive: true})
  await fs.writeFile(file, content)
}

export async function listFiles(root: string): Promise<string[]> {
  const result: string[] = []
  const stack = [root]
  while (stack.length) {
    const directory = stack.pop() as string
    for (const entry of await fs.readdir(directory, {withFileTypes: true}).catch(() => [])) {
      const full = path.join(directory, entry.name)
      if (entry.isDirectory()) stack.push(full)
      else result.push(full)
    }
  }
  return result.sort()
}

export type World = {
  root: string
  deviceDir: string
  target: string
  runDir: string
  music: string
  downloads: string
  videos: string
  /** Every original file with its content hash, by absolute path. */
  originals: Map<string, string>
  cleanup: () => Promise<void>
}

export type WorldShape = {
  /** Big library: this many extra music tracks and downloads. */
  tracks?: number
  downloads?: number
  /** Files in the space folders that the library does not list (default true). */
  loose?: boolean
}

export async function makeWorld(shape: WorldShape = {}): Promise<World> {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-migration-'))
  const deviceDir = path.join(root, 'AppData')
  const music = path.join(root, 'Music', 'Keepfin')
  const downloads = path.join(root, 'Downloads', 'Keepfin')
  const videos = path.join(root, 'Videos', 'Keepfin')
  const target = path.join(root, 'Elsewhere', 'Keepfin')
  const runDir = path.join(deviceDir, 'migration', 'run1')
  await fs.mkdir(videos, {recursive: true})
  await fs.mkdir(target, {recursive: true})

  const items: Record<string, unknown>[] = []
  const originals = new Map<string, string>()
  let counter = 0
  const add = async (space: string, folderPath: string, rel: string, kind: 'audio' | 'video', extras: Record<string, unknown> = {}) => {
    counter++
    const file = path.join(folderPath, ...rel.split('/'))
    const content = `${kind}-${counter}-${'x'.repeat(counter % 7)}`
    await put(file, content)
    originals.set(file, sha(content))
    const id = `id${counter}`
    items.push({
      id, space, path: file, folder: rel.includes('/') ? rel.slice(0, rel.lastIndexOf('/')) : '', kind, title: `Title ${counter}`, size: content.length,
      modifiedAt: 1, addedAt: counter, hasArt: false, ...extras,
    })
    return {id, file}
  }
  const side = async (file: string, suffix: string) => {
    const sidecar = file.replace(/\.[^.]+$/, '') + suffix
    const content = `side-${suffix}-${sha(file).slice(0, 6)}`
    await put(sidecar, content)
    originals.set(sidecar, sha(content))
    return sidecar
  }

  const first = await add('music', music, 'Artist A/Song 1.mp3', 'audio', {favorite: true, userTags: ['rock', 'live'], plays: 3, futureField: {nested: [1, 2, {a: 'b'}]}, hasArt: true})
  await side(first.file, '.lrc')
  const second = await add('music', music, 'Song 2.mp3', 'audio', {userTags: ['calm']})
  void second
  const third = await add('music', music, 'Song 3.flac', 'audio', {favorite: true})
  await side(third.file, '.jpg')
  const clip = await add('downloads', downloads, 'YouTube/Clip.mp4', 'video')
  await side(clip.file, '.en.srt')
  await side(clip.file, '.info.json')

  for (let index = 0; index < (shape.tracks ?? 0); index++) {
    const track = await add('music', music, `Album ${index % 10}/Track ${index}.mp3`, 'audio', index % 5 === 0 ? {favorite: true, userTags: ['t']} : {})
    if (index % 3 === 0) await side(track.file, '.lrc')
    if (index % 11 === 0) {
      await put(path.join(deviceDir, 'library-art', `${track.id}.jpg`), `art-${track.id}`)
    }
  }
  for (let index = 0; index < (shape.downloads ?? 0); index++) {
    const item = await add('downloads', downloads, `Site ${index % 4}/Video ${index}.mp4`, 'video')
    if (index % 4 === 0) await side(item.file, '.en.srt')
  }

  // Saved art for the first item.
  await put(path.join(deviceDir, 'library-art', `${first.id}.jpg`), 'art-first')
  await put(path.join(deviceDir, 'library-art', 'orphan.png'), 'art-orphan')

  // A library entry whose file is gone, and files the library does not list.
  items.push({id: 'gone', space: 'music', path: path.join(music, 'Missing.mp3'), folder: '', kind: 'audio', title: 'Gone', size: 1, modifiedAt: 1, addedAt: 1, hasArt: false, missing: true})
  if (shape.loose !== false) {
    await put(path.join(music, 'Loose 1.mp3'), 'loose-1')
    await put(path.join(music, 'Loose Folder', 'Loose 2.m4a'), 'loose-2')
    await put(path.join(downloads, 'Loose 3.mkv'), 'loose-3')
    await put(path.join(downloads, 'unfinished.mp4.part'), 'partial')
    await put(path.join(downloads, 'notes.txt'), 'notes')
    for (const file of [path.join(music, 'Loose 1.mp3'), path.join(music, 'Loose Folder', 'Loose 2.m4a'), path.join(downloads, 'Loose 3.mkv')])
      originals.set(file, sha(await fs.readFile(file)))
  }

  const collections = [
    {id: 'pl1', name: 'Mine', kind: 'own', space: 'music', itemIds: [first.id, third.id], seen: [], createdAt: 1, updatedAt: 2, someFutureThing: 7},
    {id: 'col1', name: 'Followed', kind: 'playlist', space: 'music', itemIds: [first.id], seen: ['a'], source: {url: 'https://example.invalid/p', provider: 'youtube'}, createdAt: 1, updatedAt: 2},
  ]
  await put(path.join(deviceDir, 'library.json'), JSON.stringify({version: 2, items, collections, topLevelFuture: {keep: 'me'}}))
  await put(
    path.join(deviceDir, 'queue.json'),
    JSON.stringify({
      version: 2,
      jobs: [
        {id: 'job1', file: first.file, status: 'completed'},
        {id: 'job2', file: path.join(music, 'not-there.mp3'), status: 'completed'},
        {id: 'job3', status: 'failed'},
      ],
      settings: {
        library: {
          spaces: [
            {id: 'downloads', name: 'Downloads', folder: downloads, type: 'folder'},
            {id: 'music', name: 'Music', folder: music, type: 'folder'},
            {id: 'videos', name: 'Videos', folder: videos, type: 'folder'},
            {id: 'smart1', name: 'Smart', folder: '', type: 'smart'},
          ],
        },
      },
      cookiesFile: 'cookies.txt',
    }),
  )

  return {
    root, deviceDir, target, runDir, music, downloads, videos, originals,
    // Windows can hold a just-written file for a moment (antivirus, the indexer), which fails the removal with ENOTEMPTY/EBUSY under load
    cleanup: () => fs.rm(root, {recursive: true, force: true, maxRetries: 5, retryDelay: 100}),
  }
}
