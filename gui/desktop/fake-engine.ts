// Scriptable in-memory Engine for queue/notification tests. Not imported by the app.
import fs from 'node:fs/promises'
import path from 'node:path'
import type {
  EngineStatus,
  InspectResult,
  MediaInfo,
  PlaylistEntry,
} from '../shared/contracts.js'
import type {
  DownloadEvents,
  DownloadOutcome,
  DownloadTask,
  DiscoveryResult,
  Engine,
  EngineContext,
} from './engine/index.js'

export function fakeMedia(
  url: string,
  patch: Partial<MediaInfo> = {},
): MediaInfo {
  return {
    url,
    title: 'A video',
    provider: 'Example',
    engine: 'yt-dlp',
    video: [],
    audio: [],
    subtitles: [],
    chapters: 0,
    ...patch,
  }
}

export type DownloadScript = (
  task: DownloadTask,
  ctx: EngineContext,
  events: DownloadEvents,
) => Promise<DownloadOutcome>

/** Writes `<stem>.mp4` and finishes. */
export const finishImmediately: DownloadScript = async (task) => {
  const file = path.join(task.folder, `${task.stem}.mp4`)
  await fs.mkdir(task.folder, {recursive: true})
  await fs.writeFile(file, 'saved')
  return {file, files: []}
}

export class FakeEngine implements Engine {
  statusListeners = new Set<() => void>()
  inspectCalls: string[] = []
  inspectActive = 0
  inspectPeak = 0
  inspectDelay = 0
  inspectResult: (url: string) => InspectResult = (url) => ({
    type: 'media',
    media: fakeMedia(url),
  })
  script: DownloadScript = finishImmediately
  /** Set by tests that exercise lyrics; absent like an engine without the feature. */
  addLyrics?: Engine['addLyrics']
  findLyrics?: Engine['findLyrics']
  downloads: DownloadTask[] = []
  discarded: Pick<DownloadTask, 'folder' | 'stem'>[] = []
  discoveryPages: {start: number; end: number}[] = []
  discoveryTotal = 450
  discoveryResult: Partial<DiscoveryResult> = {}
  cleared = 0
  status_: EngineStatus = {tools: [], busy: false}

  status() {
    return this.status_
  }
  onStatus(listener: () => void) {
    this.statusListeners.add(listener)
    return () => void this.statusListeners.delete(listener)
  }
  setStatus(status: EngineStatus) {
    this.status_ = status
    for (const listener of this.statusListeners) listener()
  }
  async inspect(url: string, ctx: EngineContext) {
    this.inspectCalls.push(url)
    this.inspectPeak = Math.max(this.inspectPeak, ++this.inspectActive)
    try {
      await new Promise<void>((resolve, reject) => {
        const timer = setTimeout(resolve, this.inspectDelay)
        ctx.signal?.addEventListener('abort', () => {
          clearTimeout(timer)
          reject(ctx.signal!.reason)
        })
      })
      return this.inspectResult(url)
    } finally {
      this.inspectActive--
    }
  }
  async discover(
    url: string,
    page: {start: number; end: number},
    ctx: EngineContext,
    onEntry: (entry: PlaylistEntry) => void,
    onMeta: (meta: {title?: string; uploader?: string}) => void,
  ): Promise<DiscoveryResult> {
    this.discoveryPages.push(page)
    onMeta({title: 'Mix', uploader: 'Someone'})
    for (
      let position = page.start;
      position <= Math.min(page.end, this.discoveryTotal);
      position++
    )
      onEntry({
        url: `${url.split('?')[0]}/item-${position}`,
        title: `Item ${position}`,
        position,
      })
    return {truncated: this.discoveryTotal > page.end, ...this.discoveryResult}
  }
  async download(
    task: DownloadTask,
    ctx: EngineContext,
    events: DownloadEvents,
  ) {
    this.downloads.push(task)
    return this.script(task, ctx, events)
  }
  async discard(task: Pick<DownloadTask, 'folder' | 'stem' | 'format'>) {
    this.discarded.push(task)
  }
  updates = 0
  async update() {
    this.updates++
    return this.status_
  }
  async autoUpdate() {}
  async clearCache() {
    this.cleared++
  }
}

/** A download that runs until its signal aborts. */
export function hangUntilAborted(
  onStart?: (events: DownloadEvents) => void,
): DownloadScript {
  return (task, ctx, events) =>
    new Promise((_, reject) => {
      ctx.signal?.throwIfAborted()
      events.onStage('downloading')
      onStart?.(events)
      ctx.signal?.addEventListener('abort', () =>
        reject(ctx.signal!.reason ?? new Error('aborted')),
      )
    })
}
