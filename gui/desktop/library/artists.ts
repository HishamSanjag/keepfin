// Followed artists and their new releases (Home's "New from artists you follow"). It reuses the follow check's network path: the artist's
// channel is found with the YouTube Music search (never guessed) and its releases page is listed with the engine's discovery. The first check
// of an artist records what already exists; later checks add what is new, dated by when it was found. Nothing downloads except through
// `keep`, one release per tap. The rules (keys, fresh releases, grouping) are the shared core's (gui/shared/core/home.ts).
import {randomUUID} from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import {artistKey, freshReleases, markSeenIds, type FeedRelease} from '../../shared/core/home.js'
import {artistChannelId, artistChannelUrl, isLegacyArtistAddress, type ArtistReleaseHit} from '../../shared/core/music.js'
import type {PlaylistEntry} from '../../shared/contracts.js'
import {noFollowing, type ArtistRelease, type FollowedArtist, type FollowingState} from '../../shared/following.js'

export type ArtistHost = {
  /** The artist's channel id (UC...), or undefined when no result is surely this artist. Throws when the search fails. */
  resolveChannel(name: string, key: string): Promise<string | undefined>
  /** The artist's latest releases, newest first: one request to the artist's YouTube Music page by channel id. */
  listReleases(channelId: string): Promise<ArtistReleaseHit[]>
  /** Queues every track of the release through the normal download path with the person's defaults; returns how many were queued. */
  keep(url: string): Promise<number>
}

export const ARTIST_CHECK_EVERY_MS = 12 * 3_600_000
export const ARTIST_PRUNE_MS = 60 * 86_400_000
const CAP = 200

/** What a failed check of one artist says: a plain sentence, never the raw reason (the logs hold that) or a channel id. */
const CHECK_FAILED = "Keepfin couldn't check this artist right now. It will try again later."

export class ArtistFollowing {
  private state: FollowingState = noFollowing
  private chain: Promise<unknown> = Promise.resolve()
  private loaded = false

  constructor(
    private readonly file: string,
    private readonly host: ArtistHost,
    private readonly onChange: (state: FollowingState) => void = () => {},
    private readonly now: () => number = Date.now,
  ) {}

  private async load() {
    if (this.loaded) return
    this.loaded = true
    try {
      const raw = JSON.parse(await fs.readFile(this.file, 'utf8'))
      if (Array.isArray(raw.artists) && Array.isArray(raw.releases)) {
        // the first follow check read a channel's releases tab, which most channels lack: those artists were never read, so they are checked afresh
        const artists = (raw.artists as FollowedArtist[]).map((artist) => (artist.url && isLegacyArtistAddress(artist.url) ? {...artist, checkedAt: undefined, error: undefined} : artist))
        const seenReleases = Array.isArray(raw.seenReleases) ? (raw.seenReleases as unknown[]).filter((id): id is string => typeof id === 'string') : []
        this.state = {artists, releases: raw.releases, seenReleases, checking: false}
      }
    } catch {}
  }

  private async commit(next: FollowingState) {
    this.state = next
    this.onChange(this.snapshot())
    try {
      await fs.mkdir(path.dirname(this.file), {recursive: true})
      const temp = `${this.file}.tmp`
      await fs.writeFile(temp, JSON.stringify({v: 1, artists: next.artists, releases: next.releases, seenReleases: next.seenReleases}))
      await fs.rename(temp, this.file)
    } catch {}
  }

  snapshot(): FollowingState {
    return structuredClone(this.state)
  }

  async current(): Promise<FollowingState> {
    await this.load()
    return this.snapshot()
  }

  /** Follows `name`; the same artist under any spelling is one follow. Checks it in the background. */
  async follow(name: string): Promise<FollowingState> {
    await this.load()
    const clean = name.trim().replace(/\s+-\s+Topic$/i, '')
    const key = artistKey(clean)
    if (!key) throw new Error('This song has no artist to follow.')
    if (!this.state.artists.some((artist) => artist.key === key)) {
      const artist: FollowedArtist = {id: randomUUID(), name: clean, key, seen: [], checkedOnce: false}
      await this.commit({...this.state, artists: [...this.state.artists, artist]})
      void this.check(artist.id, true).catch(() => {})
    }
    return this.snapshot()
  }

  async unfollow(id: string): Promise<FollowingState> {
    await this.load()
    if (this.state.artists.some((artist) => artist.id === id))
      await this.commit({...this.state, artists: this.state.artists.filter((artist) => artist.id !== id), releases: this.state.releases.filter((release) => release.artistId !== id)})
    return this.snapshot()
  }

  /** Checks one artist now, every artist (`id` undefined, manual) or only those due. One check at a time. */
  check(id: string | undefined, manual: boolean): Promise<FollowingState> {
    const job = this.chain.then(() => this.run(id, manual))
    this.chain = job.catch(() => {})
    return job
  }

  private async run(id: string | undefined, manual: boolean): Promise<FollowingState> {
    await this.load()
    const at = this.now()
    const wanted = this.state.artists.filter((artist) => (id ? artist.id === id : manual || artist.checkedAt === undefined || at - artist.checkedAt >= ARTIST_CHECK_EVERY_MS))
    if (!wanted.length) return this.snapshot()
    await this.commit({...this.state, checking: true})
    try {
      for (const artist of wanted) await this.checkOne(artist.id)
    } finally {
      await this.commit({...this.state, checking: false})
    }
    return this.snapshot()
  }

