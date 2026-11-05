// Managed engine binaries. yt-dlp and gallery-dl live in <userData>/tools and are
// verified (SHA-256) on every download; ffmpeg comes from ffmpeg-static; the JS
// challenge runtime is Electron's own Node. Paths and versions are cached so jobs
// never spawn `--version` probes.
import {createHash} from 'node:crypto'
import {createWriteStream, statSync} from 'node:fs'
import fs from 'node:fs/promises'
import path from 'node:path'
import {Readable, Transform} from 'node:stream'
import {pipeline} from 'node:stream/promises'
import type {EngineStatus, ToolStatus} from '../../shared/contracts.js'
import {run, toolEnv} from './process.js'
import {renameWithRetry} from '../fsutil.js'

type Channel = 'stable' | 'nightly'
type ManagedName = 'yt-dlp' | 'gallery-dl'

type ToolRecord = {version?: string; channel?: Channel; checkedAt?: number; latest?: string; sha256?: string}
type ToolState = Partial<Record<ManagedName, ToolRecord>> & {ffmpeg?: {path: string; version?: string}}

/** What a packaged build ships in resources/tools (written by scripts/build/fetch-tools.mjs). */
type BundledManifest = Partial<Record<ManagedName, {file: string; version: string; sha256: string}>>

export type ToolManagerOptions = {
  /** <userData>/tools */
  dir: string
  /** process.resourcesPath/tools in packaged builds. */
  resources?: string
  /** Optional directory holding a ready-made download engine; used only as an offline seed. */
  legacyDir?: string
  platform?: NodeJS.Platform
  arch?: string
  /** Absolute path of a Node-compatible executable (Electron or node). */
  execPath: string
  /** True when execPath is Electron and needs ELECTRON_RUN_AS_NODE. */
  electron: boolean
  nodeVersion: string
  /** Value of the ffmpeg-static import (may point inside app.asar). */
  ffmpegStatic?: string | null
  fetch?: typeof fetch
  now?: () => number
  channel?: () => Channel
  /** Release endpoints; overridable for tests. */
  endpoints?: Partial<typeof defaultEndpoints>
}

const defaultEndpoints = {
  github: 'https://github.com',
  githubApi: 'https://api.github.com',
  codebergApi: 'https://codeberg.org/api/v1',
}

const DAY = 86_400_000
const userAgent = 'Keepfin-engine-updater'

export function ytDlpAsset(platform: NodeJS.Platform, arch: string): string {
  if (platform === 'win32') return 'yt-dlp.exe'
  if (platform === 'darwin') return 'yt-dlp_macos'
  return arch === 'arm64' ? 'yt-dlp_linux_aarch64' : 'yt-dlp_linux'
}

/** Parses `sha256  name` lines (SHA2-256SUMS / SHA256SUMS). */
export function parseChecksums(text: string): Map<string, string> {
  const sums = new Map<string, string>()
  for (const line of text.split(/\r?\n/)) {
    const match = line.trim().match(/^([a-f0-9]{64})\s+\*?(.+)$/i)
    if (match) sums.set(match[2].trim(), match[1].toLowerCase())
  }
  return sums
}

/** Date-style versions (2026.08.19[.123456]) and semver (1.32.15) compare correctly. */
export function newerVersion(a?: string, b?: string): boolean {
  if (!a) return false
  if (!b) return true
  const pa = a.replace(/^v/, '').split(/[.-]/).map(Number)
  const pb = b.replace(/^v/, '').split(/[.-]/).map(Number)
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const x = pa[i] ?? 0
    const y = pb[i] ?? 0
    if (Number.isNaN(x) || Number.isNaN(y)) return a > b
    if (x !== y) return x > y
  }
  return false
}

/** ffmpeg-static resolves inside app.asar in packaged builds; binaries live in app.asar.unpacked. */
export function unpackedPath(file: string): string {
  return file.replace(/app\.asar([\\/])/, 'app.asar.unpacked$1')
}

export class ToolManager {
  private state: ToolState = {}
  private statuses = new Map<ToolStatus['name'], ToolStatus>()
  private tasks = new Map<ManagedName, Promise<string>>()
  private listeners = new Set<() => void>()
  private ffmpegPath?: string
  private ready?: Promise<void>
  private readonly platform: NodeJS.Platform
  private readonly arch: string
  private readonly endpoints: typeof defaultEndpoints
  private readonly fetcher: typeof fetch

