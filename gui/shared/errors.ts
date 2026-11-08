// Downloader stderr → short, actionable messages that never name the underlying tools. Pure.
//
// The rules (what a failure text means, its message, retry kind and tool-name scrubbing) live in the shared core
// (core/, package app.keepfin.core.errors) and reach this module through gui/shared/core/errors.ts. This file keeps the
// shapes the desktop code already uses.
import {
  analyze,
  botCheckBackoffMs,
  backoffMs,
  detail as coreDetail,
  lastErrorLine as coreLastErrorLine,
  rateLimitBackoffMs,
  restrictedMessage,
  restrictedMusicMessage,
  scrub,
  suggestsGallery as coreSuggestsGallery,
  unavailableMessage,
} from './core/errors.js'

export type ErrorAction = 'signIn' | 'updateEngine' | 'retry' | 'tryAnother' | 'none'

/**
 * Whether an automatic retry can help. 'network' = transient connection/site trouble;
 * 'engine' = extractor/engine failure that an engine update may fix. Undefined = permanent.
 */
export type RetryKind = 'network' | 'engine'

/** Special handling classes: scheduling (rate limits) and one-shot bot checks. */
export type ErrorKind = 'rateLimited' | 'botCheck'

export type FriendlyError = {
  /** Set for failures that change how retries are paced. */
  kind?: ErrorKind
  /** Custom per-attempt retry delays (ms); the number of entries is the retry limit. */
  backoff?: readonly number[]
  message: string
  action: ErrorAction
  /** True when retrying the same download automatically is worthwhile. */
  retryable: boolean
  /** True when the failure points at the engine itself, so an update should precede a retry. */
  engine: boolean
  /** Last meaningful engine line, kept for support/debugging. */
  detail?: string
}

/** Rate limits clear slowly: wait 1, 3, then 5 minutes. */
export const rateLimitBackoff = rateLimitBackoffMs
/** A bot check sometimes clears by itself: retry once after two minutes. */
export const botCheckBackoff = botCheckBackoffMs

/** The generic, ambiguous "video unavailable" message (could be transient or a regional restriction). */
export {unavailableMessage}
/** Deterministic regional restriction, music and non-music variants. */
export {restrictedMusicMessage, restrictedMessage}

/** The most informative line: last ERROR line, else last non-empty line. */
export const lastErrorLine: (stderr: string) => string | undefined = coreLastErrorLine

/**
 * Removes tool names and raw log prefixes from text that may reach the UI.
 * Internal identifiers keep their names; only display strings pass through here.
 */
export function scrubToolNames(text: string): string {
  return scrub(text)
}

/**
 * Full error text for a "Details" view: the engine's last lines with tool names scrubbed.
 * Falls back to the user-facing message when no raw output was kept.
 */
export function errorDetailText(error: unknown): string | undefined {
  const stderr = (error as {stderr?: unknown} | null)?.stderr
  const raw = typeof stderr === 'string' && stderr.trim() ? stderr : error instanceof Error ? error.message : String(error ?? '')
  return coreDetail(raw, 3000) || undefined
}

export function friendlyError(stderr: string): FriendlyError {
  const result = analyze(String(stderr ?? ''))
  const kind = result.retry === 'rateLimited' || result.retry === 'botCheck' ? result.retry : undefined
  return {
    message: result.message,
    action: result.action,
    retryable: result.retry !== 'permanent',
    engine: result.retry === 'engine',
    detail: result.detail,
    ...(kind ? {kind} : {}),
    ...(kind ? {backoff: kind === 'rateLimited' ? rateLimitBackoff : botCheckBackoff} : {}),
  }
}

/** True when the gallery engine might succeed where the main engine could not (the shared core's rule). */
export const suggestsGallery = coreSuggestsGallery

/** Delays before automatic retries 1..3 (ms): 5 s, 15 s, 30 s. */
export const retryBackoff = backoffMs

/**
 * Decides whether a failed download is retried automatically. Errors that already carry a
 * classification (engine errors) keep it; anything else is classified from its message.
 */
export type RetryPolicy = {retryable: boolean; engine: boolean; kind?: ErrorKind; backoff?: readonly number[]}

export function retryPolicy(error: unknown): RetryPolicy {
  const known = error as {retryable?: unknown; engine?: unknown; kind?: unknown; backoff?: unknown} | null
  const extras = (source: {kind?: unknown; backoff?: unknown} | null | undefined) => ({
    ...(source?.kind === 'rateLimited' || source?.kind === 'botCheck' ? {kind: source.kind as ErrorKind} : {}),
    ...(Array.isArray(source?.backoff) && source.backoff.every((n: unknown) => typeof n === 'number')
      ? {backoff: source.backoff as number[]}
      : {}),
  })
  if (known && typeof known.retryable === 'boolean')
    return {retryable: known.retryable, engine: known.engine === true, ...extras(known)}
  const text = error instanceof Error ? error.message : String(error ?? '')
  const friendly = friendlyError(text)
  return {retryable: friendly.retryable, engine: friendly.engine, ...extras(friendly)}
}

/**
 * True for the ambiguous YouTube "video unavailable" failure that no more specific rule
 * (removed, private, age, region...) explains. Only these are probed for a regional restriction.
 */
export function isGenericUnavailable(stderr: string): boolean {
  const text = String(stderr ?? '')
  return /video unavailable|this video is (not available|unavailable)/i.test(text) && analyze(text).errorClass === 'unavailable'
}
