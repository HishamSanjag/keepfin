// Adapter for the words of Storage decided once in the shared core for both apps: the sentence at the top
// (core/.../copies/Safety.kt) and what a group of files is (core/.../copies/ReviewWords.kt).
import {copiesFilesSummary, copiesLibrarySafety, JsOnePlace} from '../../../build/core-js/keepfin-core.mjs'

/** "52 lyrics files and 1 video": the files of a group by kind, the largest first. */
export const filesSummary = (paths: readonly string[]): string => copiesFilesSummary([...paths])

export type OnePlace = {kind: 'songs' | 'videos' | 'other'; count: number; place: string; here: boolean}
export type Safety = {tone: 'safe' | 'warn' | 'quiet'; title: string; detail: string; keepCopy: boolean}

/** Whether every file is kept in more than one place: [places] counts this device; [deviceName] is "this PC". */
export function librarySafety(places: number, files: number, only: readonly OnePlace[], deviceName: string): Safety {
  const s = copiesLibrarySafety(places, files, only.map((o) => new JsOnePlace(o.kind, o.count, o.place, o.here)), deviceName)
  return {tone: s.tone as Safety['tone'], title: s.title, detail: s.detail, keepCopy: s.keepCopy}
}
