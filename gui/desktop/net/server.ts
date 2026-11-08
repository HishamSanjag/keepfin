// The network server (spec 4 and 9.1): HTTP/1.1 over TLS 1.3 with mutual authentication. A peer is whoever holds a pinned key; the
// certificate's name and chain are never looked at. Order of events for every request: the TLS handshake proved the key, the key
// is looked up in the pins, the core's authorization matrix decides (deny by default), then the budgets of 9.1 #7 apply.
// Endpoints: see KEEPFIN-NETWORK-SPEC.md section 4; pairing: pairing.ts; copy mutations: peerExecutor.ts.
//   GET  /v1/hello?v=1,2                                   versions, name, the libraries this peer may see
//   GET  /v1/libraries/<id>/segments?vector=<a:n,...>      framed KFSEG files the caller lacks
//   POST /v1/libraries/<id>/segments                       framed KFSEG files pushed by the caller
//   GET  /v1/libraries/<id>/inventory                      the folder inventory as JSON lines (sorted), header x-keepfin-complete
//   GET  /v1/revisions/<sha256 hex>?library=<id>           file bytes with Range
//   GET  /v1/libraries/<id>/copy/state, POST .../copy/lock|heartbeat|unlock|trash|generation, PUT .../copy/file
//   GET  /v1/announce                                      anyone: {v:1,id,n,p} (device discovery; own budget per address)
//   POST /v1/jobs                                          Download on... (phase 6): answers 501
//   GET  /v1/events (WebSocket)                            change notices, filtered by what the peer may read
import {createReadStream} from 'node:fs'
import fs from 'node:fs/promises'
import http from 'node:http'
import https from 'node:https'
import {constants as cryptoConstants, randomBytes, X509Certificate} from 'node:crypto'
import os from 'node:os'
import path from 'node:path'
import type {Duplex} from 'node:stream'
import type {TLSSocket} from 'node:tls'
import {WebSocketServer, type WebSocket} from 'ws'
import {authorize, deviceIdOf, type NetAction} from '../../shared/core/net.js'
import type {DeviceIdentity} from './identity.js'
import {HttpError} from './httpError.js'
import {AttemptLimiter, DEFAULT_LIMITS, type Limits} from './limits.js'
import {PairError, PairingHost, peerInfo} from './pairing.js'
import type {PeerExecutor} from './peerExecutor.js'
import type {Peer, PeerStore} from './peers.js'
import {formatVector, frame, frameReader, FrameError, ingestSegment, listSegments, missingFor, parseVector, vectorOf} from './segments.js'
import type {NetLibrary, NetLog} from './types.js'

export const PROTOCOL_VERSIONS = [1]
export const PROTOCOL_MIN = 1

export interface Caller {
  deviceId: string
  spki: Uint8Array
  address: string
  peer: Peer | undefined
}

export interface NetServerDeps {
  identity: () => DeviceIdentity
  peers: PeerStore
  libraries: () => NetLibrary[]
  executor: (libraryId: string) => PeerExecutor | undefined
  pairing: PairingHost
  now: () => number
  limits?: Partial<Limits>
  log?: NetLog
  /** A paired peer made a request: for "last seen". */
  seen?: (peer: Peer, address: string) => void
  /** A job request (phase 6). Absent: 501. */
  jobs?: (caller: Caller, body: Record<string, unknown>) => Promise<{status: number; body: unknown}>
  /** The spaces a remote job may save into (id and name). */
  jobSpaces?: () => {id: string; name: string}[]
  /** This device's own addresses told to paired devices in hello (eligible IPv4 addresses, a MagicDNS name). Absent: none. */
  addresses?: () => string[]
  /** A device asks to join a library it does not hold (it names the actor it will write as). Absent: 501. */
  join?: (caller: Caller, libraryId: string, body: Record<string, unknown>) => Promise<{status: number; body: unknown}>
}

type Conn = {deviceId: string; spki: Uint8Array; address: string; paired: boolean}

const addressOf = (socket: Duplex): string => {
  const raw = (socket as TLSSocket).remoteAddress ?? ''
  return raw.startsWith('::ffff:') ? raw.slice(7) : raw
}

export class NetServer {
  readonly limits: Limits
  private server?: https.Server
  private wss?: WebSocketServer
  private readonly conns = new Map<Duplex, Conn>()
  private readonly transfers = new Map<string, number>()
  private readonly requestRate: AttemptLimiter
  private readonly strangerRate: AttemptLimiter
  private readonly announceRate: AttemptLimiter
  private readonly verifyRate: AttemptLimiter
  private readonly eventSockets = new Map<string, Set<WebSocket>>()
  private readonly changeListeners = new Map<string, () => void>()
  private readonly inventoryRuns = new Map<string, Promise<{file: string; count: number; complete: boolean; at: number}>>()
  private port = 0
  private closed = false

