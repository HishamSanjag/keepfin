// Combining S into T on this device (dev-docs/plans/LIBRARY-MERGE-CONTRACT.md sections 2 to 6). B (this device) joins A's library T into a
// records root of its own Keepfin folder, describes its files to T in receipted batches, verifies, writes the folder.json pointer
// and hands over to startup recovery for the device-local remap and the profile switch. Media files are never touched.
import fs from 'node:fs'
import fsp from 'node:fs/promises'
import path from 'node:path'
import readline from 'node:readline'
import {DatabaseSync} from 'node:sqlite'
import type {CombinePreview, CombineState} from '../../shared/combine.js'
import {remainingDrafts, type BatchOut, type MapLine, type ReviewLine} from '../../shared/core/merge.js'
import {JoinError} from '../net/join.js'
import {remapToT} from './remap.js'
import {readJson, writeJsonAtomic} from '../store/atomic.js'
import {openKeepfinFolder, recordsPath, writeFolderJson, type FolderLibrary} from '../store/keepfinFolder.js'
import {listJournals, MergeJournal, TERMINAL, workDir, type Journal, type PreviewNumbers, type Receipt} from './journal.js'
import {readJsonLines} from './lines.js'
import {HashCache, runPlan, type PlanInput, type PlanResult} from './plan.js'
import type {MergeDeps, MergeStore} from './types.js'

const DAY = 24 * 60 * 60 * 1000

/** Thrown to leave a run quietly: the journal says why (paused or finished for now). */
export class Stop extends Error {
  constructor(readonly why: string) {
    super(why)
    this.name = 'Stop'
  }
}

export const mapFileOf = (folder: string, tRecords: string, mergeId: string) => path.join(recordsPath(folder, tRecords), 'merge', mergeId, 'map.jsonl')

export class MergeEngine {
  /** The run in progress (one at a time on a device). */
  protected running?: Promise<void>
  protected pauseRequested = false
  protected progress?: CombineState['progress']
  protected lastError?: string
  protected active?: MergeJournal
  protected phaseNow: CombineState['phase'] = 'idle'
  private stopJoin?: AbortController
  /** Startup recovery runs before the library opens: it may rewrite the device-local data of the profile. */
  protected booting = false

  constructor(readonly deps: MergeDeps) {}

  // ---- state -----------------------------------------------------------------------------------------------------------------

  async current(): Promise<Journal | undefined> {
    return (await listJournals(this.deps.deviceDir)).find((j) => !TERMINAL.includes(j.stage))
  }

  async state(): Promise<CombineState> {
    const j = this.active?.state ?? (await this.current())
    if (!j) return {available: true, phase: 'idle'}
    const out: CombineState = {available: true, phase: this.phaseFor(j), mergeId: j.mergeId, deviceId: j.t.peerId, deviceName: this.deps.net?.deviceName(j.t.peerId) ?? '', libraryName: j.t.name}
    if (this.progress) out.progress = this.progress
    if (j.preview) out.preview = this.previewOf(j)
    if (j.paused) out.paused = j.paused.reason
    if (this.lastError) out.error = this.lastError
    if (j.stage === 'active' || j.stage === 'separating') {
      const until = j.activation?.keepUntil ?? 0
      out.combined = {since: new Date(j.activation?.at ?? 0).toISOString(), until: new Date(until).toISOString(), libraryName: j.t.name, canSeparate: j.stage === 'active' && this.deps.now() < until && !j.keptCombined}
    }
    return out
  }

  private phaseFor(j: Journal): CombineState['phase'] {
    if (j.paused) return 'paused'
    if (this.lastError) return 'failed'
    if (j.stage === 'active') return 'done'
    if (j.stage === 'separating') return 'separating'
    if (j.stage === 'joined') return this.running ? this.phaseNow : j.started ? 'planning' : 'ready'
    if (j.stage === 'planned') return 'joining'
    if (j.stage === 'verified' || j.stage === 'activating') return 'switching'
    if (j.stage === 'describing') return this.phaseNow === 'verifying' ? 'verifying' : 'applying'
    return 'idle'
  }

