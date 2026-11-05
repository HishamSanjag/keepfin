// The device-local data that is keyed by library ids and so has to follow a switch of library (dev-docs/plans/LIBRARY-MERGE-CONTRACT.md
// sections 5 to 7: "outside ids"). On Windows these are the consumers found:
//   1. the profile's per-item device records (`placement-state/*.json`: the download job an item came from, cached metadata hints),
//      the per-collection follow results (`placement-state.json`) and the linked spaces (`linked.json`, keyed by space id);
//   2. the device-only artwork `<userData>/library-art/<item id>.<ext>`;
//   3. the profile's outbox, projection cache and merge-state database: all caches of the library that was open, set aside whole.
// Not remapped, on purpose: copies on drives and the cloud are bound to a library by their own folder.json (Scope: they are set up
// again under T); the play cache is rebuilt; the download queue refers to files by path, not by library item.
// Every step reads from an immutable backup and is safe to run again.
import fsp from 'node:fs/promises'
import path from 'node:path'
import {mergeEntityId, type MapLine} from '../../shared/core/merge.js'
import {DeviceFiles} from '../store/device.js'
import {spaceWireId} from '../store/mapping.js'
import {MapIndex} from './scratch.js'
import {readJsonLines} from './lines.js'

const exists = (p: string) => fsp.stat(p).then(() => true, () => false)

export const backupDir = (profileDir: string, mergeId: string) => path.join(profileDir, 'merge-backup', mergeId)

const MOVED = ['outbox', 'cache', 'state'] as const
const DEVICE_ENTRIES = ['placement-state', 'placement-state.json', 'linked.json'] as const

async function copyEntry(from: string, to: string): Promise<void> {
  if (!(await exists(from))) return
  await fsp.cp(from, to, {recursive: true})
}

/** Moves [name] from [from] to [to] unless it is already there (a rerun finds the work done). */
async function moveAside(from: string, to: string): Promise<void> {
  if (!(await exists(from))) return
  if (await exists(to)) return
  await fsp.mkdir(path.dirname(to), {recursive: true})
  await fsp.rename(from, to)
}

export async function loadMap(mapFile: string, indexFile: string): Promise<MapIndex> {
  const index = new MapIndex(indexFile)
  index.load(await readJsonLines<MapLine>(mapFile))
  return index
}

export interface ToTInput {
  profileDir: string
  mergeId: string
  mapFile: string
  work: string
  artDir?: string
}

/** S to T. Backs up the S side of the profile, then writes the remapped device data in its place. */
export async function remapToT(input: ToTInput): Promise<{items: number; art: number}> {
  const bk = backupDir(input.profileDir, input.mergeId)
  const copied = path.join(bk, 'device', '.copied')
  if (!(await exists(copied))) {
    await fsp.rm(path.join(bk, 'device'), {recursive: true, force: true})
    await fsp.mkdir(path.join(bk, 'device'), {recursive: true})
    for (const name of DEVICE_ENTRIES) await copyEntry(path.join(input.profileDir, name), path.join(bk, 'device', name))
    await fsp.writeFile(copied, 'copied')
  }
  for (const name of MOVED) await moveAside(path.join(input.profileDir, name), path.join(bk, name))
  const index = await loadMap(input.mapFile, path.join(input.work, 'map.db'))
  try {
    const source = new DeviceFiles(path.join(bk, 'device'), 0)
    await source.load()
    for (const name of DEVICE_ENTRIES) await fsp.rm(path.join(input.profileDir, name), {recursive: true, force: true})
    const out = new DeviceFiles(input.profileDir, 0)
    const items = new Map<string, import('../store/device.js').DeviceItem>()
    const artIds: [string, string][] = []
    for (const [id, record] of source.items) {
      const t = index.toT(id)
      if (!t) continue
      items.set(t, record)
      artIds.push([id, t])
    }
    const collections: typeof source.collections = {}
    for (const [id, record] of Object.entries(source.collections)) collections[mergeEntityId(input.mergeId, 'playlist', id)] = record
    out.replaceAll(items, collections)
    for (const [spaceId, folder] of Object.entries(source.linked)) out.linked[mergeEntityId(input.mergeId, 'space', spaceWireId(spaceId))] = folder
    await out.flush()
    let art = 0
    if (input.artDir) {
      const have = new Map<string, string>()
      for (const name of await fsp.readdir(input.artDir).catch(() => [] as string[])) have.set(name.slice(0, name.lastIndexOf('.')), name)
      for (const [s, t] of artIds) {
        const name = have.get(s)
        if (!name || have.has(t)) continue
        const ext = name.slice(name.lastIndexOf('.'))
        await fsp.copyFile(path.join(input.artDir, name), path.join(input.artDir, `${t}${ext}`))
        art++
      }
    }
    return {items: items.size, art}
  } finally {
    index.close()
  }
}

export interface ToSInput {
  profileDir: string
  mergeId: string
}

/** T back to S: the S side comes back from the backup, the T side is kept aside in it. */
export async function restoreS(input: ToSInput): Promise<boolean> {
  const bk = backupDir(input.profileDir, input.mergeId)
  if (!(await exists(path.join(bk, 'device', '.copied')))) return false
  const tSide = path.join(bk, 't-side')
  const restored = path.join(bk, '.restored')
  if (await exists(restored)) return true
  await fsp.rm(tSide, {recursive: true, force: true})
  await fsp.mkdir(tSide, {recursive: true})
  for (const name of [...MOVED, ...DEVICE_ENTRIES]) {
    const live = path.join(input.profileDir, name)
    if (await exists(live)) await fsp.rename(live, path.join(tSide, name))
  }
  for (const name of MOVED) if (await exists(path.join(bk, name))) await fsp.cp(path.join(bk, name), path.join(input.profileDir, name), {recursive: true})
  for (const name of DEVICE_ENTRIES) if (await exists(path.join(bk, 'device', name))) await fsp.cp(path.join(bk, 'device', name), path.join(input.profileDir, name), {recursive: true})
  await fsp.writeFile(restored, 'restored')
  return true
}
