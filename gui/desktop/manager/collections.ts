// Collections: discovery sessions (paged, selectable) and following a collection. Moved out of manager.ts;
// everything it needs from the queue comes through `QueueCore`.
import {randomUUID} from 'node:crypto'
import {validateUrl, type DownloadOptions, type FormatRequest, type JobGroup, type PlaylistDiscovery, type PlaylistEntry} from '../../shared/contracts.js'
import {
  collectionKindOf,
  entryKey,
  folderSpaces,
  followIntervals,
  type Collection,
  type CollectionFollow,
  type Space,
} from '../../shared/library.js'
import {formatLabel, presetFormat} from '../../shared/policy.js'
import {prefersAudio, providerName} from '../../shared/providers.js'
import {sanitizeGroup, type StoredJob} from '../migrate.js'
import type {SignInSource} from '../../shared/core/signin.js'
import {errorText, PAGE_SIZE, playlistOf, type QueueCore} from './core.js'

type Session = {
  discovery: PlaylistDiscovery
  url: string
  epoch: number
  controller?: AbortController
  seen: Set<string>
  queued: Set<string>
  page: number
  group?: JobGroup
  /** The sign-in the site asked for while listing; the jobs made from the review carry it. */
  signIn?: SignInSource
}
const sessionId = /^[a-zA-Z0-9_-]{1,100}$/

export class Collections {
  private readonly sessions = new Map<string, Session>()
  private discoveryTimers = new Map<string, NodeJS.Timeout>()
  // follow checks in flight (aborted when cookies change or the app closes)
  private readonly followControllers = new Set<AbortController>()

  constructor(private readonly core: QueueCore) {}

  /** The app is closing: stop every discovery and follow check. */
  close() {
    for (const id of this.sessions.keys()) this.cancelDiscovery(id)
    for (const controller of this.followControllers) controller.abort(new Error('Keepfin is closing.'))
  }

  /** Drops the pending discovery pushes (the last step of closing). */
  clearTimers() {
    for (const timer of this.discoveryTimers.values()) clearTimeout(timer)
  }

  /** Cookie/proxy changes: every review fails with the message and every follow check is thrown away. */
  invalidate(message: string) {
    for (const session of this.sessions.values()) {
      session.controller?.abort(new Error(message))
      session.discovery = {
        ...session.discovery,
        entries: [],
        state: 'failed',
        error: message,
        truncated: false,
      }
      session.seen.clear()
      this.pushDiscovery(session, true)
    }
    for (const controller of this.followControllers) controller.abort(new Error(message))
  }

  /** True when an entry of an open review has this thumbnail. */
  hasThumbnail(url: string) {
    for (const session of this.sessions.values())
      if (session.discovery.entries.some((entry) => entry.thumbnail === url)) return true
    return false
  }

  /** The entries of an open review, if the id names one. */
  entriesOf(id: unknown) {
    return typeof id === 'string' ? this.sessions.get(id)?.discovery.entries : undefined
  }

  private pushDiscovery(session: Session, immediate = false) {
    const id = session.discovery.id
    const send = () => {
      this.discoveryTimers.delete(id)
      this.core.emit({
        type: 'discovery',
        discovery: structuredClone(session.discovery),
      })
    }
    if (immediate) {
      clearTimeout(this.discoveryTimers.get(id))
      return send()
    }
    if (!this.discoveryTimers.has(id))
      this.discoveryTimers.set(id, setTimeout(send, 250))
  }

  startDiscovery(input: unknown, id: unknown) {
    const url = validateUrl(input)
    if (typeof id !== 'string' || !sessionId.test(id))
      throw new Error('Invalid discovery session.')
    if (this.sessions.has(id))
      throw new Error('This discovery session already exists.')
    if (
      [...this.sessions.values()].filter(
        (s) => s.discovery.state === 'discovering',
      ).length >= 2
    )
      throw new Error('Finish or cancel another collection first.')
    while (this.sessions.size >= 8) {
      const old = [...this.sessions].find(
        ([, s]) => s.discovery.state !== 'discovering',
      )
      if (!old) throw new Error('Too many collection sessions.')
      this.sessions.delete(old[0])
    }
    const session: Session = {
      discovery: {
        id,
        entries: [],
        state: 'discovering',
        limit: PAGE_SIZE,
        truncated: false,
      },
      url,
      epoch: this.core.epoch(),
      seen: new Set(),
      queued: new Set(),
      page: 0,
    }
    this.sessions.set(id, session)
    void this.loadPage(session, 1, PAGE_SIZE)
    return structuredClone(session.discovery)
  }

