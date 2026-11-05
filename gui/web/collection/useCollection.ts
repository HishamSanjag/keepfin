import {useEffect, useMemo, useRef, useState} from 'react'
import type {DownloadOptions, DownloadPreset, PlaylistDiscovery, Snapshot} from '../../shared/contracts'
import {messageOf, request} from '../bridge'
import {prefersAudio} from '../../shared/providers'
import {isMusicService, plural} from '../format'
import {usePreference} from '../prefs'
import {applySnapshot, getMeta, onDiscovery} from '../store'
import {toast} from '../toasts'
import {collectionKindOf, defaultFollow, defaultSpaceFor, isInfiniteSource, type CollectionFollow, type ExistingMatch} from '../../shared/library'
import {navigate} from '../nav'
import {landBack} from '../capture/intake'

export type CollectionSource = {url: string; title?: string; uploader?: string}

export function useCollection(source: CollectionSource, initialSpace?: string) {
  // Music collections default to audio presets, tags and covers. Matched services (Spotify, Apple Music,
  // Deezer) can only be audio; YouTube Music follows the user's setting and can still switch to video.
  const music = prefersAudio(source.url, getMeta().settings.youtubeMusic)
  const matched = isMusicService(source.url)
  const [grouped, setGrouped] = usePreference<boolean>('keepfin-collection-grouped', true, (v) => typeof v === 'boolean')
  const [discovery, setDiscovery] = useState<PlaylistDiscovery>()
  const [selected, setSelected] = useState<Set<string>>(() => new Set())
  const [queued, setQueued] = useState<Set<string>>(() => new Set())
  const [query, setQuery] = useState('')
  const [preset, setPreset] = useState<DownloadPreset>(() => {
    const fallback = getMeta().settings.defaultPreset
    return music && !fallback.startsWith('audio-') ? 'audio-mp3' : fallback
  })
  // What the Extras start from (the person's defaults, with tags and covers on for music); the Extras tab counts changes from it.
  const [baseOptions] = useState<DownloadOptions>(() => ({
    ...getMeta().settings.options,
    ...(music ? {embedMetadata: true, embedThumbnail: true} : {}),
  }))
  const [options, setOptions] = useState<DownloadOptions>(baseOptions)
  const [adding, setAdding] = useState(false)
  const [space, setSpace] = useState<string | undefined>(initialSpace)
  // "Add to playlist": belongs to this review, off by default
  const [playlist, setPlaylist] = useState<string | undefined>()
  // "Keep in sync": off by default; the interval is only sent while it is on.
  const [keepInSync, setKeepInSync] = useState(false)
  const [syncHours, setSyncHours] = useState<CollectionFollow['intervalHours']>(6)
  // "Also keep music videos where there is one": starts as the setting says, and only ever marks this batch.
  const [keepVideos, setKeepVideos] = useState(() => getMeta().settings.musicVideos)
  const [existing, setExisting] = useState<Record<string, ExistingMatch>>({})
  const [includeExisting, setIncludeExisting] = useState(false)
  const [loadingMore, setLoadingMore] = useState(false)
  const [startError, setStartError] = useState('')
  const inspectionId = useRef(crypto.randomUUID())
  const serverId = useRef<string | undefined>(undefined)
  const lastPush = useRef(0)
  const anchor = useRef<number | null>(null)
  const alive = useRef(true)

  const accept = (next: PlaylistDiscovery) => {
    if (!alive.current) return
    if (next.id !== inspectionId.current && next.id !== serverId.current) return
    lastPush.current = Date.now()
    setDiscovery(next)
  }

  useEffect(() => {
    alive.current = true
    const id = inspectionId.current
    const unsubscribe = onDiscovery(accept)
    request<PlaylistDiscovery>({op: 'startDiscovery', url: source.url, inspectionId: id})
      .then((state) => {
        serverId.current = state.id
        if (alive.current) setDiscovery(state)
      })
      .catch((error) => {
        if (alive.current) setStartError(messageOf(error))
      })
    return () => {
      alive.current = false
      unsubscribe()
      void request({op: 'cancelDiscovery', inspectionId: id}).catch(() => {})
    }
  }, [source.url])

  const discovering = discovery?.state === 'discovering'
  // Pushes are primary; poll only if they go quiet while discovery is running.
  useEffect(() => {
    if (!discovering) return
    const timer = setInterval(() => {
      if (Date.now() - lastPush.current < 900) return
      request<PlaylistDiscovery>({op: 'discoveryState', inspectionId: inspectionId.current})
        .then((state) => {
          if (alive.current) setDiscovery(state)
        })
        .catch(() => {})
    }, 500)
    return () => clearInterval(timer)
  }, [discovering])

  const entries = discovery?.entries || []
  const library = getMeta().settings.library
  const targetSpace = space ?? (library ? defaultSpaceFor(source.url, library) : undefined)
  // Only playlists and channels can follow (not albums, radio mixes or single items), and only with the library on.
  const syncable = !!library?.enabled && defaultFollow(collectionKindOf(source.url), source.url, 6).mode === 'auto'
  const syncing = syncable && keepInSync
  // Offered for a music collection downloaded as audio, with the library on.
  const canKeepVideos = !!library?.enabled && prefersAudio(source.url)
  const offerVideos = canKeepVideos && preset.startsWith('audio-')
  const skipping =!!library?.enabled && !!library.spaces.find((entry) => entry.id === targetSpace)?.skipExisting

  // "Already in <space>": asked once per batch of discovered entries, never blocking the list.
  const known = useRef(new Set<string>())
  useEffect(() => {
    known.current = new Set()
    setExisting({})
  }, [targetSpace, skipping])
  useEffect(() => {
    if (!skipping || !targetSpace || !entries.length || discovering) return
    const fresh = entries.filter((entry) => !known.current.has(entry.url)).map((entry) => entry.url)
    if (!fresh.length) return
    let alive = true
    for (const url of fresh) known.current.add(url)
    request<Record<string, ExistingMatch>>({op: 'libraryMatch', urls: fresh, space: targetSpace, inspectionId: serverId.current || inspectionId.current})
      .then((found) => {
        if (!alive || !found || typeof found !== 'object') return
        setExisting((previous) => ({...previous, ...found}))
        // Matches that were already ticked (Select all before the check finished) are deselected.
        setSelected((previous) => {
          const next = new Set(previous)
          let changed = false
          for (const url of Object.keys(found)) if (!includeExistingRef.current && next.delete(url)) changed = true
          return changed ? next : previous
        })
      })
      .catch(() => fresh.forEach((url) => known.current.delete(url)))
    return () => {
      alive = false
    }
  }, [entries.length, discovering, skipping, targetSpace])
  const includeExistingRef = useRef(includeExisting)
  includeExistingRef.current = includeExisting

  // Albums and playlists start with everything selected, including entries that arrive later, until the user changes
  // the selection (owner feedback 2026-10-12). Channels and endless radio mixes stay a pick-what-you-want list.
  const autoSelect = useRef(collectionKindOf(source.url) !== 'channel' && !isInfiniteSource(source.url))
  const autoSeen = useRef(new Set<string>())
  useEffect(() => {
    if (!autoSelect.current) return
    const fresh = entries.filter((entry) => !autoSeen.current.has(entry.url))
    if (!fresh.length) return
    for (const entry of fresh) autoSeen.current.add(entry.url)
    setSelected((previous) => {
      const next = new Set(previous)
      for (const entry of fresh) if (!entry.unavailable && !queued.has(entry.url) && (includeExistingRef.current || !existing[entry.url])) next.add(entry.url)
      return next
    })
  }, [entries])
  const manual = () => {
    autoSelect.current = false
  }
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return entries
    return entries.filter((entry) =>
      [entry.title, entry.uploader, entry.artist, entry.album].some((value) => value?.toLowerCase().includes(q)),
    )
  }, [entries, query])

  // Private, deleted or gone entries are shown with the reason but never selected: downloading them can only fail.
  const unavailable = useMemo(() => new Set(entries.filter((entry) => entry.unavailable).map((entry) => entry.url)), [entries])
  function toggle(url: string, index: number, range: boolean) {
    if (adding || queued.has(url) || unavailable.has(url)) return
    manual()
    // Read the anchor now: state updaters run later, after the anchor has moved.
    const from0 = anchor.current
    setSelected((previous) => {
      const next = new Set(previous)
      if (range && from0 !== null) {
        const [from, to] = [from0, index].sort((a, b) => a - b)
        const on = !previous.has(url)
        for (const entry of filtered.slice(from, to + 1))
          if (!queued.has(entry.url) && !entry.unavailable) on ? next.add(entry.url) : next.delete(entry.url)
      } else if (next.has(url)) next.delete(url)
      else next.add(url)
      return next
    })
    anchor.current = index
  }
  function selectAll(value: boolean) {
    manual()
    setSelected((previous) => {
      const next = new Set(previous)
      for (const entry of filtered)
        if (!queued.has(entry.url) && !entry.unavailable && (!value || includeExisting || !existing[entry.url])) value ? next.add(entry.url) : next.delete(entry.url)
      return next
    })
    anchor.current = null
  }
  async function more() {
    if (!discovery || loadingMore) return
    setLoadingMore(true)
    try {
      const state = await request<PlaylistDiscovery>({op: 'discoveryMore', inspectionId: inspectionId.current})
      if (alive.current) setDiscovery(state)
    } catch (error) {
      toast({tone: 'error', message: messageOf(error)})
    } finally {
      if (alive.current) setLoadingMore(false)
    }
  }
  async function stop() {
    try {
      const state = await request<PlaylistDiscovery>({op: 'cancelDiscovery', inspectionId: inspectionId.current})
      if (alive.current && state) setDiscovery(state)
    } catch (error) {
      toast({tone: 'error', message: messageOf(error)})
    }
  }
  async function enqueue() {
    const urls = entries.filter((entry) => selected.has(entry.url) && !queued.has(entry.url)).map((entry) => entry.url)
    if (!urls.length || adding) return false
    setAdding(true)
    try {
      const snapshot = await request<Snapshot>({
        op: 'enqueueDiscovered',
        inspectionId: inspectionId.current,
        urls,
        preset,
        options,
        grouped,
        ...(library?.enabled ? {space: targetSpace} : {}),
        ...(library?.enabled && playlist ? {joinPlaylist: playlist} : {}),
        ...(syncing ? {keepInSync: {intervalHours: syncHours}} : {}),
        ...(offerVideos && keepVideos ? {findVideo: true} : {}),
      })
      applySnapshot(snapshot)
      if (!alive.current) return true
      setQueued((previous) => new Set([...previous, ...urls]))
      setSelected(new Set())
      const landed = landBack(library?.enabled ? targetSpace : undefined)
      toast({
        tone: 'success',
        message: `Added ${plural(urls.length, 'download')} to the queue.${syncing ? ' Keeping it in sync.' : ''}`,
        ...(landed ? {action: {label: 'Show downloads', run: () => navigate('Downloads')}} : {}),
      })
      return true
    } catch (error) {
      toast({tone: 'error', message: messageOf(error)})
      return false
    } finally {
      if (alive.current) setAdding(false)
    }
  }

  const selectable = filtered.filter((entry) => !queued.has(entry.url) && !entry.unavailable)
  const selectedVisible = selectable.filter((entry) => selected.has(entry.url)).length
  const selectedCount = entries.filter((entry) => selected.has(entry.url) && !queued.has(entry.url)).length

  return {
    source,
    music,
    matched,
    grouped,
    setGrouped,
    discovery,
    discovering,
    startError,
    entries,
    filtered,
    selected,
    queued,
    query,
    setQuery,
    preset,
    setPreset,
    options,
    setOptions,
    baseOptions,
    adding,
    syncable,
    keepInSync,
    setKeepInSync,
    syncHours,
    setSyncHours,
    space,
    setSpace,
    playlist,
    setPlaylist,
    targetSpace,
    canKeepVideos,
    offerVideos,
    keepVideos,
    setKeepVideos,
    existing,
    existingCount: Object.keys(existing).length,
    skipping,
    includeExisting,
    setIncludeExisting: (on: boolean) => {
      setIncludeExisting(on)
      setSelected((previous) => {
        const next = new Set(previous)
        for (const url of Object.keys(existing)) {
          if (queued.has(url)) continue
          if (on) next.add(url)
          else next.delete(url)
        }
        return next
      })
    },
    loadingMore,
    toggle,
    selectAll,
    more,
    stop,
    enqueue,
    selectedCount,
    allVisibleSelected: selectable.length > 0 && selectedVisible === selectable.length,
    someVisibleSelected: selectedVisible > 0,
  }
}
export type CollectionModel = ReturnType<typeof useCollection>
