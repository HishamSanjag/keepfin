// Copy runs take turns: at most two run at once and the others wait in the order they were asked for (CopiesHost.syncPlace).
export class RunQueue {
  private active = 0
  private readonly order: string[] = []
  private readonly jobs = new Map<string, () => Promise<void>>()

  constructor(private readonly slots = 2) {}

  /** Waiting for its turn (not running). */
  has(id: string): boolean {
    return this.order.includes(id)
  }

  /** Every slot is taken: a run added now waits. */
  get full(): boolean {
    return this.active >= this.slots
  }

  /** Takes a waiting run out of the line (a pause). */
  drop(id: string): void {
    const at = this.order.indexOf(id)
    if (at >= 0) this.order.splice(at, 1)
    this.jobs.delete(id)
  }

  /** Lines [run] up; it starts when a slot is free and frees the slot when it ends, however it ends. */
  add(id: string, run: () => Promise<void>): void {
    this.jobs.set(id, async () => {
      this.active++
      try {
        await run()
      } finally {
        this.active--
        this.pump()
      }
    })
    this.order.push(id)
    this.pump()
  }

  private pump(): void {
    while (this.active < this.slots && this.order.length) {
      const id = this.order.shift()!
      const job = this.jobs.get(id)
      this.jobs.delete(id)
      if (job) void job()
    }
  }
}
