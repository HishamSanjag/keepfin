// Compaction of this device's segments (dev-docs/plans/KEEPFIN-DATA-COMPACTION.md): the rounds, the merge proof, the removal
// gate and the transfer leases. The store owns the timing and the state; this class reads it through [CompactionHost].
import fs from 'node:fs/promises'
import path from 'node:path'
import {decodeSegment, planCompaction, subsumedSegments, type CompactionFile, type SyncMembership} from '../../shared/core/sync.js'
import {listSegments, peekSegmentSigner, segmentBytes, segmentName, signedSegmentBytes, writeBytesDurable, type SegmentFile} from './segmentFiles.js'
import type {CompactStats, StoreOptions} from './store.js'

/** What the compaction reads from the store that owns it. */
export interface CompactionHost {
  readonly closed: boolean
  readonly options: StoreOptions
  readonly opsDir: string
  readonly actor: string
  readonly deviceId: string | null
  readonly processed: Set<string>
  readonly problems: Map<string, string>
  readonly retries: Map<string, {attempts: number; at: number}>
  readonly equivocations: Record<string, number>
  readonly sql: {membership(): SyncMembership}
  flush(): Promise<void>
  scheduleCompact(delay: number): void
}

export class Compactor {
  /** Own segment files written since the last compaction round (decides whether a flush asks for one). */
  filesSinceCompact = 0
  lastCompactAt = 0
  /** Segment transfers in flight (token to expiry timer): compaction never removes a file while one is open. */
  private readonly leases = new Map<string, NodeJS.Timeout>()
  private removalGate?: Promise<void>

  constructor(private readonly host: CompactionHost) {}

  /** Starts a segment transfer (a peer is being sent files, or pushed to): compaction removes no file while one is open. */
  async beginTransfer(): Promise<string> {
    await this.removalGate
    const token = this.host.options.newId()
    const timer = setTimeout(() => this.leases.delete(token), 600_000) // a transfer that never ends cannot block compaction for ever
    timer.unref?.()
    this.leases.set(token, timer)
    return token
  }

  endTransfer(token: string): void {
    clearTimeout(this.leases.get(token))
    this.leases.delete(token)
  }

  private async waitForTransfers(): Promise<boolean> {
    const until = Date.now() + (this.host.options.leaseWaitMs ?? 10_000)
    while (this.leases.size) {
      if (Date.now() >= until) return false
      await new Promise((resolve) => setTimeout(resolve, 25))
    }
    return true
  }

  /** The actors whose files are left alone: equivocations, unreadable or still-arriving files. */
  private actorsWithProblems(): Set<string> {
    const bad = new Set(Object.keys(this.host.equivocations))
    for (const key of [...this.host.problems.keys(), ...this.host.retries.keys()]) bad.add(key.slice(0, key.indexOf('/')))
    return bad
  }

  /** The files membership names (the last segment an actor had when membership started): never merged or removed. */
  private namedFiles(segments: readonly SegmentFile[]): Set<string> {
    const named = new Set<string>()
    let frozen: Record<string, {seq: number}> = {}
    try {
      const info = this.host.sql.membership()
      if (info.active) frozen = info.frozen
    } catch {
      return new Set(segments.map((segment) => segment.file)) // cannot tell: touch nothing
    }
    for (const segment of segments) if (frozen[segment.actor] && segment.last === frozen[segment.actor].seq) named.add(segment.file)
    return named
  }

  async compactRound(): Promise<CompactStats> {
    const stats: CompactStats = {merged: 0, removed: 0, dropped: 0}
    const done = (skipped?: CompactStats['skipped']) => {
      if (skipped) stats.skipped = skipped
      this.host.options.onCompact?.(stats)
      return stats
    }
    if (this.host.closed) return done('closed')
    await this.host.flush()
    if (!(await this.waitForTransfers())) {
      this.host.scheduleCompact(120_000)
      return done('busy')
    }
    this.lastCompactAt = Date.now()
    this.filesSinceCompact = 0
    let segments = await listSegments(this.host.opsDir)
    stats.dropped = await this.dropSubsumed(segments)
    if (stats.dropped) segments = await listSegments(this.host.opsDir)
    const bad = this.actorsWithProblems()
    if (bad.has(this.host.actor)) return done('blocked')
    const own = segments.filter((segment) => segment.actor === this.host.actor)
    if (own.length < 2) return done()
    const named = this.namedFiles(own)
    const current = this.host.deviceId
    const files: CompactionFile[] = []
    for (const segment of own) {
      // a file over 2,000 ops is never merged, so who signed it does not matter and it is not opened
      const big = segment.last - segment.first + 1 > 2000
      const signer = big ? current : await peekSegmentSigner(segment.file)
      const size = (await fs.stat(segment.file).catch(() => undefined))?.size
      files.push({actor: segment.actor, first: segment.first, last: segment.last, bytes: size ?? 0, signer: signer ?? null, ok: size !== undefined && signer !== undefined, named: named.has(segment.file)})
    }
    for (const merge of planCompaction(files, current)) {
      if (this.host.closed) break
      const outcome = await this.applyMerge(merge.first, merge.last, merge.inputs.map((i) => own[i]))
      if (outcome === 'busy') {
        this.host.scheduleCompact(120_000)
        return done('busy')
      }
      if (outcome === 'failed') break
      stats.merged++
      stats.removed += merge.inputs.length
    }
    return done()
  }

