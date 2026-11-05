// Separate again (dev-docs/plans/LIBRARY-MERGE-CONTRACT.md section 7) and the end of the 30 days. Files never move, so nothing is deleted
// from this device's folder: T forgets the items this device brought (and clears this device's placements of the rest), the
// items added here since combining are described back into S, the pointer returns to S, and this device leaves T.
import fs from 'node:fs'
import fsp from 'node:fs/promises'
import path from 'node:path'
import type {CombineSeparatePreview, CombineState} from '../../shared/combine.js'
import {createSeparatePlan, type BatchOut, type MapLine, type SeparateChoice, type TargetItem} from '../../shared/core/merge.js'
import {openKeepfinFolder, recordsPath, writeFolderJson, type FolderLibrary} from '../store/keepfinFolder.js'
import {MergeEngine, Stop, mapFileOf} from './engine.js'
import {MergeJournal, type Journal} from './journal.js'
import {eachJsonLine} from './lines.js'
import {HashCache, runPlan, targetItemOf, canonical} from './plan.js'
import {backupDir, loadMap, restoreS} from './remap.js'
import {Scratch} from './scratch.js'
import type {MergeStore} from './types.js'

const PUBLISH_WAIT_MS = 30_000

export class SeparateEngine extends MergeEngine {
  // ---- the preview -----------------------------------------------------------------------------------------------------------

  private async activeJournal(): Promise<MergeJournal> {
    const j = await this.current()
    if (!j || j.stage !== 'active') throw new Error('There is no combined library to separate.')
    const journal = (await this.journalOf(j.mergeId))!
    if (this.expired(journal.state)) throw new Error('The days for separating are over.')
    return journal
  }

  private expired(j: Journal): boolean {
    return !!j.keptCombined || this.deps.now() >= (j.activation?.keepUntil ?? 0)
  }

  /** What Separate again would do, before anything happens. */
  async separatePreview(): Promise<CombineState> {
    const journal = await this.activeJournal()
    const j = journal.state
    const store = this.deps.activeStore()
    await store.flush()
    const index = await loadMap(mapFileOf(j.folder, j.t.records, j.mergeId), path.join(journal.work, 'map-preview.db'))
    try {
      let brought = 0
      const held: {id: string; title?: string}[] = []
      let heldTotal = 0
      let addedSince = 0
      await store.scan((rows) => {
        for (const row of rows) {
          if (row.addActor === j.t.actor && row.addSeq > j.activation!.seq) addedSince++
          if (!index.broughtT(row.id)) continue
          brought++
          if (Object.entries(row.placements).some(([replica, p]) => replica !== j.t.replicaId && p)) {
            heldTotal++
            if (held.length < 200) held.push({id: row.id, ...(typeof row.fields.title === 'string' ? {title: row.fields.title} : {})})
          }
        }
      })
      const preview: CombineSeparatePreview = {
        brought,
        heldElsewhere: held,
        heldElsewhereTotal: heldTotal,
        addedSince,
        deviceName: this.deps.net?.deviceName(j.t.peerId) ?? '',
        unreachable: !(this.deps.net?.reachable(j.t.libraryId) ?? false),
      }
      return {...(await this.state()), separate: preview}
    } finally {
      index.close()
    }
  }

  // ---- running it ------------------------------------------------------------------------------------------------------------

  async separate(args: {keepOnPeer?: string[] | 'all' | 'none'; anyway?: boolean}): Promise<CombineState> {
    const journal = await this.activeJournal()
    const j = journal.state
    const reachable = this.deps.net?.reachable(j.t.libraryId) ?? false
    if (!reachable && !args.anyway) throw new Error(`${this.deps.net?.deviceName(j.t.peerId) || 'The other device'} is not reachable. Separate anyway: it keeps everything this device brought.`)
    const blockers = await this.deps.fences.blockers()
    if (blockers.length) throw new Error(blockers[0])
    await journal.update('separate:start', (d) => {
      d.separate = {step: 'writing', keepOnPeer: args.keepOnPeer ?? 'all', anyway: !!args.anyway && !reachable, writeDone: 0, backDone: 0}
    })
    await journal.advance('separating')
    await this.run(j.mergeId)
    return this.state()
  }