  private previewOf(j: Journal): CombinePreview {
    const p = j.preview!
    return {
      deviceId: j.t.peerId,
      deviceName: this.deps.net?.deviceName(j.t.peerId) ?? '',
      libraryName: j.t.name,
      items: p.sItems,
      ...(p.tItems !== undefined ? {otherItems: p.tItems} : {}),
      added: p.added,
      linked: p.linked,
      keptDuplicates: p.keptDuplicate,
      missingFiles: p.missing,
      differing: p.differing,
      playlists: p.playlists,
      spaces: p.spaces,
      review: p.review as CombinePreview['review'],
      reviewTotal: p.reviewTotal,
      separateDays: this.deps.keepDays ?? 30,
    }
  }

  protected async emit(): Promise<void> {
    this.deps.push(await this.state())
  }

  protected setProgress(phase: CombineState['phase'], label: string, done: number, total: number): void {
    this.phaseNow = phase
    this.progress = {done, total, label}
    void this.emit().catch(() => undefined)
  }

  protected fault(point: string, detail?: unknown): void | Promise<void> {
    return this.deps.faults?.(point, detail)
  }

  // ---- starting --------------------------------------------------------------------------------------------------------------

  /** Joins T in beside S and makes the review (a dry run). Nothing about S changes. */
  async preview(deviceId: string, keep: 'mine' | 'theirs' = 'theirs'): Promise<CombineState> {
    const existing = await this.current()
    if (existing) {
      if (existing.t.peerId !== deviceId) throw new Error('Another library is being combined. Finish or cancel that first.')
      await this.run(existing.mergeId)
      return this.state()
    }
    const net = this.deps.net
    if (!net) throw new Error('Devices on the network are not available.')
    const offer = net.offer(deviceId)
    if (!offer) {
      const name = net.deviceName(deviceId) || 'That device'
      // connected but offering nothing: it has no library in a Keepfin folder yet, it shares this device's library already, or it
      // does not let this device read its library
      if (net.online?.(deviceId)) {
        const gap = net.gap?.(deviceId)
        if (gap === 'held') throw new Error(`${name} already shares this library with this device, so there is nothing to combine.`)
        if (gap === 'denied') throw new Error(`${name} does not let this device read its library. On ${name}, open Settings › Devices and allow this device to see the library, then try again.`)
        if (gap === 'none') throw new Error(`${name} has no Keepfin folder yet, so its library cannot be combined. On ${name}, open Settings › Storage & copies and create the Keepfin folder, then try again.`)
        if (gap === 'unshared') throw new Error(`${name} has not shared its library with this device yet. On ${name}, open Settings › Devices, open this device and choose Share, then try again.`)
      }
      throw new Error('That device is not offering its library right now. Make sure it is turned on and connected.')
    }
    const already = net.holds(offer.libraryId)
    if (already) throw new Error(`This device already has ${already}.`)
    const blockers = await this.deps.fences.blockers()
    if (blockers.length) throw new Error(blockers[0])
    const profile = this.deps.profile()
    const mergeId = this.deps.newId()
    const records = `libraries/${offer.libraryId}`
    const journal = await MergeJournal.create(
      this.deps.deviceDir,
      {
        v: 1,
        mergeId,
        stage: 'planned',
        createdAt: this.deps.now(),
        profileId: profile.profileId,
        folder: profile.folder,
        deviceName: this.deps.deviceName,
        s: {libraryId: profile.libraryId, replicaId: profile.replicaId, actor: profile.actorId, records: ''},
        t: {libraryId: offer.libraryId, replicaId: '', actor: '', records, name: offer.name, peerId: deviceId, hostActor: ''},
        keep,
        batchesDone: 0,
        after: {},
      },
      (point) => this.fault(point),
    )
    this.active = journal
    await this.run(mergeId)
    return this.state()
  }

  /** The person pressed Combine on the review: describe, apply, verify, switch. */
  async start(deviceId: string, keep: 'mine' | 'theirs'): Promise<CombineState> {
    const j = await this.current()
    if (!j || j.t.peerId !== deviceId) {
      await this.preview(deviceId, keep)
      return this.start(deviceId, keep)
    }
    const journal = (await this.journalOf(j.mergeId))!
    if (journal.state.stage === 'planned' || journal.state.stage === 'joined') {
      const blockers = await this.deps.fences.blockers()
      if (blockers.length) throw new Error(blockers[0])
      await journal.update('start', (d) => {
        d.keep = keep
        d.started = true
      })
    }
    await this.run(j.mergeId)
    return this.state()
  }