  /** Writes the one file that holds [inputs], proves it, then removes the inputs. */
  private async applyMerge(first: number, last: number, inputs: SegmentFile[]): Promise<'merged' | 'busy' | 'failed'> {
    const {libraryId} = this.host.options
    const lines: string[] = []
    for (const input of inputs) {
      let value: ReturnType<typeof decodeSegment>
      try {
        value = decodeSegment(await fs.readFile(input.file), libraryId)
      } catch {
        return 'failed'
      }
      if (!value.ok || value.actor !== this.host.actor || value.first !== input.first || value.last !== input.last || value.signer !== this.host.deviceId) return 'failed'
      for (const line of value.ops) lines.push(line)
    }
    if (lines.length !== last - first + 1) return 'failed'
    const target = path.join(this.host.opsDir, this.host.actor, segmentName(first, last))
    const holdsAll = (value: ReturnType<typeof decodeSegment>) =>
      value.ok && value.actor === this.host.actor && value.first === first && value.last === last && value.signer === this.host.deviceId && value.ops.length === lines.length && value.ops.every((line, i) => line === lines[i])
    const existing = await fs.readFile(target).catch(() => undefined)
    let wrote = false
    if (existing) {
      // left by an interrupted round: usable only if it is exactly the merge
      if (!holdsAll(decodeSegment(existing, libraryId))) return 'failed'
    } else {
      const bytes = this.host.options.signer ? signedSegmentBytes(libraryId, this.host.actor, lines, first, this.host.options.signer).bytes : segmentBytes(libraryId, this.host.actor, lines, first)
      if (bytes.length > 16 * 1024 * 1024) return 'failed'
      await writeBytesDurable(target, bytes)
      wrote = true
    }
    const key = `${this.host.actor}/${segmentName(first, last)}`
    this.host.processed.add(key) // its ops are held already: a rescan need not read it again
    await this.host.options.onCompactStep?.('published', {first, last, removed: 0})
    // read back what is on disk, not what was written
    let proven = false
    try {
      proven = holdsAll(decodeSegment(await fs.readFile(target), libraryId))
    } catch {
      proven = false
    }
    if (!proven) {
      this.host.processed.delete(key)
      if (wrote) await fs.rm(target, {force: true}).catch(() => undefined)
      return 'failed'
    }
    await this.host.options.onCompactStep?.('verified', {first, last, removed: 0})
    const removed = await this.removeSegments(inputs.filter((input) => input.file !== target))
    return removed === null ? 'busy' : 'merged'
  }

  /** Removes covered or merged segment files, unless a transfer is open (then none: the next round does it). Null when busy. */
  private async removeSegments(files: readonly SegmentFile[]): Promise<number | null> {
    if (!files.length) return 0
    if (this.leases.size) return null
    let open!: () => void
    this.removalGate = new Promise<void>((resolve) => (open = resolve))
    let removed = 0
    try {
      for (const file of files) {
        await this.host.options.onCompactStep?.('removing', {first: file.first, last: file.last, removed})
        await fs.rm(file.file, {force: true})
        this.host.processed.delete(`${file.actor}/${file.name}`)
        removed++
      }
    } finally {
      this.removalGate = undefined
      open()
    }
    return removed
  }

  /**
   * Removes the files that a larger file of the same actor in this folder already holds, after checking op for op that it does
   * (dev-docs/plans/KEEPFIN-DATA-COMPACTION.md R5). Never across actors, never for an actor with a problem, never a file that
   * membership names. Returns how many were removed.
   */
  async dropSubsumed(segments: readonly SegmentFile[]): Promise<number> {
    if (segments.length < 2) return 0
    const bad = this.actorsWithProblems()
    const named = this.namedFiles(segments)
    const files: CompactionFile[] = segments.map((segment) => ({actor: segment.actor, first: segment.first, last: segment.last, bytes: 0, signer: null, ok: !this.host.problems.has(`${segment.actor}/${segment.name}`) && !this.host.retries.has(`${segment.actor}/${segment.name}`), named: named.has(segment.file)}))
    const found = subsumedSegments(files, [...bad])
    if (!found.length) return 0
    const covers = new Map<string, ReturnType<typeof decodeSegment> | null>()
    const cover = async (segment: SegmentFile) => {
      if (!covers.has(segment.file)) covers.set(segment.file, await fs.readFile(segment.file).then((bytes) => decodeSegment(bytes, this.host.options.libraryId), () => null))
      return covers.get(segment.file)
    }
    const verified: SegmentFile[] = []
    for (const {index, by} of found) {
      const small = segments[index]
      const large = await cover(segments[by])
      const inner = await fs.readFile(small.file).then((bytes) => decodeSegment(bytes, this.host.options.libraryId), () => null)
      if (!large?.ok || !inner?.ok || large.actor !== inner.actor || inner.first < large.first || inner.last > large.last) continue
      let same = true
      for (let seq = inner.first; seq <= inner.last; seq++) {
        if (inner.ops[seq - inner.first] !== large.ops[seq - large.first]) {
          same = false
          break
        }
      }
      if (same) verified.push(small)
    }
    return (await this.removeSegments(verified)) ?? 0
  }
}
