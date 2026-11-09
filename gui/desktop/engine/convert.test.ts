import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {convertDownload, type ConvertRun} from './convert.js'

async function folder() {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-convert-'))
  const file = path.join(dir, 'Reel.mp4')
  await fs.writeFile(file, Buffer.alloc(10_000, 1))
  return {dir, file}
}

const REPORT = [
  '  Duration: 00:00:20.00, start: 0.000000, bitrate: 600 kb/s',
  '  Stream #0:0[0x1](und): Video: vp9 (Profile 0), yuv420p, 540x960, 105 kb/s, 29.97 fps',
  '  Stream #0:1[0x2](und): Audio: aac (HE-AAC) (mp4a / 0x6134706D), 44100 Hz, stereo, fltp, 37 kb/s (default)',
].join('\n')

/** A converter: the first call is the report of the file, the second writes `bytes` to the output (its last argument). */
const fake = (bytes: number, code = 0): {run: ConvertRun; calls: string[][]} => {
  const calls: string[][] = []
  const run: ConvertRun = async (_command, list, options) => {
    calls.push(list)
    if (!list.includes('-y')) return {code: 1, stdout: '', stderr: REPORT}
    options.onStdoutLine?.('out_time_us=5000000')
    options.onStdoutLine?.('out_time_us=10000000')
    if (code === 0) await fs.writeFile(list.at(-1)!, Buffer.alloc(bytes, 2))
    return {code, stdout: '', stderr: code === 0 ? '' : 'Error opening input'}
  }
  return {run, calls}
}

test('a successful conversion replaces the download and leaves no temporary file', async () => {
  const {dir, file} = await folder()
  const {run, calls} = fake(4_000)
  const percents: number[] = []
  const result = await convertDownload({ffmpeg: 'ffmpeg', file, height: 360, duration: 20, run, onProgress: (p) => percents.push(p)})
  assert.equal(result, file)
  assert.equal((await fs.stat(file)).size, 4_000)
  assert.deepEqual(await fs.readdir(dir), ['Reel.mp4'])
  assert.deepEqual(percents, [25, 50])
  const args = calls[1]
  assert.equal(args[args.indexOf('-i') + 1], file)
  assert.ok(args.at(-1)!.endsWith('.kftmp-converting.mp4'))
  assert.ok(args.some((a) => a.includes('360')))
  assert.equal(args[args.indexOf('-c:a') + 1], 'copy', 'light AAC audio is kept')
})

test('the length is read from the file when the job did not know it', async () => {
  const {file} = await folder()
  const percents: number[] = []
  await convertDownload({ffmpeg: 'ffmpeg', file, height: 480, run: fake(4_000).run, onProgress: (p) => percents.push(p)})
  assert.deepEqual(percents, [25, 50])
})

test('a failed conversion keeps the download and fails with a plain message', async () => {
  const {dir, file} = await folder()
  await assert.rejects(convertDownload({ffmpeg: 'ffmpeg', file, height: 360, duration: 20, run: fake(0, 1).run}), (error: Error) => {
    assert.equal(error.message, 'Keepfin could not make the smaller copy; the original size was kept.')
    assert.doesNotMatch(error.message, /ffmpeg|x264/i)
    return true
  })
  assert.equal((await fs.stat(file)).size, 10_000)
  assert.deepEqual(await fs.readdir(dir), ['Reel.mp4'])
})

test('a result that is not smaller is not used', async () => {
  const {dir, file} = await folder()
  await assert.rejects(convertDownload({ffmpeg: 'ffmpeg', file, height: 360, duration: 20, run: fake(12_000).run}), (error: Error) => error.message === 'Keepfin could not make the smaller copy; the original size was kept.')
  assert.equal((await fs.stat(file)).size, 10_000)
  assert.deepEqual(await fs.readdir(dir), ['Reel.mp4'])
})

test('no converter fails the same way', async () => {
  const {file} = await folder()
  await assert.rejects(convertDownload({ffmpeg: undefined, file, height: 360}), /original size was kept/)
})

test('cancelling removes the temporary file, keeps the download and is not turned into a failure', async () => {
  const {dir, file} = await folder()
  const controller = new AbortController()
  const run: ConvertRun = async (_command, list) => {
    if (!list.includes('-y')) return {code: 1, stdout: '', stderr: REPORT}
    await fs.writeFile(list.at(-1)!, Buffer.alloc(100))
    controller.abort(new Error('Cancelled.'))
    throw controller.signal.reason
  }
  await assert.rejects(convertDownload({ffmpeg: 'ffmpeg', file, height: 360, duration: 20, run, signal: controller.signal}), /Cancelled/)
  assert.equal((await fs.stat(file)).size, 10_000)
  assert.deepEqual(await fs.readdir(dir), ['Reel.mp4'])
})