  private patch(id: string, change: Partial<FollowedArtist>, releases?: ArtistRelease[]): FollowingState {
    return {
      ...this.state,
      artists: this.state.artists.map((artist) => (artist.id === id ? {...artist, ...change} : artist)),
      releases: releases ?? this.state.releases,
      // the seen list never outgrows the releases still known
      seenReleases: releases ? this.state.seenReleases.filter((seen) => releases.some((release) => release.id === seen)) : this.state.seenReleases,
    }
  }

  private async checkOne(id: string) {
    const artist = this.state.artists.find((item) => item.id === id)
    if (!artist) return
    const at = this.now()
    let channel = artist.url ? artistChannelId(artist.url) : undefined
    if (!channel) {
      try {
        channel = await this.host.resolveChannel(artist.name, artist.key)
      } catch {
        return this.commit(this.patch(id, {checkedAt: at, error: 'Keepfin could not reach YouTube Music right now. It will try again later.'}))
      }
      if (!channel) return this.commit(this.patch(id, {checkedAt: at, error: `Keepfin could not find ${artist.name} on YouTube Music.`}))
    }
    const url = artistChannelUrl(channel)
    let hits: ArtistReleaseHit[]
    try {
      hits = (await this.host.listReleases(channel)).slice(0, CAP)
    } catch (error) {
      console.warn('artist release check failed', error)
      return this.commit(this.patch(id, {checkedAt: at, error: CHECK_FAILED, url}))
    }
    const usable = new Map<string, ArtistReleaseHit>()
    for (const hit of hits) usable.set(hit.link, hit)
    const fresh = freshReleases(artist.seen, [...usable.keys()], !artist.checkedOnce)
    const added: ArtistRelease[] = fresh.map((link) => {
      const hit = usable.get(link)!
      return {id: link, artistId: id, title: hit.title, kind: hit.kind, url: link, thumbnail: hit.thumbnail, foundAt: at, kept: false}
    })
    const keepOld = this.state.releases.filter((release) => release.foundAt >= at - ARTIST_PRUNE_MS)
    const seen = [...new Set([...artist.seen, ...usable.keys()])]
    const merged = [...added, ...keepOld].filter((release, index, all) => all.findIndex((other) => other.id === release.id) === index)
    await this.commit(this.patch(id, {url, checkedOnce: true, seen, checkedAt: at, error: undefined}, merged))
  }

  /** The x on Home's card and "Mark all seen": every release now listed counts as seen, so the card returns only for newer ones. */
  async markSeen(): Promise<FollowingState> {
    await this.load()
    const releases: FeedRelease[] = this.state.releases.map((release) => ({id: release.id, artistKey: this.state.artists.find((artist) => artist.id === release.artistId)?.key ?? '', title: release.title, foundAt: release.foundAt, kept: release.kept}))
    await this.commit({...this.state, seenReleases: markSeenIds(releases, this.state.seenReleases, this.now())})
    return this.snapshot()
  }

  /** Keeps a release: downloads it, and only then marks it kept (a failure leaves it in the feed). */
  async keep(releaseId: string): Promise<{queued: number; state: FollowingState}> {
    await this.load()
    const release = this.state.releases.find((item) => item.id === releaseId)
    if (!release) throw new Error('This release is no longer in the list.')
    const queued = await this.host.keep(release.url)
    await this.commit({...this.state, releases: this.state.releases.map((item) => (item.id === releaseId ? {...item, kept: true} : item))})
    return {queued, state: this.snapshot()}
  }
}

/** The slice of the download manager a kept release needs. */
export type ReleaseQueue = {
  startDiscovery(url: unknown, id: unknown): unknown
  discoveryState(id: unknown): {state: string; entries: PlaylistEntry[]; error?: string}
  cancelDiscovery(id: unknown): unknown
  enqueueDiscovered(id: unknown, urls: unknown, preset: unknown, options?: unknown, grouped?: unknown, space?: unknown, keepInSync?: unknown, findVideo?: unknown): Promise<unknown>
  getSettings(): {defaultPreset: string; musicVideos?: boolean; library?: {enabled: boolean}}
}

/** The artist follow on the real engine: channel search, the follow check's listing, and a kept release through the normal download path. */
export function artistHost(queue: ReleaseQueue, resolveChannel: ArtistHost['resolveChannel'], listReleases: ArtistHost['listReleases'], isMusic: (url: string) => boolean): ArtistHost {
  return {
    resolveChannel,
    listReleases,
    async keep(url) {
      const session = `keep-${randomUUID()}`
      queue.startDiscovery(url, session)
      try {
        const deadline = Date.now() + 90_000
        let listing = queue.discoveryState(session)
        while (listing.state === 'discovering' && Date.now() < deadline) {
          await new Promise((resolve) => setTimeout(resolve, 400))
          listing = queue.discoveryState(session)
        }
        const urls = listing.entries.filter((entry) => !entry.unavailable).map((entry) => entry.url)
        if (!urls.length) throw new Error(listing.error ?? 'Keepfin could not read this release right now.')
        const preferred = queue.getSettings().defaultPreset
        const preset = isMusic(url) && !preferred.startsWith('audio-') ? 'audio-mp3' : preferred
        // the "music videos" setting is the same default the Capture and collection boxes start from: songs kept as audio in the library also get their video
        const settings = queue.getSettings()
        const findVideo = settings.musicVideos === true && settings.library?.enabled === true && isMusic(url) && preset.startsWith('audio-')
        await queue.enqueueDiscovered(session, urls, preset, undefined, true, undefined, undefined, findVideo || undefined)
        return urls.length
      } finally {
        queue.cancelDiscovery(session)
      }
    },
  }
}
