// One sync run of one relationship (main folder, copy folder, place): recognise and lock the copy, recover an interrupted
// run, inventory both sides, plan (hashing what the planner asks for), execute, fold the verified results into the
// baseline and write the coverage manifest. Nothing here is wired into the app yet.
import fs from 'node:fs/promises'
import path from 'node:path'
import {fastKey, fileSizeLimit, onlineOnlyWords, planCopies, tooLargeWords, type NameRulesId, type CopyMode, type DesiredEntry, type PlacementMove, type PlanSummary, type ReviewItem} from '../../shared/core/copies.js'
import {foldCommits, baselineFile, resortBaseline} from './baseline.js'
import {detectClockShift} from './clock.js'
import {executePlan, type ExecReport, type MainHost, type Progress, type RemoteTally} from './executor.js'
import {bumpGeneration, compareGeneration, readGeneration, readLockToken, readRelationship, writeRelationship} from './generation.js'
import {HashCache, hashRequests} from './hash.js'
import {probeNameRules, scanInventory, type InventoryResult} from './inventory.js'
import {Journal, type FaultHook} from './journal.js'
import {acquireCopyLock} from './lock.js'
import {ExternalSorter, LineWriter, ReaderSet, lineReader} from './lines.js'
import {writeManifest, type ManifestResult} from './manifest.js'
import {NameMap} from './names.js'
import {archiveJournal, recoverRemoteRun, recoverRun, type RecoveryReport} from './recovery.js'
import {clearStaging, hashRemote, REMOTE_RULES, scanRemoteInventory, type RemoteMedia, type RemoteStopState} from './remote.js'
import {DisconnectedError, RemoteStopError, exists, rootGone} from './util.js'
import {onlineOnlyFiles, volumeFormat} from './winfacts.js'
import {derivedFilter} from './exports.js'

export interface RunConfig {
  mainRoot: string
  copyRoot: string
  /** Device-local (or main-local) folder for this relationship's baseline, journal and scratch files. */
  stateDir: string
  placeId: string
  mode: CopyMode
  device: string
  /** The desired set D of the place; any order. */
  desired: Iterable<DesiredEntry>
  deleted?: string[]
  placementMoves?: PlacementMove[]
  confirmedDeletes?: string[]
  holdsKey?: string
  optionsKey?: string
  deleteGuardPercent?: number
  /** The place carries lyrics, subtitles and info files (default true). */
  companions?: boolean
  /** The place carries each song's music video (default true; the caller passes the place's effective value). */
  musicVideos?: boolean
  /** The user confirmed the preview of a new relationship. */
  previewConfirmed?: boolean
  /** The user confirmed a plan that the deletion guard held. */
  confirmHeld?: boolean
  /** Plan only (the preview of the Add a copy wizard): nothing is executed, no generation bumped, no baseline or manifest written. */
  dryRun?: boolean
  /** Free bytes on each side for the plan's space check; read from the volumes when absent. */
  freeMain?: number
  freeCopy?: number
  /** A full audit: hash every file on the copy. */
  audit?: boolean
  /** 2000 on FAT and exFAT, 1 on NTFS. Default 2000. */
  timestampGranularityMs?: number
  host?: MainHost
  parallelism?: number
  speedLimit?: number
  maxFileBytes?: number
  /** The file system of the drive under a folder (tests supply it; the app asks Windows). FAT limits a file to 4 GiB. */
  volumeFormat?: (dir: string) => Promise<string | null>
  /** The cloud files under a folder whose bytes are not on the disk, relative with `/`; null when that could not be told. */
  onlineOnlyFiles?: (root: string, signal?: AbortSignal) => Promise<string[] | null>
  maxRounds?: number
  now?: () => number
  runId?: string
  fault?: FaultHook
  signal?: AbortSignal
  onProgress?: (p: Progress) => void
  /** Sort-run size; small values exercise the external sort in tests. */
  sortChunk?: number
  /** The copy is an account or a device: planned and executed through the transport; [copyRoot] then holds only the copy's own .keepfin data. */
  remote?: RemoteMedia
  /** Where a remote copy stages the file in flight (default: staging under [stateDir]). */
  staging?: string
}

export type RunStatus = 'done' | 'paused' | 'disconnected' | 'crashed' | 'busy' | 'held' | 'refused' | 'preview'

