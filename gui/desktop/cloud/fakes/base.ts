// Shared pieces of the fake providers: a local HTTP server that implements the documented subset of one provider, an in-memory
// object tree, and fault injection. A fake is not a certificate of provider compatibility; it proves our request/response
// handling against the documented shapes (dev-docs/plans/KEEPFIN-CLOUD-SPEC.md section 7).
import crypto from 'node:crypto'
import http from 'node:http'
import type {AddressInfo} from 'node:net'

export interface FakeRequest {
  method: string
  url: URL
  headers: http.IncomingHttpHeaders
  body: Buffer
  host: string
}

export interface FakeReply {
  status: number
  headers?: Record<string, string>
  body?: Buffer | string | object
}

export type Handler = (req: FakeRequest) => FakeReply | Promise<FakeReply>

/** A fault: while `times` > 0 a request that matches gets `reply` instead of the real handler. */
export interface Fault {
  match: (req: FakeRequest) => boolean
  times: number
  reply: FakeReply | ((req: FakeRequest) => FakeReply)
}

export class FakeServer {
  private server!: http.Server
  origin = ''
  readonly faults: Fault[] = []
  readonly log: Array<{method: string; path: string; host: string; headers: http.IncomingHttpHeaders}> = []
  constructor(private readonly handler: Handler, readonly hosts: string[]) {}

  async start(): Promise<void> {
    this.server = http.createServer((req, res) => {
      const chunks: Buffer[] = []
      req.on('data', (c) => chunks.push(c))
      req.on('end', async () => {
        const host = String(req.headers['x-fake-host'] ?? req.headers.host ?? '')
        const fake: FakeRequest = {method: req.method ?? 'GET', url: new URL(req.url ?? '/', 'http://fake.local'), headers: req.headers, body: Buffer.concat(chunks), host}
        this.log.push({method: fake.method, path: fake.url.pathname + fake.url.search, host, headers: req.headers})
        let reply: FakeReply
        const fault = this.faults.find((f) => f.times > 0 && f.match(fake))
        try {
          if (fault) {
            fault.times--
            reply = typeof fault.reply === 'function' ? fault.reply(fake) : fault.reply
          } else {
            reply = await this.handler(fake)
          }
        } catch (error) {
          reply = {status: 500, body: {error: String(error)}}
        }
        const headers = {...(reply.headers ?? {})}
        let body: Buffer | string | undefined
        if (reply.body === undefined) body = undefined
        else if (typeof reply.body === 'string' || Buffer.isBuffer(reply.body)) body = reply.body
        else {
          body = JSON.stringify(reply.body)
          headers['content-type'] ??= 'application/json'
        }
        res.writeHead(reply.status, headers)
        res.end(req.method === 'HEAD' ? undefined : body)
      })
    })
    await new Promise<void>((resolve) => this.server.listen(0, '127.0.0.1', resolve))
    this.origin = `http://127.0.0.1:${(this.server.address() as AddressInfo).port}`
  }

  async stop(): Promise<void> {
    this.server.closeAllConnections?.()
    await new Promise<void>((resolve) => this.server.close(() => resolve()))
  }

  fail(match: (req: FakeRequest) => boolean, reply: FakeReply, times = 1): Fault {
    const f: Fault = {match, times, reply}
    this.faults.push(f)
    return f
  }

  clearFaults(): void {
    this.faults.length = 0
  }

  /** Maps a real provider URL to this server (hosts it fakes), keeping the original host in x-fake-host via the path prefix. */
  rewrite = (url: string): string => {
    const u = new URL(url)
    if (!this.hosts.includes(u.host)) return url
    return `${this.origin}/__h/${u.host}${u.pathname}${u.search}`
  }
}

/** Splits `/__h/<host>/<rest>` back into host and path (the rewrite above). */
export function unhost(url: URL): {host: string; path: string} {
  const m = /^\/__h\/([^/]+)(\/.*)?$/.exec(url.pathname)
  return m ? {host: m[1]!, path: m[2] ?? '/'} : {host: '', path: url.pathname}
}

export interface Node {
  id: string
  name: string
  parent: string | null
  folder: boolean
  data: Buffer
  rev: number
  created: number
  modified: number
  trashed: boolean
}

/** An in-memory tree with ids, names and revisions. */
export class MemTree {
  readonly nodes = new Map<string, Node>()
  readonly root: Node
  private seq = 0
  readonly clock: () => number
  constructor(clock?: () => number, private readonly idPrefix = 'n') {
    this.clock = clock ?? (() => Date.parse('2026-10-15T12:00:00Z') + this.seq * 1000)
    this.root = this.add(null, '', true, Buffer.alloc(0))
  }

  add(parent: string | null, name: string, folder: boolean, data: Buffer): Node {
    const t = this.clock()
    this.seq++
    const n: Node = {id: `${this.idPrefix}${this.seq}`, name, parent, folder, data, rev: 1, created: t, modified: t, trashed: false}
    this.nodes.set(n.id, n)
    return n
  }

  children(parentId: string, includeTrashed = false): Node[] {
    return [...this.nodes.values()].filter((n) => n.parent === parentId && (includeTrashed || !n.trashed))
  }

  find(parentId: string, name: string, caseInsensitive = false): Node | undefined {
    return this.children(parentId).find((n) => (caseInsensitive ? n.name.toLowerCase() === name.toLowerCase() : n.name === name))
  }

  /** Resolves a path of names from the root (case folding optional). */
  resolve(names: string[], caseInsensitive = false): Node | undefined {
    let cur: Node | undefined = this.root
    for (const part of names) {
      if (!part) continue
      cur = this.find(cur.id, part, caseInsensitive)
      if (!cur) return undefined
    }
    return cur
  }

  write(n: Node, data: Buffer): void {
    n.data = data
    n.rev++
    n.modified = this.clock()
  }

  pathOf(n: Node): string {
    const parts: string[] = []
    for (let c: Node | undefined = n; c && c.parent; c = this.nodes.get(c.parent)) parts.unshift(c.name)
    return '/' + parts.join('/')
  }
}

export const sha256Hex = (b: Buffer) => crypto.createHash('sha256').update(b).digest('hex')

/** Parses `bytes=S-E` / `bytes=S-` against a size: the satisfiable [start, end] or null. */
export function parseRange(header: string | undefined, size: number): {start: number; end: number} | null | 'none' {
  if (!header) return 'none'
  const m = /^bytes=(\d+)-(\d*)$/.exec(header)
  if (!m) return 'none'
  const start = Number(m[1])
  const end = m[2] ? Math.min(Number(m[2]), size - 1) : size - 1
  if (start >= size) return null
  return {start, end}
}

export const json = (status: number, body: object, headers: Record<string, string> = {}): FakeReply => ({status, body, headers})
