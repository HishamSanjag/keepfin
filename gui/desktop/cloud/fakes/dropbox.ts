// A fake Dropbox API v2 app folder: the documented subset the core uses. Names are case-insensitive, files have revs and a
// content_hash, uploads can go through sessions, and write conditions come back as 409 conflicts.
import crypto from 'node:crypto'
import {FakeServer, json, MemTree, parseRange, unhost} from './base.js'
import type {FakeReply, FakeRequest, Node} from './base.js'

interface Session {
  parts: Buffer[]
  received: number
  expired: boolean
}

const conflict = (summary: string) => json(409, {error_summary: summary, error: {'.tag': summary.split('/')[0]}})

export function dropboxContentHash(data: Buffer): string {
  const digests: Buffer[] = []
  for (let at = 0; at < data.length; at += 4 * 1024 * 1024) digests.push(crypto.createHash('sha256').update(data.subarray(at, at + 4 * 1024 * 1024)).digest())
  return crypto.createHash('sha256').update(Buffer.concat(digests)).digest('hex')
}

export class FakeDropbox {
  readonly tree = new MemTree(undefined, 'id:')
  readonly server: FakeServer
  readonly sessions = new Map<string, Session>()
  private readonly lists = new Map<string, Node[]>()
  pageSize = 2
  ignoreRange = false
  constructor() {
    this.server = new FakeServer((r) => this.handle(r), ['api.dropboxapi.com', 'content.dropboxapi.com'])
  }

  get rewrite() {
    return this.server.rewrite
  }

  rev(n: Node): string {
    return String(n.rev).padStart(9, '0')
  }

  meta(n: Node): object {
    const p = this.tree.pathOf(n)
    return {
      '.tag': n.folder ? 'folder' : 'file',
      id: n.id,
      name: n.name,
      path_lower: p.toLowerCase(),
      path_display: p,
      ...(n.folder ? {} : {rev: this.rev(n), size: n.data.length, server_modified: new Date(n.modified).toISOString().replace(/\.\d+Z$/, 'Z'), content_hash: dropboxContentHash(n.data)}),
    }
  }

  expireAllSessions(): void {
    for (const s of this.sessions.values()) s.expired = true
  }

  private at(p: string): Node | undefined {
    if (p === '' || p === '/') return this.tree.root
    if (p.startsWith('id:')) return this.tree.nodes.get(p)
    return this.tree.resolve(p.split('/'), true)
  }

  private ensureParents(p: string): Node {
    const parts = p.split('/').filter(Boolean)
    parts.pop()
    let cur = this.tree.root
    for (const part of parts) cur = this.tree.find(cur.id, part, true) ?? this.tree.add(cur.id, part, true, Buffer.alloc(0))
    return cur
  }

  private commit(info: {path: string; mode: unknown}, data: Buffer): FakeReply {
    const mode = info.mode
    const existing = this.at(info.path)
    if (mode === 'add') {
      if (existing) return conflict('path/conflict/file/..')
      const parent = this.ensureParents(info.path)
      return json(200, this.meta(this.tree.add(parent.id, info.path.split('/').pop()!, false, data)))
    }
    const upd = mode as {update?: string}
    if (!existing || existing.folder) return conflict('path/not_found/..')
    if (upd.update !== this.rev(existing)) return conflict('path/conflict/file/..')
    this.tree.write(existing, data)
    return json(200, this.meta(existing))
  }

