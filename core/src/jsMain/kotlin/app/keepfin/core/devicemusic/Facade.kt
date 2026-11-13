@file:OptIn(ExperimentalJsExport::class)

package app.keepfin.core.devicemusic

// The JavaScript surface of the device-music rules (see gui/shared/core/deviceMusic.ts).

@JsExport
fun deviceMusicInclude(
    relativePath: String, displayName: String, durationMs: Double, mime: String,
    isMusic: Boolean, isRingtone: Boolean, isNotification: Boolean, isAlarm: Boolean,
    isRecording: Boolean, isPodcast: Boolean, isAudiobook: Boolean, leftOut: Array<String>,
): Boolean = DeviceMusic.include(
    DeviceAudioRow(relativePath, displayName, durationMs.toLong(), mime, isMusic, isRingtone, isNotification, isAlarm, isRecording, isPodcast, isAudiobook),
    leftOut.toList(),
)

@JsExport
fun deviceMusicLeftOutFolder(relativePath: String, leftOut: Array<String>): Boolean = DeviceMusic.leftOutFolder(relativePath, leftOut.toList())

@JsExport
fun deviceMusicId(volume: String, key: String): String = DeviceMusic.idOf(volume, key)

@JsExport
fun deviceMusicDefaultLeftOutNames(): Array<String> = DeviceMusic.DEFAULT_LEFT_OUT_NAMES.toTypedArray()
