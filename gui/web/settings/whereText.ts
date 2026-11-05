// The words of "Where your files are" (dev-docs/plans/WHERE-FILES.md): what a place holds, its state, the warnings about files only one place
// holds, the sentence for a library that is in one place only, and the per-file line. Pure: the screens and the tests share them.
import {formatBytes} from '../../shared/contracts'
import {whereAgo, whereHolds, whereItemLine, whereOnePlace, whereSingle, whereState} from '../../shared/core/spaces'
import type {WhereHolds, WhereItemPlace, WhereKind, WherePlace, WhereSingles, WhereStatus} from '../../shared/where'

const plural = (count: number, one: string, many = `${one}s`) => `${count.toLocaleString('en')} ${count === 1 ? one : many}`

/** "just now", "2 min ago", "3 h ago", "yesterday", "3 days ago", "2 weeks ago". */
export const agoWords = whereAgo

/** "Everything", "Music, Favourites", "Road trip and 2 more", or how many of the library's files a device keeps. */
export const holdsWords = (holds: WhereHolds): string => whereHolds(holds)

/** The state in a few lowercase words: "up to date 2 min ago", "copying · 40 of 210", "last copied 3 days ago · not plugged in". */
export const statusWords = (place: Pick<WherePlace, 'status' | 'link'>, now: number): string => whereState(place.status, place.link?.to === 'copy', now)

/** A copy's second line: what it holds, where it is, how much of it is there ("Music · G:\My Drive\Media\Keepfin · 0 of 95 files"). */
export function copyLine(place: Pick<WherePlace, 'holds' | 'location' | 'files'>): string {
  const parts = [holdsWords(place.holds)]
  if (place.location) parts.push(place.location)
  const files = place.files
  if (files) parts.push(files.kept >= files.of ? plural(files.of, 'file') : `${files.kept.toLocaleString('en')} of ${plural(files.of, 'file')}`)
  return parts.join(' · ')
}

/** How the state is drawn: calm for what is fine, warm for what needs a look. */
export function statusTone(status: WhereStatus): 'good' | 'busy' | 'quiet' | 'warn' {
  switch (status.state) {
    case 'upToDate':
      return 'good'
    case 'copying':
      return 'busy'
    case 'waiting':
      return 'quiet'
    default:
      return 'warn'
  }
}

/** "6.2 GB" or, for a place whose size is not known yet, how many files it has so far. Empty when there is nothing to say. */
export function sizeWords(place: Pick<WherePlace, 'bytes' | 'items'>): string {
  if (place.bytes > 0) return formatBytes(place.bytes)
  if (place.items > 0) return plural(place.items, 'file')
  return ''
}

export const kindWords: Record<WhereKind, string> = {this: 'This computer', pc: 'Computer', phone: 'Phone', drive: 'Drive or folder', cloud: 'Cloud account'}

/** "12 videos are only on Desk PC", "3 songs are only on this PC". */
export const singlesWords = (single: WhereSingles, thisName: string): string =>
  whereSingle(single.kind, single.count, single.placeId === 'this' ? thisName : single.placeName)

/** Only this computer's files can be kept as a copy from here: the warning offers Keep a copy… for them. */
export const canKeepCopy = (single: WhereSingles) => single.placeId === 'this'

/** What the page says when this computer is the only place: nothing else holds the library. */
export const onePlaceWords = whereOnePlace

/** The per-file line: "On: this PC, Pixel 9, Google Drive", "Only on this PC", a place that is away marked "(offline)". */
export const itemWords = (places: readonly WhereItemPlace[], thisName = 'this PC'): string =>
  whereItemLine(places.map((place) => ({name: place.thisDevice ? thisName : place.name, offline: place.offline})))