  constructor(private readonly options: ToolManagerOptions) {
    this.platform = options.platform ?? process.platform
    this.arch = options.arch ?? process.arch
    this.endpoints = {...defaultEndpoints, ...options.endpoints}
    this.fetcher = options.fetch ?? fetch
    for (const name of ['yt-dlp', 'gallery-dl', 'ffmpeg', 'js-runtime'] as const)
      this.statuses.set(name, {name, state: 'missing'})
    this.set('js-runtime', {
      state: 'ready',
      version: options.nodeVersion,
      message: 'Built in',
    })
  }

  private now() {
    return this.options.now?.() ?? Date.now()
  }
  private exe(name: ManagedName) {
    return path.join(this.options.dir, this.platform === 'win32' ? `${name}.exe` : name)
  }
  private get stateFile() {
    return path.join(this.options.dir, 'tools.json')
  }
  private channel(): Channel {
    return this.options.channel?.() ?? 'stable'
  }

  onChange(listener: () => void) {
    this.listeners.add(listener)
    return () => void this.listeners.delete(listener)
  }
  private set(name: ToolStatus['name'], patch: Partial<ToolStatus>) {
    const current = this.statuses.get(name)!
    const next = {...current, ...patch, name}
    for (const key of Object.keys(next) as (keyof ToolStatus)[])
      if (next[key] === undefined) delete next[key]
    this.statuses.set(name, next)
    for (const listener of this.listeners) {
      try {
        listener()
      } catch {}
    }
  }

  status(): EngineStatus {
    const tools = [...this.statuses.values()].map((tool) => ({...tool}))
    return {tools, busy: tools.some((tool) => tool.state === 'installing' || tool.state === 'updating')}
  }

  /** Loads cached state, removes leftovers and seeds yt-dlp. Safe to call repeatedly. */
  init(): Promise<void> {
    return (this.ready ??= this.initialize())
  }

  private async initialize() {
    await fs.mkdir(this.options.dir, {recursive: true})
    try {
      this.state = JSON.parse(await fs.readFile(this.stateFile, 'utf8'))
    } catch {
      this.state = {}
    }
    // Replaced executables that were running during an update are deleted now.
    for (const name of await fs.readdir(this.options.dir).catch(() => [] as string[]))
      if (/\.(old-\d+|download)(\.exe)?$/.test(name))
        await fs.rm(path.join(this.options.dir, name), {force: true}).catch(() => {})
    this.resolveFfmpeg()
    for (const name of ['yt-dlp', 'gallery-dl'] as const) {
      const record = this.state[name]
      if (await exists(this.exe(name)))
        this.set(name, {state: 'ready', version: record?.version, latest: record?.latest, checkedAt: record?.checkedAt})
    }
    await this.seedFromBundle().catch(() => {})
  }

  private async save() {
    await fs.writeFile(this.stateFile + '.tmp', JSON.stringify(this.state, null, 2))
    await renameWithRetry(this.stateFile + '.tmp', this.stateFile)
  }

  // ------------------------------------------------------------------ ffmpeg

  private resolveFfmpeg() {
    const candidates: string[] = []
    if (this.options.ffmpegStatic) candidates.push(unpackedPath(this.options.ffmpegStatic))
    const exe = this.platform === 'win32' ? 'ffmpeg.exe' : 'ffmpeg'
    for (const dir of (process.env.PATH ?? '').split(path.delimiter).filter(Boolean))
      candidates.push(path.join(dir, exe))
    // Synchronous existence checks happen once at startup, never per job.
    for (const candidate of candidates) {
      if (fileExistsSync(candidate)) {
        this.ffmpegPath = candidate
        break
      }
    }
    if (!this.ffmpegPath) {
      this.set('ffmpeg', {state: 'missing', message: 'The media processor was not found. Merging and conversion are unavailable.'})
      return
    }
    const cached = this.state.ffmpeg
    if (cached?.path === this.ffmpegPath && cached.version) {
      this.set('ffmpeg', {state: 'ready', version: cached.version})
      return
    }
    this.set('ffmpeg', {state: 'ready'})
    // One version probe per new ffmpeg path, in the background.
    void run(this.ffmpegPath, ['-hide_banner', '-version'], {collectStdout: true, timeoutMs: 15_000})
      .then(async ({stdout}) => {
        const version = stdout.match(/ffmpeg version (\S+)/)?.[1]?.slice(0, 40)
        this.state.ffmpeg = {path: this.ffmpegPath!, version}
        this.set('ffmpeg', {state: 'ready', version})
        await this.save()
      })
      .catch(() => {})
  }

  ffmpeg(): string | undefined {
    return this.ffmpegPath
  }

