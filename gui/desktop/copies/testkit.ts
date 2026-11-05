// Helpers for the copies tests: real temp folders, a desired set read from the main folder, a library host that places staged
// files in the main folder, and small file utilities. Not used by the app.
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import type {DesiredEntry, PlanAction} from '../../shared/core/copies.js'
import {writeJsonAtomic} from '../store/atomic.js'
import {hashFile} from './hash.js'
import type {MainHost} from './executor.js'
import {runCopy, type RunConfig, type RunReport} from './run.js'

export interface World {
  root: string
  main: string
  copy: string
  state: string
  hostLog: string[]
  host: MainHost
  /** A run with defaults; [over] overrides. The desired set is read from the main folder (and [extra]). */
  run(over?: Partial<RunConfig> & {desiredFrom?: 'main' | DesiredEntry[]}): Promise<RunReport>
  write(side: 'main' | 'copy', rel: string, text: string | Buffer, mtime?: Date): Promise<void>
  read(side: 'main' | 'copy', rel: string): Promise<string | null>
  remove(side: 'main' | 'copy', rel: string): Promise<void>
  list(side: 'main' | 'copy'): Promise<string[]>
  clock: {t: number}
  cleanup(): Promise<void>
}

const OLD = new Date('2020-01-01T00:00:00Z')

export async function walk(root: string, skipKeepfin = true): Promise<string[]> {
  const out: string[] = []
  const stack = ['']
  while (stack.length) {
    const rel = stack.pop()!
    for (const e of await fs.readdir(rel ? path.join(root, rel) : root, {withFileTypes: true})) {
      if (skipKeepfin && e.name === '.keepfin' && !rel) continue
      const r = rel ? `${rel}/${e.name}` : e.name
      if (e.isDirectory()) stack.push(r)
      else out.push(r)
    }
  }
  return out.sort()
}

export async function desiredFromMain(main: string, idOf: (p: string) => string = (p) => `item:${p}`): Promise<DesiredEntry[]> {
  const out: DesiredEntry[] = []
  for (const rel of await walk(main)) {
    if (rel.includes('.kftmp')) continue
    const abs = path.join(main, ...rel.split('/'))
    const h = await hashFile(abs)
    out.push({path: rel, itemId: idOf(rel), revisionOp: 'rev1', contentId: h.contentId, bytes: h.size})
  }
  return out
}

/** A library host over the main folder: it places staged files and logs what it was asked. */
export function fakeHost(main: string, log: string[]): MainHost {
  const abs = (p: string) => path.join(main, ...p.split('/'))
  return {
    async importNew({action, stagedPath, destPath}) {
      await fs.mkdir(path.dirname(abs(destPath)), {recursive: true})
      await fs.rename(stagedPath, abs(destPath))
      log.push(`import ${action.path} -> ${destPath}`)
      return {itemId: action.itemId ?? `item:${destPath}`, revisionOp: 'rev-import', path: destPath}
    },
    async reviseFromCopy({action, stagedPath}) {
      await fs.rename(stagedPath, abs(action.path)).catch(async () => {
        await fs.rm(abs(action.path), {force: true})
        await fs.rename(stagedPath, abs(action.path))
      })
      log.push(`revise ${action.path}`)
      return {revisionOp: 'rev-revise'}
    },
    async restoreEditedDeleted({action, stagedPath}) {
      await fs.mkdir(path.dirname(abs(action.path)), {recursive: true})
      await fs.rename(stagedPath, abs(action.path))
      log.push(`restoreEdited ${action.path}`)
      return {revisionOp: 'rev-restore'}
    },
    async deleteItem({action}: {action: PlanAction}) {
      log.push(`delete ${action.path}`)
    },
  }
}

export async function makeWorld(prefix = 'kf-copies-'): Promise<World> {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), prefix))
  const main = path.join(root, 'main')
  const copy = path.join(root, 'copy')
  const state = path.join(root, 'state')
  await fs.mkdir(path.join(main, '.keepfin'), {recursive: true})
  await fs.mkdir(path.join(copy, '.keepfin'), {recursive: true})
  await writeJsonAtomic(path.join(main, '.keepfin', 'folder.json'), {format: 1, libraryId: 'lib', replicaId: 'main-replica', createdAt: 'x'})
  await writeJsonAtomic(path.join(copy, '.keepfin', 'folder.json'), {format: 1, libraryId: 'lib', replicaId: 'copy-replica', createdAt: 'x', generation: 0})
  const hostLog: string[] = []
  const clock = {t: Date.parse('2026-10-14T12:00:00Z')}
  const dir = (side: 'main' | 'copy') => (side === 'main' ? main : copy)
  const world: World = {
    root,
    main,
    copy,
    state,
    hostLog,
    host: fakeHost(main, hostLog),
    clock,
    async run(over = {}) {
      const {desiredFrom, ...rest} = over
      const desired = desiredFrom === undefined || desiredFrom === 'main' ? await desiredFromMain(main) : desiredFrom
      // every run is a new process as far as the lock is concerned: time moves on past the stale limit
      clock.t += 5 * 60_000
      return runCopy({
        mainRoot: main,
        copyRoot: copy,
        stateDir: state,
        placeId: 'place-1',
        mode: 'backup',
        device: 'test-device',
        desired,
        host: world.host,
        now: () => clock.t,
        parallelism: 2,
        confirmHeld: true,
        ...rest,
      })
    },
    async write(side, rel, text, mtime = OLD) {
      const file = path.join(dir(side), ...rel.split('/'))
      await fs.mkdir(path.dirname(file), {recursive: true})
      await fs.writeFile(file, text)
      await fs.utimes(file, mtime, mtime)
    },
    async read(side, rel) {
      try {
        return await fs.readFile(path.join(dir(side), ...rel.split('/')), 'utf8')
      } catch {
        return null
      }
    },
    async remove(side, rel) {
      await fs.rm(path.join(dir(side), ...rel.split('/')), {force: true})
    },
    list: (side) => walk(dir(side)),
    async cleanup() {
      await fs.rm(root, {recursive: true, force: true})
    },
  }
  return world
}

/** All files under the copy's `.keepfin/versions` and `.keepfin/trash` with their text (small test files). */
export async function archived(copy: string): Promise<Map<string, string>> {
  const out = new Map<string, string>()
  for (const sub of ['versions', 'trash']) {
    const base = path.join(copy, '.keepfin', sub)
    try {
      for (const rel of await walk(base, false)) {
        if (rel.endsWith('.jsonl')) continue
        out.set(`${sub}/${rel}`, await fs.readFile(path.join(base, ...rel.split('/')), 'utf8'))
      }
    } catch {
      // none yet
    }
  }
  return out
}

export const leftovers = async (root: string): Promise<string[]> => (await walk(root, false)).filter((f) => f.includes('.kftmp'))
