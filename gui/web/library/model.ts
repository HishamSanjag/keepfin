// Pure grouping, sorting and search for library views: albums, artists, folders, smart playlists.
import type {Collection, LibraryItem, Space} from '../../shared/library'

const collator = new Intl.Collator(undefined, {numeric: true, sensitivity: 'base'})
export const compareText = (a = '', b = '') => collator.compare(a, b)

/** Lower-case with accents stripped, so "Beyoncé" matches "beyonce". */
export function norm(text: string | undefined) {
  return (text || '').normalize('NFD').replace(/\p{M}/gu, '').toLowerCase().trim()
}

const keys = new WeakMap<LibraryItem, string>()
export function searchKey(item: LibraryItem) {
  let key = keys.get(item)
  if (key === undefined) {
    key = norm([item.title, item.artist, item.albumArtist, item.album, item.folder, item.genre, item.year ? String(item.year) : ''].filter(Boolean).join('\n'))
    keys.set(item, key)
  }
  return key
}

/** Every word of the query must appear (in any field); ids from lyric search are added by the caller. */
export function matchesQuery(item: LibraryItem, words: string[]) {
  if (!words.length) return true
  const key = searchKey(item)
  return words.every((word) => key.includes(word))
}
export const queryWords = (query: string) => norm(query).split(/\s+/).filter(Boolean)

export type SortKey = 'added' | 'title' | 'artist' | 'album' | 'duration' | 'size' | 'plays'
export const sortLabels: Record<SortKey, string> = {
  added: 'Recently added',
  title: 'Title A–Z',
  artist: 'Artist A–Z',
  album: 'Album',
  duration: 'Longest first',
  size: 'Largest first',
  plays: 'Most played',
}
export function sortItems(items: LibraryItem[], sort: SortKey): LibraryItem[] {
  const list = items.slice()
  const by: Record<SortKey, (a: LibraryItem, b: LibraryItem) => number> = {
    added: (a, b) => b.addedAt - a.addedAt || compareText(a.title, b.title),
    title: (a, b) => compareText(a.title, b.title),
    artist: (a, b) => compareText(a.artist || a.albumArtist, b.artist || b.albumArtist) || compareText(a.album, b.album) || trackOrder(a, b),
    album: (a, b) => compareText(a.album, b.album) || trackOrder(a, b),
    duration: (a, b) => (b.duration || 0) - (a.duration || 0),
    size: (a, b) => b.size - a.size,
    plays: (a, b) => (b.plays || 0) - (a.plays || 0) || compareText(a.title, b.title),
  }
  return list.sort(by[sort])
}
export const trackOrder = (a: LibraryItem, b: LibraryItem) =>
  (a.disc || 1) - (b.disc || 1) || (a.track || 9999) - (b.track || 9999) || compareText(a.title, b.title)

export function totalDuration(items: LibraryItem[]) {
  return items.reduce((sum, item) => sum + (item.duration || 0), 0)
}
export function durationWords(seconds: number) {
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) return `${Math.max(1, minutes)} min`
  const hours = Math.floor(minutes / 60)
  return `${hours} h ${String(minutes % 60).padStart(2, '0')} min`
}

// ---------------------------------------------------------------------------
// Music: albums and artists
// ---------------------------------------------------------------------------

export type Album = {
  key: string
  title: string
  /** Album artist, or "Various Artists" for compilations. */
  artist: string
  year?: number
  items: LibraryItem[]
  discs: number
  duration: number
  artId?: string
  compilation: boolean
}
export const VARIOUS = 'Various Artists'