  /** JS challenge runtime for yt-dlp (`--js-runtimes node:<path>`). */
  jsRuntime(): {path: string; env: NodeJS.ProcessEnv} {
    return {
      path: this.options.execPath,
      env: toolEnv(this.options.electron ? {ELECTRON_RUN_AS_NODE: '1'} : {}),
    }
  }

  // ------------------------------------------------------------------ seeding

  private async readBundle(): Promise<BundledManifest | undefined> {
    if (!this.options.resources) return
    try {
      return JSON.parse(await fs.readFile(path.join(this.options.resources, 'tools.json'), 'utf8'))
    } catch {
      return
    }
  }

  /** Copies a newer bundled binary over the managed one (first run, or after an app update). */
  private async seedFromBundle() {
    const bundle = await this.readBundle()
    for (const name of ['yt-dlp', 'gallery-dl'] as const) {
      const entry = bundle?.[name]
      if (!entry || !this.options.resources || !/^[\w.-]+$/.test(entry.file)) continue
      const managed = (await exists(this.exe(name))) ? this.state[name]?.version : undefined
      if (managed && !newerVersion(entry.version, managed)) continue
      const source = path.join(this.options.resources, entry.file)
      if (!(await exists(source))) continue
      if ((await sha256File(source)) !== entry.sha256.toLowerCase()) continue
      await this.install(name, source, entry.version, true)
    }
  }

  private async seedFromLegacy(): Promise<boolean> {
    if (!this.options.legacyDir) return false
    const source = path.join(this.options.legacyDir, this.platform === 'win32' ? 'yt-dlp.exe' : 'yt-dlp')
    if (!(await exists(source))) return false
    const version = await this.probeVersion(source).catch(() => undefined)
    if (!version) return false
    await this.install('yt-dlp', source, version, true)
    return true
  }

  private async probeVersion(file: string) {
    const {stdout, code} = await run(file, ['--version'], {collectStdout: true, timeoutMs: 60_000})
    const version = stdout.trim().split(/\s+/)[0]
    if (code !== 0 || !/^\d{4}\.\d{2}\.\d{2}|^\d+\.\d+/.test(version ?? ''))
      throw new Error('The downloaded engine did not start.')
    return version
  }

  /** Atomically places `source` at the managed path; a running old binary is renamed aside. */
  private async install(name: ManagedName, source: string, version: string, copy: boolean, sha256?: string) {
    const target = this.exe(name)
    const staging = `${target}.download`
    if (copy) await fs.copyFile(source, staging)
    else if (source !== staging) await fs.rename(source, staging)
    if (this.platform !== 'win32') await fs.chmod(staging, 0o755)
    let previous: string | undefined
    if (await exists(target)) {
      previous = path.join(this.options.dir, `${name}.old-${this.now()}${this.platform === 'win32' ? '.exe' : ''}`)
      await fs.rename(target, previous)
    }
    try {
      await fs.rename(staging, target)
    } catch (error) {
      if (previous) await fs.rename(previous, target).catch(() => {})
      throw error
    }
    if (previous) await fs.rm(previous, {force: true}).catch(() => {})
    this.state[name] = {...this.state[name], version, channel: this.channel(), sha256}
    await this.save()
    this.set(name, {state: 'ready', version, message: undefined})
  }

  // ------------------------------------------------------------------ public paths

  /** Ready-to-run yt-dlp path; seeds or downloads it on first use. */
  async ytDlp(): Promise<string> {
    await this.init()
    const pending = this.tasks.get('yt-dlp')
    if (pending) await pending.catch(() => {})
    if (await exists(this.exe('yt-dlp'))) {
      if (!this.state['yt-dlp']?.version) {
        const version = await this.probeVersion(this.exe('yt-dlp')).catch(() => undefined)
        this.state['yt-dlp'] = {...this.state['yt-dlp'], version}
        this.set('yt-dlp', {state: 'ready', version})
        await this.save().catch(() => {})
      }
      return this.exe('yt-dlp')
    }
    return this.task('yt-dlp', async () => {
      this.set('yt-dlp', {state: 'installing', message: 'Setting up the download engine…'})
      if (!(await this.seedFromLegacy().catch(() => false))) await this.download('yt-dlp')
      return this.exe('yt-dlp')
    })
  }

  /** gallery-dl is installed lazily, the first time an image post needs it. */
  async galleryDl(): Promise<string> {
    await this.init()
    const pending = this.tasks.get('gallery-dl')
    if (pending) await pending.catch(() => {})
    if (await exists(this.exe('gallery-dl'))) return this.exe('gallery-dl')
    return this.task('gallery-dl', async () => {
      this.set('gallery-dl', {state: 'installing', message: 'Installing the image engine…'})
      await this.download('gallery-dl')
      return this.exe('gallery-dl')
    })
  }

