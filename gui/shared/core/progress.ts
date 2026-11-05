// Adapter between the shared core (core/, Kotlin Multiplatform; built to build/core-js by `npm run core:build`)
// and the desktop download runner. This is the only file that imports the core's progress package.
//
// The core parses the engine's output lines into transfer progress and a processing-phase key. It runs for
// every output line of every download, so `accept` is a single call that returns what changed and the
// snapshot is read only when something did.
import {JsProgressParser, progressPrefix} from '../../../build/core-js/keepfin-core.mjs'

/** Phase keys: the visible wording belongs to each app. */
export type PhaseKey =
  | 'merge'
  | 'extract-audio'
  | 'embed-cover'
  | 'convert-cover'
  | 'metadata'
  | 'embed-subs'
  | 'convert-subs'
  | 'sponsorblock'
  | 'modify-chapters'
  | 'split-chapters'
  | 'fixup'
  | 'remux'
  | 'convert-video'

export type ProgressChange = 'progress' | 'phase' | undefined

export type CoreProgress = {
  /** 0-99.5 and never decreasing. */
  percent: number
  /** Null until a transfer line was seen. */
  downloadedBytes: number | null
  totalBytes: number | null
  speed: number | null
  eta: number | null
  phase: PhaseKey | null
  /** The extension of an audio conversion (`mp3`), when the engine named a destination. */
  phaseDetail: string | null
  processing: boolean
}

/** The prefix of the desktop engine's progress template (`KEEPFIN-P|`). The args policy owns the template itself. */
export const PROGRESS_LINE_PREFIX: string = progressPrefix('desktop')

export class ProgressCore {
  private parser: JsProgressParser

  constructor(streamSizes: Record<string, number> = {}) {
    const ids = Object.keys(streamSizes)
    this.parser = new JsProgressParser('desktop', null, ids, Float64Array.from(ids, (id) => streamSizes[id]))
  }

  /** Feeds one output line and says what it changed. */
  accept(line: string): ProgressChange {
    const change = this.parser.accept(line)
    return change === 1 ? 'progress' : change === 2 ? 'phase' : undefined
  }

  snapshot(): CoreProgress {
    const s = this.parser.snapshot()
    return {
      percent: s.percent,
      downloadedBytes: s.downloadedBytes ?? null,
      totalBytes: s.totalBytes ?? null,
      speed: s.speed ?? null,
      eta: s.eta ?? null,
      phase: (s.phase ?? null) as PhaseKey | null,
      phaseDetail: s.phaseDetail ?? null,
      processing: s.processing,
    }
  }
}