  constructor(private readonly deps: NetServerDeps) {
    this.limits = {...DEFAULT_LIMITS, ...deps.limits}
    this.requestRate = new AttemptLimiter(this.limits.requestsPerMinute, 60_000, deps.now)
    this.strangerRate = new AttemptLimiter(this.limits.strangerRequestsPerMinute, 60_000, deps.now)
    this.announceRate = new AttemptLimiter(this.limits.announcePerMinute, 60_000, deps.now)
    this.verifyRate = new AttemptLimiter(this.limits.verifiesPerMinute, 60_000, deps.now)
  }

  get listeningPort(): number {
    return this.port
  }

  // ---- lifecycle -----------------------------------------------------------------------------------------------------------

  start(port: number, host?: string): Promise<number> {
    const identity = this.deps.identity()
    const limits = this.limits
    const server = https.createServer(
      {
        key: identity.keyPem,
        cert: identity.certPem,
        requestCert: true,
        rejectUnauthorized: false, // trust is the pin, decided below; the handshake still proves the key
        minVersion: 'TLSv1.3',
        maxVersion: 'TLSv1.3',
        // resumption and 0-RTT off (spec 9.1 #4): no tickets, no session cache
        secureOptions: cryptoConstants.SSL_OP_NO_TICKET,
        sessionTimeout: 0,
        handshakeTimeout: limits.handshakeMs,
        maxHeaderSize: limits.maxHeaderBytes,
        // Node looks for expired header and request deadlines only this often (30 s by default)
        connectionsCheckingInterval: Math.max(100, Math.min(5000, Math.floor(limits.headersMs / 2))),
        ALPNProtocols: ['http/1.1'],
      } as https.ServerOptions,
      (req, res) => void this.handle(req, res),
    )
    server.on('resumeSession', (_id, done) => done(null, undefined))
    server.on('newSession', (_id, _data, done) => done())
    server.headersTimeout = limits.headersMs
    server.requestTimeout = limits.requestMs
    server.keepAliveTimeout = limits.idleMs
    server.maxConnections = limits.maxConnections
    server.on('secureConnection', (socket) => this.onSecure(socket))
    server.on('tlsClientError', () => undefined) // a failed handshake is a closed socket, nothing more
    server.on('clientError', (_error, socket) => socket.destroy())
    server.on('upgrade', (req, socket, head) => void this.onUpgrade(req, socket, head))
    this.wss = new WebSocketServer({noServer: true, maxPayload: 16 * 1024})
    this.server = server
    return new Promise((resolve, reject) => {
      server.once('error', reject)
      server.listen({port, host, exclusive: true}, () => {
        server.off('error', reject)
        server.on('error', (error) => this.deps.log?.(`server error ${(error as Error).message}`))
        this.port = (server.address() as {port: number}).port
        this.watchLibraries()
        resolve(this.port)
      })
    })
  }

  async close(): Promise<void> {
    this.closed = true
    for (const off of this.changeListeners.values()) off()
    this.changeListeners.clear()
    for (const set of this.eventSockets.values()) for (const ws of set) ws.terminate()
    this.eventSockets.clear()
    this.wss?.close()
    for (const socket of this.conns.keys()) socket.destroy()
    await new Promise<void>((resolve) => (this.server ? this.server.close(() => resolve()) : resolve()))
  }

  /** Closes everything open for one peer (Remove device: its streams end now). */
  dropPeer(deviceId: string): void {
    for (const ws of this.eventSockets.get(deviceId) ?? []) ws.terminate()
    this.eventSockets.delete(deviceId)
    for (const [socket, conn] of this.conns) if (conn.deviceId === deviceId) socket.destroy()
  }

  openConnections(deviceId?: string): number {
    let n = 0
    for (const conn of this.conns.values()) if (!deviceId || conn.deviceId === deviceId) n++
    return n
  }

  isOnline(deviceId: string): boolean {
    return (this.eventSockets.get(deviceId)?.size ?? 0) > 0 || this.openConnections(deviceId) > 0
  }

  // ---- connections ---------------------------------------------------------------------------------------------------------

