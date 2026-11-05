// Adapter between the shared core (core/, Kotlin Multiplatform; built to build/core-js by `npm run core:build`) and the desktop queue.
// This is the only file that imports the core's after-download package. The core decides the steps of a finished job, their order and
// what a failure of each means (fixtures/engine/after); the queue runs them (gui/desktop/manager/afterDownload.ts).
import {afterPlan} from '../../../build/core-js/keepfin-core.mjs'

export type AfterKind = 'lyrics' | 'save' | 'attachVideo' | 'waitForSong' | 'joinPlaylist' | 'cover' | 'notify' | 'notifyExisting' | 'settleVideos'
/** `continue`: the job still completes. `notSaved`: it completes as "Not saved" with Save again. `saveOrdinary`: the video is saved as an ordinary download. */
export type AfterFailure = 'continue' | 'notSaved' | 'saveOrdinary'
export type AfterStep = {kind: AfterKind; onFailure: AfterFailure; fallback: boolean}

/** What is known about a finished job. `video` is the fate of a music video download; `library` is `legacy` only on the phone. */
export type AfterFacts = {
  audio?: boolean
  hasFile?: boolean
  existing?: boolean
  video?: 'none' | 'attach' | 'wait' | 'ordinary'
  playlist?: boolean
  lyrics?: boolean
  library?: 'folder' | 'legacy'
}

/** The steps of a finished job in the order they run; a `fallback` step runs only when the step before it declined or failed. */
export const planAfter = (facts: AfterFacts): AfterStep[] =>
  afterPlan(!!facts.audio, facts.hasFile ?? true, !!facts.existing, facts.video ?? 'none', !!facts.playlist, !!facts.lyrics, facts.library ?? 'folder').map(
    (step: {kind: string; onFailure: string; fallback: boolean}) => ({kind: step.kind as AfterKind, onFailure: step.onFailure as AfterFailure, fallback: step.fallback}),
  )
