// Non-Latin names must survive the trip from the download engine to the job: the environment, the decoding of the
// pipes, and (with the real engine, when it is on this machine) the saved path itself.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {spawn, spawnSync} from 'node:child_process'
import {createServer} from 'node:http'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import ffmpegPath from 'ffmpeg-static'
import {FILE_PREFIX, toolArgs} from '../../shared/policy.js'
import {run, toolEnv} from './process.js'

const TITLE = 'كرت 5090 يحتاج هوية! 😳 _ أخبارَ الباشا 📰'

test('tool environments force UTF-8 pipes, also when a caller passes its own', async () => {
  const env = toolEnv()
  assert.equal(env.PYTHONIOENCODING, 'utf-8')
  assert.equal(env.PYTHONUTF8, '1')
  const probe = 'process.stdout.write([process.env.PYTHONIOENCODING, process.env.PYTHONUTF8].join(","))'
  const result = await run(process.execPath, ['-e', probe], {env: {PATH: process.env.PATH ?? '', SystemRoot: process.env.SystemRoot ?? ''}, collectStdout: true})
  assert.equal(result.stdout, 'utf-8,1')
})

test('stdout and stderr are decoded as UTF-8 even when a character is split between two reads', async () => {
  const line = `${FILE_PREFIX}C:\\Downloads\\${TITLE}.mp4`
  const bytes = Buffer.from(`${line}\n`, 'utf8')
  // The child writes the bytes in pieces that cut through multi-byte characters (an Arabic letter, an emoji).
  const cuts = [Math.floor(bytes.length / 4) + 1, bytes.indexOf(Buffer.from('😳')) + 2, bytes.length - 3]
  const script = `
    const bytes = Buffer.from(${JSON.stringify(bytes.toString('base64'))}, 'base64')
    const cuts = ${JSON.stringify(cuts)}
    let from = 0
    const pieces = [...cuts, bytes.length].map((to) => { const piece = bytes.subarray(from, to); from = to; return piece })
    const write = (stream, i) => { if (i === pieces.length) return; stream.write(pieces[i], () => setTimeout(() => write(stream, i + 1), 15)) }
    write(process.stdout, 0)
    write(process.stderr, 0)
  `
  const lines: string[] = []
  const errors: string[] = []
  const result = await run(process.execPath, ['-e', script], {collectStdout: true, onStdoutLine: (value) => lines.push(value), onStderrLine: (value) => errors.push(value)})
  assert.equal(result.code, 0)
  assert.deepEqual(lines, [line])
  assert.deepEqual(errors, [line])
  assert.equal(result.stdout, `${line}\n`)
  assert.ok(!result.stdout.includes('\uFFFD'), 'no replacement characters')
})

// ---------------------------------------------------------------- the real engine (skipped where it is not on this machine)

const engine = path.resolve(import.meta.dirname, '../../../build/tools/yt-dlp.exe')
const haveEngine = process.platform === 'win32' && !!ffmpegPath && (await fs.stat(engine).catch(() => undefined))?.isFile()

type Run = {printed: string; bytes: string; onDisk: string[]}

async function download(args: string[]): Promise<Run> {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-encoding-'))
  try {
    const fixture = path.join(dir, 'fixture.mp4')
    const made = spawnSync(ffmpegPath!, ['-y', '-f', 'lavfi', '-i', 'testsrc2=size=320x240:rate=10', '-t', '1', '-pix_fmt', 'yuv420p', fixture])
    assert.equal(made.status, 0)
    const body = await fs.readFile(fixture)
    const server = createServer((_request, response) => {
      response.writeHead(200, {'Content-Type': 'video/mp4', 'Content-Length': body.length})
      response.end(body)
    })
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
    try {
      const out = path.join(dir, 'out')
      await fs.mkdir(out)
      const url = `http://127.0.0.1:${(server.address() as {port: number}).port}/video.mp4`
      const child = spawn(engine, [...args, '--no-playlist', '--print', `after_move:${FILE_PREFIX}%(filepath)s`, '-o', path.join(out, `${TITLE}.%(ext)s`), url], {
        env: toolEnv(),
        windowsHide: true,
        stdio: ['ignore', 'pipe', 'ignore'],
      })
      const chunks: Buffer[] = []
      child.stdout.on('data', (chunk: Buffer) => chunks.push(chunk))
      await new Promise((resolve) => child.on('close', resolve))
      const raw = Buffer.concat(chunks)
      const line = raw.toString('utf8').split(/\r?\n/).find((value) => value.startsWith(FILE_PREFIX)) ?? ''
      const nameBytes = Buffer.from(path.basename(line.slice(FILE_PREFIX.length)), 'utf8')
      return {printed: path.basename(line.slice(FILE_PREFIX.length)), bytes: nameBytes.toString('hex'), onDisk: await fs.readdir(out)}
    } finally {
      server.close()
    }
  } finally {
    await fs.rm(dir, {recursive: true, force: true})
  }
}

test('the real engine prints the saved path in full only with the encoding flag', {skip: !haveEngine && 'the download engine is not in build/tools'}, async () => {
  const stem = `${TITLE}`
  const before = await download(['--ignore-config'])
  const after = await download(toolArgs({}))
  // The file itself is always saved with its full name; only what the engine prints is at stake.
  assert.deepEqual(before.onDisk, [`${stem}.mp4`])
  assert.deepEqual(after.onDisk, [`${stem}.mp4`])
  assert.notEqual(before.printed, `${stem}.mp4`, `without the flag the printed name is damaged: ${before.printed} (${before.bytes})`)
  assert.match(before.printed, /^[\x00-\x7f]*\.mp4$/, 'every non-ASCII character is dropped')
  assert.equal(after.printed, `${stem}.mp4`)
  assert.equal(after.bytes, Buffer.from(`${stem}.mp4`, 'utf8').toString('hex'))
})
