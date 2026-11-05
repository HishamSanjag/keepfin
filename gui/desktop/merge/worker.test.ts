// The app opens T's store in its worker thread (StoreClient): the scan, the receipts and a crash behave as with the store itself.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {mediaTree, mergeRig, Crash} from './testkit.js'
import {mergeEntityId} from '../../shared/core/merge.js'
import {uuidN} from './testkit.js'

const song = (n: number) => Buffer.from(`audio bytes of song ${n} `.repeat(40))

test('a combine with T\'s store in a worker thread ends the same, including a cut batch', async () => {
  for (const partial of [undefined, 2]) {
    const rig = await mergeRig({worker: true})
    try {
      await rig.a.put(1, 'Music/a-own.mp3', song(1))
      await rig.a.put(2, 'Music/shared.mp3', song(2))
      await rig.b.put(11, 'Music/b-own.mp3', song(11))
      await rig.b.put(12, 'Music/b-copy.mp3', song(2))
      const before = await mediaTree(rig.b.root)
      if (partial !== undefined) {
        rig.fault.partial = partial
        await assert.rejects(() => rig.engine.start(rig.a.identity.deviceId, 'theirs'), Crash)
        const e = rig.restart()
        await e.recover()
      } else {
        const state = await rig.engine.start(rig.a.identity.deviceId, 'theirs')
        assert.equal(state.phase, 'done')
        await rig.restart().recover()
      }
      const mergeId = (await rig.restart().current())!.mergeId
      const live = await rig.openActive()
      try {
        const titles = live.store.views().items.map((i) => String(i.fields.title)).sort()
        assert.deepEqual(titles, ['Song 1', 'Song 11', 'Song 2'])
        assert.ok(live.store.views().items.some((i) => i.id === mergeEntityId(mergeId, 'item', uuidN(11))))
      } finally {
        await live.store.close()
      }
      assert.deepEqual(await mediaTree(rig.b.root), before)
    } finally {
      await rig.cleanup()
    }
  }
})
