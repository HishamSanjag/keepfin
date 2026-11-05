// Play from another device on a real joined library (spec 9.4): device B joins device A's library through the join flow, has no media,
// and opens the joined Keepfin folder as its library (the app's StoreLibrary + LibraryIndex). A's items are listed there as not on this
// computer; libraryPrepare plays one from A through the peer URL with Range; with A stopped, it answers with an unreachable result.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {createHash, randomBytes, randomUUID} from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import {defaultLibrarySettings, notOnThisPc} from '../../shared/library.js'
import {LibraryIndex} from '../library/index.js'
import {handleLibraryRequest, type LibraryDeps} from '../library/ops.js'
import {respondToMedia, type MediaRequest} from '../library/protocol.js'
import {BUILTIN_SPACE_IDS} from '../store/mapping.js'
import {openKeepfinFolder} from '../store/keepfinFolder.js'
import {StoreLibrary} from '../store/persistence.js'
import {StoreClient} from '../store/storeClient.js'
import type {Joined} from './join.js'
import {NetService} from './service.js'
import {netLibraryFor} from './storeLibrary.js'
import {playFromItem} from './playFromItem.js'
import {plainBox, World, until} from './testkit.js'

const request = (url: string, range?: string): MediaRequest => ({url, method: 'GET', headers: {get: (name) => (name.toLowerCase() === 'range' ? range ?? null : null)}})
const sha = (bytes: Buffer) => createHash('sha256').update(bytes).digest('hex')