  async pause(): Promise<CombineState> {
    this.pauseRequested = true
    this.stopJoin?.abort()
    if (this.running) await this.running.catch(() => undefined)
    return this.state()
  }

  async resume(): Promise<CombineState> {
    const j = await this.current()
    if (j) await this.run(j.mergeId)
    return this.state()
  }

  /** Leaves before Combine was pressed: the staged records of T are removed (nothing else was written). */
  async cancel(): Promise<CombineState> {
    const j = await this.current()
    if (!j) return this.state()
    if (this.running) {
      this.pauseRequested = true
      await this.running.catch(() => undefined)
    }
    const journal = (await this.journalOf(j.mergeId))!
    if (journal.state.stage !== 'planned' && journal.state.stage !== 'joined') throw new Error('This can no longer be cancelled. Use Separate again instead.')
    // only records that no folder.json names are removed
    const opened = await openKeepfinFolder(j.folder)
    const named = opened.ok && (opened.folder.records === j.t.records || opened.folder.libraries?.some((l) => l.records === j.t.records))
    if (!named) await fsp.rm(recordsPath(j.folder, j.t.records), {recursive: true, force: true})
    await journal.advance('cancelled')
    await fsp.rm(journal.work, {recursive: true, force: true}).catch(() => undefined)
    this.active = undefined
    this.progress = undefined
    this.lastError = undefined
    await this.fenceLeave()
    await this.emit()
    return this.state()
  }

  // ---- the run ---------------------------------------------------------------------------------------------------------------

  protected async journalOf(mergeId: string): Promise<MergeJournal | undefined> {
    if (this.active && this.active.id === mergeId) return this.active
    const opened = await MergeJournal.open(this.deps.deviceDir, mergeId, (point) => this.fault(point))
    if (opened) this.active = opened
    return opened
  }

  protected fenced = false
  protected async fenceEnter(): Promise<void> {
    if (this.fenced) return
    this.fenced = true
    await this.deps.fences.enter()
  }
  protected async fenceLeave(): Promise<void> {
    if (!this.fenced) return
    this.fenced = false
    await this.deps.fences.leave()
  }

  /** Drives the journal forward until it is done, paused, or waiting for the person. */
  run(mergeId: string, boot = false): Promise<void> {
    if (this.running) return this.running
    this.booting = boot
    this.pauseRequested = false
    this.lastError = undefined
    const run = (async () => {
      const journal = await this.journalOf(mergeId)
      if (!journal) throw new Error('That combine is not known on this device.')
      try {
        if (!boot || !journal.state.paused) await journal.resume()
        await this.drive(journal)
        if (boot && journal.state.stage === 'active') await this.afterActive(journal)
      } catch (error) {
        if (error instanceof Stop) {
          if (error.why) await journal.pause(error.why, this.deps.now()).catch(() => undefined)
        } else if (error instanceof JoinError && error.code === 'cancelled') {
          await journal.pause('Paused.', this.deps.now()).catch(() => undefined)
        } else {
          this.lastError = error instanceof Error ? error.message : String(error)
          throw error
        }
      } finally {
        this.progress = undefined
        this.phaseNow = 'idle'
        this.running = undefined
        await this.emit().catch(() => undefined)
      }
    })()
    this.running = run
    return run
  }

  protected checkPause(): void {
    if (this.pauseRequested) throw new Stop('Paused.')
  }

  /** The folder must be there: a missing drive or card pauses, it never rolls anything back. */
  protected async requireFolder(j: Journal): Promise<void> {
    const opened = await openKeepfinFolder(j.folder)
    if (!opened.ok) throw new Stop('Waiting for the Keepfin folder.')
  }