  private onSecure(socket: TLSSocket): void {
    const raw = socket.getPeerCertificate()?.raw
    if (!raw || this.closed) return void socket.destroy()
    let spki: Buffer
    try {
      spki = new X509Certificate(raw).publicKey.export({type: 'spki', format: 'der'}) as Buffer
    } catch {
      return void socket.destroy()
    }
    const deviceId = deviceIdOf(spki)
    const address = addressOf(socket)
    const paired = !!this.deps.peers.get(deviceId)
    let same = 0
    for (const conn of this.conns.values()) if (paired ? conn.deviceId === deviceId : !conn.paired && conn.address === address) same++
    if (same >= (paired ? this.limits.perPeerConnections : this.limits.perStrangerAddress)) return void socket.destroy()
    this.conns.set(socket, {deviceId, spki, address, paired})
    socket.on('close', () => this.conns.delete(socket))
    socket.on('error', () => undefined)
    socket.setNoDelay(true)
  }

  private callerOf(socket: Duplex): Caller | undefined {
    const conn = this.conns.get(socket)
    if (!conn) return undefined
    return {deviceId: conn.deviceId, spki: conn.spki, address: conn.address, peer: this.deps.peers.get(conn.deviceId)}
  }

  private check(caller: Caller, action: NetAction, library: string | null): void {
    // pairing endpoints are reached by strangers in three ways (the window, a scanned code, an unrequested request from this network); the pairing host decides which and costs the attempts
    const decision = authorize({pairingMode: this.deps.pairing.active || action === 'pair', peer: this.deps.peers.grants(caller.deviceId)}, action, library)
    if (!decision.allow) throw new HttpError(403, decision.reason, `Not allowed (${decision.reason}).`)
  }

  private budget(caller: Caller): void {
    const limiter = caller.peer ? this.requestRate : this.strangerRate
    if (!limiter.take(caller.peer ? caller.deviceId : caller.address)) throw new HttpError(429, 'rate', 'Too many requests.')
    if (caller.peer) this.deps.seen?.(caller.peer, caller.address)
  }

  private transfer(caller: Caller): () => void {
    const have = this.transfers.get(caller.deviceId) ?? 0
    if (have >= this.limits.transfersPerPeer) throw new HttpError(429, 'transfers', 'Too many transfers at once.')
    this.transfers.set(caller.deviceId, have + 1)
    let released = false
    return () => {
      if (released) return
      released = true
      const now = (this.transfers.get(caller.deviceId) ?? 1) - 1
      if (now <= 0) this.transfers.delete(caller.deviceId)
      else this.transfers.set(caller.deviceId, now)
    }
  }

  // ---- requests ------------------------------------------------------------------------------------------------------------

  private async handle(req: http.IncomingMessage, res: http.ServerResponse): Promise<void> {
    try {
      const caller = this.callerOf(req.socket)
      if (!caller) throw new HttpError(401, 'no-key', 'No device key was presented.')
      const url = new URL(req.url ?? '/', 'https://keepfin.invalid')
      // anyone who completes the handshake may ask who this is (device discovery); that has its own, separate budget
      if (url.pathname === '/v1/announce' && (req.method ?? 'GET') === 'GET') return this.announceTo(caller, res)
      this.budget(caller)
      await this.route(caller, req, res, url)
    } catch (error) {
      this.fail(res, error)
    }
  }

  private fail(res: http.ServerResponse, error: unknown): void {
    let status = 500
    let code = 'error'
    let message = 'Something went wrong.'
    if (error instanceof HttpError) ({status, code, message} = {status: error.status, code: error.code, message: error.message})
    else if (error instanceof PairError) ({status, code, message} = {status: error.status, code: error.code, message: error.message})
    else if (error instanceof FrameError) ({status, code, message} = {status: 413, code: 'frame', message: error.message})
    else this.deps.log?.(`request failed: ${error instanceof Error ? error.message : String(error)}`)
    if (res.headersSent) return void res.destroy()
    res.writeHead(status, {'content-type': 'application/json', 'cache-control': 'no-store', connection: status >= 400 && status !== 404 ? 'close' : 'keep-alive'})
    res.end(JSON.stringify({error: code, message}))
  }

  private json(res: http.ServerResponse, value: unknown, status = 200): void {
    const body = JSON.stringify(value)
    res.writeHead(status, {'content-type': 'application/json', 'content-length': Buffer.byteLength(body), 'cache-control': 'no-store'})
    res.end(body)
  }

  private library(id: string): NetLibrary {
    const lib = this.deps.libraries().find((l) => l.libraryId === id)
    if (!lib) throw new HttpError(404, 'library', 'No such library.')
    return lib
  }

