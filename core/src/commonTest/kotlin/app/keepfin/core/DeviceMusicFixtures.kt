package app.keepfin.core

import app.keepfin.core.devicemusic.DeviceAudioRow
import app.keepfin.core.devicemusic.DeviceMusic
import app.keepfin.core.json.JsonArray
import app.keepfin.core.json.JsonBool
import app.keepfin.core.json.JsonNumber
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue

// fixtures/engine/devicemusic: see SCHEMA.md there.

internal fun deviceMusicHandler(input: JsonObject): JsonValue {
    if ("volume" in input.members) return obj("id" to jstr(DeviceMusic.idOf((input.members.getValue("volume") as JsonString).value, (input.members.getValue("key") as JsonString).value)))
    val row = input.members.getValue("row") as JsonObject
    fun flag(name: String) = (row.members[name] as? JsonBool)?.value ?: false
    val leftOut = (input.members["leftOut"] as? JsonArray)?.items?.map { (it as JsonString).value } ?: emptyList()
    val r = DeviceAudioRow(
        (row.members.getValue("relativePath") as JsonString).value,
        (row.members.getValue("displayName") as JsonString).value,
        (row.members.getValue("durationMs") as JsonNumber).value.toLong(),
        (row.members.getValue("mime") as JsonString).value,
        flag("isMusic"), flag("isRingtone"), flag("isNotification"), flag("isAlarm"), flag("isRecording"), flag("isPodcast"), flag("isAudiobook"),
    )
    return obj("include" to JsonBool(DeviceMusic.include(r, leftOut)))
}
