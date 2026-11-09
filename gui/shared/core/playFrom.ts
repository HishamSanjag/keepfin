// Adapter for the shared core's "Play from" decision (core net/PlayFrom.kt): which device that holds an item plays it, and the line under
// the item. The core decides the same way on Windows and on the phone. This is the only file that imports it.
import {netPlayHint} from '../../../build/core-js/keepfin-core.mjs'

/** A paired device that holds the item. `lastSeen` is epoch ms or null. */
export type PlayHolder = {name: string; online: boolean; offersMedia: boolean; near: boolean; lastSeen: number | null}

export type PlayHint = {kind: 'peer' | 'notAllowed' | 'away' | 'unknown'; index: number; text: string}

/** `here` is "this PC" or "this phone". */
export function playHint(here: string, holders: readonly PlayHolder[]): PlayHint {
  return JSON.parse(
    netPlayHint(
      here,
      holders.map((h) => h.name),
      holders.map((h) => h.online),
      holders.map((h) => h.offersMedia),
      holders.map((h) => h.near),
      holders.map((h) => h.lastSeen ?? -1),
    ),
  ) as PlayHint
}
