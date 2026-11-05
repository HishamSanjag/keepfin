// A copy that was made before copies ran directly over the transport kept a full local mirror of the replica (mirror.ts). The first run
// of such a place sends whatever the mirror still holds that the replica does not (the old push, once), then drops the mirror's media
// and the copy-side file ids of the baseline, and from then on the place runs directly. The mirror folder keeps only the copy's own
// `.keepfin` data. If the old push cannot finish, nothing is dropped and the place is tried again at its next run.
import fsp from 'node:fs/promises'
import path from 'node:path'
import {stripCopyFileIds} from '../copies/baseline.js'
import {readJson, writeJsonAtomic} from '../store/atomic.js'
import {isArchiveBytes, readState, saveState, type PushReport} from './mirror.js'

const META = '.keepfin'
export const directMarker = (stateDir: string) => path.join(stateDir, 'direct.json')

export type MigrationResult = {state: 'direct'; cleaned: number} | {state: 'migrated'; cleaned: number} | {state: 'blocked'; failed: number; stopped: PushReport['stopped']; error?: unknown}

async function topLevel(mirror: string): Promise<string[]> {
  return (await fsp.readdir(mirror).catch(() => [] as string[])).filter((n) => n !== META)
}

/** Removes the media (and the bytes of archived versions) from the mirror folder. What the replica needs is on the replica already. */
export async function dropMirrorMedia(mirror: string): Promise<number> {
  let removed = 0
  for (const name of await topLevel(mirror)) {
    await fsp.rm(path.join(mirror, name), {recursive: true, force: true})
    removed++
  }
  const stack = [META]
  while (stack.length) {
    const dir = stack.pop()!
    for (const e of await fsp.readdir(path.join(mirror, ...dir.split('/')), {withFileTypes: true}).catch(() => [])) {
      const rel = `${dir}/${e.name}`
      if (e.isDirectory()) stack.push(rel)
      else if (e.isFile() && isArchiveBytes(rel)) {
        await fsp.rm(path.join(mirror, ...rel.split('/')), {force: true})
        removed++
      }
    }
  }
  return removed
}

/**
 * [flush] is the old two-way push of the mirror (full, not meta-only) and whatever else the old design did to settle it. Returns whether the
 * place runs directly now. Idempotent: a place already migrated only has stray media removed.
 */
export async function migrateLegacyMirror(o: {stateDir: string; mirror: string; stateFile: string; flush: () => Promise<PushReport>}): Promise<MigrationResult> {
  const marker = directMarker(o.stateDir)
  if (await readJson<{direct?: boolean}>(marker).then((j) => j?.direct === true, () => false)) {
    const stray = (await topLevel(o.mirror)).length > 0
    return {state: 'direct', cleaned: stray ? await dropMirrorMedia(o.mirror) : 0}
  }
  const state = await readState(o.stateFile)
  const legacy = Object.keys(state.files).some((p) => !p.startsWith(`${META}/`)) || (await topLevel(o.mirror)).length > 0
  if (legacy) {
    let pushed: PushReport
    try {
      pushed = await o.flush()
    } catch (error) {
      return {state: 'blocked', failed: 0, stopped: null, error}
    }
    if (pushed.failed.length > 0 || pushed.stopped) return {state: 'blocked', failed: pushed.failed.length, stopped: pushed.stopped}
    await stripCopyFileIds(o.stateDir)
    const after = await readState(o.stateFile)
    for (const p of Object.keys(after.files)) if (!p.startsWith(`${META}/`) || isArchiveBytes(p)) delete after.files[p]
    await saveState(o.stateFile, after)
  }
  await writeJsonAtomic(marker, {direct: true})
  return {state: 'migrated', cleaned: legacy ? await dropMirrorMedia(o.mirror) : 0}
}
