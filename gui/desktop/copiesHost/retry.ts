// When a copy's run did not finish, and how long to wait before the next try.
import type {RunSummary} from '../copies/placeRun.js'

/** A run that ended without a verdict on the library: the copy could not be reached, or another run held it. */
export function runUnfinished(summary: RunSummary): boolean {
  return summary.status === 'disconnected' || summary.status === 'busy' || summary.status === 'crashed'
}

/** 2, 4, 8, 16, then 30 minutes between tries. */
export function retryDelayMs(attempts: number): number {
  return Math.min(30, 2 ** Math.max(1, attempts)) * 60_000
}

/** Plans the next try of a run that did not finish: one more attempt, after [retryDelayMs]. */
export function planRetry(rt: {retry?: {attempts: number; at: number; reason: string}}, reason: string, now: number): void {
  const attempts = (rt.retry?.attempts ?? 0) + 1
  rt.retry = {attempts, at: now + retryDelayMs(attempts), reason}
}

export function retryText(name: string, reason: string, at: number): string {
  const time = new Date(at).toLocaleTimeString([], {hour: '2-digit', minute: '2-digit'})
  return `${name} could not be finished: ${reason.replace(/\.$/, '')}. Will try again at ${time}.`
}
