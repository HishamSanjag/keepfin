// The lock of a copy (spec 7): `.keepfin/lock`, created exclusively, holding {device, run, heartbeatAt}. The heartbeat is
// refreshed every 10 s; a lock older than 2 minutes may be taken over after re-reading the generation. Lock and generation
// together give endpoint-wide exclusion across computers.
import fs from 'node:fs/promises'
import path from 'node:path'
import {readGeneration} from './generation.js'
import {code} from './util.js'

export const HEARTBEAT_MS = 10_000
export const STALE_MS = 120_000

export interface LockBody {
  device: string
  run: string
  heartbeatAt: number
}

export const lockFile = (copyRoot: string) => path.join(copyRoot, '.keepfin', 'lock')

export type LockResult =
  | {ok: true; release: () => Promise<void>; abandon: () => void; tookOver: boolean}
  | {ok: false; reason: 'held' | 'changed'; holder?: LockBody}

async function readLock(file: string): Promise<LockBody | undefined> {
  try {
    const parsed = JSON.parse(await fs.readFile(file, 'utf8')) as Partial<LockBody>
    if (typeof parsed.heartbeatAt === 'number') return {device: String(parsed.device ?? ''), run: String(parsed.run ?? ''), heartbeatAt: parsed.heartbeatAt}
  } catch (error) {
    if (code(error) === 'ENOENT') return undefined
    // unreadable: treat as a lock with no heartbeat (stale)
    return {device: '', run: '', heartbeatAt: 0}
  }
  return {device: '', run: '', heartbeatAt: 0}
}

export interface LockOptions {
  device: string
  run: string
  now?: () => number
  heartbeatMs?: number
  staleMs?: number
}

/**
 * Takes the lock of [copyRoot]. A fresh lock held by another run refuses; a stale one is taken over after the generation
 * is read again (if it moved meanwhile, the takeover is abandoned). Returns a release that stops the heartbeat and removes
 * the lock if it is still ours.
 */
export async function acquireCopyLock(copyRoot: string, options: LockOptions): Promise<LockResult> {
  const now = options.now ?? Date.now
  const file = lockFile(copyRoot)
  await fs.mkdir(path.dirname(file), {recursive: true})
  const body = (): string => JSON.stringify({device: options.device, run: options.run, heartbeatAt: now()} satisfies LockBody)
  let tookOver = false
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      await fs.writeFile(file, body(), {flag: 'wx'})
      break
    } catch (error) {
      if (code(error) !== 'EEXIST') throw error
    }
    const held = await readLock(file)
    if (!held) continue
    if (now() - held.heartbeatAt < (options.staleMs ?? STALE_MS) && held.run !== options.run) return {ok: false, reason: 'held', holder: held}
    // stale: re-read the generation, then take it over by moving it aside (only one taker can succeed)
    const before = await readGeneration(copyRoot)
    const aside = `${file}.stale-${options.run}`
    try {
      await fs.rename(file, aside)
    } catch (error) {
      if (code(error) === 'ENOENT') continue
      throw error
    }
    await fs.rm(aside, {force: true})
    const after = await readGeneration(copyRoot)
    if (before !== after) return {ok: false, reason: 'changed', holder: held}
    tookOver = true
  }
  const stillOurs = await readLock(file)
  if (!stillOurs || stillOurs.run !== options.run) return {ok: false, reason: 'held', holder: stillOurs}

  let stopped = false
  const beat = setInterval(() => {
    if (stopped) return
    fs.writeFile(file, body()).catch(() => undefined)
  }, options.heartbeatMs ?? HEARTBEAT_MS)
  beat.unref()
  return {
    ok: true,
    tookOver,
    abandon: () => {
      stopped = true
      clearInterval(beat)
    },
    release: async () => {
      stopped = true
      clearInterval(beat)
      const current = await readLock(file)
      if (current && current.run === options.run) await fs.rm(file, {force: true})
    },
  }
}

/** Reads the lock without taking it (for status displays and tests). */
export const peekLock = (copyRoot: string): Promise<LockBody | undefined> => readLock(lockFile(copyRoot))
