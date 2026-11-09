// "Where your files are" (dev-docs/plans/WHERE-FILES.md): the places that hold part of this library, what each holds, how much, and in what
// state; the files only one place holds; and, for one file, the places it is on. Types only: main builds them from the library's
// placements, the copies and the paired devices, and the renderer turns them into words (gui/web/settings/where/whereText.ts).

/** The icon of a place: this computer, another computer, a phone, a drive or folder, a cloud account. */
export type WhereKind = 'this' | 'pc' | 'phone' | 'drive' | 'cloud'

/** What a place holds, names ready for words: the spaces and playlists are named, never ids. */
export type WhereHolds = {
  /** Everything in the library. */
  all: boolean
  /** Space names. */
  spaces: string[]
  /** Playlist names. */
  playlists: string[]
  favourites: boolean
  /** A place that keeps whatever its device has instead of a chosen selection (this computer, a phone): how many of the library's files it holds. */
  files?: {held: number; of: number}
}

export type WhereStatus =
  | {state: 'upToDate'; at?: number}
  | {state: 'copying'; done: number; total: number}
  /** Something waits for the next sync; [reason] is a plain sentence when the place has one ("Waiting for Wi-Fi"). */
  | {state: 'waiting'; reason?: string}
  /** Not reachable now. [unplugged]: a drive or folder that is not there; [at]: when it was last copied or seen. */
  | {state: 'offline'; at?: number; unplugged?: boolean}
  | {state: 'needsDecision'}
  /** Some files could not be copied at the last sync: not a decision, a problem the copy's dialog explains. */
  | {state: 'problem'; count: number}

/** Where a tap on a row goes: a paired device opens its card in Devices; a drive or cloud copy opens its copy in Storage. */
export type WhereLink = {to: 'device'; id: string} | {to: 'copy'; id: string}

export type WherePlace = {
  /** A paired device's id, a copy's id, or `this`. */
  id: string
  kind: WhereKind
  name: string
  /** True for the computer this app runs on (always first). */
  thisDevice?: boolean
  holds: WhereHolds
  /** Bytes of the files it holds (0 when not known yet, for example a copy that has not finished). */
  bytes: number
  /** Files it holds. */
  items: number
  status: WhereStatus
  /** A drive or folder copy: where it is on this computer, and how much of what it holds is there (for the card's second line). */
  location?: string
  files?: {kept: number; of: number}
  link?: WhereLink
}

/** Files that only one place holds, per kind: "12 videos are only on Desk PC". */
export type WhereSingles = {
  kind: 'songs' | 'videos' | 'other'
  count: number
  placeId: string
  placeName: string
  /** The space most of them are in, when it can be kept as a copy: Keep a copy… opens with it chosen. */
  spaceId?: string
}

export type WhereState = {
  /** False while no library is open, or in the browser preview: the screens show nothing. */
  available: boolean
  /** This device first. */
  places: WherePlace[]
  singles: WhereSingles[]
}

export const emptyWhere: WhereState = {available: false, places: [], singles: []}

/** One place a file is on (for the per-file line). */
export type WhereItemPlace = {name: string; thisDevice?: boolean; /** The paired device behind this place, when it is one (Play from names the holder with it). */ deviceId?: string; /** The place is not reachable now (the library remembers it). */ offline: boolean}

export type WhereItemView = {places: WhereItemPlace[]}