export interface RunReport {
  status: RunStatus
  reason?: string
  rounds: number
  generation: { copy: number | null; relationship: number | null; state: string; committedAt: number | null }
  recovery: RecoveryReport | null
  main: InventoryResult | null
  copy: InventoryResult | null
  summary: PlanSummary | null
  held: boolean
  holdReason: string | null
  additiveOnly: boolean
  review: ReviewItem[]
  reviewCount: number
  exec: ExecReport | null
  manifest: ManifestResult | null
  hashed: number
  /** Milliseconds per phase. */
  timings: Record<string, number>
  /** A remote copy only: what it did, and why a run ended before it was done. */
  remote?: RemoteTally
  remoteStop?: {state: RemoteStopState; message: string}
  /** A remote copy only: names the provider cannot tell apart (the planner sends them to Needs review). */
  collisions?: string[]
  /** Every file on the copy was hashed this run: because it was asked for, or a time zone or daylight saving shift was seen (spec 3). */
  audit?: {reason: 'asked' | 'clock'; hours?: number}
  /** The file system of the copy's drive, when it is a lettered Windows drive. */
  copyFormat?: string | null
  /** Cloud files that were left alone, per side. */
  onlineOnly?: {main: number; copy: number}
}

/** Refuses a copy root that nests with the Keepfin folder, is a link, or is not a Keepfin folder. */
export async function checkCopyRoot(mainRoot: string, copyRoot: string): Promise<string | null> {
  let st
  try {
    st = await fs.lstat(copyRoot)
  } catch (error) {
    const c = (error as NodeJS.ErrnoException).code
    if (c === 'ENOENT' || c === 'EIO' || c === 'ENOTDIR') throw new DisconnectedError(copyRoot, error)
    throw error
  }
  if (st.isSymbolicLink()) return 'The copy is reached through a link.'
  const a = path.resolve(await fs.realpath(mainRoot))
  const b = path.resolve(await fs.realpath(copyRoot))
  const norm = (p: string) => (process.platform === 'win32' ? p.toLowerCase() : p)
  const na = norm(a)
  const nb = norm(b)
  if (na === nb || nb.startsWith(na + path.sep) || na.startsWith(nb + path.sep)) return 'The copy and the Keepfin folder are nested.'
  if (!(await exists(path.join(copyRoot, '.keepfin', 'folder.json')))) return 'The folder is not a Keepfin folder.'
  return null
}

async function writeDesired(entries: Iterable<DesiredEntry>, file: string, rules: NameRulesId, tmpDir: string, chunk?: number): Promise<number> {
  const sorter = new ExternalSorter(tmpDir, chunk ?? 50_000, 'desired')
  let seq = 0
  for (const e of entries) await sorter.add({key: fastKey(rules, e.path), path: e.path, seq: seq++, text: JSON.stringify(e)})
  const w = await LineWriter.create(file)
  for await (const rec of sorter.sorted()) await w.write(rec.text)
  await w.close(false)
  return w.count
}

const emptyReport = (): RunReport => ({
  status: 'done',
  rounds: 0,
  generation: {copy: null, relationship: null, state: 'equal', committedAt: null},
  recovery: null,
  main: null,
  copy: null,
  summary: null,
  held: false,
  holdReason: null,
  additiveOnly: false,
  review: [],
  reviewCount: 0,
  exec: null,
  manifest: null,
  hashed: 0,
  timings: {},
})