  private task(name: ManagedName, work: () => Promise<string>): Promise<string> {
    const existing = this.tasks.get(name)
    if (existing) return existing
    const promise = work()
      .catch((error) => {
        this.set(name, {
          state: this.statuses.get(name)?.version ? 'ready' : 'error',
          message: `Engine update failed: ${String((error as Error).message).slice(0, 200)}`,
        })
        throw error
      })
      .finally(() => this.tasks.delete(name))
    this.tasks.set(name, promise)
    return promise
  }

  // ------------------------------------------------------------------ updates

  /** Checks for and installs newer releases. Returns after both tools were handled. */
  async update(options: {force?: boolean} = {}): Promise<EngineStatus> {
    await this.init()
    const names: ManagedName[] = ['yt-dlp']
    if (await exists(this.exe('gallery-dl'))) names.push('gallery-dl')
    const errors: string[] = []
    for (const name of names) {
      try {
        await this.task(name, async () => {
          this.set(name, {state: 'updating', message: 'Checking for updates…'})
          const release = await this.latest(name)
          const record = this.state[name] ?? {}
          record.latest = release.version
          record.checkedAt = this.now()
          this.state[name] = record
          const switching = record.channel !== undefined && record.channel !== this.channel()
          if (options.force || switching || newerVersion(release.version, record.version) || !(await exists(this.exe(name))))
            await this.download(name, release)
          else {
            await this.save()
            this.set(name, {state: 'ready', latest: release.version, checkedAt: record.checkedAt, message: undefined})
          }
          return this.exe(name)
        })
      } catch (error) {
        errors.push(`${toolLabel(name)}: ${(error as Error).message}`)
      }
    }
    if (errors.length && errors.length === names.length) throw new Error(`Engine update failed. ${errors.join(' ')}`)
    return this.status()
  }

  /** Daily background check; never throws. */
  async autoUpdate(enabled: boolean) {
    if (!enabled) return
    await this.init()
    const checked = this.state['yt-dlp']?.checkedAt ?? 0
    if (this.now() - checked < DAY) return
    await this.update().catch(() => {})
  }

  private async json(url: string): Promise<unknown> {
    const response = await this.fetcher(url, {
      headers: {Accept: 'application/json', 'User-Agent': userAgent},
      signal: AbortSignal.timeout(20_000),
    })
    if (!response.ok) throw new Error(`Release check failed (${response.status}).`)
    return response.json()
  }

  private async text(url: string): Promise<string> {
    const response = await this.fetcher(url, {headers: {'User-Agent': userAgent}, signal: AbortSignal.timeout(20_000)})
    if (!response.ok) throw new Error(`Could not download checksums (${response.status}).`)
    const text = await response.text()
    if (text.length > 200_000) throw new Error('Checksum file is too large.')
    return text
  }

  private async githubLatestTag(repo: string): Promise<string> {
    try {
      const data = (await this.json(`${this.endpoints.githubApi}/repos/${repo}/releases/latest`)) as {tag_name?: unknown}
      if (typeof data.tag_name === 'string' && /^[\w.-]{1,60}$/.test(data.tag_name)) return data.tag_name
    } catch {}
    // API rate limits (60/h) fall back to the release redirect.
    const response = await this.fetcher(`${this.endpoints.github}/${repo}/releases/latest`, {
      redirect: 'manual',
      headers: {'User-Agent': userAgent},
      signal: AbortSignal.timeout(20_000),
    })
    const tag = response.headers.get('location')?.match(/\/releases\/tag\/([\w.-]{1,60})$/)?.[1]
    if (!tag) throw new Error('Could not find the latest engine release.')
    return tag
  }

