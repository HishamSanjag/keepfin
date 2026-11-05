// A fake Google Drive v3 (drive.file): the documented subset the core uses. Objects have ids and may share names.
import crypto from 'node:crypto'
import {FakeServer, json, MemTree, parseRange, sha256Hex, unhost} from './base.js'
import type {FakeReply, FakeRequest, Node} from './base.js'

interface Session {
  parent: string
  name: string
  size: number
  parts: Buffer[]
  received: number
  expired: boolean
}

const FOLDER = 'application/vnd.google-apps.folder'
const unq = (s: string) => s.replace(/\\(.)/g, '$1')

export class FakeDrive {
  readonly tree = new MemTree(undefined, 'd')
  readonly server: FakeServer
  readonly sessions = new Map<string, Session>()
  pageSize = 2
  /** Answer ranged downloads with 200 and the whole body (a server that ignores Range). */
  ignoreRange = false
  constructor() {
    this.server = new FakeServer((r) => this.handle(r), ['www.googleapis.com'])
  }

  get rewrite() {
    return this.server.rewrite
  }

  private node(id: string): Node | undefined {
    return id === 'root' ? this.tree.root : this.tree.nodes.get(id)
  }

  private meta(n: Node): object {
    return {
      id: n.id,
      name: n.name,
      mimeType: n.folder ? FOLDER : 'application/octet-stream',
      ...(n.folder ? {} : {size: String(n.data.length), md5Checksum: crypto.createHash('md5').update(n.data).digest('hex'), sha256Checksum: sha256Hex(n.data)}),
      modifiedTime: new Date(n.modified).toISOString(),
      createdTime: new Date(n.created).toISOString(),
      version: String(n.rev),
      parents: n.parent ? [n.parent] : [],
      trashed: n.trashed,
    }
  }

  expireAllSessions(): void {
    for (const s of this.sessions.values()) s.expired = true
  }

  private async handle(req: FakeRequest): Promise<FakeReply> {
    const {path} = unhost(req.url)
    const q = req.url.searchParams
    const m = req.method
    if (m === 'GET' && path === '/drive/v3/files') return this.list(q)
    if (m === 'GET' && path === '/drive/v3/about') return json(200, {storageQuota: {limit: '1000000000', usage: '1234'}, user: {emailAddress: 'fake@example.com'}})
    let g = /^\/drive\/v3\/files\/([^/]+)$/.exec(path)
    if (g) {
      const n = this.node(decodeURIComponent(g[1]!))
      if (!n) return json(404, {error: {code: 404, message: 'File not found', errors: [{reason: 'notFound'}]}})
      if (m === 'GET') return q.get('alt') === 'media' ? this.content(n, req) : json(200, this.meta(n))
      if (m === 'PATCH') {
        const body = req.body.length ? (JSON.parse(req.body.toString()) as {name?: string; trashed?: boolean}) : {}
        if (body.name !== undefined) n.name = body.name
        if (body.trashed !== undefined) n.trashed = body.trashed
        const add = q.get('addParents')
        if (add) n.parent = add
        n.rev++
        return json(200, this.meta(n))
      }
    }
    if (m === 'POST' && path === '/drive/v3/files') {
      const b = JSON.parse(req.body.toString()) as {name: string; mimeType?: string; parents: string[]}
      const parent = this.node(b.parents[0]!)
      if (!parent) return json(404, {error: {code: 404, errors: [{reason: 'notFound'}]}})
      return json(200, this.meta(this.tree.add(parent.id, b.name, b.mimeType === FOLDER, Buffer.alloc(0))))
    }
    if (path === '/upload/drive/v3/files' || (g = /^\/upload\/drive\/v3\/files\/([^/]+)$/.exec(path))) return this.upload(req, g ? g[1]! : null)
    return json(404, {error: {code: 404, message: 'no such route ' + path}})
  }

