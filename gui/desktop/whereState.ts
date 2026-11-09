// "Where your files are" as the screens get it (dev-docs/plans/WHERE-FILES.md): pure, so it is tested with plain data. The counts come from the
// library's placements (store/where.ts); the copies and the paired devices say which place is which and what state it is in.
import type {SyncMembership} from '../shared/core/sync.js'
import type {CopyPlaceView, CopyRow} from '../shared/copies.js'
import type {PairedDevice} from '../shared/devices.js'
import type {WhereHolds, WhereItemView, WhereKind, WherePlace, WhereSingles, WhereState, WhereStatus} from '../shared/where.js'
import type {CopyCover, WhereCounts, WhereItemFacts} from './store/where.js'

/** A copy as the host knows it: its view, the replica it keeps (null when unknown) and the items its last finished run lacks (null: too many to list; absent: not known). */
export type WhereCopy = {view: CopyPlaceView; replica: string | null; missing?: readonly string[] | null}

export type WhereInput = {
  thisName: string
  mainReplica: string
  counts: WhereCounts
  copies: readonly WhereCopy[]
  /** The spaces and playlists a copy can hold, for their names. */
  rows: readonly CopyRow[]
  paired: readonly PairedDevice[]
  /** Who may write, to tell which device wrote a replica's placements. Null while it is not known. */
  membership: SyncMembership | null
}

/**
 * What a copy covers for the "only one copy" count: the files its last finished run holds (its manifest), also while it runs again or
 * waits for the next sync. The items that run lacks are named in [except]. A copy that was never verified, has no plan confirmed yet or
 * cannot be used covers nothing; so does one whose missing items are too many to list (and unknown).
 */
export function coverOf(copy: WhereCopy): CopyCover | null {
  const {view} = copy
  if (view.lastVerifiedAt === undefined || view.status === 'new' || view.status === 'refused' || view.previewPending) return null
  const except = copy.missing !== undefined ? copy.missing : view.waiting === 0 && view.review === 0 && !view.problem ? [] : null
  if (except === null) return null
  const h = view.holds
  return {id: view.id, holds: {all: h.all, spaces: h.spaces, playlists: h.playlists, favourites: h.favourites, ...(h.smart?.length ? {smart: h.smart} : {})}, ...(except.length ? {except: [...except]} : {})}
}

/** The copies that cover files of the library, the way the counting wants them. */
export function completeCovers(copies: readonly WhereCopy[]): CopyCover[] {
  return copies.map(coverOf).filter((c): c is CopyCover => c !== null)
}

const names = (ids: readonly string[], kind: CopyRow['kind'], rows: readonly CopyRow[]) => ids.map((id) => rows.find((r) => r.kind === kind && r.id === id)?.name).filter((n): n is string => !!n)

/** What a copy holds, with the spaces and playlists named. */
export function holdsOf(holds: CopyPlaceView['holds'], rows: readonly CopyRow[]): WhereHolds {
  // a smart space is named like a space: it is a place in the library a copy holds
  return {all: holds.all, spaces: [...names(holds.spaces, 'space', rows), ...names(holds.smart ?? [], 'smart', rows)], playlists: names(holds.playlists, 'playlist', rows), favourites: holds.favourites}
}

function copyStatus(view: CopyPlaceView): WhereStatus {
  switch (view.status) {
    case 'syncing':
      // only Keepfin's own records are being removed from the account: the person's files are all there
      if (view.progress?.dataStep === 'tidying') return {state: 'tidying'}
      return {state: 'copying', done: view.progress?.doneActions ?? 0, total: view.progress?.totalActions || view.waiting}
    case 'upToDate':
      return {state: 'upToDate', ...(view.lastVerifiedAt ? {at: view.lastVerifiedAt} : {})}
    case 'disconnected':
      // a cloud account that needs a new sign-in is something to decide, not a drive that is away
      if (view.cloud?.needsSignIn && view.cloud.provider !== 'webdav') return {state: 'needsDecision'}
      return {state: 'offline', ...(view.lastVerifiedAt ? {at: view.lastVerifiedAt} : {}), ...(!view.cloud && !view.device ? {unplugged: true} : {})}
    case 'held':
    case 'preview':
    case 'review':
    case 'refused':
      return {state: 'needsDecision'}
    case 'problem':
      return {state: 'problem', count: view.problem?.count ?? 0}
    case 'paused':
      return {state: 'waiting', reason: 'Paused'}
    case 'new':
      return {state: 'waiting', reason: 'Not copied yet'}
    default:
      return {state: 'waiting', ...(view.statusText ? {reason: view.statusText} : {})}
  }
}

