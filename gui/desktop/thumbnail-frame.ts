import {spawn} from 'node:child_process'
import fs from 'node:fs/promises'
import path from 'node:path'
import ffmpeg from 'ffmpeg-static'
import type {ThumbnailImage} from './thumbnails.js'

/** Only the main-process manager supplies this local file; no renderer paths or network protocols. */
export async function thumbnailFrame(
  file: string,
): Promise<ThumbnailImage | undefined> {
  if (
    !path.isAbsolute(file) ||
    !(await fs.stat(file).catch(() => undefined))?.isFile()
  )
    return
  if (!/\.(mp4|mkv|webm|mov|m4v|avi|mp3|m4a|aac|flac|ogg|opus)$/i.test(file))
    return
  const executable =
    typeof ffmpeg === 'string'
      ? ffmpeg.replace(/app\.asar([/\\])/, 'app.asar.unpacked$1')
      : 'ffmpeg'
  const stream = await pictureStream(executable, file)
  return new Promise((resolve) => {
    const child = spawn(
      executable,
      [
        '-hide_banner',
        '-nostdin',
        '-loglevel',
        'error',
        '-threads',
        '1',
        '-protocol_whitelist',
        'file,pipe',
        '-i',
        file,
        '-map',
        stream,
        '-frames:v',
        '1',
        '-vf',
        'scale=480:270:force_original_aspect_ratio=decrease',
        '-f',
        'image2pipe',
        '-vcodec',
        'mjpeg',
        'pipe:1',
      ],
      {windowsHide: true, stdio: ['ignore', 'pipe', 'ignore']},
    )
    const chunks: Buffer[] = []
    let size = 0
    let finished = false
    const finish = (image?: ThumbnailImage) => {
      if (finished) return
      finished = true
      clearTimeout(timer)
      resolve(image)
    }
    const timer = setTimeout(() => {
      child.kill()
      finish()
    }, 6000)
    child.stdout.on('data', (chunk: Buffer) => {
      size += chunk.length
      if (size > 1_000_000) {
        child.kill()
        finish()
      } else chunks.push(chunk)
    })
    child.on('error', () => finish())
    child.on('close', (code) =>
      finish(
        code === 0 && size > 0 && size <= 1_000_000
          ? {mime: 'image/jpeg', bytes: Buffer.concat(chunks)}
          : undefined,
      ),
    )
  })
}

/** The cover inside the file when it has one (listed as "attached pic"), else the first video stream. */
function pictureStream(executable: string, file: string): Promise<string> {
  return new Promise((resolve) => {
    const child = spawn(
      executable,
      ['-hide_banner', '-nostdin', '-protocol_whitelist', 'file,pipe', '-i', file],
      {windowsHide: true, stdio: ['ignore', 'ignore', 'pipe']},
    )
    let info = ''
    child.stderr.on('data', (chunk: Buffer) => {
      if (info.length < 64_000) info += chunk
    })
    const timer = setTimeout(() => child.kill(), 6000)
    const done = () => {
      clearTimeout(timer)
      const cover = /Stream #0:(\d+)[^\n]*\(attached pic\)/.exec(info)
      resolve(cover ? `0:${cover[1]}` : '0:v:0')
    }
    child.on('error', done)
    child.on('close', done)
  })
}
