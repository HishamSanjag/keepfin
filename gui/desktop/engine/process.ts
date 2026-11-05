import {spawn, type ChildProcess} from 'node:child_process'
import os from 'node:os'

export type RunOptions = {
  env?: NodeJS.ProcessEnv
  signal?: AbortSignal
  /** Hard deadline; the process tree is killed when it elapses. */
  timeoutMs?: number
  onStdoutLine?: (line: string) => void
  onStderrLine?: (line: string) => void
  /** Keep stdout in memory (for -J). Lines are still delivered when false. */
  collectStdout?: boolean
  /** Fail when collected stdout exceeds this many characters. */
  maxStdout?: number
  cwd?: string
  /** Run below normal priority (background analysis). */
  lowPriority?: boolean
}

export type RunResult = {code: number | null; stdout: string; stderr: string}

export class ProcessError extends Error {
  constructor(
    message: string,
    readonly stderr: string,
    readonly code: number | null,
  ) {
    super(message)
  }
}

/** Environment for Python tools: UTF-8 pipes so non-Latin titles/paths survive. */
export function toolEnv(extra: NodeJS.ProcessEnv = {}): NodeJS.ProcessEnv {
  return {
    ...process.env,
    PYTHONIOENCODING: 'utf-8',
    PYTHONUTF8: '1',
    ...extra,
  }
}

/** yt-dlp spawns ffmpeg (and the JS runtime): kill the whole tree, not just the parent. */
export function killTree(child: ChildProcess) {
  if (child.exitCode !== null || child.signalCode !== null || !child.pid) return
  if (process.platform === 'win32') {
    const killer = spawn('taskkill', ['/PID', String(child.pid), '/T', '/F'], {
      windowsHide: true,
      stdio: 'ignore',
    })
    killer.on('error', () => child.kill())
  } else {
    try {
      // Children were started in their own process group (detached: true).
      process.kill(-child.pid, 'SIGKILL')
    } catch {
      child.kill('SIGKILL')
    }
  }
}

function lineSplitter(onLine?: (line: string) => void) {
  let buffer = ''
  return {
    push(chunk: string) {
      if (!onLine) return
      buffer += chunk
      let index: number
      while ((index = buffer.search(/\r?\n|\r/)) !== -1) {
        const line = buffer.slice(0, index)
        const match = buffer.slice(index).match(/^(\r\n|\n|\r)/)!
        buffer = buffer.slice(index + match[0].length)
        if (line) onLine(line)
      }
      // Guard against a tool printing an unbounded line.
      if (buffer.length > 4_000_000) buffer = buffer.slice(-1_000_000)
    },
    flush() {
      if (onLine && buffer) onLine(buffer)
      buffer = ''
    },
  }
}

export function run(command: string, args: string[], options: RunOptions = {}): Promise<RunResult> {
  return new Promise((resolve, reject) => {
    if (options.signal?.aborted) return reject(options.signal.reason)
    let child: ChildProcess
    try {
      child = spawn(command, args, {
        // Every child is asked for UTF-8 pipes, even when a caller passes its own environment.
        env: {PYTHONIOENCODING: 'utf-8', PYTHONUTF8: '1', ...(options.env ?? process.env)},
        cwd: options.cwd,
        windowsHide: true,
        detached: process.platform !== 'win32',
        stdio: ['ignore', 'pipe', 'pipe'],
      })
    } catch (error) {
      return reject(error)
    }
    if (options.lowPriority && child.pid) {
      try {
        os.setPriority(child.pid, os.constants.priority.PRIORITY_BELOW_NORMAL)
      } catch {}
    }
    let stdout = ''
    let stderr = ''
    let failure: Error | undefined
    let settled = false
    const out = lineSplitter(options.onStdoutLine)
    const err = lineSplitter(options.onStderrLine)
    const abort = () => {
      failure ??= options.signal?.reason instanceof Error ? options.signal.reason : new Error('Cancelled.')
      killTree(child)
    }
    options.signal?.addEventListener('abort', abort, {once: true})
    const timer = options.timeoutMs
      ? setTimeout(() => {
          failure ??= new ProcessError('The engine took too long to respond.', stderr, null)
          killTree(child)
        }, options.timeoutMs)
      : undefined
    child.stdout!.setEncoding('utf8')
    child.stderr!.setEncoding('utf8')
    child.stdout!.on('data', (chunk: string) => {
      if (options.collectStdout) {
        stdout += chunk
        if (options.maxStdout && stdout.length > options.maxStdout) {
          failure ??= new ProcessError('The engine response exceeded the safe size limit.', stderr, null)
          killTree(child)
          stdout = ''
        }
      }
      out.push(chunk)
    })
    child.stderr!.on('data', (chunk: string) => {
      stderr = (stderr + chunk).slice(-64_000)
      err.push(chunk)
    })
    const finish = (error: Error | undefined, code: number | null) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      options.signal?.removeEventListener('abort', abort)
      out.flush()
      err.flush()
      if (error) reject(error)
      else resolve({code, stdout, stderr})
    }
    child.on('error', (error) => finish(failure ?? error, null))
    child.on('close', (code) => finish(failure, code))
  })
}
