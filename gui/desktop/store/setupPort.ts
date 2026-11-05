// The adapter between the migration engine (migration/) and the setup window's requests (SetupPort in service.ts).
import fs from 'node:fs/promises'
import path from 'node:path'
import type {SetupCounts, SetupInventory, SetupPhase, SetupPlan, SetupProgress, SetupReport, SetupSource, SourceChoice} from '../../shared/storage.js'
import {readJson} from './atomic.js'
import {takeInventory} from './migration/inventory.js'
import {sameVolume, statOrUndefined} from './migration/io.js'
import {readJournal} from './migration/journal.js'
import {buildPlan, readPlan, writePlan} from './migration/plan.js'
import {buildReport, renderReport} from './migration/report.js'
import {abandonRun, resumeRun, startRun, type CommitContext, type RunDeps} from './migration/runner.js'
import type {Inventory, Plan, Progress} from './migration/types.js'
import type {SetupPort} from './service.js'

export type SetupPortDeps = {
  /** %APPDATA%\Keepfin */
  deviceDir: string
  /** Where the Keepfin folder goes by default; used to suggest Move or Copy per source. */
  defaultTarget: string
  newId(): string
  now(): number
  /** shell.trashItem */
  trash(file: string): Promise<void>
  pause?(): void | Promise<void>
  resume?(): void | Promise<void>
  push(progress: SetupProgress): void
  saveReportDialog(text: string): Promise<boolean>
  /** The library store writes the new store's operations here (the switch point). */
  commit(plan: Plan, context: CommitContext): Promise<void>
  /** Engine overrides, for tests. */
  engine?: Partial<Pick<RunDeps, 'sameVolume' | 'copyFile' | 'crash'>>
}

const margin = 200 * 1024 * 1024
const safeRunId = /^[A-Za-z0-9_-]{1,80}$/