test('a device that joined a library lists its items as not on this PC and plays one from the other device, then names it when it is gone', async () => {
  const w = await World.create()
  let reopened: StoreLibrary | undefined
  let service: NetService | undefined
  let index: LibraryIndex | undefined
  try {
    const adopted: Joined[] = []
    const host = await w.node('Host')
    const guest = await w.node('Guest', {libraryId: randomUUID(), adoptJoined: async (j) => void adopted.push(j)})
    const hostReplica = JSON.parse(await fs.readFile(path.join(host.root, '.keepfin', 'folder.json'), 'utf8')).replicaId as string
    const files = [
      {n: 1, rel: 'Music/First Song.mp3', title: 'First Song', bytes: randomBytes(300_000)},
      {n: 2, rel: 'Music/Second Song.mp3', title: 'Second Song', bytes: randomBytes(200_000)},
    ]
    for (const f of files) {
      const {contentId} = await host.putFile(f.rel, f.bytes)
      await host.store.apply([
        {kind: 'item.add', body: {item: host.item(f.n), fields: {media: 'audio', space: BUILTIN_SPACE_IDS.music, title: f.title}}},
        {kind: 'item.place', body: {item: host.item(f.n), place: hostReplica, path: f.rel}},
        {kind: 'item.revise', body: {item: host.item(f.n), content: contentId, size: f.bytes.length, parents: []}},
      ])
    }
    await host.store.flush()
    await w.pair(guest, host)
    await host.service.shareLibrary(guest.identity.deviceId, w.libraryId)
    await until(() => guest.service.state().offers.length === 1, 'the offer to reach the other device')
    const joined = await guest.service.joinLibrary(host.identity.deviceId, w.libraryId, path.join(w.dir, 'Guest', 'Joined'))
    assert.equal(adopted.length, 1)
    await host.service.setAccess(guest.identity.deviceId, w.libraryId, ['metadata.read', 'metadata.write', 'media.read'])

    // the next start of device B: the joined folder is its library, the network service is built on it (the app's boot does the same)
    await guest.service.close()
    await guest.store.close()
    StoreClient.defaultSigner = {pkcs8: Buffer.from(guest.identity.pkcs8).toString('base64'), spki: Buffer.from(guest.identity.spki).toString('base64')}
    const opened = await openKeepfinFolder(joined.root)
    assert.ok(opened.ok)
    const dir = path.join(w.dir, 'GuestApp')
    reopened = new StoreLibrary({deviceDir: dir, newId: randomUUID, now: Date.now, store: {watch: false, flushIdleMs: 20}})
    await reopened.open(opened.folder, joined.actor)
    const session = reopened.persistence
    const lib = netLibraryFor(session.store, {libraryId: joined.libraryId, name: 'Joined', root: joined.root, watchDebounceMs: 30})
    service = await NetService.create({
      deviceDir: guest.deviceDir,
      box: plainBox,
      libraries: () => [lib],
      push: () => undefined,
      port: 0,
      bind: '127.0.0.1',
      discovery: false,
      advertiseHost: () => '127.0.0.1',
      backoff: {minMs: 40, maxMs: 400},
    })
    await service.enable()
    const svc = service

    // the index of device B lists A's items, none of them with a file here
    const settings = defaultLibrarySettings({downloads: path.join(dir, 'Downloads'), music: path.join(dir, 'Music'), videos: path.join(dir, 'Videos')})
    index = new LibraryIndex({file: path.join(dir, 'library.json'), artDir: path.join(dir, 'art'), ffmpeg: () => undefined, watch: false, analyse: false, persistence: session, newId: randomUUID})
    await index.init()
    index.configure(settings)
    const entries = files.map((f) => index!.lookup(host.item(f.n)))
    for (const [i, entry] of entries.entries()) {
      assert.ok(entry, `${files[i].title} is listed in the joined library (library index id == store item id)`)
      assert.equal(entry.title, files[i].title)
      assert.equal(entry.missing, true, 'listed, with no file on this computer')
      assert.equal(notOnThisPc(entry), true)
    }

    const deps = {
      index,
      cache: {prepare: async () => assert.fail('the file is not on this computer')},
      playFrom: (itemId: string, fileName: string) => playFromItem(svc, session.store, joined.libraryId, itemId, fileName),
    } as unknown as LibraryDeps
    await until(async () => (await handleLibraryRequest({op: 'libraryPrepare', id: host.item(1)} as never, deps).then((r) => 'url' in (r as object), () => false)), 'device B to reach A and be allowed to play', 20_000)

    const prepared = (await handleLibraryRequest({op: 'libraryPrepare', id: host.item(1)} as never, deps)) as {url: string; remuxed: boolean; from?: string}
    assert.match(prepared.url, /^keepfin-media:\/\/peer\/[0-9a-f]{32}$/)
    assert.equal(prepared.from, host.identity.name)
    const resolver = {item: () => undefined, art: () => undefined, sub: () => undefined, job: () => undefined, prepared: () => undefined, peer: (id: string) => svc.peerSource(id)}
    const body = async (r: Response) => Buffer.from(await r.arrayBuffer())
    const bytes = files[0].bytes
    const seek = await respondToMedia(request(prepared.url, 'bytes=250000-259999'), resolver)
    assert.equal(seek.status, 206)
    assert.equal(seek.headers.get('Content-Range'), `bytes 250000-259999/${bytes.length}`)
    assert.equal(sha(await body(seek)), sha(bytes.subarray(250_000, 260_000)), 'the ranged slice is the same bytes as on the other device')
    const second = (await handleLibraryRequest({op: 'libraryPrepare', id: host.item(2)} as never, deps)) as {url: string}
    const tail = await body(await respondToMedia(request(second.url, 'bytes=150000-'), resolver))
    assert.equal(sha(tail), sha(files[1].bytes.subarray(150_000)))
    // nothing was saved on this computer
    assert.equal(await fs.stat(entries[0]!.path).then(() => true, () => false), false)

    // device A is gone: a plain unreachable answer naming it and when it was last seen
    await host.service.close()
    await until(async () => {
      const r = (await handleLibraryRequest({op: 'libraryPrepare', id: host.item(1)} as never, deps).catch(() => undefined)) as {unreachable?: unknown} | undefined
      return !!r?.unreachable
    }, 'device B to notice that A is gone', 20_000)
    const gone = (await handleLibraryRequest({op: 'libraryPrepare', id: host.item(1)} as never, deps)) as {unreachable: {device: string; lastSeen: number | null}}
    assert.equal(gone.unreachable.device, host.identity.name)
    assert.equal(typeof gone.unreachable.lastSeen, 'number')
  } finally {
    await index?.close?.()
    await service?.close().catch(() => undefined)
    await reopened?.close().catch(() => undefined)
    await w.cleanup()
  }
})