  async readJsonBody(req: http.IncomingMessage): Promise<Record<string, unknown>> {
    const chunks: Buffer[] = []
    let size = 0
    const started = this.deps.now()
    for await (const chunk of req) {
      size += (chunk as Buffer).length
      if (size > this.limits.maxJsonBytes) throw new HttpError(413, 'too-large', 'The request is too large.')
      chunks.push(chunk as Buffer)
      this.slowCheck(size, started)
    }
    try {
      const value = JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}')
      if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('not an object')
      return value as Record<string, unknown>
    } catch {
      throw new HttpError(400, 'json', 'The request is not valid.')
    }
  }

  /** A body that trickles in below the minimum rate (after a grace period) is cut off. */
  private slowCheck(bytes: number, startedAt: number): void {
    const elapsed = this.deps.now() - startedAt
    if (elapsed > 5000 && bytes / (elapsed / 1000) < this.limits.minBodyBytesPerSecond) throw new HttpError(408, 'slow', 'The request is arriving too slowly.')
  }

  private async route(caller: Caller, req: http.IncomingMessage, res: http.ServerResponse, url: URL): Promise<void> {
    const method = req.method ?? 'GET'
    const parts = url.pathname.split('/').filter(Boolean)
    if (parts[0] !== 'v1') throw new HttpError(404, 'not-found', 'Not found.')
    const [, a, b, c, d] = parts

    if (a === 'hello' && method === 'GET' && parts.length === 2) {
      this.check(caller, 'hello', null)
      return this.hello(caller, url, res)
    }
    if (a === 'pair' && method === 'POST' && parts.length === 3) {
      this.check(caller, 'pair', null)
      return this.pair(caller, b, req, res)
    }
    if (a === 'jobs' && method === 'POST' && parts.length === 2) {
      // the jobs action is granted per library; a job names no library, so any grant of it counts
      const grants = this.deps.peers.grants(caller.deviceId)
      const library = Object.entries(grants?.libraries ?? {}).find(([, actions]) => actions.includes('jobs'))?.[0] ?? null
      this.check(caller, 'jobs', library)
      if (!this.deps.jobs) throw new HttpError(501, 'not-available', 'Download on another device is not available.')
      const answer = await this.deps.jobs(caller, await this.readJsonBody(req))
      return this.json(res, answer.body, answer.status)
    }
    if (a === 'revisions' && (method === 'GET' || method === 'HEAD') && parts.length === 3) {
      const library = url.searchParams.get('library')
      this.check(caller, 'media.read', library)
      return this.revision(caller, this.library(library as string), b, req, res)
    }
    if (a === 'libraries' && b && c) {
      if (c === 'segments' && method === 'GET' && !d) {
        this.check(caller, 'metadata.read', b)
        return this.segmentsOut(caller, this.library(b), url, req, res)
      }
      if (c === 'segments' && method === 'POST' && !d) {
        this.check(caller, 'metadata.write', b)
        return this.segmentsIn(caller, this.library(b), req, res)
      }
      if (c === 'join' && method === 'POST' && !d) {
        // the library must be shared with this peer already (the person here chose it); the grant of its actor is the host's decision
        this.check(caller, 'metadata.write', b)
        if (!this.deps.join) throw new HttpError(501, 'not-available', 'Joining a library is not available.')
        const answer = await this.deps.join(caller, b, await this.readJsonBody(req))
        return this.json(res, answer.body, answer.status)
      }
      if (c === 'inventory' && method === 'GET' && !d) {
        this.check(caller, 'inventory.read', b)
        return this.inventory(caller, this.library(b), res)
      }
      if (c === 'copy') {
        const executor = this.deps.executor(b)
        if (!executor) throw new HttpError(404, 'library', 'No such library.')
        const read = method === 'GET' && d === 'state'
        // reading a file of the copy by name is reading media; everything else that changes the folder is a copy mutation
        this.check(caller, read ? 'inventory.read' : method === 'GET' && d === 'file' ? 'media.read' : 'copy.mutate', b)
        try {
          return await this.copy(caller, executor, d, method, url, req, res)
        } finally {
          // a listing made before a change would show the folder as it was: the next one is made again (a copy trusts the listing)
          if (method !== 'GET') this.inventoryRuns.delete(b)
        }
      }
    }
    throw new HttpError(404, 'not-found', 'Not found.')
  }

  /** GET /v1/announce: the same three facts as the DNS-SD record and nothing more (no libraries, peers or addresses), and c: 1 while a typed pairing code is showing (never the code). */
  private announceTo(caller: Caller, res: http.ServerResponse): void {
    if (!this.announceRate.take(caller.address)) throw new HttpError(429, 'rate', 'Too many requests.')
    const identity = this.deps.identity()
    this.json(res, {v: 1, id: identity.deviceId.slice(0, 16), n: identity.name, p: 'windows', ...(this.deps.pairing.codeShowing ? {c: 1} : {})})
  }

  private hello(caller: Caller, url: URL, res: http.ServerResponse): void {
    const asked = (url.searchParams.get('v') ?? '1').split(',').map(Number).filter((n) => Number.isInteger(n) && n > 0)
    const common = PROTOCOL_VERSIONS.filter((v) => asked.includes(v))
    const identity = this.deps.identity()
    const grants = this.deps.peers.grants(caller.deviceId)
    const libraries = this.deps.libraries().filter((l) => grants?.libraries[l.libraryId]?.length)
    if (!common.length) {
      // an older peer is told to update; both keep working alone
      const update = Math.max(...asked, 0) < PROTOCOL_MIN ? 'peer' : 'here'
      return this.json(res, {error: 'version', update, versions: PROTOCOL_VERSIONS, message: update === 'peer' ? 'The other device needs to update Keepfin.' : 'This device needs to update Keepfin.'}, 426)
    }
    this.json(res, {
      app: 'keepfin',
      versions: PROTOCOL_VERSIONS,
      version: Math.max(...common),
      name: identity.name,
      platform: 'windows',
      deviceId: identity.deviceId,
      libraries: libraries.map((l) => ({libraryId: l.libraryId, name: l.name, actor: l.actor(), actions: grants!.libraries[l.libraryId]})),
      // how many libraries this device holds that it has not shared with the asking device (a count only), so it can tell "has no
      // Keepfin folder" from "has not shared it with you yet"
      unshared: this.deps.libraries().length - libraries.length,
      capabilities: {segments: true, inventory: true, revisions: true, copies: true, join: !!this.deps.join, jobs: !!this.deps.jobs},
      // how this device can be reached (a hint for reconnecting; at most six)
      addresses: (this.deps.addresses?.() ?? []).slice(0, 6),
      // where this device lets the asking device save a job (only told to a peer that may send jobs at all)
      jobSpaces: this.deps.jobs && Object.values(grants?.libraries ?? {}).some((a) => a.includes('jobs')) ? this.deps.jobSpaces?.() ?? [] : [],
    })
  }

  private async pair(caller: Caller, step: string, req: http.IncomingMessage, res: http.ServerResponse): Promise<void> {
    const body = await this.readJsonBody(req)
    const b64 = (value: unknown, max = 512): Uint8Array => {
      if (typeof value !== 'string' || value.length > max * 2) throw new HttpError(400, 'bad-field', 'A field is missing.')
      return new Uint8Array(Buffer.from(value, 'base64'))
    }
    const pairing = this.deps.pairing
    if (step === 'start') {
      const versions = Array.isArray(body.versions) ? body.versions.map(Number) : [1]
      if (!PROTOCOL_VERSIONS.some((v) => versions.includes(v))) return this.json(res, {error: 'version', update: Math.max(...versions) < PROTOCOL_MIN ? 'peer' : 'here', versions: PROTOCOL_VERSIONS}, 426)
      const token = body.token === undefined ? null : b64(body.token, 64)
      if (token && token.length !== 16) throw new PairError('token', 'This code is not valid.')
      // the typed code: the 20-bit number. A request carries a token or a code, never both
      const code = body.code
      if (code !== undefined && (token || typeof code !== 'number' || !Number.isInteger(code) || code < 0 || code >= 1 << 20)) throw new PairError('token', 'This code is not valid.')
      const started = pairing.start(peerInfo(caller.spki, body), caller.address, token, code === undefined ? null : (code as number))
      return this.json(res, {id: started.id, commitment: Buffer.from(started.commitment).toString('base64'), expiresAt: started.expiresAt, name: started.name, platform: started.platform, libraries: started.libraries, versions: PROTOCOL_VERSIONS})
    }
    if (step === 'nonce') {
      const nb = pairing.nonce(caller.deviceId, String(body.id ?? ''), b64(body.na, 64))
      return this.json(res, {nb: Buffer.from(nb).toString('base64')})
    }
    if (step === 'confirm') {
      if (body.decline === true) {
        pairing.cancel(caller.deviceId, String(body.id ?? ''))
        return this.json(res, {ok: true})
      }
      const sig = await pairing.confirm(caller.deviceId, String(body.id ?? ''), b64(body.sig, 160))
      return this.json(res, {sig: Buffer.from(sig).toString('base64')})
    }
    throw new HttpError(404, 'not-found', 'Not found.')
  }

  // ---- segments ------------------------------------------------------------------------------------------------------------

  private async segmentsOut(caller: Caller, lib: NetLibrary, url: URL, req: http.IncomingMessage, res: http.ServerResponse): Promise<void> {
    const text = url.searchParams.get('vector') ?? ''
    if (text.length > this.limits.maxVectorChars) throw new HttpError(414, 'vector', 'The version vector is too long.')
    const vector = parseVector(text)
    if (!vector) throw new HttpError(400, 'vector', 'The version vector is not valid.')
    const release = this.transfer(caller)
    let hold = () => {}
    try {
      await lib.flush()
      hold = (await lib.hold?.()) ?? hold // while files are listed and sent, compaction removes none of them
      const list = await listSegments(lib.root, lib.records)
      const missing = missingFor(list, vector)
      res.writeHead(200, {'content-type': 'application/x-keepfin-segments', 'cache-control': 'no-store', 'x-keepfin-vector': formatVector(vectorOf(list)).slice(0, 8192)})
      let closed = false
      req.on('close', () => (closed = true))
      for (const segment of missing) {
        if (closed || res.destroyed) break
        const bytes = await fs.readFile(segment.file).catch(() => undefined)
        if (!bytes || bytes.length > this.limits.maxSegmentBytes) continue
        if (!res.write(frame(bytes))) await new Promise<void>((resolve) => res.once('drain', resolve).once('close', resolve))
      }
      res.end()
    } finally {
      hold()
      release()
    }
  }

  private async segmentsIn(caller: Caller, lib: NetLibrary, req: http.IncomingMessage, res: http.ServerResponse): Promise<void> {
    const release = this.transfer(caller)
    try {
      const declared = Number(req.headers['content-length'] ?? 0)
      if (declared > this.limits.maxPushBytes) throw new HttpError(413, 'too-large', 'The push is too large.')
      let total = 0
      const started = this.deps.now()
      const result = {stored: 0, have: 0, rejected: [] as string[]}
      const reader = frameReader(this.limits.maxSegmentBytes, this.limits.maxPushSegments, async (bytes) => {
        // signature checks cost CPU: they run only for an authenticated peer and inside a budget
        if (!this.verifyRate.take(caller.deviceId)) throw new HttpError(429, 'verify-budget', 'Too many segments to check right now.')
        const outcome = await ingestSegment(lib.root, lib.libraryId, bytes, lib.records)
        if (outcome.status === 'rejected') result.rejected.push(outcome.reason)
        else result[outcome.status]++
      })
      reader.on('data', () => undefined)
      await new Promise<void>((resolve, reject) => {
        req.on('data', (chunk: Buffer) => {
          total += chunk.length
          try {
            if (total > this.limits.maxPushBytes) throw new HttpError(413, 'too-large', 'The push is too large.')
            this.slowCheck(total, started)
          } catch (error) {
            reject(error)
            req.destroy()
          }
        })
        req.on('error', reject)
        reader.on('error', reject)
        reader.on('end', resolve)
        req.pipe(reader)
      })
      if (result.stored) await lib.rescan()
      this.json(res, result)
    } finally {
      release()
    }
  }

  // ---- inventory and media -------------------------------------------------------------------------------------------------

  private async inventory(caller: Caller, lib: NetLibrary, res: http.ServerResponse): Promise<void> {
    const release = this.transfer(caller)
    try {
      // one scan at a time per library; a scan made in the last 10 seconds is reused
      let run = this.inventoryRuns.get(lib.libraryId)
      if (!run || this.deps.now() - (await run.then((r) => r.at).catch(() => 0)) > 10_000) {
        const tmp = await fs.mkdtemp(path.join(os.tmpdir(), 'kf-net-inv-'))
        run = lib.scanInventory(path.join(tmp, 'inventory.jsonl'), tmp).then((r) => ({file: r.file, count: r.count, complete: r.complete, at: this.deps.now()}))
        this.inventoryRuns.set(lib.libraryId, run)
        run.catch(() => this.inventoryRuns.delete(lib.libraryId))
      }
      const done = await run
      res.writeHead(200, {'content-type': 'application/x-ndjson', 'cache-control': 'no-store', 'x-keepfin-complete': done.complete ? '1' : '0', 'x-keepfin-count': String(done.count)})
      const stream = createReadStream(done.file)
      res.on('close', () => stream.destroy())
      await new Promise<void>((resolve) => {
        stream.on('error', () => (res.destroy(), resolve()))
        stream.on('end', resolve)
        stream.pipe(res)
      })
    } finally {
      release()
    }
  }

  private async revision(caller: Caller, lib: NetLibrary, hex: string, req: http.IncomingMessage, res: http.ServerResponse): Promise<void> {
    if (!/^[0-9a-f]{64}$/.test(hex)) throw new HttpError(400, 'content-id', 'The content id is not valid.')
    const file = await lib.findContent(`sha256:${hex}`)
    if (!file) throw new HttpError(404, 'content', 'This file is not in the library.')
    return this.sendFile(caller, file, `"sha256:${hex}"`, req, res)
  }

  /** A file of a copy by its name below the folder (the pull of a copy): the same Range rules, an ETag of size and time. */
  private async copyFile(caller: Caller, ex: PeerExecutor, url: URL, req: http.IncomingMessage, res: http.ServerResponse): Promise<void> {
    const opened = await ex.open(url.searchParams.get('path') ?? '')
    return this.sendFile(caller, opened.file, `"${opened.size}-${Math.floor(opened.mtimeMs)}"`, req, res)
  }

  private async sendFile(caller: Caller, file: string, etag: string, req: http.IncomingMessage, res: http.ServerResponse): Promise<void> {
    const release = this.transfer(caller)
    try {
      const stat = await fs.stat(file).catch(() => undefined)
      if (!stat?.isFile()) throw new HttpError(404, 'content', 'This file is not here.')
      let start = 0
      let end = stat.size - 1
      let status = 200
      const range = req.headers.range
      if (range) {
        const m = /^bytes=(\d*)-(\d*)$/.exec(range)
        if (!m || (m[1] === '' && m[2] === '')) throw new HttpError(416, 'range', 'The range is not valid.')
        if (m[1] === '') {
          const n = Number(m[2])
          start = Math.max(0, stat.size - n)
        } else {
          start = Number(m[1])
          if (m[2] !== '') end = Math.min(end, Number(m[2]))
        }
        if (start > end || start >= stat.size || end - start + 1 > this.limits.maxRangeBytes) {
          res.writeHead(416, {'content-range': `bytes */${stat.size}`})
          return void res.end()
        }
        status = 206
      }
      const headers: http.OutgoingHttpHeaders = {'content-type': 'application/octet-stream', 'accept-ranges': 'bytes', 'content-length': end - start + 1, etag, 'cache-control': 'no-store', 'x-keepfin-name': encodeURIComponent(path.basename(file))}
      if (status === 206) headers['content-range'] = `bytes ${start}-${end}/${stat.size}`
      res.writeHead(status, headers)
      if (req.method === 'HEAD' || stat.size === 0) return void res.end()
      const stream = createReadStream(file, {start, end})
      res.on('close', () => stream.destroy())
      await new Promise<void>((resolve) => {
        stream.on('error', () => (res.destroy(), resolve()))
        res.on('close', resolve)
        stream.on('end', resolve)
        stream.pipe(res)
      })
    } finally {
      release()
    }
  }

  // ---- copy mutations (peer executor) --------------------------------------------------------------------------------------

  private async copy(caller: Caller, ex: PeerExecutor, what: string | undefined, method: string, url: URL, req: http.IncomingMessage, res: http.ServerResponse): Promise<void> {
    const owner = caller.deviceId
    if (what === 'state' && method === 'GET') return this.json(res, await ex.state())
    if (what === 'lock' && method === 'POST') {
      const body = await this.readJsonBody(req)
      return this.json(res, await ex.lock(owner, String(body.device ?? ''), String(body.run ?? ''), this.deps.now()))
    }
    if (what === 'heartbeat' && method === 'POST') {
      const body = await this.readJsonBody(req)
      return this.json(res, await ex.heartbeat(owner, String(body.token ?? '')))
    }
    if (what === 'unlock' && method === 'POST') {
      const body = await this.readJsonBody(req)
      return this.json(res, await ex.unlock(owner, String(body.token ?? '')))
    }
    if (what === 'trash' && method === 'POST') {
      const body = await this.readJsonBody(req)
      return this.json(res, await ex.trash(owner, String(body.token ?? ''), String(body.path ?? ''), body.revision === null || body.revision === undefined ? null : String(body.revision), typeof body.ifMatch === 'string' ? body.ifMatch : null))
    }
    if (what === 'move' && method === 'POST') {
      const body = await this.readJsonBody(req)
      return this.json(res, await ex.move(owner, String(body.token ?? ''), String(body.from ?? ''), String(body.to ?? ''), body.revision === null || body.revision === undefined ? null : String(body.revision), typeof body.ifMatch === 'string' ? body.ifMatch : null))
    }
    if (what === 'file' && method === 'GET') return this.copyFile(caller, ex, url, req, res)
    if (what === 'generation' && method === 'POST') {
      const body = await this.readJsonBody(req)
      return this.json(res, await ex.appendGeneration(owner, String(body.token ?? ''), Number(body.generation), String(body.replica ?? '')))
    }
    if (what === 'file' && method === 'PUT') {
      const release = this.transfer(caller)
      try {
        const q = url.searchParams
        const size = Number(req.headers['content-length'] ?? -1)
        if (!Number.isFinite(size) || size < 0) throw new HttpError(411, 'length', 'The size of the file is needed.')
        const ifMatch = typeof req.headers['if-match'] === 'string' ? req.headers['if-match'].replace(/^"|"$/g, '') : null
        const result = await ex.put(owner, {token: q.get('token') ?? '', path: q.get('path') ?? '', contentId: q.get('content') ?? '', mode: q.get('mode') === 'replace' ? 'replace' : 'new', revision: q.get('revision'), ifMatch, size}, req)
        return this.json(res, result)
      } finally {
        release()
      }
    }
    throw new HttpError(404, 'not-found', 'Not found.')
  }

  // ---- events (WebSocket) --------------------------------------------------------------------------------------------------

  private async onUpgrade(req: http.IncomingMessage, socket: Duplex, head: Buffer): Promise<void> {
    const refuse = (status: number, code: string) => {
      socket.write(`HTTP/1.1 ${status} ${http.STATUS_CODES[status] ?? 'Error'}\r\nconnection: close\r\ncontent-type: application/json\r\n\r\n${JSON.stringify({error: code})}`)
      socket.destroy()
    }
    try {
      const caller = this.callerOf(socket)
      if (!caller) return refuse(401, 'no-key')
      this.budget(caller)
      const url = new URL(req.url ?? '/', 'https://keepfin.invalid')
      if (url.pathname !== '/v1/events') return refuse(404, 'not-found')
      this.check(caller, 'hello', null)
      const open = this.eventSockets.get(caller.deviceId) ?? new Set<WebSocket>()
      if (open.size >= this.limits.eventSocketsPerPeer) return refuse(429, 'event-sockets')
      this.wss!.handleUpgrade(req, socket as never, head, (ws) => {
        open.add(ws)
        this.eventSockets.set(caller.deviceId, open)
        ws.on('close', () => open.delete(ws))
        ws.on('error', () => ws.terminate())
        ws.on('message', () => undefined) // the stream is one way; anything a peer sends is ignored
        const ping = setInterval(() => ws.readyState === ws.OPEN && ws.ping(), 30_000)
        ping.unref?.()
        ws.on('close', () => clearInterval(ping))
        void this.sendVectors(caller.deviceId, ws)
      })
    } catch (error) {
      refuse(error instanceof HttpError ? error.status : 500, error instanceof HttpError ? error.code : 'error')
    }
  }

  private allowedFor(deviceId: string, libraryId: string): boolean {
    return authorize({pairingMode: false, peer: this.deps.peers.grants(deviceId)}, 'metadata.read', libraryId).allow
  }

  private send(ws: WebSocket, value: unknown): void {
    if (ws.readyState !== ws.OPEN) return
    if (ws.bufferedAmount > this.limits.eventBackpressureBytes) return void ws.terminate()
    ws.send(JSON.stringify(value))
  }

  /** On connect, and after every change: the libraries this peer may read, with our version vector. */
  private async sendVectors(deviceId: string, only?: WebSocket): Promise<void> {
    for (const lib of this.deps.libraries()) {
      if (!this.allowedFor(deviceId, lib.libraryId)) continue
      const vector = formatVector(vectorOf(await listSegments(lib.root, lib.records)))
      for (const ws of only ? [only] : this.eventSockets.get(deviceId) ?? []) this.send(ws, {t: 'segments', library: lib.libraryId, vector})
    }
  }

  private watchLibraries(): void {
    let timer: NodeJS.Timeout | undefined
    const notify = () => {
      clearTimeout(timer)
      timer = setTimeout(() => {
        for (const id of this.eventSockets.keys()) void this.sendVectors(id).catch(() => undefined)
      }, 250)
      timer.unref?.()
    }
    for (const lib of this.deps.libraries()) this.changeListeners.set(lib.libraryId, lib.onChange(notify))
  }

  /** A notice for one peer only (the progress of a job it asked for). Dropped when the peer has no event stream open: it asks again on reconnect. */
  sendTo(deviceId: string, value: unknown): void {
    for (const ws of this.eventSockets.get(deviceId) ?? []) this.send(ws, value)
  }

  /** Tells every connected peer that may read [libraryId] that something changed. */
  announce(): void {
    for (const id of this.eventSockets.keys()) void this.sendVectors(id).catch(() => undefined)
  }
}

export const randomToken = () => randomBytes(16).toString('hex')