const number = (value: number) => value.toLocaleString('en-GB')
export function formatBytes(bytes: number): string {
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`
  if (bytes < 1024 ** 3) return `${(bytes / 1024 ** 2).toFixed(1)} MB`
  return `${(bytes / 1024 ** 3).toFixed(1)} GB`
}
const plural = (count: number, one: string, many = `${one}s`) => `${number(count)} ${count === 1 ? one : many}`

async function nearestExisting(target: string): Promise<string | undefined> {
  let probe = target
  for (;;) {
    if (await statOrUndefined(probe)) return probe
    const parent = path.dirname(probe)
    if (parent === probe) return undefined
    probe = parent
  }
}

async function freeBytesAt(target: string): Promise<number | undefined> {
  try {
    const where = await nearestExisting(target)
    if (!where) return undefined
    const stats = await fs.statfs(where)
    return Number(stats.bsize) * Number(stats.bavail)
  } catch {
    return undefined
  }
}

const phaseOf: Record<Progress['phase'], SetupPhase> = {copying: 'copying', committing: 'saving', cleaning: 'cleaning', done: 'done'}

export function createSetupPort(deps: SetupPortDeps): SetupPort {
  const migrationDir = path.join(deps.deviceDir, 'migration')
  const runDir = (runId: string) => {
    if (!safeRunId.test(runId)) throw new Error('Invalid request.')
    return path.join(migrationDir, runId)
  }
  let cached: Inventory | undefined
  let running: string | undefined

  const getInventory = async () => (cached ??= await takeInventory(deps.deviceDir))

  const sameDrive = async (folder: string, target: string) => {
    try {
      return await (deps.engine?.sameVolume ?? sameVolume)(folder, target)
    } catch {
      return false
    }
  }

  const toCounts = (counts: Inventory['counts']): SetupCounts => ({...counts})

  const engine = (runId: string, acceptFailures: boolean) => {
    const dir = runDir(runId)
    const runDeps: RunDeps = {
      commit: (plan, context) => deps.commit(plan, context),
      trash: deps.trash,
      pause: deps.pause,
      resume: deps.resume,
      now: deps.now,
      ...deps.engine,
      onProgress: (progress) => {
        // The final 'done' is pushed from the run result, which knows how many files failed.
        if (progress.phase !== 'done') deps.push(mapProgress(runId, progress, 0))
      },
    }
    return {dir, runDeps, options: {acceptFailures}}
  }

  function mapProgress(runId: string, progress: Progress, failed: number): SetupProgress {
    const verifying = progress.phase === 'copying' && progress.total > 0 && progress.done >= progress.total
    return {
      runId,
      phase: verifying ? 'verifying' : phaseOf[progress.phase],
      done: progress.done,
      total: progress.total,
      bytesDone: progress.bytesDone,
      bytesTotal: progress.bytesTotal,
      current: progress.current ? path.basename(progress.current) : undefined,
      failed,
    }
  }

  return {
    async unfinishedRun() {
      let names: string[] = []
      try {
        names = await fs.readdir(migrationDir)
      } catch {
        return undefined
      }
      let best: {runId: string; at: number} | undefined
      for (const name of names) {
        if (!safeRunId.test(name)) continue
        const plan = await readJson<Plan>(path.join(migrationDir, name, 'plan.json')).catch(() => undefined)
        if (!plan) continue
        const fold = await readJournal(path.join(migrationDir, name)).catch(() => undefined)
        if (!fold || !fold.run.started || fold.run.done || fold.run.abandoned) continue
        if (!best || plan.createdAt > best.at) best = {runId: name, at: plan.createdAt}
      }
      return best?.runId
    },

    async inventory(): Promise<SetupInventory> {
      cached = undefined
      const inventory = await getInventory()
      const sources: SetupSource[] = []
      for (const space of inventory.spaces) {
        if (space.type !== 'folder' || !space.folder) continue
        const files = [...inventory.media, ...inventory.sidecars].filter((file) => file.space === space.id)
        sources.push({
          id: space.id,
          name: space.name,
          path: space.folder,
          files: files.length,
          bytes: files.reduce((sum, file) => sum + file.size, 0),
          suggested: (await sameDrive(space.folder, deps.defaultTarget)) ? 'move' : 'copy',
        })
      }
      return {sources, counts: toCounts(inventory.counts), unindexed: inventory.unindexed.length, missing: inventory.missing.length}
    },

    async plan(target, choices): Promise<SetupPlan> {
      const inventory = await getInventory()
      const runId = deps.newId().replace(/[^A-Za-z0-9_-]/g, '')
      const plan = await buildPlan(inventory, {target, choices, newId: deps.newId, runId, now: deps.now})
      await writePlan(runDir(runId), plan)

      const lines: string[] = []
      let bytesToCopy = 0
      for (const space of plan.spaces) {
        const files = plan.files.filter((file) => file.space === space.id)
        const left = plan.left.filter((file) => file.space === space.id)
        const count = files.length + left.length
        const bytes = [...files, ...left.map((file) => ({size: inventory.media.concat(inventory.sidecars).find((item) => item.path === file.path)?.size ?? 0}))].reduce((sum, file) => sum + file.size, 0)
        if (space.choice === 'leave') {
          lines.push(`Leave ${space.name} where it is (linked)`)
          continue
        }
        const instant = space.choice === 'move' && (await sameDrive(space.folder, target))
        if (!instant) bytesToCopy += bytes
        if (!count) continue
        if (space.choice === 'move') lines.push(instant ? `Move ${plural(count, 'file')} from ${space.name} (same drive, instant)` : `Move ${plural(count, 'file')} from ${space.name} (${formatBytes(bytes)}, copied then removed)`)
        else lines.push(`Copy ${plural(count, 'file')} from ${space.name} (${formatBytes(bytes)})`)
      }
      // Saved artwork is always copied.
      bytesToCopy += plan.files.filter((file) => file.kind === 'art').reduce((sum, file) => sum + file.size, 0)

      const c = inventory.counts
      const parts = [
        c.playlists ? plural(c.playlists, 'playlist') : '',
        c.collections ? plural(c.collections, 'collection') : '',
        c.favourites ? plural(c.favourites, 'favourite') : '',
        c.history ? 'your play history' : '',
      ].filter(Boolean)
      if (parts.length) lines.push(`Bring over ${parts.length > 1 ? `${parts.slice(0, -1).join(', ')} and ${parts[parts.length - 1]}` : parts[0]}`)
      if (inventory.missing.length) lines.push(`${plural(inventory.missing.length, 'file')} listed in your library ${inventory.missing.length === 1 ? 'is' : 'are'} missing today and will come over marked as missing`)

      const freeBytes = await freeBytesAt(target)
      const fits = freeBytes === undefined ? true : bytesToCopy * 1.02 + margin <= freeBytes
      return {runId, target, choices: choices as Record<string, SourceChoice>, bytesToCopy, freeBytes, fits, files: plan.files.length + plan.left.length, lines}
    },

    async start(runId, acceptFailures) {
      if (running) throw new Error('Setup is already running.')
      const { dir, runDeps, options } = engine(runId, acceptFailures)
      const plan = await readPlan(dir)
      if (!plan) throw new Error('This setup has no plan. Review the plan again.')
      const started = (await readJournal(dir)).run.started
      running = runId
      deps.push({runId, phase: 'preparing', done: 0, total: plan.files.length, bytesDone: 0, bytesTotal: plan.files.reduce((sum, file) => sum + file.size, 0), failed: 0})
      void (started ? resumeRun(dir, runDeps, options) : startRun(dir, plan, runDeps, options))
        .then((result) => {
          const phase: SetupPhase = result.status === 'completed' ? 'done' : result.status === 'no-space' ? 'stoppedDiskFull' : 'failed'
          deps.push({runId, phase, done: plan.files.length, total: plan.files.length, bytesDone: 0, bytesTotal: 0, failed: result.failed.length})
        })
        .catch(() => deps.push({runId, phase: 'failed', done: 0, total: plan.files.length, bytesDone: 0, bytesTotal: 0, failed: 0}))
        .finally(() => {
          running = undefined
        })
    },

    async abandon(runId) {
      if (running === runId) throw new Error('Setup is running. Wait for it to stop first.')
      await abandonRun(runDir(runId), { commit: deps.commit, trash: deps.trash, pause: deps.pause, resume: deps.resume, now: deps.now })
    },

    async report(runId): Promise<SetupReport> {
      const dir = runDir(runId)
      const plan = await readPlan(dir)
      if (!plan) throw new Error('This setup has no report.')
      const report = buildReport(plan, await readJournal(dir))
      return {
        runId,
        before: toCounts(report.before),
        after: toCounts(report.after),
        attention: report.attention.map((item) => ({name: path.basename(item.path), reason: item.reason, actions: item.actions})),
        text: renderReport(report),
      }
    },

    async saveReport(runId) {
      return deps.saveReportDialog((await this.report(runId)).text)
    },
  }
}
