// Timed lyrics written into a real file must come back timed: the words alone are not enough, the player follows the timestamps.
import assert from 'node:assert/strict'
import {execFile} from 'node:child_process'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {test} from 'node:test'
import {promisify} from 'node:util'
import ffmpegPath from 'ffmpeg-static'
import {readEmbeddedLyrics} from '../library/tags.js'
import {writeLyrics} from './tagging.js'

const run = promisify(execFile)
const ffmpeg = ffmpegPath as unknown as string
const lrc = '[00:01.50]First line\n[00:03.25]Second line\n[01:02.00]Third line'
const formats: [string, string[]][] = [
  ['mp3', ['-c:a', 'libmp3lame']],
  ['m4a', ['-c:a', 'aac']],
  ['opus', ['-c:a', 'libopus']],
  ['flac', ['-c:a', 'flac']],
]

for (const [extension, codec] of formats) {
  test(`timed lyrics in a real .${extension} stay timed`, async () => {
    const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-lyrics-'))
    try {
      const file = path.join(dir, `Song.${extension}`)
      await run(ffmpeg, ['-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=2', ...codec, file])
      const where = await writeLyrics(ffmpeg, file, lrc)
      if (where === 'sidecar') return
      assert.equal(where, 'embedded')
      const lyrics = await readEmbeddedLyrics(file)
      assert.ok(lyrics, 'the lyrics come back')
      assert.equal(lyrics.synced, true, `lost their timing: ${JSON.stringify(lyrics).slice(0, 200)}`)
      assert.deepEqual(lyrics.lines.map((line) => line.time), [1.5, 3.25, 62])
    } finally {
      await fs.rm(dir, {recursive: true, force: true})
    }
  })
}