  private async latest(name: ManagedName): Promise<Release> {
    const channel = this.channel()
    if (name === 'yt-dlp') {
      const repo = channel === 'nightly' ? 'yt-dlp/yt-dlp-nightly-builds' : 'yt-dlp/yt-dlp'
      const tag = await this.githubLatestTag(repo)
      const asset = ytDlpAsset(this.platform, this.arch)
      const base = `${this.endpoints.github}/${repo}/releases/download/${tag}`
      return {
        version: tag,
        url: `${base}/${asset}`,
        sha256: async () => {
          const sum = parseChecksums(await this.text(`${base}/SHA2-256SUMS`)).get(asset)
          if (!sum) throw new Error('The release has no checksum for this platform.')
          return sum
        },
      }
    }
    if (channel === 'stable' && this.platform !== 'darwin') {
      // gallery-dl publishes stable standalone builds on Codeberg.
      const release = (await this.json(`${this.endpoints.codebergApi}/repos/mikf/gallery-dl/releases/latest`)) as GiteaRelease
      const assetName = this.platform === 'win32' ? 'gallery-dl.exe' : 'gallery-dl.bin'
      const asset = release.assets?.find((item) => item.name === assetName)
      const sums = release.assets?.find((item) => item.name === 'SHA256SUMS')
      if (!asset || !sums || typeof release.tag_name !== 'string') throw new Error('The gallery engine release is incomplete.')
      return {
        version: release.tag_name.replace(/^v/, ''),
        url: checkedUrl(asset.browser_download_url),
        sha256: async () => {
          const sum = parseChecksums(await this.text(checkedUrl(sums.browser_download_url))).get(assetName)
          if (!sum) throw new Error('The gallery engine release has no checksum for this platform.')
          return sum
        },
      }
    }
    // Nightly (and macOS) builds: GitHub gdl-org/builds with API-provided asset digests.
    const release = (await this.json(`${this.endpoints.githubApi}/repos/gdl-org/builds/releases/latest`)) as GithubRelease
    const assetName =
      this.platform === 'win32' ? 'gallery-dl_windows.exe' : this.platform === 'darwin' ? 'gallery-dl_macos' : 'gallery-dl_linux'
    const asset = release.assets?.find((item) => item.name === assetName)
    const digest = asset?.digest?.match(/^sha256:([a-f0-9]{64})$/i)?.[1]
    if (!asset || !digest || typeof release.tag_name !== 'string') throw new Error('The gallery engine build is unavailable for this platform.')
    return {version: release.tag_name, url: checkedUrl(asset.browser_download_url), sha256: async () => digest.toLowerCase()}
  }

  private async download(name: ManagedName, known?: Release) {
    const release = known ?? (await this.latest(name))
    this.set(name, {state: this.statuses.get(name)?.version ? 'updating' : 'installing', message: `Downloading ${toolLabel(name).toLowerCase()} ${release.version}…`})
    const expected = await release.sha256()
    const staging = `${this.exe(name)}.download`
    const response = await this.fetcher(release.url, {headers: {'User-Agent': userAgent}, signal: AbortSignal.timeout(600_000)})
    if (!response.ok || !response.body) throw new Error(`Download failed (${response.status}).`)
    const hash = createHash('sha256')
    let size = 0
    const meter = new Transform({
      transform(chunk: Buffer, _encoding, callback) {
        size += chunk.length
        if (size > 300_000_000) return callback(new Error('Engine download is unexpectedly large.'))
        hash.update(chunk)
        callback(null, chunk)
      },
    })
    try {
      await pipeline(Readable.fromWeb(response.body as never), meter, createWriteStream(staging, {mode: 0o755}))
      const actual = hash.digest('hex')
      if (actual !== expected) throw new Error('Checksum mismatch; the download was discarded.')
      // A real start proves the binary works on this machine before it replaces anything.
      const version = (await this.probeVersion(staging).catch(() => undefined)) ?? release.version
      await this.install(name, staging, version, false, actual)
      const record = this.state[name]!
      record.latest = release.version
      record.checkedAt = this.now()
      await this.save()
      this.set(name, {state: 'ready', version, latest: release.version, checkedAt: record.checkedAt, message: undefined})
    } catch (error) {
      await fs.rm(staging, {force: true}).catch(() => {})
      throw error
    }
  }
}

type Release = {version: string; url: string; sha256: () => Promise<string>}
type GiteaRelease = {tag_name?: unknown; assets?: {name: string; browser_download_url: string}[]}
type GithubRelease = {tag_name?: unknown; assets?: {name: string; browser_download_url: string; digest?: string}[]}

function checkedUrl(value: unknown): string {
  if (typeof value !== 'string' || !/^https:\/\//.test(value)) throw new Error('Invalid release asset URL.')
  return value
}

async function exists(file: string) {
  try {
    return (await fs.stat(file)).isFile()
  } catch {
    return false
  }
}

async function sha256File(file: string) {
  const hash = createHash('sha256')
  hash.update(await fs.readFile(file))
  return hash.digest('hex')
}

function fileExistsSync(file: string) {
  try {
    return statSync(file).isFile()
  } catch {
    return false
  }
}

function toolLabel(name: ManagedName) {
  return name === 'yt-dlp' ? 'Download engine' : 'Gallery engine'
}