  private async loadPage(session: Session, start: number, end: number) {
    const controller = new AbortController()
    session.controller = controller
    const untrack = this.core.track(session.discovery.id, controller)
    const epoch = session.epoch
    const live = () =>
      session.controller === controller &&
      !controller.signal.aborted &&
      epoch === this.core.epoch()
    const before = session.discovery.entries.length
    try {
      const {value: result, used} = await this.core.withMetadataSlot(controller.signal, () =>
        this.core.withSignIn(session.url, controller.signal, session.signIn, (cookies) => {
          const ctx = this.core.context(controller.signal)
          return this.core.options.engine.discover(
            session.url,
            {start, end},
            cookies ? {...ctx, cookies} : ctx,
            (entry: PlaylistEntry) => {
              if (!live() || session.seen.has(entry.url)) return
              session.seen.add(entry.url)
              session.discovery.entries.push(entry)
              this.pushDiscovery(session)
            },
            (meta) => {
              if (!live()) return
              session.discovery.title ??= meta.title
              session.discovery.uploader ??= meta.uploader
            },
          )
        }),
      )
      if (!live()) return
      session.signIn = used
      session.discovery.truncated = result.truncated
      const note =
        [result.partialError, result.notice].filter(Boolean).join(' ') ||
        undefined
      if (!session.discovery.entries.length) {
        session.discovery.state = 'failed'
        session.discovery.error =
          note ??
          'No downloadable entries were found. Try an individual video link.'
      } else {
        session.discovery.state = 'completed'
        session.discovery.error = undefined
        session.discovery.notice = note
      }
    } catch (error) {
      if (session.controller !== controller || epoch !== this.core.epoch()) return
      if (session.discovery.state === 'cancelled') return
      session.discovery.state =
        session.discovery.entries.length > before || (before && start > 1)
          ? 'completed'
          : 'failed'
      if (session.discovery.state === 'completed') {
        // Partial pages stay usable: report the interruption as a notice.
        session.discovery.notice = errorText(error)
        session.discovery.truncated = true
      } else session.discovery.error = errorText(error)
    } finally {
      untrack()
      if (session.controller === controller) session.controller = undefined
      this.pushDiscovery(session, true)
    }
  }

  private session(id: unknown) {
    const session = typeof id === 'string' ? this.sessions.get(id) : undefined
    if (!session)
      throw new Error('Collection review expired. Inspect the link again.')
    return session
  }

  discoveryState(id: unknown) {
    return structuredClone(this.session(id).discovery)
  }

  discoveryMore(id: unknown) {
    const session = this.session(id)
    if (session.discovery.state === 'discovering')
      throw new Error('This collection is still loading.')
    if (!session.discovery.truncated)
      throw new Error('There are no more entries to load.')
    const start = session.discovery.limit + 1
    session.discovery.limit += PAGE_SIZE
    session.discovery.state = 'discovering'
    session.discovery.error = undefined
    session.discovery.notice = undefined
    void this.loadPage(session, start, session.discovery.limit)
    return structuredClone(session.discovery)
  }

  cancelDiscovery(id: unknown) {
    const session = typeof id === 'string' ? this.sessions.get(id) : undefined
    if (!session) return null
    if (session.discovery.state === 'discovering') {
      session.discovery.state = 'cancelled'
      session.controller?.abort(new Error('Discovery cancelled.'))
      this.pushDiscovery(session, true)
    }
    return structuredClone(session.discovery)
  }

