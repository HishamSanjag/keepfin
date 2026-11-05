// Play from this device in the app flow: a library item whose file exists only on a paired device. The renderer's player asks for
// `libraryPrepare`; the file is not here, so the request falls back to the device that has it (the same lookup the app does: the item's
// head content id from the store, then the network service), and the media URL it gets back plays with Range, so seeking works.
// Two instances: the device with the file, and this one, whose index lists the item as missing.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {randomBytes} from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import {defaultLibrarySettings, type LibraryItem} from '../../shared/library.js'
import {LibraryIndex, type LibraryEntry, type LibraryPersistence, type PersistedLibrary} from '../library/index.js'
import {handleLibraryRequest, type LibraryDeps} from '../library/ops.js'
import {respondToMedia, type MediaRequest} from '../library/protocol.js'
import {playFromItem} from './playFromItem.js'
import {World, until} from './testkit.js'

const request = (url: string, range?: string): MediaRequest => ({url, method: 'GET', headers: {get: (name) => (name.toLowerCase() === 'range' ? range ?? null : null)}})

test('libraryPrepare for an item that is only on a paired device returns a URL that plays, seeks and stays inside the permission', async () => {
  const w = await World.create()
  try {
    const host = await w.node('Host')
    const guest = await w.node('Guest')
    await w.pair(guest, host)
    const bytes = randomBytes(400_000)
    const {contentId} = await host.putFile('Music/Far Song.mp3', bytes)
    // the item exists in the shared library: added on the host with its first revision, and it reaches the guest by sync
    const item = host.item(1)
    await host.store.apply([
      {kind: 'item.add', body: {item, fields: {media: 'audio', title: 'Far Song'}}},
      {kind: 'item.place', body: {item, place: host.item(99), path: 'Music/Far Song.mp3'}},
      {kind: 'item.revise', body: {item, content: contentId, size: bytes.length, parents: []}},
    ])
    await until(() => guest.titles().includes('Far Song'), 'the item to reach this device')
    await host.service.setAccess(guest.identity.deviceId, w.libraryId, ['metadata.read', 'metadata.write', 'media.read'])

    // this device's index lists the item (same id as the store's) but the file is not on this computer
    const settings = defaultLibrarySettings({downloads: path.join(guest.dir, 'Downloads'), music: path.join(guest.dir, 'Music'), videos: path.join(guest.dir, 'Videos')})
    const music = settings.spaces.find((s) => s.folder.endsWith('Music'))!
    const entry = {
      id: item,
      path: path.join(music.folder, 'Far Song.mp3'),
      space: music.id,
      title: 'Far Song',
      kind: 'audio',
      size: bytes.length,
      addedAt: 1,
      managed: true,
      folder: '',
      hasArt: false,
      modifiedAt: 0,
      missing: true,
    } as unknown as LibraryEntry
    const persistence: LibraryPersistence = {
      load: async () => ({items: [entry], collections: []}) as PersistedLibrary,
      fetchRemote: async () => undefined,
      applyRemote: () => ({items: [entry], collections: []}) as PersistedLibrary,
      save: async () => undefined,
      onRemoteChange: () => () => undefined,
    }
    const index = new LibraryIndex({file: path.join(guest.dir, 'library.json'), artDir: path.join(guest.dir, 'art'), ffmpeg: () => undefined, watch: false, analyse: false, persistence, newId: () => 'x'})
    await index.init()
    index.configure(settings)
    assert.equal(index.lookup(item)?.missing, true, 'the item is listed although its file is not here')

    let asked = 0
    const deps = {
      index,
      cache: {prepare: async () => assert.fail('the file is not on this computer')},
      // exactly what the app does (keepfinBoot.playFromItem): the head content id from the store, then the network service
      playFrom: async (itemId: string, fileName: string) => {
        asked++
        return playFromItem(guest.service, guest.store, w.libraryId, itemId, fileName)
      },
    } as unknown as LibraryDeps

    await until(async () => {
      try {
        await guest.service.playFrom(w.libraryId, contentId, 'x.mp3')
        return true
      } catch {
        return false
      }
    }, 'the permission to reach the player')
    const prepared = (await handleLibraryRequest({op: 'libraryPrepare', id: item} as never, deps)) as {url: string; remuxed: boolean; from?: string}
    assert.equal(prepared.from, host.identity.name, 'the player is told which device it plays from')
    assert.equal(asked, 1)
    assert.match(prepared.url, /^keepfin-media:\/\/peer\/[0-9a-f]{32}$/)
    assert.equal(prepared.remuxed, false)

    const resolver = {item: () => undefined, art: () => undefined, sub: () => undefined, job: () => undefined, prepared: () => undefined, peer: (id: string) => guest.service.peerSource(id)}
    const body = async (r: Response) => Buffer.from(await r.arrayBuffer())
    const head = await respondToMedia(request(prepared.url, 'bytes=0-1023'), resolver)
    assert.equal(head.status, 206)
    assert.ok((await body(head)).equals(bytes.subarray(0, 1024)))
    const seek = await respondToMedia(request(prepared.url, 'bytes=300000-300999'), resolver)
    assert.equal(seek.status, 206, 'seeking is a partial answer')
    assert.equal(seek.headers.get('Content-Range'), `bytes 300000-300999/${bytes.length}`)
    assert.ok((await body(seek)).equals(bytes.subarray(300_000, 301_000)))
    const tail = await respondToMedia(request(prepared.url, 'bytes=390000-'), resolver)
    assert.ok((await body(tail)).equals(bytes.subarray(390_000)))
    assert.equal((await respondToMedia(request(prepared.url, 'bytes=999999-'), resolver)).status, 416)
    // nothing was saved on this computer
    assert.equal(await fs.stat(entry.path).then(() => true, () => false), false)

    // an id the library does not list is the original error, not a stream from the device
    await assert.rejects(() => handleLibraryRequest({op: 'libraryPrepare', id: 'not-an-item'} as never, deps), /no longer/)
    // without the permission there is nothing to play and the player gets the original error
    await host.service.setAccess(guest.identity.deviceId, w.libraryId, ['metadata.read', 'metadata.write'])
    await until(async () => {
      try {
        await guest.service.playFrom(w.libraryId, contentId, 'x.mp3')
        return false
      } catch {
        return true
      }
    }, 'the permission to be withdrawn')
    await assert.rejects(() => handleLibraryRequest({op: 'libraryPrepare', id: item} as never, deps), /no longer at its saved location/)
    void ({} as LibraryItem)
  } finally {
    await w.cleanup()
  }
})