  async drive(journal: MergeJournal): Promise<void> {
    for (;;) {
      this.checkPause()
      const j = journal.state
      await this.requireFolder(j)
      switch (j.stage) {
        case 'planned':
          await this.stageJoin(journal)
          break
        case 'joined':
          if (!j.started) {
            if (!j.preview) await this.review(journal)
            return
          }
          await this.fenceEnter()
          await this.describe(journal)
          break
        case 'describing':
          await this.fenceEnter()
          await this.describe(journal)
          break
        case 'verified':
        case 'activating':
          await this.fenceEnter()
          await this.activate(journal)
          break
        case 'separating':
          await this.driveSeparate(journal)
          return
        case 'active':
          await this.fenceLeave()
          // the profile is switched by startup recovery, before the library opens: the app restarts onto T
          if (!this.booting && !j.after.profile) this.deps.relaunch()
          return
        default:
          return
      }
    }
  }

  // ---- staging T -------------------------------------------------------------------------------------------------------------

  private async stageJoin(journal: MergeJournal): Promise<void> {
    const j = journal.state
    const net = this.deps.net
    if (!net) throw new Stop('Waiting for the other device.')
    this.setProgress('joining', 'Taking in the other library', 0, 0)
    this.stopJoin = new AbortController()
    let joined
    try {
      joined = await net.join(j.t.peerId, j.t.libraryId, j.t.name, {root: j.folder, records: j.t.records, recordFile: path.join(journal.work, 'join.json')}, (segments) => this.setProgress('joining', 'Taking in the other library', segments, 0), this.stopJoin.signal)
    } catch (error) {
      if (error instanceof JoinError && (error.code === 'network' || error.code === 'incomplete')) throw new Stop(error.message)
      throw error
    } finally {
      this.stopJoin = undefined
    }
    await this.fault('join:done')
    await journal.update('joined', (d) => {
      d.t.replicaId = joined.replicaId
      d.t.actor = joined.actor
      d.t.hostActor = joined.hostActor
    })
    await journal.advance('joined')
  }

  protected async openT(journal: MergeJournal): Promise<MergeStore> {
    const j = journal.state
    return this.deps.openStore({
      root: j.folder,
      records: j.t.records,
      libraryId: j.t.libraryId,
      actor: j.t.actor,
      outboxDir: path.join(journal.work, 't-outbox'),
      statePath: path.join(journal.work, 't-state', 'library-state.v1.db'),
    })
  }

  private planInput(journal: MergeJournal, s: MergeStore, t: MergeStore, planDir?: string): PlanInput {
    const j = journal.state
    return {
      deps: this.deps,
      mergeId: j.mergeId,
      actor: j.t.actor,
      replicaId: j.t.replicaId,
      sReplica: j.s.replicaId,
      root: j.folder,
      s,
      t,
      keep: j.keep,
      planDir,
      work: journal.work,
      sArtDir: path.join(recordsPath(j.folder, j.s.records), 'art'),
      onProgress: (label, done, total) => this.setProgress(planDir ? 'planning' : 'reviewing', label, done, total),
    }
  }

  /** The dry run: what would happen, nothing written to T or S. */
  private async review(journal: MergeJournal): Promise<void> {
    const t = await this.openT(journal)
    const hashes = new HashCache(path.join(journal.work, 'hashes.db'))
    try {
      this.setProgress('reviewing', 'Looking at both libraries', 0, 0)
      const result = await runPlan(this.planInput(journal, this.deps.sStore(), t), hashes)
      await journal.update('preview', (d) => {
        d.preview = previewOf(result, this.deps.now())
      })
    } finally {
      hashes.close()
      await t.close().catch(() => undefined)
    }
  }

  // ---- describing S into T ---------------------------------------------------------------------------------------------------

  private async describe(journal: MergeJournal): Promise<void> {
    const planDir = path.join(journal.work, 'plan')
    const t = await this.openT(journal)
    const hashes = new HashCache(path.join(journal.work, 'hashes.db'))
    try {
      if (!journal.state.plan) {
        await fsp.rm(planDir, {recursive: true, force: true})
        await fsp.mkdir(planDir, {recursive: true})
        this.setProgress('planning', 'Describing this library', 0, 0)
        const result = await runPlan(this.planInput(journal, this.deps.sStore(), t, planDir), hashes)
        await this.fault('plan:written')
        await journal.update('plan', (d) => {
          d.plan = {batches: result.batches, drafts: result.drafts, sItems: result.sItems, routesBatch: d.keep === 'mine'}
          d.preview = previewOf(result, this.deps.now())
        })
      }
      if (journal.state.stage === 'joined') await journal.advance('describing')
      await this.applyBatches(journal, t)
      this.setProgress('verifying', 'Making sure nothing is missing', 0, 0)
      await t.flush()
      await this.finishRecords(journal, t)
      await journal.advance('verified')
    } finally {
      hashes.close()
      await t.close().catch(() => undefined)
    }
  }

