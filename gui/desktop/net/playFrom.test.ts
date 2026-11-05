// Play from this device: a file that is on a paired device only is streamed with Range through the keepfin-media protocol. Seeking
// works, nothing is saved here, and it needs the media-read permission.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {randomBytes} from 'node:crypto'
import fs from 'node:fs/promises'
import {respondToMedia, type MediaRequest} from '../library/protocol.js'
import {World, until} from './testkit.js'

const request = (url: string, range?: string, method = 'GET'): MediaRequest => ({url, method, headers: {get: (name) => (name.toLowerCase() === 'range' ? range ?? null : null)}})

test('a file on a paired device plays with Range: the whole file, a middle part, the tail, a seek past the end', async () => {
  const w = await World.create()
  try {
    const host = await w.node('Host')
    const guest = await w.node('Guest')
    await w.pair(guest, host)
    const bytes = randomBytes(300_000)
    const {contentId} = await host.putFile('Music/Long Song.mp3', bytes)
    // without the permission there is nothing to play from
    await until(() => guest.service.state().paired[0].online, 'the link')
    await assert.rejects(() => guest.service.playFrom(w.libraryId, contentId, 'Long Song.mp3'), /No paired device/)
    await host.service.setAccess(guest.identity.deviceId, w.libraryId, ['metadata.read', 'metadata.write', 'media.read'])
    await until(async () => {
      try {
        await guest.service.playFrom(w.libraryId, contentId, 'Long Song.mp3')
        return true
      } catch {
        return false
      }
    }, 'the permission to reach the player')
    const played = await guest.service.playFrom(w.libraryId, contentId, 'Long Song.mp3')
    assert.match(played.url, /^keepfin-media:\/\/peer\/[0-9a-f]{32}$/)
    assert.equal(played.size, bytes.length)
    assert.equal(played.deviceName, host.identity.name)
    const resolver = {
      item: () => undefined,
      art: () => undefined,
      sub: () => undefined,
      job: () => undefined,
      prepared: () => undefined,
      peer: (id: string) => guest.service.peerSource(id),
    }
    const body = async (r: Response) => Buffer.from(await r.arrayBuffer())

    const whole = await respondToMedia(request(played.url), resolver)
    assert.equal(whole.status, 200)
    assert.equal(whole.headers.get('Content-Type'), 'audio/mpeg')
    assert.equal(whole.headers.get('Accept-Ranges'), 'bytes')
    assert.ok((await body(whole)).equals(bytes))

    const middle = await respondToMedia(request(played.url, 'bytes=100000-100999'), resolver)
    assert.equal(middle.status, 206, 'seeking is a partial answer')
    assert.equal(middle.headers.get('Content-Range'), `bytes 100000-100999/${bytes.length}`)
    assert.ok((await body(middle)).equals(bytes.subarray(100000, 101000)))

    const open = await respondToMedia(request(played.url, 'bytes=250000-'), resolver)
    assert.equal(open.status, 206)
    assert.ok((await body(open)).equals(bytes.subarray(250000)))

    const tail = await respondToMedia(request(played.url, 'bytes=-500'), resolver)
    assert.equal(tail.status, 206)
    assert.ok((await body(tail)).equals(bytes.subarray(bytes.length - 500)))

    const beyond = await respondToMedia(request(played.url, `bytes=${bytes.length + 10}-`), resolver)
    assert.equal(beyond.status, 416)
    assert.equal(beyond.headers.get('Content-Range'), `bytes */${bytes.length}`)

    // a few seeks in a row on one stream source, as a player does
    for (const start of [5, 299_000, 120_000, 0]) {
      const r = await respondToMedia(request(played.url, `bytes=${start}-${start + 99}`), resolver)
      assert.ok((await body(r)).equals(bytes.subarray(start, start + 100)), `seek to ${start}`)
    }

    // nothing was saved on this device
    assert.deepEqual((await fs.readdir(guest.root)).filter((n) => n !== '.keepfin').sort(), ['Downloads', 'Music', 'Videos'])
    assert.deepEqual(await fs.readdir(`${guest.root}/Music`), [])

    // an unknown or expired token, and a name that is not media, are 404
    assert.equal((await respondToMedia(request('keepfin-media://peer/' + 'f'.repeat(32)), resolver)).status, 404)
    assert.equal((await respondToMedia(request(played.url, undefined, 'POST'), resolver)).status, 405)
  } finally {
    await w.cleanup()
  }
})

test('a file the other device does not have is not offered, and a device that goes away ends the stream with an error, not a wrong file', async () => {
  const w = await World.create()
  try {
    const host = await w.node('Host')
    const guest = await w.node('Guest')
    await w.pair(guest, host)
    await host.service.setAccess(guest.identity.deviceId, w.libraryId, ['metadata.read', 'metadata.write', 'media.read'])
    const {contentId} = await host.putFile('Music/a.mp3', randomBytes(1000))
    await until(async () => (await guest.service.playFrom(w.libraryId, contentId, 'a.mp3').then(() => true, () => false)), 'access')
    await assert.rejects(() => guest.service.playFrom(w.libraryId, 'sha256:' + '0'.repeat(64), 'x.mp3'), /No paired device/)
    await assert.rejects(() => guest.service.playFrom(w.libraryId, 'not-a-content-id', 'x.mp3'), /no content/)
    const played = await guest.service.playFrom(w.libraryId, contentId, 'a.mp3')
    const source = guest.service.peerSource(played.url.split('/').pop()!)!
    await host.service.close()
    const resolver = {item: () => undefined, art: () => undefined, sub: () => undefined, job: () => undefined, prepared: () => undefined, peer: () => source}
    const answer = await respondToMedia(request(played.url, 'bytes=0-99'), resolver)
    assert.ok(answer.status === 502 || answer.status === 404, `the player gets an error (${answer.status})`)
  } finally {
    await w.cleanup()
  }
})