export function groupAlbums(songs: LibraryItem[]): Album[] {
  const groups = new Map<string, LibraryItem[]>()
  for (const song of songs) {
    // A named album groups by album + album artist; tracks without an album artist group by album alone.
    const key = song.album ? `${norm(song.album)}|${norm(song.albumArtist)}` : `|${norm(song.artist || song.albumArtist)}`
    const list = groups.get(key)
    if (list) list.push(song)
    else groups.set(key, [song])
  }
  const albums: Album[] = []
  for (const [key, items] of groups) {
    items.sort(trackOrder)
    const artists = new Set(items.map((item) => norm(item.artist)).filter(Boolean))
    const albumArtist = items.find((item) => item.albumArtist)?.albumArtist
    const named = !!items[0].album
    const compilation = named && (norm(albumArtist) === norm(VARIOUS) || (!albumArtist && artists.size > 1))
    albums.push({
      key,
      title: named ? items[0].album! : 'Singles',
      artist: compilation ? VARIOUS : albumArtist || items.find((item) => item.artist)?.artist || 'Unknown artist',
      year: items.map((item) => item.year).find(Boolean),
      items,
      discs: new Set(items.map((item) => item.disc || 1)).size,
      duration: totalDuration(items),
      artId: items.find((item) => item.hasArt)?.id,
      compilation,
    })
  }
  return albums.sort((a, b) => compareText(a.title === 'Singles' ? '￿' : a.title, b.title === 'Singles' ? '￿' : b.title))
}

export type Artist = {key: string; name: string; songs: LibraryItem[]; albums: Album[]; artId?: string}
export function groupArtists(songs: LibraryItem[], albums: Album[]): Artist[] {
  const map = new Map<string, Artist>()
  const ensure = (name: string) => {
    const key = norm(name)
    let artist = map.get(key)
    if (!artist) map.set(key, (artist = {key, name, songs: [], albums: []}))
    return artist
  }
  for (const song of songs) {
    const names = new Set([song.artist, song.albumArtist].filter((name): name is string => !!name && norm(name) !== norm(VARIOUS)))
    if (!names.size) names.add('Unknown artist')
    const seen = new Set<string>()
    for (const name of names) {
      const artist = ensure(name)
      if (seen.has(artist.key)) continue
      seen.add(artist.key)
      artist.songs.push(song)
    }
  }
  for (const album of albums) {
    const owners = album.compilation ? new Set(album.items.map((item) => item.artist || 'Unknown artist')) : new Set([album.artist])
    for (const name of owners) {
      const artist = ensure(name)
      if (!artist.albums.includes(album)) artist.albums.push(album)
    }
  }
  const artists = [...map.values()]
  for (const artist of artists) artist.artId = artist.songs.find((song) => song.hasArt)?.id
  return artists.sort((a, b) => compareText(a.name === 'Unknown artist' ? '￿' : a.name, b.name === 'Unknown artist' ? '￿' : b.name))
}

// ---------------------------------------------------------------------------
// Smart playlists and folders
// ---------------------------------------------------------------------------

export type SmartId = 'recent-added' | 'recent-played' | 'most-played' | 'never-played' | 'favorites' | 'continue'
export const smartPlaylists: {id: SmartId; name: string; icon: string; blurb: string}[] = [
  {id: 'recent-added', name: 'Recently added', icon: 'clock', blurb: 'The newest 100 songs in your library.'},
  {id: 'most-played', name: 'Most played', icon: 'sparkle', blurb: 'The songs you play most often.'},
  {id: 'never-played', name: 'Never played', icon: 'music', blurb: 'Songs you have not played yet.'},
  {id: 'favorites', name: 'Favorites', icon: 'heart', blurb: 'Songs you marked with a heart.'},
]
export const smartTitle = (id: SmartId) =>
  id === 'recent-played' ? 'Recently played' : id === 'continue' ? 'Continue watching' : (smartPlaylists.find((entry) => entry.id === id)?.name ?? id)