  /** Never imports twice: the receipt names the sequence number before the batch, a restart counts what landed (contract 3). */
  protected async applyBatches(journal: MergeJournal, t: MergeStore): Promise<void> {
    const j = journal.state
    await this.applyBatchFile(journal, t, path.join(journal.work, 'plan', 'batches.jsonl'), j.t.actor, j.plan!.batches, {
      done: () => journal.state.batchesDone,
      receipt: () => journal.state.batch,
      write: (point, change) =>
        journal.update(point, (d) => {
          const cursor = { get batchesDone() { return d.batchesDone }, set batchesDone(v: number) { d.batchesDone = v }, get batch() { return d.batch }, set batch(v) { d.batch = v } }
          change(cursor)
        }),
    })
  }

  protected async applyBatchFile(
    journal: MergeJournal,
    t: MergeStore,
    file: string,
    actor: string,
    total: number,
    cursor: {done(): number; receipt(): Receipt | undefined; write(point: string, change: (c: {batchesDone: number; batch: Receipt | undefined}) => void): Promise<void>},
  ): Promise<void> {
    const lines = readline.createInterface({input: fs.createReadStream(file), crlfDelay: Infinity})
    let k = 0
    try {
      for await (const line of lines) {
        if (!line.trim()) continue
        const index = k++
        if (index < cursor.done()) continue
        this.checkPause()
        const batch = JSON.parse(line) as BatchOut
        const receipt = cursor.receipt()
        let drafts = batch.drafts
        if (receipt && receipt.index === index) {
          const landed = (await t.lastSeqOf(actor)) - receipt.actorSeqBefore
          if (landed < 0 || landed > batch.drafts.length || receipt.draftsHash !== batch.draftsHash) throw new Error('The records do not match the batch that was being written. Nothing was changed further.')
          drafts = remainingDrafts(batch, landed)
        } else {
          const before = await t.lastSeqOf(actor)
          await cursor.write(`receipt:${index}`, (c) => {
            c.batch = {index, actorSeqBefore: before, drafts: batch.drafts.length, draftsHash: batch.draftsHash}
          })
        }
        await this.fault(`batch:apply:${index}`, {drafts: drafts.length})
        if (drafts.length) await t.apply(drafts as never)
        await this.fault(`batch:applied:${index}`)
        await cursor.write(`batch:${index}`, (c) => {
          c.batchesDone = index + 1
          c.batch = undefined
        })
        this.setProgress('applying', 'Writing the changes', index + 1, total)
      }
    } finally {
      lines.close()
    }
  }
  /** The lasting map and the artwork copies, then the check that every S item is accounted for. */
  private async finishRecords(journal: MergeJournal, t: MergeStore): Promise<void> {
    const j = journal.state
    const plan = path.join(journal.work, 'plan')
    const tRecords = recordsPath(j.folder, j.t.records)
    const mapFile = mapFileOf(j.folder, j.t.records, j.mergeId)
    if (!j.mapDone) {
      await fsp.mkdir(path.dirname(mapFile), {recursive: true})
      const temp = `${mapFile}.${process.pid}.tmp`
      await fsp.copyFile(path.join(plan, 'map.jsonl'), temp)
      const handle = await fsp.open(temp, 'r+')
      await handle.sync()
      await handle.close()
      await fsp.rename(temp, mapFile)
      await writeJsonAtomic(path.join(path.dirname(mapFile), 'merge.json'), {mergeId: j.mergeId, sLibraryId: j.s.libraryId, sReplicaId: j.s.replicaId, tLibraryId: j.t.libraryId, tReplicaId: j.t.replicaId, bActor: j.t.actor, startedAt: j.createdAt})
      await this.fault('map:written')
      await journal.update('map', (d) => void (d.mapDone = true))
    }
    if (!j.artDone) {
      const art = path.join(plan, 'art.jsonl')
      if (await fsp.stat(art).then(() => true, () => false)) {
        for await (const line of readline.createInterface({input: fs.createReadStream(art), crlfDelay: Infinity})) {
          if (!line.trim()) continue
          const copy = JSON.parse(line) as {source: string; target: string}
          const target = path.join(tRecords, ...copy.target.split('/'))
          if (await fsp.stat(target).then(() => true, () => false)) continue
          await fsp.mkdir(path.dirname(target), {recursive: true})
          const temp = `${target}.${process.pid}.tmp`
          await fsp.copyFile(copy.source, temp).catch(() => undefined)
          if (await fsp.stat(temp).then(() => true, () => false)) await fsp.rename(temp, target)
        }
      }
      await this.fault('art:copied')
      await journal.update('art', (d) => void (d.artDone = true))
    }
    // verify: a map line for every S item, every planned T item exists, every placement exists or is listed
    const mapped = new Set<string>()
    const verify = new DatabaseSync(path.join(journal.work, 'verify.db'))
    verify.exec('PRAGMA journal_mode = OFF; DROP TABLE IF EXISTS ids; CREATE TABLE ids (id TEXT PRIMARY KEY) WITHOUT ROWID;')
    try {
      const insert = verify.prepare('INSERT OR IGNORE INTO ids (id) VALUES (?)')
      await t.scan((rows) => {
        verify.exec('BEGIN')
        for (const row of rows) insert.run(row.id)
        verify.exec('COMMIT')
      })
      const has = verify.prepare('SELECT 1 AS x FROM ids WHERE id = ?')
      let lines = 0
      for await (const line of readline.createInterface({input: fs.createReadStream(mapFile), crlfDelay: Infinity})) {
        if (!line.trim()) continue
        const m = JSON.parse(line) as MapLine
        lines++
        mapped.add(m.s)
        if (!has.get(m.t)) throw new Error('An item of this library is missing from the other library after the batches. Nothing was switched.')
      }
      if (lines !== j.plan!.sItems) throw new Error('Not every item of this library is accounted for. Nothing was switched.')
    } finally {
      verify.close()
    }
    const listed = new Set<string>()
    const reviewFile = path.join(plan, 'review.jsonl')
    for await (const line of readline.createInterface({input: fs.createReadStream(reviewFile), crlfDelay: Infinity})) {
      if (!line.trim()) continue
      const r = JSON.parse(line) as ReviewLine
      if (r.kind === 'missingFile') listed.add(r.s)
    }
    let missing = listed.size
    const paths = path.join(plan, 'paths.jsonl')
    for await (const line of readline.createInterface({input: fs.createReadStream(paths), crlfDelay: Infinity})) {
      if (!line.trim()) continue
      const row = JSON.parse(line) as {s: string; path: string}
      const file = this.deps.files?.resolve(row.path) ?? path.join(j.folder, ...row.path.split('/'))
      if (!(await fsp.stat(file).then((s) => s.isFile(), () => false)) && !listed.has(row.s)) missing++
    }
    await journal.update('verified', (d) => void (d.verified = {mapped: mapped.size, missing}))
  }

