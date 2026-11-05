// A small in-memory WebDAV server with ETags that honours (or, by switch, ignores) the conditions the transport relies on:
// If-Match, If-None-Match, Overwrite: F and Range. It exists to test both outcomes of the probe; the real-server test uses a
// published WebDAV implementation instead.
import crypto from 'node:crypto'
import {FakeServer, MemTree, parseRange} from './base.js'
import type {FakeReply, FakeRequest, Node} from './base.js'

const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

export class FakeWebDav {
  readonly tree = new MemTree(undefined, 'w')
  readonly server: FakeServer
  ignoreIfMatch = false
  ignoreIfNoneMatch = false
  ignoreOverwrite = false
  ignoreRange = false
  weakEtags = false
  quota = true
  readonly prefix = '/dav'
  constructor() {
    this.server = new FakeServer((r) => this.handle(r), [])
  }

  get base() {
    return this.server.origin + this.prefix
  }

  etag(n: Node): string {
    const h = crypto.createHash('md5').update(n.data).digest('hex').slice(0, 8)
    return `${this.weakEtags ? 'W/' : ''}"${n.rev}-${h}"`
  }

  private nodeAt(p: string): Node | undefined {
    return this.tree.resolve(p.split('/').filter(Boolean))
  }

  private rel(url: URL): string {
    return decodeURIComponent(url.pathname).slice(this.prefix.length)
  }

  private response(n: Node, rel: string, props: boolean): string {
    const href = this.prefix + rel.split('/').map(encodeURIComponent).join('/') + (n.folder && !rel.endsWith('/') ? '/' : '')
    if (!props) return `<d:response><d:href>${href}</d:href><d:propstat><d:prop/><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>`
    return `<d:response><d:href>${esc(href)}</d:href><d:propstat><d:prop>${n.folder ? '<d:resourcetype><d:collection/></d:resourcetype>' : `<d:resourcetype/><d:getcontentlength>${n.data.length}</d:getcontentlength><d:getetag>${esc(this.etag(n))}</d:getetag>`}<d:getlastmodified>${new Date(n.modified).toUTCString()}</d:getlastmodified></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>`
  }

  private async handle(req: FakeRequest): Promise<FakeReply> {
    const rel = this.rel(req.url)
    const node = this.nodeAt(rel)
    const parts = rel.split('/').filter(Boolean)
    const leaf = parts[parts.length - 1]
    const parent = this.nodeAt(parts.slice(0, -1).join('/'))
    const ifMatch = req.headers['if-match'] as string | undefined
    const ifNone = req.headers['if-none-match'] as string | undefined
    switch (req.method) {
      case 'PROPFIND': {
        if (!node) return {status: 404}
        if (req.body.toString().includes('quota-available-bytes')) {
          const body = this.quota
            ? '<d:response><d:href>/</d:href><d:propstat><d:prop><d:quota-available-bytes>5000</d:quota-available-bytes><d:quota-used-bytes>10</d:quota-used-bytes></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>'
            : '<d:response><d:href>/</d:href><d:propstat><d:prop/><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>'
          return {status: 207, headers: {'content-type': 'application/xml'}, body: `<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">${body}</d:multistatus>`}
        }
        const depth = String(req.headers.depth ?? '1')
        let out = this.response(node, rel, true)
        if (depth !== '0' && node.folder) for (const k of this.tree.children(node.id)) out += this.response(k, (rel.replace(/\/$/, '') + '/' + k.name), true)
        return {status: 207, headers: {'content-type': 'application/xml; charset=utf-8'}, body: `<?xml version="1.0" encoding="utf-8"?><d:multistatus xmlns:d="DAV:">${out}</d:multistatus>`}
      }
      case 'GET': {
        if (!node || node.folder) return {status: 404}
        const range = parseRange(req.headers.range, node.data.length)
        const headers = {etag: this.etag(node)}
        if (range === 'none' || this.ignoreRange) return {status: 200, headers, body: node.data}
        if (range === null) return {status: 416}
        return {status: 206, headers: {...headers, 'content-range': `bytes ${range.start}-${range.end}/${node.data.length}`}, body: node.data.subarray(range.start, range.end + 1)}
      }
      case 'PUT': {
        if (!parent || !parent.folder || !leaf) return {status: 409}
        if (node) {
          if (ifNone === '*' && !this.ignoreIfNoneMatch) return {status: 412}
          if (ifMatch !== undefined && !this.ignoreIfMatch && ifMatch !== this.etag(node)) return {status: 412}
          this.tree.write(node, req.body)
          return {status: 204, headers: {etag: this.etag(node)}}
        }
        if (ifMatch !== undefined && !this.ignoreIfMatch) return {status: 412}
        const n = this.tree.add(parent.id, leaf, false, req.body)
        return {status: 201, headers: {etag: this.etag(n)}}
      }
      case 'MKCOL': {
        if (node) return {status: 405}
        if (!parent || !parent.folder || !leaf) return {status: 409}
        this.tree.add(parent.id, leaf, true, Buffer.alloc(0))
        return {status: 201}
      }
      case 'DELETE': {
        if (!node) return {status: 404}
        if (ifMatch !== undefined && !this.ignoreIfMatch && ifMatch !== this.etag(node)) return {status: 412}
        node.trashed = true
        return {status: 204}
      }
      case 'MOVE': {
        if (!node) return {status: 404}
        const dest = new URL(String(req.headers.destination))
        const dparts = decodeURIComponent(dest.pathname).slice(this.prefix.length).split('/').filter(Boolean)
        const dparent = this.nodeAt(dparts.slice(0, -1).join('/'))
        if (!dparent || !dparent.folder) return {status: 409}
        const existing = this.tree.find(dparent.id, dparts[dparts.length - 1]!)
        if (existing && String(req.headers.overwrite) === 'F' && !this.ignoreOverwrite) return {status: 412}
        if (ifMatch !== undefined && !this.ignoreIfMatch && ifMatch !== this.etag(node)) return {status: 412}
        if (existing) existing.trashed = true
        node.parent = dparent.id
        node.name = dparts[dparts.length - 1]!
        node.rev++
        return {status: existing ? 204 : 201, headers: {etag: this.etag(node)}}
      }
      default:
        return {status: 405}
    }
  }
}
