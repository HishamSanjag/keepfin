// Download progress for the desktop runner. Parsing the engine's output lines (progress template, byte weighting
// of the streams, post-processing phases) is the shared core's job (gui/shared/core/progress.ts); this file adds
// what is desktop-only: the visible wording of each phase and the files the engine reports it wrote.
import path from 'node:path'
import {FILE_PREFIX} from '../../shared/policy.js'
import {ProgressCore, type PhaseKey, type ProgressChange} from '../../shared/core/progress.js'

export type RunnerProgress = {
  percent: number
  downloadedBytes?: number
  totalBytes?: number
  speed?: number
  eta?: number
}

const phaseText: Record<Exclude<PhaseKey, 'extract-audio'>, string> = {
  merge: 'Merging',
  'embed-cover': 'Embedding thumbnail',
  'convert-cover': 'Converting thumbnail',
  metadata: 'Writing metadata',
  'embed-subs': 'Embedding subtitles',
  'convert-subs': 'Converting subtitles',
  sponsorblock: 'Checking SponsorBlock',
  'modify-chapters': 'Removing segments',
  'split-chapters': 'Splitting chapters',
  fixup: 'Fixing container',
  remux: 'Remuxing',
  'convert-video': 'Converting video',
}

const writtenFile = /^\[(?:info|SplitChapters)\] .*?(?:Writing video (?:subtitles|thumbnail.*?|description|metadata as JSON) to|Destination): (.+)$/

/** Parses the engine's output line by line; the shared core does the arithmetic, this adds desktop wording and files. */
export class ProgressParser {
  file?: string
  readonly files: string[] = []
  private core: ProgressCore

  constructor(private options: {formatSizes?: Record<string, number>; audioTarget?: string} = {}) {
    this.core = new ProgressCore(options.formatSizes)
  }

  /** Returns what changed: 'progress', 'phase' or undefined. */
  line(raw: string): ProgressChange {
    const line = raw.trim()
    if (line.startsWith(FILE_PREFIX)) {
      const file = line.slice(FILE_PREFIX.length).trim()
      if (path.isAbsolute(file)) this.file = file
      return
    }
    if (line.startsWith('[info]') || line.startsWith('[SplitChapters]')) {
      const written = line.match(writtenFile)
      if (written && path.isAbsolute(written[1].trim())) {
        const file = written[1].trim().replace(/^"|"$/g, '')
        if (!this.files.includes(file)) this.files.push(file)
      }
    }
    return this.core.accept(line)
  }

  /** The phase key of the post-processing step that is running (shared with Android), if any. */
  get phaseKey(): PhaseKey | undefined {
    return this.core.snapshot().phase ?? undefined
  }

  /** The visible wording of the running post-processing step. */
  get phase(): string | undefined {
    const key = this.phaseKey
    if (!key) return undefined
    if (key === 'extract-audio') {
      const target = this.options.audioTarget
      return target && target !== 'original' ? `Converting to ${target.toUpperCase()}` : 'Extracting audio'
    }
    return phaseText[key]
  }

  get processing(): boolean {
    return this.core.snapshot().processing
  }

  progress(): RunnerProgress {
    const s = this.core.snapshot()
    return {
      percent: s.percent,
      downloadedBytes: s.downloadedBytes || undefined,
      totalBytes: s.totalBytes ?? undefined,
      speed: s.speed ?? undefined,
      eta: s.eta ?? undefined,
    }
  }
}
