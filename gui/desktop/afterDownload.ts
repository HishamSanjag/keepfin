// What happens when a download finishes, on the desktop: the shared core's plan (gui/shared/core/after.ts, dev-docs/plans/AFTER-DOWNLOAD.md) run
// through one interface, one function per step. The queue's adapter is gui/desktop/manager/afterSteps.ts; the library supplies the saving
// steps (`savingPlan`); tests use an in-memory adapter.
import {planAfter, type AfterFacts, type AfterKind} from '../shared/core/after.js'

export type {AfterFacts}

export type AfterDownloadSteps = {
  /** Lyrics into the file, before it is saved. */
  lyrics(): Promise<void>
  /** Saves the file into the library; returns the library items it became. */
  save(): Promise<string[]>
  /** Keeps a music video beside its song; returns the song's item, or undefined when the song cannot take it (the plan then saves it as an ordinary download). */
  attachVideo(): Promise<string[] | undefined>
  /** A music video that finished before its song stays where it is until the song is adopted. */
  waitForSong(): Promise<void>
  joinPlaylist(items: string[]): Promise<void>
  cover(): Promise<void>
  notifyDone(): Promise<void>
  /** "Already in ...". */
  notifyExisting(): Promise<void>
  /** Videos that waited for this song are kept beside it; a song that asked for its video looks for it. */
  settleVideos(): Promise<void>
  /** The job completes as "Not saved" (with Save again). */
  markNotSaved(error: unknown): Promise<void>
}

/** The steps that put the file in the library: what "Save again" runs. */
export const SAVING: readonly AfterKind[] = ['attachVideo', 'save', 'joinPlaylist']

/** The saving steps of a library that is off (or absent): nothing to save, nothing to join. */
export const noSaving: Pick<AfterDownloadSteps, 'save' | 'attachVideo' | 'waitForSong' | 'joinPlaylist' | 'settleVideos'> = {
  save: async () => [],
  attachVideo: async () => undefined,
  waitForSong: async () => {},
  joinPlaylist: async () => {},
  settleVideos: async () => {},
}

/**
 * Runs the plan for `facts` on `steps`. `only` limits it to those kinds, `items` are the library items the job already is (an existing
 * recording). A failing step does what the plan says: `notSaved` marks the job, `saveOrdinary` runs the fallback save, `continue` goes on.
 * An aborted `signal` (the download was cancelled) stops the run before the next step and is thrown.
 */
export async function runAfterDownload(
  facts: AfterFacts,
  steps: Partial<AfterDownloadSteps>,
  options: {only?: readonly AfterKind[]; items?: string[]; signal?: AbortSignal} = {},
) {
  let items = options.items ?? []
  let fellBack = false
  for (const step of planAfter(facts)) {
    if ((options.only && !options.only.includes(step.kind)) || (step.fallback && !fellBack)) continue
    options.signal?.throwIfAborted()
    try {
      switch (step.kind) {
        case 'lyrics': await need(steps.lyrics, step.kind)(); break
        case 'save': items = await need(steps.save, step.kind)(); break
        case 'attachVideo': {
          const attached = await need(steps.attachVideo, step.kind)()
          fellBack = !attached
          if (attached) items = attached
          break
        }
        case 'waitForSong': await need(steps.waitForSong, step.kind)(); break
        case 'joinPlaylist': await need(steps.joinPlaylist, step.kind)(items); break
        case 'cover': await need(steps.cover, step.kind)(); break
        case 'notify': await need(steps.notifyDone, step.kind)(); break
        case 'notifyExisting': await need(steps.notifyExisting, step.kind)(); break
        case 'settleVideos': await need(steps.settleVideos, step.kind)(); break
      }
    } catch (error) {
      if (options.signal?.aborted) throw error
      if (step.onFailure === 'notSaved') {
        items = []
        await steps.markNotSaved?.(error)
      } else if (step.onFailure === 'saveOrdinary') fellBack = true
    }
  }
}

function need<T>(step: T | undefined, kind: AfterKind): T {
  if (!step) throw new Error(`This run has no ${kind} step.`)
  return step
}