export function smartItems(id: SmartId, items: LibraryItem[], limit = 100): LibraryItem[] {
  switch (id) {
    case 'recent-added':
      return sortItems(items, 'added').slice(0, limit)
    case 'recent-played':
      return items.filter((item) => item.lastPlayedAt).sort((a, b) => b.lastPlayedAt! - a.lastPlayedAt!).slice(0, limit)
    case 'most-played':
      return items.filter((item) => (item.plays || 0) > 0).sort((a, b) => b.plays! - a.plays! || compareText(a.title, b.title)).slice(0, limit)
    case 'never-played':
      return sortItems(items.filter((item) => !item.plays), 'added')
    case 'favorites':
      return sortItems(items.filter((item) => item.favorite), 'title')
    case 'continue':
      return continueWatching(items)
  }
}

/** A started, unfinished video: past the first few seconds and before the "watched" mark. */
export function inProgress(item: LibraryItem) {
  if (item.kind !== 'video' && !(item.kind === 'audio' && (item.duration || 0) > 1200)) return false
  if (item.watched) return false
  const position = item.position || 0
  if (position < 5) return false
  return !item.duration || position < item.duration * 0.92
}
export const continueWatching = (items: LibraryItem[]) =>
  items.filter(inProgress).sort((a, b) => (b.lastPlayedAt || 0) - (a.lastPlayedAt || 0))
export const progressFraction = (item: LibraryItem) =>
  item.duration && item.position ? Math.min(1, item.position / item.duration) : 0

export type FolderCard = {name: string; path: string; count: number; art: string[]}
/** The folders directly under `path` (counting everything below) and the items that sit exactly at `path`. */
export function folderLevel(items: LibraryItem[], path: string): {folders: FolderCard[]; loose: LibraryItem[]} {
  const prefix = path ? `${path}/` : ''
  const folders = new Map<string, FolderCard>()
  const loose: LibraryItem[] = []
  for (const item of items) {
    if (item.folder === path) loose.push(item)
    else if (item.folder.startsWith(prefix) || (!path && item.folder)) {
      const rest = item.folder.slice(prefix.length)
      const name = rest.split('/')[0]
      if (!name) continue
      let card = folders.get(name)
      if (!card) folders.set(name, (card = {name, path: prefix + name, count: 0, art: []}))
      card.count++
      if (item.hasArt && card.art.length < 4) card.art.push(item.id)
    }
  }
  return {folders: [...folders.values()].sort((a, b) => compareText(a.name, b.name)), loose}
}

export type CollectionView = {id: string; title: string; items: LibraryItem[]}

/** Downloaded collections as seen from a scope (a space, say): their items in source order, empty ones left out. */
export function collectionViews(collections: Collection[], scope: LibraryItem[]): CollectionView[] {
  const inScope = new Map(scope.map((item) => [item.id, item]))
  const views: CollectionView[] = []
  for (const collection of collections) {
    if (collection.kind === 'own') continue
    const items = collection.itemIds.map((id) => inScope.get(id)).filter((item): item is LibraryItem => !!item)
    if (items.length) views.push({id: collection.id, title: collection.name, items})
  }
  return views.sort((a, b) => compareText(a.title, b.title))
}

/** Folders of a music space that are not a downloaded collection, shown as playlists. */
export function folderPlaylists(items: LibraryItem[], collected: ReadonlySet<string>) {
  const map = new Map<string, LibraryItem[]>()
  for (const item of items) if (item.folder && !collected.has(item.id)) (map.get(item.folder) ?? map.set(item.folder, []).get(item.folder)!).push(item)
  return [...map.entries()].map(([folder, list]) => ({folder, name: folder.split('/').pop()!, items: list.sort(trackOrder)})).sort((a, b) => compareText(a.name, b.name))
}

export function kindsIn(items: LibraryItem[]) {
  const kinds = new Set(items.map((item) => item.kind))
  return {video: kinds.has('video'), audio: kinds.has('audio'), image: kinds.has('image'), other: kinds.has('other')}
}

export const isMusicSpace = (space?: Space) => space?.kind === 'music'
export const playableKind = (item?: LibraryItem) => item?.kind === 'audio' || item?.kind === 'video'