  private list(q: URLSearchParams): FakeReply {
    const query = q.get('q') ?? ''
    const mm = /^'((?:[^'\\]|\\.)*)' in parents and trashed=false(?: and name='((?:[^'\\]|\\.)*)')?(?: and mimeType='([^']*)')?$/.exec(query)
    if (!mm) return json(400, {error: {code: 400, message: 'bad q', errors: [{reason: 'invalid'}]}})
    const parent = this.node(unq(mm[1]!))
    if (!parent) return json(404, {error: {code: 404, errors: [{reason: 'notFound'}]}})
    let kids = this.tree.children(parent.id).sort((a, b) => a.created - b.created || a.id.localeCompare(b.id, 'en', {numeric: true}))
    if (mm[2] !== undefined) kids = kids.filter((n) => n.name === unq(mm[2]!))
    if (mm[3]) kids = kids.filter((n) => (n.folder ? FOLDER : 'x') === mm[3])
    const start = Number(q.get('pageToken') ?? 0)
    if (!Number.isInteger(start) || start < 0 || start > kids.length) return json(400, {error: {code: 400, message: 'Invalid Value', errors: [{reason: 'invalid'}]}})
    const page = kids.slice(start, start + this.pageSize)
    const next = start + this.pageSize < kids.length ? String(start + this.pageSize) : undefined
    return json(200, {incompleteSearch: false, ...(next ? {nextPageToken: next} : {}), files: page.map((n) => this.meta(n))})
  }

  private content(n: Node, req: FakeRequest): FakeReply {
    const range = parseRange(req.headers.range, n.data.length)
    if (range === 'none' || this.ignoreRange) return {status: 200, headers: {'content-length': String(n.data.length)}, body: n.data}
    if (range === null) return {status: 416, headers: {'content-range': `bytes */${n.data.length}`}}
    return {status: 206, headers: {'content-range': `bytes ${range.start}-${range.end}/${n.data.length}`}, body: n.data.subarray(range.start, range.end + 1)}
  }

  private upload(req: FakeRequest, id: string | null): FakeReply {
    const q = req.url.searchParams
    const type = q.get('uploadType')
    if (req.method === 'PATCH' && id && type === 'media') {
      const n = this.node(id)
      if (!n) return json(404, {error: {code: 404, errors: [{reason: 'notFound'}]}})
      this.tree.write(n, req.body)
      return json(200, this.meta(n))
    }
    if (req.method === 'POST' && type === 'multipart') {
      const ct = String(req.headers['content-type'] ?? '')
      const boundary = /boundary=([^;]+)/.exec(ct)?.[1]
      if (!boundary) return json(400, {error: {code: 400, errors: [{reason: 'invalid'}]}})
      const parts = req.body.toString('latin1').split('--' + boundary).slice(1, 3)
      const body = (p: string) => Buffer.from(p.slice(p.indexOf('\r\n\r\n') + 4).replace(/\r\n$/, ''), 'latin1')
      const meta = JSON.parse(Buffer.from(body(parts[0]!)).toString('utf8')) as {name: string; parents: string[]}
      const parent = this.node(meta.parents[0]!)
      if (!parent) return json(404, {error: {code: 404, errors: [{reason: 'notFound'}]}})
      return json(200, this.meta(this.tree.add(parent.id, meta.name, false, body(parts[1]!))))
    }
    if (req.method === 'POST' && type === 'resumable') {
      const meta = JSON.parse(req.body.toString()) as {name: string; parents: string[]}
      const sid = crypto.randomBytes(8).toString('hex')
      this.sessions.set(sid, {parent: meta.parents[0]!, name: meta.name, size: Number(req.headers['x-upload-content-length']), parts: [], received: 0, expired: false})
      return {status: 200, headers: {location: `https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&upload_id=${sid}`}}
    }
    if (req.method === 'PUT' && q.get('upload_id')) {
      const s = this.sessions.get(q.get('upload_id')!)
      if (!s || s.expired) return json(404, {error: {code: 404, message: 'Not Found'}})
      const cr = String(req.headers['content-range'] ?? '')
      const query = /^bytes \*\/(\d+)$/.exec(cr)
      const mm = /^bytes (\d+)-(\d+)\/(\d+)$/.exec(cr)
      const state = (): FakeReply => ({status: 308, headers: s.received > 0 ? {range: `bytes=0-${s.received - 1}`} : {}})
      if (query && !mm) {
        if (Number(query[1]) === 0 && s.size === 0) return json(200, this.meta(this.tree.add(s.parent, s.name, false, Buffer.alloc(0))))
        return state()
      }
      if (!mm) return json(400, {error: {code: 400, message: 'bad Content-Range'}})
      const [a, b, total] = [Number(mm[1]), Number(mm[2]), Number(mm[3])]
      if (total !== s.size) return json(400, {error: {code: 400, message: 'size changed'}})
      if (a !== s.received) return state()
      const len = b - a + 1
      if (b + 1 !== total && len % (256 * 1024) !== 0) return json(400, {error: {code: 400, message: 'chunk not a multiple of 256 KiB'}})
      s.parts.push(req.body)
      s.received += len
      if (s.received === s.size) {
        this.sessions.delete(q.get('upload_id')!)
        return json(200, this.meta(this.tree.add(s.parent, s.name, false, Buffer.concat(s.parts))))
      }
      return state()
    }
    return json(404, {error: {code: 404, message: 'no such upload route'}})
  }
}