  async enqueueDiscovered(
    id: unknown,
    urlsInput: unknown,
    preset: unknown,
    optionsInput?: unknown,
    grouped: unknown = true,
    spaceInput?: unknown,
    keepInSyncInput?: unknown,
    findVideoInput?: unknown,
    playlistInput?: unknown,
  ) {
    const session = this.session(id)
    const urls = [
      ...new Set(
        (Array.isArray(urlsInput) ? urlsInput : []).map((item) =>
          validateUrl(item),
        ),
      ),
    ]
    if (!urls.length) throw new Error('Select at least one entry.')
    const format = presetFormat(preset ?? this.core.settings().defaultPreset)
    const options = this.core.resolveOptions(optionsInput)
    const keepInSyncHours = this.resolveKeepInSync(keepInSyncInput)
    // music videos go with songs downloaded as audio from a music collection, with the library on
    const findVideo = findVideoInput === true
    if (findVideo && (format.type !== 'audio' || !this.core.options.library || !this.core.settings().library.enabled || !prefersAudio(session.url)))
      throw new Error('Music videos go with songs from a music collection downloaded as audio.')
    const entries = new Map(
      session.discovery.entries.map((entry) => [entry.url, entry]),
    )
    const fresh: PlaylistEntry[] = []
    for (const url of urls) {
      const entry = entries.get(url)
      if (!entry)
        throw new Error(
          'Inspect this collection again before downloading these entries.',
        )
      if (!session.queued.has(url)) fresh.push(entry)
    }
    if (!fresh.length) throw new Error('These entries are already queued.')
    // Validated once up front so a bad choice queues nothing.
    const spaces = new Map(fresh.map((entry) => [entry.url, this.core.resolveSpace(entry.url, spaceInput)]))
    this.core.checkCapacity(fresh.length)
    const group = grouped === false ? undefined : this.sessionGroup(session)
    const collection = this.collectionFor(session, spaces.get(fresh[0].url), keepInSyncHours)
    for (const entry of fresh) {
      session.queued.add(entry.url)
      this.core.jobs().push(
        this.discoveredJob(entry, {
          format,
          options: structuredClone(options),
          group: group && structuredClone(group),
          space: spaces.get(entry.url),
          collection: collection && {id: collection.id, index: entry.position - 1},
          findVideo,
          playlist: spaces.get(entry.url) ? playlistOf(playlistInput) : undefined,
          signIn: session.signIn,
        }),
      )
    }
    await this.core.commit()
    void this.core.pump()
    return this.core.snapshot()
  }

  /** "Keep in sync" from the renderer: structured, never raw. Absent means leave following as it is. */
  private resolveKeepInSync(input: unknown): CollectionFollow['intervalHours'] | undefined {
    if (input === undefined || input === null) return undefined
    const hours = typeof input === 'object' ? (input as Record<string, unknown>).intervalHours : undefined
    if (!(followIntervals as readonly unknown[]).includes(hours)) throw new Error('Choose how often to check for new items.')
    return hours as CollectionFollow['intervalHours']
  }

  /** The persistent collection a discovered set feeds. Everything discovered counts as seen, selected or not. */
  private collectionFor(session: Session, space: string | undefined, keepInSyncHours?: CollectionFollow['intervalHours']): Collection | undefined {
    const library = this.core.options.library
    if (!library || !this.core.settings().library.enabled || !space) return
    const collection = library.ensureCollection({
      url: session.url,
      name: session.discovery.title,
      kind: collectionKindOf(session.url),
      space,
      keepInSyncHours,
    })
    library.noteSeen(collection.id, session.discovery.entries.map((entry) => entryKey(entry.url)))
    return collection
  }

  private discoveredJob(
    entry: PlaylistEntry,
    input: {
      format: FormatRequest
      options: DownloadOptions
      group?: JobGroup
      space?: string
      collection?: {id: string; index: number}
      followed?: boolean
      findVideo?: boolean
      playlist?: string
      signIn?: SignInSource
    },
  ): StoredJob {
    return {
      group: input.group,
      id: randomUUID(),
      url: entry.url,
      title: entry.title || `Item ${entry.position}`,
      thumbnail: entry.thumbnail,
      uploader: entry.uploader,
      duration: entry.duration,
      provider: providerName(entry.url),
      label: formatLabel(input.format),
      kind: input.format.type,
      format: input.format,
      options: input.options,
      status: 'queued',
      percent: 0,
      music:
        entry.artist || entry.album
          ? {
              track: entry.title,
              artist: entry.artist,
              album: entry.album,
              trackNumber: entry.position,
              trackCount: entry.albumTrackCount,
              releaseYear: entry.releaseYear,
            }
          : undefined,
      space: input.space,
      collectionId: input.collection?.id,
      collectionIndex: input.collection?.index,
      followed: input.followed ? true : undefined,
      findVideo: input.findVideo ? true : undefined,
      playlist: input.playlist,
      signIn: input.signIn,
      createdAt: this.core.now(),
    }
  }