export async function runCopy(cfg: RunConfig): Promise<RunReport> {
  const report = emptyReport()
  const now = cfg.now ?? Date.now
  const runId = cfg.runId ?? `r${now().toString(36)}${Math.random().toString(36).slice(2, 6)}`
  const timer = (name: string, start: number) => (report.timings[name] = (report.timings[name] ?? 0) + (Date.now() - start))

  let refusal: string | null
  try {
    refusal = await checkCopyRoot(cfg.mainRoot, cfg.copyRoot)
  } catch (error) {
    if (error instanceof DisconnectedError) return {...report, status: 'disconnected', reason: error.message}
    throw error
  }
  if (refusal) return {...report, status: 'refused', reason: refusal}

  const lock = await acquireCopyLock(cfg.copyRoot, {device: cfg.device, run: runId, now})
  if (!lock.ok) return {...report, status: 'busy', reason: lock.reason === 'held' ? 'Another run holds this copy.' : 'The copy changed while its lock was taken over.'}

  const tmpDir = path.join(cfg.stateDir, `tmp-${runId}`)
  try {
    await fs.mkdir(tmpDir, {recursive: true})
    const journalFile = path.join(cfg.stateDir, 'journal.jsonl')
    const remote = cfg.remote
    const staging = cfg.staging ?? path.join(cfg.stateDir, 'staging')
    const rules = remote ? REMOTE_RULES : await probeNameRules(cfg.copyRoot)
    const names = remote ? NameMap.identity(cfg.copyRoot) : await NameMap.load(cfg.copyRoot)
    if (remote) await clearStaging(staging) // our own temporaries from a run that died
    // the baseline file is sorted by the name rule it was written under: sort it again when the rule changed (or was never recorded)
    {
      const recorded = await readRelationship(cfg.stateDir)
      if (recorded.rules !== rules && (await exists(baselineFile(cfg.stateDir)))) {
        await resortBaseline({stateDir: cfg.stateDir, rules, tmpDir, chunk: cfg.sortChunk})
        if (!cfg.dryRun) await writeRelationship(cfg.stateDir, {...recorded, rules})
      }
    }
    const format = remote ? null : await (cfg.volumeFormat ?? volumeFormat)(cfg.copyRoot)
    report.copyFormat = format
    const sizeLimit = cfg.maxFileBytes ?? fileSizeLimit(format) ?? undefined

    // 1. an interrupted run: reconcile the files with its journal, then fold what it committed
    let t = Date.now()
    if (await exists(journalFile)) {
      report.recovery = remote ? await recoverRemoteRun({journalFile, copyRoot: cfg.copyRoot, remote, staging}) : await recoverRun({journalFile, copyRoot: cfg.copyRoot})
      await foldCommits({stateDir: cfg.stateDir, journalFile, rules, tmpDir, chunk: cfg.sortChunk})
      await archiveJournal(journalFile)
    }
    timer('recovery', t)

    // 2. the generation check (spec 7)
    let rel = await readRelationship(cfg.stateDir)
    const copyGen = await readGeneration(cfg.copyRoot)
    // a run of ours that crashed after bumping the generation: the bump is ours (same lock token), not another computer's
    if (report.recovery && copyGen !== null && copyGen !== rel.generation) {
      const token = await readLockToken(cfg.copyRoot)
      const mine = report.recovery.runs.find((r) => r.run === token && r.generation === copyGen)
      if (mine) {
        rel = {...rel, generation: copyGen}
        await writeRelationship(cfg.stateDir, rel)
      }
    }
    const genState = compareGeneration(copyGen, rel.generation)
    report.generation = {copy: copyGen, relationship: rel.generation, state: genState, committedAt: null}
    let runGeneration = copyGen ?? 0
    const previewConfirmed = cfg.previewConfirmed === true || rel.previewConfirmed
    const desiredFile = path.join(cfg.stateDir, 'desired.jsonl')
    t = Date.now()
    await writeDesired(cfg.desired, desiredFile, rules, tmpDir, cfg.sortChunk)
    timer('desired', t)

    const cache = new HashCache()
    const freeOf = async (root: string) => {
      const s = await fs.statfs(root).catch(() => undefined)
      return s ? Number(s.bavail) * Number(s.bsize) : undefined
    }
    const freeMain = cfg.freeMain ?? (await freeOf(cfg.mainRoot))
    const freeCopy = remote ? cfg.freeCopy : (cfg.freeCopy ?? (await freeOf(cfg.copyRoot)))
    const priorCopyCount = remote ? countLines(baselineFile(cfg.stateDir)) : 0
    const rounds = cfg.maxRounds ?? 3
    let bumped = false
    let audited = false
    let lastScanStart = now()
    for (let round = 1; round <= rounds; round++) {
      report.rounds = round
      if (cfg.signal?.aborted) {
        report.status = 'paused'
        break
      }
      // 3. inventories (sorted, on disk)
      t = Date.now()
      lastScanStart = now()
      const mainInv = path.join(tmpDir, 'main.inv')
      const copyInv = path.join(tmpDir, 'copy.inv')
      // cloud files that are only in the cloud: never listed (so never hashed or read), the side is not fully known this run, and the
      // executor refuses any action that touches their names
      const listOnline = cfg.onlineOnlyFiles ?? onlineOnlyFiles
      const mainOnline = await listOnline(cfg.mainRoot, cfg.signal)
      const copyOnline = remote ? [] : await listOnline(cfg.copyRoot, cfg.signal)
      const keysOf = (files: string[] | null, library: (p: string) => string) => new Set((files ?? []).map((p) => fastKey(rules, library(p))))
      const mainOnlineKeys = keysOf(mainOnline, (p) => p)
      const copyOnlineKeys = keysOf(copyOnline, (p) => names.library(p))
      const except = (keys: Set<string>) => (keys.size ? (p: string) => !keys.has(fastKey(rules, p)) : undefined)
      report.main = await scanInventory(cfg.mainRoot, mainInv, {rules, tmpDir, filter: except(mainOnlineKeys), signal: cfg.signal, chunk: cfg.sortChunk})
      if (remote) {
        const scanned = await scanRemoteInventory(remote, copyInv, {rules, tmpDir, priorCount: priorCopyCount, signal: cfg.signal, chunk: cfg.sortChunk})
        report.copy = scanned
        report.collisions = scanned.collisions
        if (!scanned.complete) {
          // a listing that did not reach its end changes nothing: the account or device is asked again next time
          timer('inventory', t)
          return {...report, status: 'disconnected', reason: 'The copy could not be listed completely, so nothing was changed.', remoteStop: {state: 'incomplete', message: 'The copy could not be listed completely, so nothing was changed. Try again.'}}
        }
      } else {
        // derived exports (playlist files, flat and MP3 versions) are not library content: never listed, never imported back
        const derived = await derivedFilter(cfg.copyRoot)
        const online = except(copyOnlineKeys)
        report.copy = await scanInventory(cfg.copyRoot, copyInv, {rules, tmpDir, names, filter: (p) => derived(p) && (online?.(p) ?? true), signal: cfg.signal, chunk: cfg.sortChunk})
      }
      const unsure = (r: InventoryResult | null, files: string[] | null) => {
        if (r && (files === null || files.length > 0)) {
          r.complete = false
          r.unreadable.push(files === null ? '(cloud files could not be checked)' : '(files only in the cloud)')
        }
      }
      unsure(report.main, mainOnline)
      unsure(report.copy, copyOnline)
      const onlineKeys = new Set([...mainOnlineKeys, ...copyOnlineKeys])
      report.onlineOnly = {main: mainOnline?.length ?? 0, copy: copyOnline?.length ?? 0}
      timer('inventory', t)

      // 4. plan; hash what the planner asks for; plan again
      const readers = new ReaderSet()
      const sources = {
        desired: readers.source(desiredFile),
        main: readers.source(mainInv),
        copy: readers.source(copyInv),
        baseline: readers.source(baselineFile(cfg.stateDir)),
      }
      // a time zone or daylight saving shift makes every stat untrustworthy: hash everything this run (spec 3)
      let audit = cfg.audit === true
      if (audit) report.audit = {reason: 'asked'}
      else if (!remote && (await exists(baselineFile(cfg.stateDir)))) {
        const shift = detectClockShift(baselineFile(cfg.stateDir), copyInv, rules, 'copy')
        if (shift.detected) {
          audit = true
          report.audit = {reason: 'clock', hours: shift.hours}
        }
      }
      audited ||= audit
      const options = {
        mode: cfg.mode,
        rules,
        generation: genState,
        mainComplete: report.main.complete,
        copyComplete: report.copy.complete,
        previewConfirmed,
        audit,
        timestampGranularityMs: cfg.timestampGranularityMs ?? 2000,
        deleteGuardPercent: cfg.deleteGuardPercent,
        companions: cfg.companions !== false,
        musicVideos: cfg.musicVideos !== false,
        freeMain,
        freeCopy,
        confirmedDeletes: cfg.confirmedDeletes,
        deleted: cfg.deleted,
        placementMoves: cfg.placementMoves,
      }
      let plan
      try {
        for (let attempt = 0; ; attempt++) {
          t = Date.now()
          const step = planCopies(options, sources, (side, p, size, mtimeMs) => cache.get(side, p, size, mtimeMs))
          readers.closeAll()
          timer('plan', t)
          if (step.ready) {
            plan = step.plan
            break
          }
          if (attempt >= 6) throw new Error('The planner kept asking for hashes; the files changed while they were read.')
          t = Date.now()
          const got = await hashRequests(
            step.requests,
            {
              main: cfg.mainRoot,
              copy: cfg.copyRoot,
              mainFile: (p) => path.join(cfg.mainRoot, ...p.split('/')),
              copyFile: (p) => path.join(cfg.copyRoot, ...names.physical(p).split('/')),
              ...(remote ? {copyHash: (p: string) => remoteHash(remote, p, staging, cfg.signal)} : {}),
            },
            cache,
            {signal: cfg.signal},
          )
          timer('hash', t)
          report.hashed += got.hashed
          if (got.hashed === 0) throw new Error('The files the planner asked about are no longer there; scan again.')
        }
      } finally {
        readers.closeAll()
      }

      report.summary = plan.summary
      report.held = plan.held
      report.holdReason = plan.holdReason
      report.additiveOnly = plan.additiveOnly
      report.reviewCount = plan.reviewCount
      report.review = []
      for (const r of plan.review()) {
        if (report.review.length >= 200) break
        report.review.push(r)
      }
      // what the executors know and the planner does not: cloud files left alone, files a FAT drive cannot hold
      const note = (kind: 'OnlineOnly' | 'TooLarge', p: string, detail: string) => {
        report.reviewCount++
        if (report.review.length < 200) report.review.push({kind, path: p, itemId: null, detail, action: null})
      }
      for (const [files, side] of [[mainOnline, 'main'], [copyOnline, 'copy']] as const) {
        void side
        for (const f of files ?? []) note('OnlineOnly', f, onlineOnlyWords(f.slice(f.lastIndexOf('/') + 1)))
      }
      if (sizeLimit !== undefined) {
        for (const a of plan.actions()) {
          if (a.kind === 'CopyTo' && a.bytes > sizeLimit) note('TooLarge', a.path, tooLargeWords(a.path.slice(a.path.lastIndexOf('/') + 1), format))
        }
      }
      if (cfg.dryRun) {
        report.status = 'preview'
        break
      }
      if (plan.held && !cfg.confirmHeld) {
        report.status = 'held'
        break
      }
      if (plan.isEmpty) break

      // 5. execute (the generation is bumped once, at the start of the first run that changes the copy)
      const changesCopy = (['CopyTo', 'MoveOnCopy', 'RemoveFromCopy'] as const).some((k) => (plan.summary.counts[k] ?? 0) > 0)
      if (changesCopy && !bumped && genState !== 'lower' && genState !== 'unknown') {
        runGeneration = await bumpGeneration(cfg.copyRoot, runId)
        bumped = true
      }
      const journal = await Journal.open(journalFile)
      await journal.append({t: 'run', run: runId, at: now(), generation: runGeneration}, true)
      t = Date.now()
      const exec = await executePlan(
        {
          mainRoot: cfg.mainRoot,
          copyRoot: cfg.copyRoot,
          mode: cfg.mode,
          rules,
          runId: `${runId}.${round}`,
          generation: runGeneration,
          names,
          journal,
          host: cfg.host,
          parallelism: cfg.parallelism,
          speedLimit: cfg.speedLimit,
          maxFileBytes: sizeLimit,
          onlineOnly: onlineKeys,
          now,
          fault: cfg.fault,
          signal: cfg.signal,
          onProgress: cfg.onProgress,
          remote,
          staging,
          knownCopyHash: remote ? (p, size, mtimeMs) => cache.get('Copy', p, size, mtimeMs) : undefined,
        },
        plan.actions(),
        {actions: plan.actionCount, bytes: Object.values(plan.summary.bytes).reduce<number>((a, b) => a + (b ?? 0), 0)},
      )
      timer('execute', t)
      if (exec.status !== 'crashed') await journal.append({t: 'end', run: `${runId}.${round}`}, true).catch(() => undefined)
      await journal.close()
      report.exec = mergeExec(report.exec, exec)
      if (exec.remote) report.remote = report.exec.remote
      if (exec.remoteStop) {
        report.remoteStop = exec.remoteStop
        report.reason = exec.remoteStop.message
      }
      if (exec.status === 'crashed') {
        report.status = 'crashed'
        return report // like a killed process: no folding, no cleanup, the next run recovers
      }

      // 6. fold the verified results into the baseline; a hash that proved a file unchanged refreshes its stat
      t = Date.now()
      await foldCommits({
        stateDir: cfg.stateDir,
        journalFile,
        rules,
        tmpDir,
        chunk: cfg.sortChunk,
        refresh: (e) => {
          const h = cache.lookup('Copy', e.path)
          if (h && h.contentId === e.contentId && (h.size !== e.copy.size || h.mtimeMs !== e.copy.mtimeMs)) {
            return {...e, copy: {size: h.size, mtimeMs: h.mtimeMs, ...(e.copy.fileId ? {fileId: e.copy.fileId} : {})}, scannedAtMs: lastScanStart}
          }
          return e
        },
      })
      await archiveJournal(journalFile)
      timer('fold', t)
      if (exec.status === 'disconnected') {
        report.status = 'disconnected'
        break
      }
      if (exec.status === 'paused') {
        report.status = 'paused'
        break
      }
      if (!exec.needsReplan) break
    }

    // 7. what is verified: the generation, the previewed state and the coverage manifest
    if (report.status === 'done' || report.status === 'paused' || report.status === 'disconnected' || report.status === 'held') {
      if (genState !== 'lower' && genState !== 'unknown') {
        const generation = bumped ? runGeneration : (await readGeneration(cfg.copyRoot)) ?? runGeneration
        // the first completed run hashed everything it copied; a run that audited (or saw the clock change) is the new last audit
        const auditedAt = report.status === 'done' && (audited || rel.auditedAt === undefined) ? now() : rel.auditedAt
        await writeRelationship(cfg.stateDir, {generation, previewConfirmed, rules, ...(auditedAt !== undefined ? {auditedAt} : {})})
        report.generation.committedAt = generation
      }
      if (report.status === 'done' && genState !== 'lower' && genState !== 'unknown') {
        t = Date.now()
        report.manifest = await writeManifest({
          stateDir: cfg.stateDir,
          mainRoot: cfg.mainRoot,
          copyRoot: cfg.copyRoot,
          placeId: cfg.placeId,
          generation: report.generation.committedAt ?? runGeneration,
          holdsKey: cfg.holdsKey ?? '',
          optionsKey: cfg.optionsKey ?? '',
          now: now(),
        })
        timer('manifest', t)
      }
    }
    return report
  } catch (error) {
    if (error instanceof RemoteStopError) return {...report, status: 'disconnected', reason: error.message, remoteStop: {state: error.state, message: error.message}}
    if (error instanceof DisconnectedError || (await rootGone(cfg.copyRoot))) return {...report, status: 'disconnected', reason: error instanceof Error ? error.message : String(error)}
    throw error
  } finally {
    if (report.status === 'crashed') {
      lock.abandon() // a killed process leaves its lock behind
    } else {
      await fs.rm(tmpDir, {recursive: true, force: true}).catch(() => undefined)
      await lock.release().catch(() => undefined)
    }
  }
}

