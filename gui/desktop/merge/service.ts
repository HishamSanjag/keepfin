// The combine* requests of the renderer, mapped onto the engine. Every answer is the current CombineState. A long step (the review,
// Combine, Separate again) runs on in the background after its checks pass: the page follows it through the state pushes.
import {emptyCombineState, type CombineArgs, type CombineState} from '../../shared/combine.js'
import {SeparateEngine} from './separate.js'

const SETTLE_MS = 400

export class CombineService {
  constructor(private readonly engine: SeparateEngine | undefined, private readonly why = 'Combining libraries needs the Keepfin folder and devices on the network.') {}

  /** Runs [work]; an error that comes quickly (a refusal) is thrown, a long run answers with the state and goes on. */
  private async soon(work: () => Promise<unknown>): Promise<CombineState> {
    const engine = this.engine!
    const run = work()
    run.catch(() => undefined)
    const early = await Promise.race([run.then(() => 'done' as const), new Promise<'running'>((resolve) => setTimeout(() => resolve('running'), SETTLE_MS))])
    if (early === 'done') await run
    return engine.state()
  }

  async handle(op: string, args: CombineArgs): Promise<CombineState> {
    const engine = this.engine
    if (!engine) return emptyCombineState(this.why)
    const device = () => {
      if (typeof args.deviceId !== 'string' || !args.deviceId) throw new Error('Choose the device to combine with.')
      return args.deviceId
    }
    const keep = args.keep === 'mine' ? 'mine' : 'theirs'
    switch (op) {
      case 'combineState':
        return engine.state()
      case 'combinePreview':
        return this.soon(() => engine.preview(device(), keep))
      case 'combineStart':
        return this.soon(() => engine.start(device(), keep))
      case 'combinePause':
        return engine.pause()
      case 'combineResume':
        return this.soon(() => engine.resume())
      case 'combineCancel':
        return engine.cancel()
      case 'combineSeparatePreview':
        return engine.separatePreview()
      case 'combineSeparate': {
        const keepOnPeer = args.keepOnPeer === 'none' ? 'none' : Array.isArray(args.keepOnPeer) ? args.keepOnPeer.filter((id): id is string => typeof id === 'string') : 'all'
        return this.soon(() => engine.separate({keepOnPeer, anyway: args.anyway === true}))
      }
      case 'combineKeep':
        return engine.keepCombined()
      default:
        throw new Error('Unknown request.')
    }
  }
}
