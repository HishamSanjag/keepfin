// A fake Microsoft Graph v1.0 OneDrive app folder: the documented subset the core uses. Names are case-insensitive, objects have
// eTags, uploads go to a separate pre-authenticated host, and downloads redirect to another one.
import crypto from 'node:crypto'
import {quickXorHasher} from '../../../shared/core/cloud.js'
import {FakeServer, json, MemTree, parseRange, unhost} from './base.js'
import type {FakeReply, FakeRequest, Node} from './base.js'

interface Session {
  parent: string
  name: string
  replaceId?: string
  size: number
  parts: Buffer[]
  received: number
  expired: boolean
}

export const GRAPH_HOST = 'graph.microsoft.com'
const err = (status: number, code: string) => json(status, {error: {code, message: code}})
// Upload sessions stay valid for an hour from now (a fixed date here once made every journaled session look expired after it passed).
const sessionExpiry = () => new Date(Date.now() + 3_600_000).toISOString()

export class FakeGraph {
  readonly tree = new MemTree(undefined, 'g')
  readonly server: FakeServer
  readonly sessions = new Map<string, Session>()
  pageSize = 2
  ignoreRange = false
  constructor() {
    this.server = new FakeServer((r) => this.handle(r), [GRAPH_HOST, 'up.graph.example', 'dl.graph.example'])
  }

  get rewrite() {
    return this.server.rewrite
  }

  etag(n: Node): string {
    return `"{${n.id}},${n.rev}"`
  }

  item(n: Node): object {
    return {
      id: n.id,
      name: n.name,
      size: n.data.length,
      eTag: this.etag(n),
      cTag: `"c:{${n.id}},${n.rev}"`,
      ...(n.folder ? {folder: {childCount: this.tree.children(n.id).length}} : {file: {hashes: {quickXorHash: quick(n.data)}}}),
      fileSystemInfo: {lastModifiedDateTime: new Date(n.modified).toISOString(), createdDateTime: new Date(n.created).toISOString()},
      parentReference: {id: n.parent ?? ''},
    }
  }

  expireAllSessions(): void {
    for (const s of this.sessions.values()) s.expired = true
  }

