// Adapter between the shared core (core/, Kotlin Multiplatform; built to build/core-js by `npm run core:build`)
// and the failure-text helpers the desktop code uses. This is the only file that imports the core's errors
// package: gui/shared/errors.ts re-exports from here, so the core's generated API stays behind one boundary.
//
// The core decides what a failure text means (class, retry kind, message, tool-name scrubbing, details, retry pacing).
import {
  errorsAllMessages,
  errorsAnalyze,
  errorsConstants,
  errorsDetail,
  errorsLastErrorLine,
  errorsRetryNext,
  errorsScrub,
} from '../../../build/core-js/keepfin-core.mjs'

export type CorePlatform = 'desktop' | 'android'
export type ErrorClassName =
  | 'botCheck' | 'ageRestricted' | 'signInRequired' | 'rateLimited' | 'regionBlocked' | 'notStarted' | 'liveEnded' | 'removed'
  | 'unavailable' | 'unsupported' | 'drm' | 'formatUnavailable' | 'extractorBroken' | 'forbidden' | 'diskFull' | 'permission'
  | 'processorMissing' | 'processing' | 'cookiesUnreadable' | 'network' | 'unknown'
export type CoreRetryKind = 'permanent' | 'retryable' | 'engine' | 'rateLimited' | 'botCheck'
export type CoreAction = 'signIn' | 'updateEngine' | 'retry' | 'tryAnother' | 'none'

export type Analysis = {
  errorClass: ErrorClassName
  retry: CoreRetryKind
  action: CoreAction
  /** One clear sentence with the next step; never names an external tool. */
  message: string
  /** False when no rule matched the text (class `unknown`, retry `engine`). */
  recognized: boolean
  /** The last meaningful line, cleaned and scrubbed; absent when there was no text. */
  detail?: string
}

export type RetryDecision = {retry: number; delayMs: number; engineRelated: boolean; rateLimited: boolean; refreshEngine: boolean}

/** Reads one failure text. `exhausted` picks the wording used once automatic retries are used up. */
export function analyze(text: string, platform: CorePlatform = 'desktop', exhausted = false): Analysis {
  const result = errorsAnalyze(text, platform, exhausted)
  return {
    errorClass: result.errorClass as ErrorClassName,
    retry: result.retry as CoreRetryKind,
    action: result.action as CoreAction,
    message: result.message,
    recognized: result.recognized,
    ...(result.detail != null ? {detail: result.detail} : {}),
  }
}

/** Removes tool names and raw log prefixes from text that may reach the UI. */
export const scrub = (text: string, platform: CorePlatform = 'desktop'): string => errorsScrub(text, platform)

/** The engine's last lines for a "Details" view, tool names scrubbed (empty when there is nothing to show). */
export const detail = (text: string, limit = 3000, platform: CorePlatform = 'desktop'): string => errorsDetail(text, limit, platform)

/** The most informative line: the last ERROR line, else the last non-empty line. */
export const lastErrorLine = (text: string): string | undefined => errorsLastErrorLine(text) ?? undefined

/** What to do after a failed attempt; undefined when it is permanent or the three retries are used. */
export function nextRetry(retriesUsed: number, text: string, crashed = false, platform: CorePlatform = 'desktop'): RetryDecision | undefined {
  const d = errorsRetryNext(retriesUsed, text, crashed, platform)
  return d == null
    ? undefined
    : {retry: d.retry, delayMs: d.delayMs, engineRelated: d.engineRelated, rateLimited: d.rateLimited, refreshEngine: d.refreshEngine}
}

/** Every fixed message the core can produce for a platform. */
export const allMessages = (platform: CorePlatform): string[] => [...errorsAllMessages(platform)]

const constants = errorsConstants()
export const maxRetries: number = constants.maxRetries
export const backoffMs: readonly number[] = [...constants.backoffMs]
export const rateLimitBackoffMs: readonly number[] = [...constants.rateLimitBackoffMs]
export const botCheckBackoffMs: readonly number[] = [...constants.botCheckBackoffMs]
export const unavailableMessage: string = constants.unavailableMessage
export const restrictedMusicMessage: string = constants.restrictedMusicMessage
export const restrictedMessage: string = constants.restrictedMessage