  // ---- activation ------------------------------------------------------------------------------------------------------------

  private async activate(journal: MergeJournal): Promise<void> {
    const j = journal.state
    if (j.stage === 'verified') {
      const t = await this.openT(journal)
      let seq: number
      try {
        await t.flush()
        seq = await t.lastSeqOf(j.t.actor)
      } finally {
        await t.close().catch(() => undefined)
      }
      const at = this.deps.now()
      await journal.update('activation', (d) => {
        d.activation = {seq, at, keepUntil: at + (this.deps.keepDays ?? 30) * DAY}
      })
      await journal.advance('activating')
    }
    this.setProgress('switching', 'Switching to the combined library', 0, 0)
    const now = journal.state
    const opened = await openKeepfinFolder(now.folder)
    if (!opened.ok) throw new Stop('Waiting for the Keepfin folder.')
    const done = opened.folder.libraryId === now.t.libraryId && opened.folder.records === now.t.records
    if (!done) {
      const libraries: FolderLibrary[] = (opened.folder.libraries ?? [{libraryId: opened.folder.libraryId, replicaId: opened.folder.replicaId, records: opened.folder.records ?? '', role: 'active' as const}]).map((l) =>
        l.libraryId === now.s.libraryId ? {...l, role: 'previous' as const, keepUntil: new Date(now.activation!.keepUntil).toISOString(), mergeId: now.mergeId} : l.role === 'active' ? {...l, role: 'previous' as const} : l,
      )
      libraries.push({libraryId: now.t.libraryId, replicaId: now.t.replicaId, records: now.t.records, role: 'active', since: new Date(now.activation!.at).toISOString(), mergeId: now.mergeId})
      await this.fault('activate:folderjson:before')
      await writeFolderJson(now.folder, {format: 2, libraryId: now.t.libraryId, replicaId: now.t.replicaId, createdAt: opened.folder.createdAt, records: now.t.records, libraries})
      await this.fault('activate:folderjson:after')
      const back = await openKeepfinFolder(now.folder)
      if (!back.ok || back.folder.libraryId !== now.t.libraryId || back.folder.records !== now.t.records) throw new Error('The Keepfin folder did not accept the switch. The old library is still in charge.')
    }
    await journal.advance('active')
    await this.fault('active')
  }