/** Where a copy is and how much of it is there: the second line of its card. A cloud copy names its service; a device is named already. */
function copyFacts(view: CopyPlaceView): Pick<WherePlace, 'location' | 'files'> {
  // the person's files, counted the way the main storage counts them (sidecars are not files)
  const of = view.items ?? view.kept + view.waiting
  const kept = view.itemsKept ?? view.kept
  const location = view.cloud ? view.cloud.providerName : view.device ? undefined : view.path || undefined
  return {...(location ? {location} : {}), ...(of > 0 ? {files: {kept, of}} : {})}
}

const kindOfPlatform = (platform: string): WhereKind => (platform === 'android' ? 'phone' : 'pc')

type Resolved = {id: string; name: string; kind: WhereKind; thisDevice?: true; device?: PairedDevice}

/**
 * Which paired device wrote each replica: the actors that wrote a replica's placements are members whose keys name a device. Where the
 * library does not sign its writers (no membership yet), one unexplained replica and one device that shares the library are taken as the
 * same device; anything else stays "Another device".
 */
export function resolveReplicas(replicas: WhereCounts['replicas'], input: Pick<WhereInput, 'mainReplica' | 'thisName' | 'paired' | 'membership' | 'copies'>): Map<string, Resolved> {
  const out = new Map<string, Resolved>()
  const copyReplicas = new Set(input.copies.map((c) => c.replica).filter((r): r is string => !!r))
  const byDevice = new Map(input.paired.map((d) => [d.deviceId, d]))
  const claimed = new Set<string>()
  const open: WhereCounts['replicas'] = []
  for (const replica of replicas) {
    if (replica.replica === input.mainReplica) {
      out.set(replica.replica, {id: 'this', name: input.thisName, kind: 'this', thisDevice: true})
      continue
    }
    if (copyReplicas.has(replica.replica)) continue // a copy is described by the copy itself
    let device: PairedDevice | undefined
    for (const actor of replica.actors) {
      for (const key of input.membership?.members[actor]?.keys ?? []) {
        device = byDevice.get(key.device)
        if (device) break
      }
      if (device) break
    }
    if (device) {
      claimed.add(device.deviceId)
      out.set(replica.replica, {id: device.deviceId, name: device.name, kind: kindOfPlatform(device.platform), device})
    } else open.push(replica)
  }
  const unclaimed = input.paired.filter((d) => !claimed.has(d.deviceId) && d.libraries.length > 0)
  for (const replica of open) {
    if (open.length === 1 && unclaimed.length === 1) {
      const device = unclaimed[0]
      out.set(replica.replica, {id: device.deviceId, name: device.name, kind: kindOfPlatform(device.platform), device})
    } else out.set(replica.replica, {id: `replica:${replica.replica}`, name: 'Another device', kind: 'pc'})
  }
  return out
}

const singleKind = (media: string): WhereSingles['kind'] => (media === 'audio' ? 'songs' : media === 'video' ? 'videos' : 'other')
const KIND_ORDER: WhereSingles['kind'][] = ['songs', 'videos', 'other']

