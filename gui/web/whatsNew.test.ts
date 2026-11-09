import assert from 'node:assert/strict'
import test from 'node:test'
import {whatsNewToShow} from './whatsNew'

const run = (seen: string | null, current: string, answer: {notes?: string; notesVersion?: string} | null = {notes: '- A', notesVersion: current}) => {
  const box = {seen, loads: 0}
  return whatsNewToShow({
    current,
    read: () => box.seen,
    write: (v) => void (box.seen = v),
    load: async () => {
      box.loads++
      return answer ?? undefined
    },
  }).then((notes) => ({notes, box}))
}

test('shows once when the version changed, however it was installed', async () => {
  const first = await run('1.0.2', '1.1.0')
  assert.equal(first.notes, '- A')
  assert.equal(first.box.seen, '1.1.0')
  const again = await whatsNewToShow({current: '1.1.0', read: () => first.box.seen, write: () => {}, load: async () => ({notes: '- A'})})
  assert.equal(again, undefined)
})

test('a fresh install records the version and shows nothing', async () => {
  const r = await run(null, '1.1.0')
  assert.equal(r.notes, undefined)
  assert.equal(r.box.seen, '1.1.0')
  assert.equal(r.box.loads, 0)
})

test('a downgrade shows nothing and records the version', async () => {
  const r = await run('1.1.0', '1.0.2')
  assert.equal(r.notes, undefined)
  assert.equal(r.box.seen, '1.0.2')
})

test('notes that cannot be loaded keep the stored version so the next start asks again', async () => {
  const r = await run('1.0.2', '1.1.0', null)
  assert.equal(r.notes, undefined)
  assert.equal(r.box.seen, '1.0.2')
})

test('empty notes or notes for another version are recorded as seen but not shown', async () => {
  assert.equal((await run('1.0.2', '1.1.0', {notes: '  '})).notes, undefined)
  const other = await run('1.0.2', '1.1.0', {notes: '- B', notesVersion: '1.0.2'})
  assert.equal(other.notes, undefined)
  assert.equal(other.box.seen, '1.1.0')
})