  /**
   * After the switch of the pointer: the device-local data follows (remap), then the profile names T. Only startup recovery runs
   * this, before the library opens, so nothing is writing the files it replaces; each part can be repeated.
   */
  async afterActive(journal: MergeJournal): Promise<void> {
    const j = journal.state
    const profile = this.deps.profile()
    if (!j.after.deviceData) {
      await remapToT({profileDir: profile.profileDir, mergeId: j.mergeId, mapFile: mapFileOf(j.folder, j.t.records, j.mergeId), work: journal.work, artDir: this.deps.artDir})
      await this.fault('remap:device')
      await journal.update('after:device', (d) => void (d.after.deviceData = true))
    }
    if (!journal.state.after.profile) {
      await this.deps.setProfileLibrary(j.profileId, {libraryId: j.t.libraryId, actorId: j.t.actor})
      await this.fault('remap:profile')
      await journal.update('after:profile', (d) => void (d.after.profile = true))
    }
  }

  /** Separating is the subclass's business (separate.ts). */
  protected async driveSeparate(_journal: MergeJournal): Promise<void> {}
  protected async recoverSeparate(_journal: MergeJournal): Promise<void> {}

  /** Startup: finishes what a stopped combine left, offline. Steps that need the other device pause and wait for combineResume. */
  async recover(): Promise<void> {
    const j = await this.current()
    if (!j) return
    const journal = (await this.journalOf(j.mergeId))!
    const s = j.stage
    if (s === 'planned' || (s === 'joined' && !j.started)) return
    if (s === 'separating') return this.recoverSeparate(journal)
    await this.run(j.mergeId, true).catch(() => undefined)
  }

  // ---- reading ---------------------------------------------------------------------------------------------------------------

  /** The review file of a plan, a page at a time (the whole list; the state carries the first entries). */
  async reviewPage(mergeId: string, offset: number, limit: number): Promise<ReviewLine[]> {
    const file = path.join(workDir(this.deps.deviceDir, mergeId), 'plan', 'review.jsonl')
    const out: ReviewLine[] = []
    let n = 0
    for await (const line of readline.createInterface({input: fs.createReadStream(file), crlfDelay: Infinity})) {
      if (!line.trim()) continue
      if (n++ < offset) continue
      out.push(JSON.parse(line) as ReviewLine)
      if (out.length >= limit) break
    }
    return out
  }
}

export function previewOf(result: PlanResult, at: number): PreviewNumbers {
  return {
    sItems: result.sItems,
    tItems: result.tItems,
    linked: result.linked,
    added: result.added,
    keptDuplicate: result.keptDuplicate,
    missing: result.missing,
    differing: result.differing,
    playlists: result.playlists,
    spaces: result.spaces,
    review: result.review.map((r) => ({kind: r.kind, s: r.s, ...(r.t ? {t: r.t} : {}), ...(r.related ? {related: r.related} : {}), ...(r.field ? {field: r.field} : {}), ...(r.title ? {title: r.title} : {})})),
    reviewTotal: result.reviewTotal,
    at,
  }
}

export {readJson, readJsonLines}
