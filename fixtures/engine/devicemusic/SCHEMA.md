# devicemusic

What counts as "Music on this device", shared by the Windows scan and the Android MediaStore reader. Decided by `app.keepfin.core.devicemusic.DeviceMusic` (`core/`): desktop through `gui/shared/core/deviceMusic.ts`.

| input | expected |
|---|---|
| `row` (`relativePath`, `displayName`, `durationMs`, `mime`, and optional flags `isMusic`, `isRingtone`, `isNotification`, `isAlarm`, `isRecording`, `isPodcast`, `isAudiobook`, all false when absent), optional `leftOut` (folders the owner left out) | `include` |

`relativePath` uses `/` and ends with `/` (Android `RELATIVE_PATH`, for example `Music/Rock/`); Windows passes the folder relative to its root with `/`.

A row is left out when:

- a ringtone, notification, alarm, recording, podcast or audiobook flag is set (nothing overrides a flag, not even `isMusic`);
- `durationMs` is below 30000 (zero or unknown too);
- the type is not audio: `mime` must start with `audio/` (any case). Windows has no type, so an empty `mime` is accepted only when the file's extension is mp3, m4a, aac, flac, wav, ogg, opus, wma, alac, aiff, aif, ape, wv or mka;
- a folder name (any segment, ASCII case-insensitive) is one of the default left-out names, unless `isMusic` is true: ringtones, notifications, alarms, recordings, voice recorder, voice notes, call recordings, call recorder, whatsapp audio, whatsapp voice notes, sounds, ui sounds;
- the folder is inside one of the owner's `leftOut` folders: an entry (a trailing `/` is added, any case) must be a prefix of the path on whole folders, so `Music/Rock/` does not leave out `Music/Rock2/`. `isMusic` does not override this. An empty entry is ignored.

Non-ASCII names pass through unchanged (only A-Z are lowered). Ids: input `volume` and `key`, expected `id`: `dm-<volume>-<key>` with every character outside A-Z a-z 0-9 `_` replaced by `_` in each part (so the id fits the media protocol, `[A-Za-z0-9_-]{1,100}`), cut to 100 characters (`DeviceMusic.idOf`).