  /** "Keep combined": the days of Separate again end now and the frozen records of S are removed. */
  async keepCombined(): Promise<CombineState> {
    const j = await this.current()
    if (!j || j.stage !== 'active') throw new Error('There is no combined library.')
    const journal = (await this.journalOf(j.mergeId))!
    await this.cleanup(journal)
    return this.state()
  }

  /** Startup: after the days are over the frozen records are removed. */
  async cleanupIfExpired(): Promise<boolean> {
    const j = await this.current()
    if (!j || j.stage !== 'active' || !j.after.profile) return false
    if (!this.expired(j)) return false
    await this.cleanup((await this.journalOf(j.mergeId))!)
    return true
  }

  override async recover(): Promise<void> {
    await super.recover()
    await this.cleanupIfExpired().catch(() => false)
  }

  /** S's frozen records go; T's stay where the pointer names them. The media and S's trash are never touched. */
  private async cleanup(journal: MergeJournal): Promise<void> {
    const j = journal.state
    const opened = await openKeepfinFolder(j.folder)
    if (!opened.ok) throw new Stop('Waiting for the Keepfin folder.')
    if (opened.folder.libraryId !== j.t.libraryId) throw new Error('The combined library is not the one in charge, so the old records are kept.')
    const s = recordsPath(j.folder, j.s.records)
    for (const sub of ['ops', 'checkpoints', 'actors', 'art', 'places', 'membership']) await fsp.rm(path.join(s, sub), {recursive: true, force: true})
    const libraries: FolderLibrary[] = (opened.folder.libraries ?? []).map((l) => (l.libraryId === j.s.libraryId ? {libraryId: l.libraryId, replicaId: l.replicaId, records: l.records, role: 'left' as const, mergeId: j.mergeId} : l))
    await writeFolderJson(j.folder, {format: 2, libraryId: opened.folder.libraryId, replicaId: opened.folder.replicaId, createdAt: opened.folder.createdAt, records: opened.folder.records, libraries})
    await journal.update('kept', (d) => void (d.keptCombined = true))
    await journal.advance('cleaned')
    await fsp.rm(backupDir(this.deps.profile().profileDir, j.mergeId), {recursive: true, force: true}).catch(() => undefined)
    await fsp.rm(journal.work, {recursive: true, force: true}).catch(() => undefined)
    this.active = undefined
    await this.emit()
  }

  protected override async recoverSeparate(journal: MergeJournal): Promise<void> {
    await this.run(journal.id, true).catch(() => undefined)
  }

  protected override async driveSeparate(journal: MergeJournal): Promise<void> {
    for (;;) {
      this.checkPause()
      const j = journal.state
      const sep = j.separate
      if (!sep) throw new Error('The separate has no steps.')
      await this.requireFolder(j)
      await this.fenceEnter()
      switch (sep.step) {
        case 'previewed':
        case 'writing':
          await this.writeIntoT(journal)
          break
        case 'published':
          await this.publish(journal)
          break
        case 'describing':
          await this.describeBack(journal)
          break
        case 'pointer':
          await this.pointerToS(journal)
          break
        case 'membership':
        case 'remap':
          // the profile's device data is replaced before the library opens: the app restarts, recovery finishes it
          if (!this.booting) {
            this.deps.relaunch()
            return
          }
          await this.remapBack(journal)
          break
        case 'done':
          await this.fenceLeave()
          return
      }
    }
  }

  /** Runs [work] with T's store: the live one while the app is running, one of this merge's own at startup (the library is closed then). */
  private async withT<R>(journal: MergeJournal, work: (t: MergeStore) => Promise<R>): Promise<R> {
    if (!this.booting) return work(this.deps.activeStore())
    const t = await this.openT(journal)
    try {
      return await work(t)
    } finally {
      await t.close().catch(() => undefined)
    }
  }

  // ---- 2. T forgets what this device brought ---------------------------------------------------------------------------------