  private async handle(req: FakeRequest): Promise<FakeReply> {
    const {path} = unhost(req.url)
    if (req.method !== 'POST') return json(404, {error_summary: 'not_found'})
    const arg = req.headers['dropbox-api-arg'] ? (JSON.parse(String(req.headers['dropbox-api-arg'])) as Record<string, any>) : null
    const body = !arg && req.body.length ? (JSON.parse(req.body.toString()) as Record<string, any>) : {}
    switch (path) {
      case '/2/files/list_folder': {
        const dir = this.at(body.path)
        if (!dir || !dir.folder) return conflict('path/not_found/..')
        const kids = this.tree.children(dir.id).sort((a, b) => a.created - b.created)
        const id = crypto.randomBytes(6).toString('hex')
        this.lists.set(id, kids)
        return this.page(id, 0, Math.min(this.pageSize, body.limit ?? this.pageSize))
      }
      case '/2/files/list_folder/continue': {
        const [id, at] = String(body.cursor).split(':')
        if (!this.lists.has(id!)) return conflict('reset/..')
        return this.page(id!, Number(at), this.pageSize)
      }
      case '/2/files/get_metadata': {
        const n = this.at(body.path)
        return n ? json(200, this.meta(n)) : conflict('path/not_found/..')
      }
      case '/2/files/create_folder_v2': {
        if (this.at(body.path)) return conflict('path/conflict/folder/..')
        const parent = this.ensureParents(body.path)
        return json(200, {metadata: this.meta(this.tree.add(parent.id, String(body.path).split('/').pop()!, true, Buffer.alloc(0)))})
      }
      case '/2/files/upload':
        return this.commit(arg as {path: string; mode: unknown}, req.body)
      case '/2/files/upload_session/start': {
        const sid = crypto.randomBytes(8).toString('hex')
        this.sessions.set(sid, {parts: [], received: 0, expired: false})
        if (req.body.length) {
          this.sessions.get(sid)!.parts.push(req.body)
          this.sessions.get(sid)!.received += req.body.length
        }
        return json(200, {session_id: sid})
      }
      case '/2/files/upload_session/append_v2':
      case '/2/files/upload_session/finish': {
        const s = this.sessions.get(arg!.cursor.session_id)
        if (!s || s.expired) return conflict('lookup_failed/not_found/..')
        if (arg!.cursor.offset !== s.received) return json(409, {error_summary: 'lookup_failed/incorrect_offset/..', error: {'.tag': 'incorrect_offset', correct_offset: s.received}})
        s.parts.push(req.body)
        s.received += req.body.length
        if (path.endsWith('append_v2')) return {status: 200, body: 'null'}
        this.sessions.delete(arg!.cursor.session_id)
        const info = arg!.commit as {path: string; mode: unknown; strict_conflict?: boolean}
        const mode = typeof info.mode === 'string' ? info.mode : { update: (info.mode as {update: string}).update }
        return this.commit({path: info.path, mode}, Buffer.concat(s.parts))
      }
      case '/2/files/download': {
        const n = this.at(arg!.path)
        if (!n || n.folder) return conflict('path/not_found/..')
        const range = parseRange(req.headers.range, n.data.length)
        const headers = {'dropbox-api-result': JSON.stringify(this.meta(n))}
        if (range === 'none' || this.ignoreRange) return {status: 200, headers, body: n.data}
        if (range === null) return {status: 416}
        return {status: 206, headers: {...headers, 'content-range': `bytes ${range.start}-${range.end}/${n.data.length}`}, body: n.data.subarray(range.start, range.end + 1)}
      }
      case '/2/files/delete_v2': {
        const n = this.at(body.path)
        if (!n) return conflict('path_lookup/not_found/..')
        if (body.parent_rev !== undefined && body.parent_rev !== this.rev(n)) return conflict('path_lookup/conflict/..')
        n.trashed = true
        return json(200, {metadata: this.meta(n)})
      }
      case '/2/files/move_v2': {
        const n = this.at(body.from_path)
        if (!n) return conflict('from_lookup/not_found/..')
        if (this.at(body.to_path)) return conflict('to/conflict/file/..')
        const parent = this.ensureParents(body.to_path)
        n.parent = parent.id
        n.name = String(body.to_path).split('/').pop()!
        n.rev++
        return json(200, {metadata: this.meta(n)})
      }
      case '/2/users/get_space_usage':
        return json(200, {used: 1234, allocation: {'.tag': 'individual', allocated: 2_000_000_000}})
      default:
        return json(404, {error_summary: 'not_found'})
    }
  }

  private page(id: string, from: number, size: number): FakeReply {
    const all = this.lists.get(id)!
    const part = all.slice(from, from + size)
    const more = from + size < all.length
    return json(200, {entries: part.map((n) => this.meta(n)), cursor: `${id}:${from + size}`, has_more: more})
  }
}