  /**
   * Lists a followed source: the paged discovery, up to `cap` entries. A page that failed or was cut short
   * keeps what was read and sets `partial`, so nothing is concluded from what was not seen.
   */
  async followDiscover(url: string, cap = 1000): Promise<{entries: PlaylistEntry[]; truncated: boolean; partial: boolean; title?: string}> {
    const controller = new AbortController()
    this.followControllers.add(controller)
    const entries: PlaylistEntry[] = []
    const keys = new Set<string>()
    let title: string | undefined
    let truncated = false
    let partial = false
    try {
      for (let start = 1; start <= cap; start += PAGE_SIZE) {
        const end = Math.min(cap, start + PAGE_SIZE - 1)
        let result
        try {
          result = await this.core.withMetadataSlot(controller.signal, () =>
            this.core.options.engine.discover(
              url,
              {start, end},
              this.core.context(controller.signal),
              (entry) => {
                if (keys.has(entry.url)) return
                keys.add(entry.url)
                entries.push(entry)
              },
              (meta) => {
                title ??= meta.title
              },
            ),
          )
        } catch (error) {
          if (controller.signal.aborted || !entries.length) throw error
          partial = true
          truncated = true
          break
        }
        truncated = result.truncated
        if (result.partialError) {
          partial = true
          break
        }
        if (!result.truncated) break
      }
      return {entries, truncated, partial: partial || truncated, title}
    } catch (error) {
      throw controller.signal.aborted ? (controller.signal.reason ?? error) : error
    } finally {
      this.followControllers.delete(controller)
    }
  }

  /** The format a followed collection downloads in: what its last job used, else the space default. */
  private followFormat(collection: Collection, space: Space): {format: FormatRequest; options: DownloadOptions} {
    const last = [...this.core.jobs()].reverse().find((job) => job.collectionId === collection.id && job.format.type !== 'gallery')
    if (last) return {format: structuredClone(last.format), options: structuredClone(last.options)}
    const preferred = this.core.settings().defaultPreset
    const audio = preferred.startsWith('audio-')
    const preset = space.kind === 'music' ? (audio ? preferred : 'audio-mp3') : audio ? 'best' : preferred
    return {format: presetFormat(preset), options: structuredClone(this.core.settings().options)}
  }

  /** Queues newly found entries of a followed collection into its space, after the items already there. */
  async followEnqueue(collectionId: string, entries: PlaylistEntry[]): Promise<number> {
    const library = this.core.options.library
    if (!library || !entries.length) return 0
    const collection = library.collection(collectionId)
    const space = folderSpaces(this.core.settings().library.spaces).find((item) => item.id === collection.space)
    if (!space || !this.core.settings().library.enabled) throw new Error('The space for this collection no longer exists.')
    this.core.checkCapacity(entries.length)
    const { format, options } = this.followFormat(collection, space)
    const members = this.core.jobs().filter((job) => job.collectionId === collection.id)
    let next = Math.max(library.maxCollectionIndex(collection.id), ...members.map((job) => job.collectionIndex ?? -1)) + 1
    const group = sanitizeGroup({
      id: randomUUID(),
      title: collection.name,
      url: collection.source?.url ?? entries[0].url,
      uploader: members.at(-1)?.group?.uploader,
      thumbnail: entries.find((entry) => entry.thumbnail)?.thumbnail,
      kind: collection.kind === 'own' ? undefined : collection.kind,
    })
    for (const entry of entries)
      this.core.jobs().push(
        this.discoveredJob(entry, {format, options: structuredClone(options), group, space: space.id, collection: {id: collection.id, index: next++}, followed: true}),
      )
    library.noteSeen(collection.id, entries.map((entry) => entryKey(entry.url)))
    await this.core.commit()
    void this.core.pump()
    return entries.length
  }

  /** One group per discovery session, so several batches from it stay together. */
  private sessionGroup(session: Session): JobGroup | undefined {
    const {discovery} = session
    session.group ??= sanitizeGroup({
      id: randomUUID(),
      title: discovery.title,
      url: session.url,
      uploader: discovery.uploader,
      thumbnail: discovery.entries.find((entry) => entry.thumbnail)?.thumbnail,
      kind: collectionKind(session.url),
    })
    return session.group
  }
}

function collectionKind(url: string): JobGroup['kind'] {
  if (/\/album\/|[?&]list=OLAK|\/browse\/MPRE/i.test(url)) return 'album'
  if (/\/(channel|c|user)\/|\/@[^/]+/i.test(url)) return 'channel'
  if (/[?&]list=|\/playlist|\/sets\//i.test(url)) return 'playlist'
  return 'collection'
}
