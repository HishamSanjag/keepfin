// Random op histories for the SQL merge state tests: three actors over small pools, with equal clocks, late and
// unknown parents, removes that name tags of adds that may arrive later, and entry conflicts.
import {makeOp, type SyncJson} from '../../shared/core/sync.js'

export function prng(seed: number) {
  let s = seed >>> 0
  return () => {
    s = (Math.imul(s, 1664525) + 1013904223) >>> 0
    return s / 4294967296
  }
}

export const hex = (rnd: () => number, n: number) => Array.from({length: n}, () => '0123456789abcdef'[Math.floor(rnd() * 16)]).join('')
export const uuid = (rnd: () => number) => `${hex(rnd, 8)}-${hex(rnd, 4)}-4${hex(rnd, 3)}-8${hex(rnd, 3)}-${hex(rnd, 12)}`

export interface OpSpec {
  actor: string
  seq: number
  hlc: string
  kind: string
  body: Record<string, SyncJson>
}

export interface History {
  ops: string[]
  specs: OpSpec[]
  items: string[]
  playlists: string[]
}

/** Random ops from three actors over small pools, with equal clocks, late and unknown parents, removes that name tags
 * of adds that may arrive later, and entry ids inserted into different playlists (entry conflicts). */
export function history(seed: number, count: number): History {
  const rnd = prng(seed)
  const pick = <T>(list: T[]): T => list[Math.floor(rnd() * list.length)]
  const actors = [uuid(rnd), uuid(rnd), uuid(rnd)]
  const items = Array.from({length: 6}, () => uuid(rnd))
  const playlists = Array.from({length: 3}, () => uuid(rnd))
  const spaces = Array.from({length: 2}, () => uuid(rnd))
  const places = Array.from({length: 2}, () => uuid(rnd))
  const entries = Array.from({length: 6}, () => uuid(rnd))
  const sets = ['favourites', 'tags:x']
  const elements = ['e1', 'e2', 'e3']
  const keys = ['a0', 'a1', 'a2', 'Zz', 'b00', 'a0V']
  const seqs = new Map<string, number>()
  const adds = new Map<string, string[]>()
  const revises = new Map<string, string[]>()
  const ops: string[] = []
  const specs: OpSpec[] = []
  const value = (): SyncJson => {
    switch (Math.floor(rnd() * 8)) {
      case 0: return 'plain'
      case 1: return 'café \u{1F600}'
      case 2: return 'lone \ud800 \u0001 " \\ \n'
      case 3: return Math.floor(rnd() * 2000) - 1000
      case 4: return null
      case 5: return rnd() < 0.5
      case 6: return [1, 'x', null]
      default: return {b: 2, a: 'ÿ', '': null}
    }
  }
  const subset = (list: string[] | undefined): string[] => (list ?? []).filter(() => rnd() < 0.7)
  for (let n = 0; n < count; n++) {
    const actor = pick(actors)
    const seq = (seqs.get(actor) ?? 0) + 1
    seqs.set(actor, seq)
    const id = `${actor}:${seq}`
    // few distinct clocks, so ties between actors and within an actor are common
    const hlc = `${String(1_700_000_000_000 + Math.floor(rnd() * 3)).padStart(13, '0')}.${String(Math.floor(rnd() * 2)).padStart(6, '0')}.${actor}`
    const item = pick(items)
    const roll = Math.floor(rnd() * 100)
    let kind = ''
    let body: Record<string, SyncJson> = {}
    const note = (map: Map<string, string[]>, key: string) => map.set(key, [...(map.get(key) ?? []), id])
    if (roll < 12) {
      kind = 'item.add'
      body = rnd() < 0.5 ? {item} : {item, fields: {media: 'audio', title: value() as SyncJson}}
      note(adds, `item|${item}`)
    } else if (roll < 24) {
      kind = 'item.set'
      body = {item, field: pick(['title', 'artist', 'rating']), value: value()}
    } else if (roll < 30) {
      kind = 'item.set'
      body = {item, field: `plays@${actor}`, value: Math.floor(rnd() * 50)}
    } else if (roll < 36) {
      kind = 'item.delete'
      const heads = revises.get(item)
      body = {item, observedTags: subset(adds.get(`item|${item}`)), observedHeads: subset(heads)}
    } else if (roll < 46) {
      kind = 'item.revise'
      const known = revises.get(item) ?? []
      const parents = subset(known)
      if (rnd() < 0.2) parents.push(`${pick(actors)}:${seq + 1 + Math.floor(rnd() * 5)}`) // a late or never-arriving parent
      body = {item, content: 'sha256:' + hex(rnd, 1).repeat(64), size: Math.floor(rnd() * 1000), parents: parents.filter((p) => p !== id)}
      note(revises, item)
    } else if (roll < 50) {
      kind = 'item.place'
      body = {item, place: pick(places), path: rnd() < 0.25 ? null : `a/b${Math.floor(rnd() * 3)}.mp3`}
    } else if (roll < 56) {
      const set = pick(sets)
      const element = pick(elements)
      kind = 'set.add'
      body = {set, element}
      note(adds, `set|${set}|${element}`)
    } else if (roll < 60) {
      const set = pick(sets)
      const element = pick(elements)
      kind = 'set.remove'
      body = {set, element, observedTags: subset(adds.get(`set|${set}|${element}`))}
    } else if (roll < 64) {
      const playlist = pick(playlists)
      kind = 'playlist.add'
      body = rnd() < 0.5 ? {playlist} : {playlist, fields: {name: 'Mix'}}
      note(adds, `playlist|${playlist}`)
    } else if (roll < 67) {
      kind = 'playlist.set'
      body = {playlist: pick(playlists), field: pick(['name', 'kind']), value: value()}
    } else if (roll < 69) {
      const playlist = pick(playlists)
      kind = 'playlist.delete'
      body = {playlist, observedTags: subset(adds.get(`playlist|${playlist}`))}
    } else if (roll < 72) {
      const space = pick(spaces)
      kind = 'space.add'
      body = rnd() < 0.5 ? {space} : {space, fields: {name: 'Gym'}}
      note(adds, `space|${space}`)
    } else if (roll < 74) {
      kind = 'space.set'
      body = {space: pick(spaces), field: pick(['name', 'colour']), value: value()}
    } else if (roll < 76) {
      const space = pick(spaces)
      kind = 'space.delete'
      body = {space, observedTags: subset(adds.get(`space|${space}`))}
    } else if (roll < 86) {
      kind = 'entry.insert'
      // the same entry id with different playlists or items: a conflict the join must report and resolve by the smaller op
      body = {playlist: pick(playlists), entry: pick(entries), item: pick(items), key: pick(keys)}
    } else if (roll < 91) {
      kind = 'entry.move'
      body = {entry: pick(entries), key: pick(keys)}
    } else if (roll < 94) {
      kind = 'entry.remove'
      body = {entry: pick(entries)}
    } else if (roll < 96) {
      kind = 'place.set'
      body = {place: pick(places), field: pick(['mode', 'name']), value: value()}
    } else if (roll < 98) {
      kind = 'profile.set'
      body = {field: pick(['name', 'colour']), value: value()}
    } else {
      kind = 'future.thing'
      body = {x: value()}
    }
    specs.push({actor, seq, hlc, kind, body})
    ops.push(makeOp(actor, seq, hlc, kind, body))
  }
  return {ops, specs, items, playlists}
}

export function shuffled<T>(list: T[], rnd: () => number): T[] {
  const out = [...list]
  for (let i = out.length - 1; i > 0; i--) {
    const j = Math.floor(rnd() * (i + 1))
    ;[out[i], out[j]] = [out[j], out[i]]
  }
  return out
}