  private async handle(req: FakeRequest): Promise<FakeReply> {
    const {host, path} = unhost(req.url)
    if (host === 'up.graph.example') return this.uploadHost(req, path)
    if (host === 'dl.graph.example') {
      const n = this.tree.nodes.get(path.split('/').pop()!)
      if (!n || n.trashed) return err(404, 'itemNotFound')
      const range = parseRange(req.headers.range, n.data.length)
      if (range === 'none' || this.ignoreRange) return {status: 200, body: n.data}
      if (range === null) return {status: 416}
      return {status: 206, headers: {'content-range': `bytes ${range.start}-${range.end}/${n.data.length}`}, body: n.data.subarray(range.start, range.end + 1)}
    }
    const p = path.replace(/^\/v1\.0/, '')
    if (req.method === 'GET' && p === '/me/drive') return json(200, {quota: {total: 5_000_000_000, used: 1234, remaining: 4_999_998_766, state: 'normal'}})
    const m = /^\/me\/drive\/(special\/approot|items\/([^/:]+))(.*)$/.exec(p)
    if (!m) return err(404, 'invalidRequest')
    const anchor = m[2] ? this.tree.nodes.get(decodeURIComponent(m[2])) : this.tree.root
    if (!anchor || anchor.trashed) return err(404, 'itemNotFound')
    let rest = m[3] ?? ''
    let names: string[] = []
    if (rest.startsWith(':/')) {
      const end = rest.indexOf(':', 2)
      names = (end < 0 ? rest.slice(2) : rest.slice(2, end)).split('/').map(decodeURIComponent)
      rest = end < 0 ? '' : rest.slice(end + 1)
    }
    const tail = rest
    const q = req.url.searchParams
    const reqIf = req.headers['if-match'] as string | undefined
    const lastName = names[names.length - 1]
    // Names resolve from the anchor, not from the root.
    const rooted = (ns: string[]) => {
      let cur: Node | undefined = anchor
      for (const part of ns) {
        if (!part) continue
        cur = this.tree.find(cur.id, part, true)
        if (!cur) return undefined
      }
      return cur
    }
    const tgt = names.length ? rooted(names) : anchor
    const par = names.length ? rooted(names.slice(0, -1)) : undefined

    if (req.method === 'GET' && tail === '') return tgt ? json(200, this.item(tgt)) : err(404, 'itemNotFound')
    if (req.method === 'GET' && tail === '/children') {
      if (!tgt) return err(404, 'itemNotFound')
      const kids = this.tree.children(tgt.id).sort((a, b) => a.created - b.created || a.id.localeCompare(b.id, 'en', {numeric: true}))
      const start = Number(q.get('$skiptoken') ?? 0)
      const page = kids.slice(start, start + this.pageSize)
      const next = start + this.pageSize < kids.length ? `https://${GRAPH_HOST}/v1.0${p}?$skiptoken=${start + this.pageSize}` : undefined
      return json(200, {value: page.map((n) => this.item(n)), ...(next ? {'@odata.nextLink': next} : {})})
    }
    if (req.method === 'POST' && tail === '/children') {
      if (!tgt) return err(404, 'itemNotFound')
      const b = JSON.parse(req.body.toString()) as {name: string}
      if (this.tree.find(tgt.id, b.name, true)) return err(409, 'nameAlreadyExists')
      return json(201, this.item(this.tree.add(tgt.id, b.name, true, Buffer.alloc(0))))
    }
    if (req.method === 'PUT' && tail === '/content') {
      if (tgt && !names.length) {
        // by id: replace, conditional on If-Match
        if (reqIf && reqIf !== this.etag(tgt)) return err(412, 'preconditionFailed')
        this.tree.write(tgt, req.body)
        return json(200, this.item(tgt))
      }
      if (!par || !lastName) return err(404, 'itemNotFound')
      if (this.tree.find(par.id, lastName, true)) {
        if (q.get('@microsoft.graph.conflictBehavior') === 'fail') return err(409, 'nameAlreadyExists')
        const ex = this.tree.find(par.id, lastName, true)!
        this.tree.write(ex, req.body)
        return json(200, this.item(ex))
      }
      return json(201, this.item(this.tree.add(par.id, lastName, false, req.body)))
    }
    if (req.method === 'POST' && tail === '/createUploadSession') {
      const sid = crypto.randomBytes(8).toString('hex')
      if (names.length) {
        if (!par || !lastName) return err(404, 'itemNotFound')
        this.sessions.set(sid, {parent: par.id, name: lastName, size: -1, parts: [], received: 0, expired: false})
      } else {
        if (reqIf && reqIf !== this.etag(anchor)) return err(412, 'preconditionFailed')
        this.sessions.set(sid, {parent: anchor.parent ?? '', name: anchor.name, replaceId: anchor.id, size: -1, parts: [], received: 0, expired: false})
      }
      return json(200, {uploadUrl: `https://up.graph.example/upload/${sid}`, expirationDateTime: sessionExpiry(), nextExpectedRanges: ['0-']})
    }
    if (req.method === 'GET' && tail === '/content') {
      if (!tgt || tgt.folder) return err(404, 'itemNotFound')
      return {status: 302, headers: {location: `https://dl.graph.example/dl/${tgt.id}`}}
    }
    if (req.method === 'DELETE' && tail === '') {
      if (!tgt) return err(404, 'itemNotFound')
      if (reqIf && reqIf !== this.etag(tgt)) return err(412, 'preconditionFailed')
      tgt.trashed = true
      return {status: 204}
    }
    if (req.method === 'PATCH' && tail === '') {
      if (!tgt) return err(404, 'itemNotFound')
      if (reqIf && reqIf !== this.etag(tgt)) return err(412, 'preconditionFailed')
      const b = JSON.parse(req.body.toString()) as {name?: string; parentReference?: {id: string}}
      const newParent = b.parentReference?.id ?? tgt.parent!
      const newName = b.name ?? tgt.name
      const clash = this.tree.find(newParent, newName, true)
      if (clash && clash.id !== tgt.id) return err(409, 'nameAlreadyExists')
      tgt.parent = newParent
      tgt.name = newName
      tgt.rev++
      return json(200, this.item(tgt))
    }
    return err(400, 'invalidRequest')
  }

  private uploadHost(req: FakeRequest, path: string): FakeReply {
    const s = this.sessions.get(path.split('/').pop()!)
    if (!s || s.expired) return err(404, 'itemNotFound')
    if (req.method === 'GET') return json(200, {expirationDateTime: sessionExpiry(), nextExpectedRanges: [`${s.received}-`]})
    if (req.method !== 'PUT') return err(405, 'methodNotAllowed')
    const mm = /^bytes (\d+)-(\d+)\/(\d+)$/.exec(String(req.headers['content-range'] ?? ''))
    if (!mm) return err(400, 'invalidRange')
    const [a, b, total] = [Number(mm[1]), Number(mm[2]), Number(mm[3])]
    if (a !== s.received) return json(416, {error: {code: 'invalidRange', message: 'fragmentOverlap'}})
    const len = b - a + 1
    if (b + 1 !== total && len % (320 * 1024) !== 0) return err(400, 'invalidRange')
    if (req.headers.authorization) return err(401, 'InvalidAuthenticationToken') // the pre-authenticated URL must not get a bearer token
    s.size = total
    s.parts.push(req.body)
    s.received += len
    if (s.received < total) return json(202, {expirationDateTime: sessionExpiry(), nextExpectedRanges: [`${s.received}-${total - 1}`]})
    this.sessions.delete(path.split('/').pop()!)
    const data = Buffer.concat(s.parts)
    if (s.replaceId) {
      const n = this.tree.nodes.get(s.replaceId)!
      this.tree.write(n, data)
      return json(200, this.item(n))
    }
    if (this.tree.find(s.parent, s.name, true)) return err(409, 'nameAlreadyExists')
    return json(201, this.item(this.tree.add(s.parent, s.name, false, data)))
  }
}

function quick(data: Buffer): string {
  const h = quickXorHasher()
  h.update(data)
  return h.digest()
}
