// Adapter between the shared core (core/, Kotlin Multiplatform; built to build/core-js by `npm run core:build`)
// and the desktop's "Music on this device" scan. This is the only file that imports the core's devicemusic package.
import {deviceMusicId, deviceMusicInclude} from '../../../build/core-js/keepfin-core.mjs'

/** One audio file: `relativePath` uses '/' and ends with '/' (the folder relative to its root). */
export type DeviceAudioRow = {
  relativePath: string
  displayName: string
  durationMs: number
  mime: string
  isMusic?: boolean
  isRingtone?: boolean
  isNotification?: boolean
  isAlarm?: boolean
  isRecording?: boolean
  isPodcast?: boolean
  isAudiobook?: boolean
}

/** True when the file counts as music (the shared rules); `leftOut` are the owner's left-out folders. */
export function deviceMusicIncludes(row: DeviceAudioRow, leftOut: readonly string[] = []): boolean {
  return deviceMusicInclude(
    row.relativePath, row.displayName, row.durationMs, row.mime,
    row.isMusic ?? false, row.isRingtone ?? false, row.isNotification ?? false, row.isAlarm ?? false,
    row.isRecording ?? false, row.isPodcast ?? false, row.isAudiobook ?? false, Array.from(leftOut),
  )
}

/** The id of a device file, safe for the media protocol ([A-Za-z0-9_-], at most 100 characters). */
export function deviceMusicIdOf(volume: string, key: string): string {
  return deviceMusicId(volume, key)
}
