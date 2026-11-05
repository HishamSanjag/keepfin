// The network client: one pinned TLS 1.3 connection pool to one peer. The pin is checked on every new socket before any byte is
// written; an unpinned client (pairing by address) accepts the first key it sees and then insists on that same key.
import http from 'node:http'
import type {IncomingHttpHeaders, IncomingMessage} from 'node:http'
import {X509Certificate} from 'node:crypto'
import type {Readable} from 'node:stream'
import tls from 'node:tls'
import WebSocket from 'ws'
import {deviceIdOf, pairPin, pinMatches} from '../../shared/core/net.js'
import type {DeviceIdentity} from './identity.js'

export class PinMismatch extends Error {
  constructor() {
    super('This is not the device that was paired: its key is different.')
    this.name = 'PinMismatch'
  }
}

export class PeerHttpError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly body?: Record<string, unknown>,
  ) {
    super(message)
    this.name = 'PeerHttpError'
  }
}

export interface ClientOptions {
  identity: Pick<DeviceIdentity, 'keyPem' | 'certPem'>
  host: string
  port: number
  /** The device id (SHA-256 of the SPKI) the peer must have, or null to accept the first key and keep it. */
  pin: string | null
  /** The first 30 bits of the key's pin that a typed code carried: a key that does not start with them is refused before any request is written. */
  pinBits?: number
  connectTimeoutMs?: number
  /** Idle sockets stay this long. */
  keepAliveMs?: number
}

export interface ClientResponse {
  status: number
  headers: IncomingHttpHeaders
  res: IncomingMessage
}

export class PeerClient {
  private agent: http.Agent
  private pin: string | null
  peerSpki?: Uint8Array
  peerDeviceId?: string

  constructor(private readonly options: ClientOptions) {
    this.pin = options.pin
    this.agent = new http.Agent({keepAlive: true, maxSockets: 4, keepAliveMsecs: options.keepAliveMs ?? 15_000})
    ;(this.agent as unknown as {createConnection: unknown}).createConnection = (_o: unknown, cb: (error: Error | null, socket?: tls.TLSSocket) => void) => this.connect(cb)
  }

  get host(): string {
    return this.options.host
  }

  get port(): number {
    return this.options.port
  }

  /** Opens one verified TLS socket. */
  private connect(done: (error: Error | null, socket?: tls.TLSSocket) => void): void {
    const {identity, host, port} = this.options
    const socket = tls.connect({
      host,
      port,
      key: identity.keyPem,
      cert: identity.certPem,
      minVersion: 'TLSv1.3',
      maxVersion: 'TLSv1.3',
      rejectUnauthorized: false, // the pin is the trust; the handshake still proves the peer holds the key
      checkServerIdentity: () => undefined,
      ALPNProtocols: ['http/1.1'],
      servername: undefined,
    })
    let finished = false
    const finish = (error: Error | null) => {
      if (finished) return
      finished = true
      clearTimeout(timer)
      if (error) {
        socket.destroy()
        done(error)
      } else done(null, socket)
    }
    const timer = setTimeout(() => finish(new Error('The device did not answer.')), this.options.connectTimeoutMs ?? 10_000)
    timer.unref?.()
    socket.once('error', (error) => finish(error))
    socket.once('secureConnect', () => {
      try {
        const raw = socket.getPeerCertificate()?.raw
        if (!raw) return finish(new PinMismatch())
        const spki = new X509Certificate(raw).publicKey.export({type: 'spki', format: 'der'}) as Buffer
        const id = deviceIdOf(spki)
        if (this.pin !== null && id !== this.pin) return finish(new PinMismatch())
        if (this.options.pinBits !== undefined && !pinMatches(pairPin(new Uint8Array(spki)), this.options.pinBits)) return finish(new PinMismatch())
        this.pin = id // the first key stays the key
        this.peerSpki = new Uint8Array(spki)
        this.peerDeviceId = id
        socket.setNoDelay(true)
        finish(null)
      } catch (error) {
        finish(error as Error)
      }
    })
  }

  /** Sends a request. [body] is a Buffer, a string or a stream (then [contentLength] is required). */
  request(method: string, path: string, options: {json?: unknown; body?: Buffer | string | Readable; contentLength?: number; headers?: Record<string, string>; timeoutMs?: number; signal?: AbortSignal} = {}): Promise<ClientResponse> {
    return new Promise((resolve, reject) => {
      const headers: Record<string, string | number> = {...options.headers}
      let payload: Buffer | undefined
      if (options.json !== undefined) {
        payload = Buffer.from(JSON.stringify(options.json), 'utf8')
        headers['content-type'] = 'application/json'
      } else if (typeof options.body === 'string') payload = Buffer.from(options.body, 'utf8')
      else if (Buffer.isBuffer(options.body)) payload = options.body
      if (payload) headers['content-length'] = payload.length
      else if (options.body && options.contentLength !== undefined) headers['content-length'] = options.contentLength
      const req = http.request({agent: this.agent, host: this.options.host, port: this.options.port, method, path, headers, signal: options.signal, timeout: options.timeoutMs ?? 30_000})
      req.on('timeout', () => req.destroy(new Error('The device stopped answering.')))
      req.on('error', reject)
      req.on('response', (res) => resolve({status: res.statusCode ?? 0, headers: res.headers, res}))
      if (payload) req.end(payload)
      else if (options.body && typeof options.body !== 'string') {
        ;(options.body as Readable).on('error', (error) => req.destroy(error))
        ;(options.body as Readable).pipe(req)
      } else req.end()
    })
  }

  /** A request whose answer is JSON; any status of 400 or more throws PeerHttpError with the peer's words. */
  async json<T = Record<string, unknown>>(method: string, path: string, options: {json?: unknown; timeoutMs?: number; signal?: AbortSignal} = {}): Promise<T> {
    const response = await this.request(method, path, options)
    const bytes = await collect(response.res, 4 * 1024 * 1024)
    let body: Record<string, unknown> = {}
    try {
      body = bytes.length ? JSON.parse(bytes.toString('utf8')) : {}
    } catch {
      body = {}
    }
    if (response.status >= 400) throw new PeerHttpError(response.status, String(body.error ?? 'error'), String(body.message ?? `The device answered ${response.status}.`), body)
    return body as T
  }

  /** The WebSocket of change notices. */
  events(onMessage: (value: unknown) => void, onClose: () => void): WebSocket {
    const ws = new WebSocket(`wss://${this.options.host.includes(':') ? `[${this.options.host}]` : this.options.host}:${this.options.port}/v1/events`, {
      createConnection: ((_o: unknown, cb: (error: Error | null, socket?: tls.TLSSocket) => void) => this.connect(cb)) as never,
      perMessageDeflate: false,
      maxPayload: 256 * 1024,
      handshakeTimeout: 10_000,
    })
    ws.on('message', (data) => {
      try {
        onMessage(JSON.parse(data.toString()))
      } catch {
        // a message that is not JSON is ignored
      }
    })
    ws.on('close', onClose)
    ws.on('error', () => undefined)
    return ws
  }

  close(): void {
    this.agent.destroy()
  }
}

export async function collect(stream: Readable, max: number): Promise<Buffer> {
  const chunks: Buffer[] = []
  let size = 0
  for await (const chunk of stream) {
    size += (chunk as Buffer).length
    if (size > max) {
      stream.destroy()
      throw new Error('The answer is too large.')
    }
    chunks.push(chunk as Buffer)
  }
  return Buffer.concat(chunks)
}
