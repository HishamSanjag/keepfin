// A copy that is a folder behind the transport interface: the remote path of the runner, exercised without any network. Used by the tests
// (and as the reference for what a transport must do): revisions are size and time, replacing and moving are conditional on them, a name
// that is taken is never replaced, and nothing is deleted. Not used by the app.
import crypto from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import type {RemoteBase, RemoteErrorKind, RemoteMedia, RemoteScan} from './remote.js'
import type {TransportInventoryLine} from './transport.js'

export class FolderRemoteError extends Error {
  constructor(readonly kind: RemoteErrorKind | 'down', message?: string) {
    super(message ?? kind)
    this.name = 'FolderRemoteError'
  }
}

export class FolderRemote implements RemoteMedia {
  readonly kind = 'folder'
  readonly archive = 'move' as const
  /** Every call that read or changed the copy, in order. */
  readonly calls: string[] = []
  /** Returns an error to throw from the named operation (`upload`, `replace`, `download`, `move`, `trash`, `stat`, `scan`). */
  fail: (op: string, path: string) => FolderRemoteError | undefined = () => undefined
  /** Lists only this many files and says the listing is not complete. */
  tearAfter: number | null = null
  private seq = 0

  constructor(readonly root: string) {}

  private abs(rel: string) {
    return path.join(this.root, ...rel.split('/'))
  }

  private hook(op: string, rel: string) {
    this.calls.push(`${op} ${rel}`)
    const error = this.fail(op, rel)
    if (error) throw error
  }

  private lineOf(rel: string, st: {size: number; mtimeMs: number; ino: number}): TransportInventoryLine {
    return {path: rel, size: st.size, mtimeMs: Math.floor(st.mtimeMs), fileId: String(st.ino), revision: `${st.size}-${Math.floor(st.mtimeMs)}`}
  }

  identity(line: TransportInventoryLine) {
    return `${line.fileId ?? ''}@${line.revision ?? ''}`
  }

  async scan(onLine: (line: TransportInventoryLine) => void): Promise<RemoteScan> {
    this.hook('scan', '')
    let count = 0
    let complete = true
    const stack = ['']
    while (stack.length) {
      const dir = stack.pop()!
      for (const e of await fs.readdir(dir ? this.abs(dir) : this.root, {withFileTypes: true})) {
        const rel = dir ? `${dir}/${e.name}` : e.name
        if (!dir && e.name === '.keepfin') continue
        if (e.isDirectory()) stack.push(rel)
        else if (e.isFile()) {
          if (this.tearAfter !== null && count >= this.tearAfter) {
            complete = false
            continue
          }
          onLine(this.lineOf(rel, await fs.stat(this.abs(rel))))
          count++
        }
      }
    }
    return {count, complete, unreadable: complete ? [] : ['torn'], collisions: [], missingRoot: false}
  }

  async stat(rel: string) {
    this.hook('stat', rel)
    const st = await fs.stat(this.abs(rel)).catch(() => null)
    return st?.isFile() ? this.lineOf(rel, st) : null
  }

  async download(rel: string, staged: string, expected: string): Promise<string> {
    this.hook('download', rel)
    let bytes: Buffer
    try {
      bytes = await fs.readFile(this.abs(rel))
    } catch {
      throw new FolderRemoteError('gone')
    }
    const id = 'sha256:' + crypto.createHash('sha256').update(bytes).digest('hex')
    if (expected !== '*' && id !== expected) throw new FolderRemoteError('mismatch')
    await fs.mkdir(path.dirname(staged), {recursive: true})
    await fs.writeFile(staged, bytes)
    return id
  }

  private async put(source: string, rel: string, contentId: string) {
    const bytes = await fs.readFile(source)
    if ('sha256:' + crypto.createHash('sha256').update(bytes).digest('hex') !== contentId) throw new FolderRemoteError('mismatch')
    await fs.mkdir(path.dirname(this.abs(rel)), {recursive: true})
    try {
      await fs.writeFile(this.abs(rel), bytes, {flag: 'wx'})
    } catch {
      throw new FolderRemoteError('exists')
    }
    return {revision: this.lineOf(rel, await fs.stat(this.abs(rel))).revision}
  }

  async uploadNew(source: string, rel: string, contentId: string) {
    this.hook('upload', rel)
    return this.put(source, rel, contentId)
  }

  async replace(source: string, rel: string, contentId: string, base: RemoteBase) {
    this.hook('replace', rel)
    const now = await fs.stat(this.abs(rel)).catch(() => null)
    if (!now) throw new FolderRemoteError('gone')
    if (this.lineOf(rel, now).revision !== base.revision) throw new FolderRemoteError('changed')
    await this.moveAside(rel)
    return this.put(source, rel, contentId)
  }

  private async moveAside(rel: string) {
    const to = `.keepfin/trash/${Date.now()}-${this.seq++}-${path.basename(rel)}`
    await fs.mkdir(path.dirname(this.abs(to)), {recursive: true})
    await fs.rename(this.abs(rel), this.abs(to))
  }

  async moveToTrash(rel: string, base: RemoteBase) {
    this.hook('trash', rel)
    const now = await fs.stat(this.abs(rel)).catch(() => null)
    if (!now) throw new FolderRemoteError('gone')
    if (this.lineOf(rel, now).revision !== base.revision) throw new FolderRemoteError('changed')
    await this.moveAside(rel)
  }

  async moveTo(from: string, to: string, base: RemoteBase) {
    this.hook('move', `${from} -> ${to}`)
    const now = await fs.stat(this.abs(from)).catch(() => null)
    if (!now) throw new FolderRemoteError('gone')
    if (base.revision !== null && this.lineOf(from, now).revision !== base.revision) throw new FolderRemoteError('changed')
    if (await fs.stat(this.abs(to)).then(() => true, () => false)) throw new FolderRemoteError('exists')
    await fs.mkdir(path.dirname(this.abs(to)), {recursive: true})
    await fs.rename(this.abs(from), this.abs(to))
    return {revision: this.lineOf(to, await fs.stat(this.abs(to))).revision}
  }

  classify(error: unknown): RemoteErrorKind | null {
    return error instanceof FolderRemoteError && error.kind !== 'down' ? error.kind : null
  }

  stop(error: unknown) {
    return error instanceof FolderRemoteError && error.kind === 'down' ? {state: 'offline' as const, message: 'The copy could not be reached.'} : null
  }
}
