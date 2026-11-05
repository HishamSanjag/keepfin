// Undo for library actions. An action the window wants to be able to take back is sent with an `undoId`; the main process records
// how to reverse it from the state at that moment (never from a list the page held), and `libraryUndo` with the same id runs
// that reversal on the state as it is then, so anything changed since stays. Records live in memory only: an Undo is a notice
// on screen for a few seconds, not a history.
import type {UndoResult} from '../../shared/library.js'

export type UndoRun = () => Promise<UndoResult>

const MAX_RECORDS = 100
const KEEP_MS = 30 * 60_000

export class UndoShelf {
  private readonly records = new Map<string, {run: UndoRun; at: number; holds: string[]; expire?: () => Promise<void>}>()

  constructor(private readonly now: () => number = Date.now) {}

  /** The id the window sent, or undefined when it sent none (or something that is not an id). */
  static idOf(value: unknown): string | undefined {
    return typeof value === 'string' && value.length >= 8 && value.length <= 80 && /^[\w-]+$/.test(value) ? value : undefined
  }

  /**
   * Remembers how to reverse an action. A request without a valid undoId records nothing. [expire] runs when the Undo is given up
   * ([expire] below, or the record ages out): Delete for good erases its files then.
   */
  record(undoId: unknown, run: UndoRun, holds: string[] = [], expire?: () => Promise<void>) {
    const id = UndoShelf.idOf(undoId)
    if (!id) return
    const at = this.now()
    for (const [key, entry] of this.records) if (at - entry.at > KEEP_MS) this.drop(key)
    while (this.records.size >= MAX_RECORDS) this.drop(this.records.keys().next().value as string)
    this.records.set(id, {run, at, holds, expire})
  }

  private drop(id: string) {
    const record = this.records.get(id)
    this.records.delete(id)
    void record?.expire?.().catch(() => undefined)
  }

  /** The notice of [undoId] went without Undo: the record goes, and what it kept for Undo is given up (Delete for good erases it). */
  async expire(undoId: unknown): Promise<void> {
    const id = UndoShelf.idOf(undoId)
    const record = id ? this.records.get(id) : undefined
    if (!id || !record) return
    this.records.delete(id)
    await record.expire?.()
  }

  /** Item ids whose Keepfin trash folders an Undo still needs (the 30-day purge leaves them alone). */
  held(): Set<string> {
    return new Set([...this.records.values()].flatMap((record) => record.holds))
  }

  /**
   * Items left the Keepfin trash another way (the Trash page restored or erased them): a record that held only those items goes, so a
   * late Undo says it can no longer be undone instead of doing it twice. A record that also holds other items stays, minus these.
   */
  forgetHolding(itemIds: readonly string[]) {
    const gone = new Set(itemIds)
    if (!gone.size) return
    for (const [key, record] of this.records) {
      if (!record.holds.some((id) => gone.has(id))) continue
      record.holds = record.holds.filter((id) => !gone.has(id))
      if (!record.holds.length) this.records.delete(key)
    }
  }

  /** Reverses the action once. A second Undo of the same action finds nothing to do. */
  async undo(undoId: unknown): Promise<UndoResult> {
    const id = UndoShelf.idOf(undoId)
    const record = id ? this.records.get(id) : undefined
    if (!id || !record) throw new Error('This can no longer be undone.')
    this.records.delete(id)
    return record.run()
  }
}