  private async writeIntoT(journal: MergeJournal): Promise<void> {
    const j = journal.state
    if (j.separate!.anyway) {
      // nothing is written or published: the other library keeps everything this device brought
      await journal.update('separate:skip', (d) => void (d.separate!.step = 'describing'))
      return
    }
    await this.withT(journal, async (t) => {
      const sep = () => journal.state.separate!
      if (!sep().writePlan) {
        const dir = path.join(journal.work, 'separate')
        await fsp.rm(dir, {recursive: true, force: true})
        await fsp.mkdir(dir, {recursive: true})
        const plan = await this.planSeparate(journal, t, path.join(dir, 'batches.jsonl'))
        await journal.update('separate:plan', (d) => void (d.separate!.writePlan = plan))
      }
      await this.applyBatchFile(journal, t, path.join(journal.work, 'separate', 'batches.jsonl'), j.t.actor, sep().writePlan!.batches, {
        done: () => journal.state.separate!.writeDone,
        receipt: () => journal.state.separate!.writeBatch,
        write: (point, change) =>
          journal.update(`separate:${point}`, (d) => {
            const s = d.separate!
            change({
              get batchesDone() { return s.writeDone },
              set batchesDone(v: number) { s.writeDone = v },
              get batch() { return s.writeBatch },
              set batch(v) { s.writeBatch = v },
            })
          }),
      })
      await t.flush()
      const seq = await t.lastSeqOf(j.t.actor)
      await journal.update('separate:written', (d) => {
        d.separate!.seq = seq
        d.separate!.step = 'published'
      })
    })
  }

  private async planSeparate(journal: MergeJournal, t: MergeStore, batchesFile: string): Promise<{batches: number; drafts: number}> {
    const j = journal.state
    const index = await loadMap(mapFileOf(j.folder, j.t.records, j.mergeId), path.join(journal.work, 'map-separate.db'))
    const scratch = new Scratch(path.join(journal.work, 'scratch-separate.db'))
    const heldIds = new Set<string>()
    try {
      await t.scan((rows) => {
        scratch.tx(() => {
          for (const row of rows) {
            if (!index.fromT(row.id).length) continue
            scratch.addTarget(targetItemOf(row))
            if (Object.entries(row.placements).some(([replica, p]) => replica !== j.t.replicaId && p)) heldIds.add(row.id)
          }
        })
      })
      const keep = j.separate!.keepOnPeer
      const choices: Record<string, SeparateChoice> = {}
      for (const id of heldIds) choices[id] = keep === 'all' ? 'Keep' : keep === 'none' ? 'Remove' : keep.includes(id) ? 'Keep' : 'Remove'
      let batches = 0
      let drafts = 0
      const fd = fs.openSync(batchesFile, 'w')
      let observed: Record<string, {tags: string[]; heads: string[]}> = {}
      try {
        const plan = createSeparatePlan(j.t.replicaId, (id) => scratch.stores().target(id) as TargetItem, (id) => observed[id] ?? {tags: [], heads: []}, {
          batch(batch: BatchOut) {
            batches++
            drafts += batch.drafts.length
            fs.writeSync(fd, `${JSON.stringify(batch)}\n`)
          },
        })
        let page: MapLine[] = []
        const flush = async () => {
          if (!page.length) return
          const ids = [...new Set(page.map((m) => m.t))]
          observed = (await t.observed({items: ids})).items
          const asked: Record<string, SeparateChoice> = {}
          for (const m of page) if (choices[m.t]) asked[m.t] = choices[m.t]
          plan.items(page, asked)
          page = []
        }
        for await (const line of eachJsonLine<MapLine>(mapFileOf(j.folder, j.t.records, j.mergeId))) {
          page.push(line)
          if (page.length >= 200) await flush()
        }
        await flush()
        plan.finish()
      } finally {
        fs.fsyncSync(fd)
        fs.closeSync(fd)
      }
      return {batches, drafts}
    } finally {
      scratch.close()
      index.close()
    }
  }

  // ---- 3. wait for the other device ------------------------------------------------------------------------------------------

  private async publish(journal: MergeJournal): Promise<void> {
    const j = journal.state
    const net = this.deps.net
    if (!net) throw new Stop('Waiting for the other device.')
    this.setProgress('separating', 'Telling the other device', 0, 0)
    const ok = await net.published(j.t.libraryId, j.t.actor, j.separate!.seq ?? 0, PUBLISH_WAIT_MS)
    if (!ok) throw new Stop(`${net.deviceName(j.t.peerId) || 'The other device'} has not confirmed yet. Keepfin will continue when it is reachable.`)
    await this.fault('separate:published')
    const left = await net.leave(j.t.peerId, j.t.libraryId, j.t.actor).catch(() => false)
    await journal.update('separate:published', (d) => {
      d.separate!.left = left
      d.separate!.step = 'describing'
    })
  }

  // ---- 4. what was added here since combining goes back to S -----------------------------------------------------------------

