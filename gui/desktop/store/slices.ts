// Gives the thread back to the event loop at a steady pace: a loop over a big library yields when its slice has used its time
// instead of after a fixed count of items, so every turn of the loop is about the same short length whatever the machine's
// speed and whatever each item costs, and a small library is not slowed by yields it does not need.
const turn = () => new Promise<void>((resolve) => setImmediate(() => resolve()))

export class Slicer {
  private began = performance.now()

  /** @param budgetMs How long a turn may run before the loop yields. */
  constructor(private readonly budgetMs = 10) {}

  /** True when the current turn has used its time. Cheap enough to ask every few items. */
  get due(): boolean {
    return performance.now() - this.began >= this.budgetMs
  }

  /** Gives the thread back and starts a new turn. */
  async yield(): Promise<void> {
    await turn()
    this.began = performance.now()
  }

  /** Yields if the turn is over. Ask every 64 items or so, not for every item. */
  async tick(): Promise<void> {
    if (this.due) await this.yield()
  }
}