export function buildWhere(input: WhereInput): WhereState {
  const {counts, rows} = input
  const resolved = resolveReplicas(counts.replicas, input)
  const places: WherePlace[] = []
  const total = counts.items

  const filesHolds = (held: number): WhereHolds => ({all: held >= total, spaces: [], playlists: [], favourites: false, files: {held, of: total}})

  // this device first, even when it holds nothing yet
  const mainTotal = counts.replicas.find((r) => r.replica === input.mainReplica)
  places.push({id: 'this', kind: 'this', name: input.thisName, thisDevice: true, holds: filesHolds(mainTotal?.items ?? 0), bytes: mainTotal?.bytes ?? 0, items: mainTotal?.items ?? 0, status: {state: 'upToDate'}})

  const devices: WherePlace[] = []
  for (const replica of counts.replicas) {
    const who = resolved.get(replica.replica)
    if (!who || who.thisDevice || replica.items === 0) continue
    const device = who.device
    const status: WhereStatus = !device ? {state: 'offline'} : device.online ? {state: 'upToDate', ...(device.lastSync ? {at: device.lastSync.at} : {})} : {state: 'offline', ...(device.lastSeen ? {at: device.lastSeen} : {})}
    devices.push({id: who.id, kind: who.kind, name: who.name, holds: filesHolds(replica.items), bytes: replica.bytes, items: replica.items, status, ...(device ? {link: {to: 'device' as const, id: device.deviceId}} : {})})
  }
  devices.sort((a, b) => a.name.localeCompare(b.name))
  places.push(...devices)

  const copyPlaces: WherePlace[] = []
  for (const { view } of input.copies) {
    const held = counts.copies[view.id]
    const kind: WhereKind = view.cloud ? 'cloud' : view.device ? kindOfPlatform(input.paired.find((d) => d.deviceId === view.device!.deviceId)?.platform ?? '') : 'drive'
    copyPlaces.push({
      id: view.id,
      kind,
      name: view.name,
      holds: holdsOf(view.holds, rows),
      bytes: held?.bytes ?? 0,
      items: held?.items ?? view.itemsKept ?? view.kept,
      status: copyStatus(view),
      ...copyFacts(view),
      link: {to: 'copy', id: view.id},
    })
  }
  copyPlaces.sort((a, b) => a.name.localeCompare(b.name))
  places.push(...copyPlaces)

  // files only one place holds, per place and kind
  const byKey = new Map<string, {kind: WhereSingles['kind']; place: WherePlace; count: number; spaces: Map<string, number>}>()
  for (const group of counts.solos) {
    const who = resolved.get(group.replica)
    const place = who && places.find((p) => p.id === who.id)
    if (!place) continue
    const kind = singleKind(group.media)
    const key = `${place.id}\0${kind}`
    let entry = byKey.get(key)
    if (!entry) byKey.set(key, (entry = {kind, place, count: 0, spaces: new Map()}))
    entry.count += group.count
    entry.spaces.set(group.space, (entry.spaces.get(group.space) ?? 0) + group.count)
  }
  const singles: WhereSingles[] = [...byKey.values()]
    .sort((a, b) => KIND_ORDER.indexOf(a.kind) - KIND_ORDER.indexOf(b.kind) || places.indexOf(a.place) - places.indexOf(b.place))
    .map((entry) => {
      const best = [...entry.spaces].filter(([id]) => rows.some((r) => r.kind === 'space' && r.id === id)).sort((a, b) => b[1] - a[1])[0]
      return {kind: entry.kind, count: entry.count, placeId: entry.place.id, placeName: entry.place.name, ...(best ? {spaceId: best[0]} : {})}
    })

  return {available: true, places, singles}
}

/** The places one file is on, from the replicas that have a file for it. A copy that holds the file counts as one place when it is complete and selects it. */
export function itemPlaces(input: Pick<WhereInput, 'mainReplica' | 'thisName' | 'paired' | 'membership' | 'copies'> & {facts: WhereItemFacts}): WhereItemView {
  const {facts} = input
  const actors = new Map<string, string[]>()
  for (const r of facts.replicas) actors.set(r.replica, [...(actors.get(r.replica) ?? []), r.actor])
  const replicas: WhereCounts['replicas'] = [...actors].map(([replica, list]) => ({replica, actors: list, items: 1, bytes: 0}))
  const resolved = resolveReplicas(replicas, input)
  const places: WhereItemView['places'] = []
  const seen = new Set<string>()
  // this computer first
  for (const replica of [...actors.keys()].sort((a, b) => Number(b === input.mainReplica) - Number(a === input.mainReplica))) {
    const who = resolved.get(replica)
    if (!who || seen.has(who.id)) continue
    seen.add(who.id)
    places.push({name: who.name, ...(who.thisDevice ? {thisDevice: true as const} : {}), ...(who.device ? {deviceId: who.device.deviceId} : {}), offline: !!who.device && !who.device.online})
  }
  // a copy holds the file when this computer has it and the copy is complete and selects it
  if (actors.has(input.mainReplica)) {
    for (const copy of input.copies) {
      const cover = coverOf(copy)
      if (!cover || (facts.id !== undefined && cover.except?.includes(facts.id))) continue
      const {view} = copy
      const h = view.holds
      const selected = h.all || h.spaces.includes(facts.space) || (h.favourites && facts.favourite) || facts.playlists.some((p) => h.playlists.includes(p)) || (facts.smart ?? []).some((s) => (h.smart ?? []).includes(s))
      if (selected) places.push({name: view.name, offline: view.status === 'disconnected'})
    }
  }
  return {places}
}