function mergeExec(a: ExecReport | null, b: ExecReport): ExecReport {
  if (!a) return b
  const byKind = {...a.byKind}
  for (const [k, v] of Object.entries(b.byKind)) byKind[k] = (byKind[k] ?? 0) + v
  return {
    status: b.status,
    committed: a.committed + b.committed,
    skipped: [...a.skipped, ...b.skipped].slice(0, 1000),
    skippedCount: a.skippedCount + b.skippedCount,
    failed: [...a.failed, ...b.failed].slice(0, 1000),
    failedCount: a.failedCount + b.failedCount,
    bytes: a.bytes + b.bytes,
    needsReplan: b.needsReplan,
    byKind,
    ...(a.remote && b.remote ? {remote: {uploaded: a.remote.uploaded + b.remote.uploaded, replaced: a.remote.replaced + b.remote.replaced, moved: a.remote.moved + b.remote.moved, trashed: a.remote.trashed + b.remote.trashed, downloaded: a.remote.downloaded + b.remote.downloaded, bytesUp: a.remote.bytesUp + b.remote.bytesUp, bytesDown: a.remote.bytesDown + b.remote.bytesDown}} : b.remote ? {remote: b.remote} : {}),
    ...(b.remoteStop ? {remoteStop: b.remoteStop} : {}),
  }
}

function countLines(file: string): number {
  const read = lineReader(file)
  let n = 0
  try {
    while (read() !== null) n++
  } finally {
    read.close()
  }
  return n
}

/** The content id of a remote object for the planner: an object that is gone reads as absent, a stop ends the run. */
async function remoteHash(remote: RemoteMedia, remotePath: string, staging: string, signal?: AbortSignal): Promise<{contentId: string; size: number}> {
  try {
    return await hashRemote(remote, remotePath, staging, signal)
  } catch (error) {
    const stop = remote.stop(error)
    if (stop) throw new RemoteStopError(stop.state, stop.message, error)
    if (remote.classify(error) === 'gone') throw Object.assign(new Error('The object is no longer on the copy.'), {code: 'ENOENT'})
    throw error
  }
}

/** The user says a copy with a lower generation is the same drive (restored from its own backup): trust its generation again. */
export async function acceptSameDrive(stateDir: string, copyRoot: string): Promise<void> {
  const rel = await readRelationship(stateDir)
  const gen = await readGeneration(copyRoot)
  await writeRelationship(stateDir, {...rel, generation: gen})
}
