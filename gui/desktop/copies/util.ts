import fs from 'node:fs/promises'
import path from 'node:path'

/** The copy root could not be reached (ENOENT or EIO on the root): the run pauses as "disconnected" (spec 5.3). */
export class DisconnectedError extends Error {
  constructor(readonly root: string, cause?: unknown) {
    super(`The copy at ${root} is not reachable.`, {cause})
    this.name = 'DisconnectedError'
  }
}

/** A copy kept on an account or a device cannot be reached or refuses to go on (sign-in, quota, connection): the run ends here, nothing is lost. */
export class RemoteStopError extends Error {
  constructor(readonly state: 'needsSignIn' | 'offline' | 'quota' | 'incomplete' | 'busy' | 'unavailable', message: string, cause?: unknown) {
    super(message, {cause})
    this.name = 'RemoteStopError'
  }
}

/** Stands in for a killed process in fault-injection tests: nothing after it may touch the disk. */
export class CrashError extends Error {
  constructor(readonly step: string) {
    super(`crash injected at ${step}`)
    this.name = 'CrashError'
  }
}

/** An action that must not run (and nothing is touched): the reason is recorded and the next plan decides again. */
export class SkipError extends Error {
  constructor(readonly reason: string, message?: string) {
    super(message ?? reason)
    this.name = 'SkipError'
  }
}

export const code = (error: unknown): string | undefined => (error as NodeJS.ErrnoException | undefined)?.code

export async function exists(file: string): Promise<boolean> {
  try {
    await fs.lstat(file)
    return true
  } catch (error) {
    if (code(error) === 'ENOENT' || code(error) === 'ENOTDIR') return false
    throw error
  }
}

/** True when the copy root cannot be read any more (unplugged, share gone). */
export async function rootGone(root: string): Promise<boolean> {
  try {
    await fs.stat(root)
    return false
  } catch (error) {
    const c = code(error)
    return c === 'ENOENT' || c === 'EIO' || c === 'ENXIO' || c === 'ENODEV' || c === 'ENOTDIR'
  }
}

/** Runs [fn] for every item with at most [limit] in flight; stops starting new ones after the first error. */
export async function pool<T>(items: Iterable<T> | AsyncIterable<T>, limit: number, fn: (item: T) => Promise<void>): Promise<void> {
  const running = new Set<Promise<void>>()
  let failure: unknown
  let failed = false
  for await (const item of items as AsyncIterable<T>) {
    if (failed) break
    const p: Promise<void> = fn(item).catch((error) => {
      if (!failed) {
        failed = true
        failure = error
      }
    }).finally(() => running.delete(p))
    running.add(p)
    if (running.size >= limit) await Promise.race(running)
  }
  await Promise.all(running)
  if (failed) throw failure
}

/** The components of a `/` path joined onto [root]. Rejects traversal. */
export function under(root: string, rel: string): string {
  const parts = rel.split('/')
  if (parts.some((p) => p === '..' || p === '' || p === '.')) throw new Error(`Unsafe path: ${rel}`)
  return path.join(root, ...parts)
}

export function stamp(ms: number): string {
  return new Date(ms).toISOString()
}

/** Splits `dir/name.ext` into stem and extension (extension includes the dot). */
export function splitExt(rel: string): {stem: string; ext: string} {
  const slash = rel.lastIndexOf('/')
  const dot = rel.lastIndexOf('.')
  if (dot > slash + 1) return {stem: rel.slice(0, dot), ext: rel.slice(dot)}
  return {stem: rel, ext: ''}
}

/** `name (2).ext`, `name (3).ext`... : the counter added to a name that is already taken (spec 5.4). */
export function withCounter(rel: string, n: number): string {
  const {stem, ext} = splitExt(rel)
  return `${stem} (${n})${ext}`
}

/** The first of [wanted], [wanted] (2), ... that [taken] says is free. */
export async function uniqueName(wanted: string, taken: (candidate: string) => Promise<boolean>): Promise<string> {
  if (!(await taken(wanted))) return wanted
  for (let n = 2; n < 10_000; n++) {
    const candidate = withCounter(wanted, n)
    if (!(await taken(candidate))) return candidate
  }
  throw new Error(`No free name for ${wanted}`)
}