  private async describeBack(journal: MergeJournal): Promise<void> {
    const j = journal.state
    const dir = path.join(journal.work, 'separate-back')
    const s = await this.deps.openStore({
      root: j.folder,
      records: j.s.records,
      libraryId: j.s.libraryId,
      actor: j.s.actor,
      outboxDir: path.join(journal.work, 's-outbox'),
      statePath: path.join(journal.work, 's-state', 'library-state.v1.db'),
    })
    const hashes = new HashCache(path.join(journal.work, 'hashes.db'))
    try {
      if (!journal.state.separate!.backPlan) {
        await fsp.rm(dir, {recursive: true, force: true})
        await fsp.mkdir(dir, {recursive: true})
        const result = await this.withT(journal, (t) =>
          runPlan(
            {
              deps: this.deps,
              mergeId: `${j.mergeId}:back`,
              actor: j.s.actor,
              replicaId: j.s.replicaId,
              sReplica: j.t.replicaId,
              root: j.folder,
              s: t,
              t: s,
              keep: 'theirs',
              planDir: dir,
              work: journal.work,
              reverse: {bActorInT: j.t.actor, activationSeq: j.activation!.seq},
              onProgress: (label, done, total) => this.setProgress('separating', label, done, total),
            },
            hashes,
          ),
        )
        await journal.update('separate:backplan', (d) => void (d.separate!.backPlan = {batches: result.batches, drafts: result.drafts, items: result.sItems}))
      }
      await this.applyBatchFile(journal, s, path.join(dir, 'batches.jsonl'), j.s.actor, journal.state.separate!.backPlan!.batches, {
        done: () => journal.state.separate!.backDone,
        receipt: () => journal.state.separate!.backBatch,
        write: (point, change) =>
          journal.update(`separate:back:${point}`, (d) => {
            const x = d.separate!
            change({
              get batchesDone() { return x.backDone },
              set batchesDone(v: number) { x.backDone = v },
              get batch() { return x.backBatch },
              set batch(v) { x.backBatch = v },
            })
          }),
      })
      await s.flush()
      await journal.update('separate:described', (d) => void (d.separate!.step = 'pointer'))
    } finally {
      hashes.close()
      await s.close().catch(() => undefined)
    }
  }

  // ---- 5. the pointer goes back to S -----------------------------------------------------------------------------------------

  private async pointerToS(journal: MergeJournal): Promise<void> {
    const j = journal.state
    const opened = await openKeepfinFolder(j.folder)
    if (!opened.ok) throw new Stop('Waiting for the Keepfin folder.')
    if (opened.folder.libraryId !== j.s.libraryId || (opened.folder.records ?? '') !== j.s.records) {
      const libraries: FolderLibrary[] = (opened.folder.libraries ?? []).map((l) =>
        l.libraryId === j.s.libraryId ? {libraryId: l.libraryId, replicaId: l.replicaId, records: l.records, role: 'active' as const, since: new Date(this.deps.now()).toISOString(), mergeId: j.mergeId} : l.libraryId === j.t.libraryId ? {libraryId: l.libraryId, replicaId: l.replicaId, records: l.records, role: 'left' as const, mergeId: j.mergeId} : l,
      )
      await this.fault('separate:folderjson:before')
      await writeFolderJson(j.folder, {format: 2, libraryId: j.s.libraryId, replicaId: j.s.replicaId, createdAt: opened.folder.createdAt, records: j.s.records, libraries})
      await this.fault('separate:folderjson:after')
      const back = await openKeepfinFolder(j.folder)
      if (!back.ok || back.folder.libraryId !== j.s.libraryId || (back.folder.records ?? '') !== j.s.records) throw new Error('The Keepfin folder did not accept the switch back.')
    }
    await journal.update('separate:pointer', (d) => void (d.separate!.step = 'remap'))
  }

  // ---- 7. the device-local data comes back ----------------------------------------------------------------------------------

  private async remapBack(journal: MergeJournal): Promise<void> {
    const j = journal.state
    const profile = this.deps.profile()
    await restoreS({profileDir: profile.profileDir, mergeId: j.mergeId})
    await this.fault('separate:restored')
    await this.deps.setProfileLibrary(j.profileId, {libraryId: j.s.libraryId, actorId: j.s.actor})
    await this.fault('separate:profile')
    await journal.update('separate:done', (d) => void (d.separate!.step = 'done'))
    await journal.advance('separated')
  }
}

export {canonical}
