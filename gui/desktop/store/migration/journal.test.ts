import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {Journal, assertTransition, canTransition, foldJournal, journalFile, readJournal} from './journal.js'
import type {FileState} from './types.js'

const line = (record: object) => JSON.stringify(record)

test('the journal folds lines into each file\'s last state and ignores a torn last line', () => {
  const text = [
    line({k: 'run', event: 'started'}),
    line({k: 'intent', id: 'a', op: 'rename', from: '/s/a', to: '/t/a', size: 3, mtimeMs: 5}),
    line({k: 'file', id: 'a', state: 'copied', mode: 'rename', size: 3, mtimeMs: 5}),
    line({k: 'file', id: 'a', state: 'verified'}),
    line({k: 'file', id: 'b', state: 'copied', mode: 'copy', srcHash: 'h', destHash: 'h'}),
  ].join('\n')
  const torn = `${text}\n{"k":"file","id":"b","state":"ver`
  const fold = foldJournal(torn)
  assert.equal(fold.torn, true)
  assert.equal(fold.corrupt, 0)
  assert.equal(fold.files.get('a')?.state, 'verified')
  assert.equal(fold.files.get('a')?.intent?.to, '/t/a')
  assert.equal(fold.files.get('b')?.state, 'copied')
  assert.equal(fold.files.get('b')?.srcHash, 'h')
  assert.equal(fold.run.started, true)
  // A cleanly ended file with the same content is not torn.
  assert.equal(foldJournal(`${text}\n`).torn, false)
  // Empty and garbage input never throw.
  assert.equal(foldJournal('').files.size, 0)
  assert.equal(foldJournal('not json\n{"x":1}\n').corrupt, 2)
})

test('the transition table is strict', () => {
  const states: FileState[] = ['planned', 'copied', 'verified', 'committed', 'removable', 'done', 'failed']
  assert.equal(canTransition('planned', 'copied'), true)
  assert.equal(canTransition('committed', 'done'), true)
  assert.equal(canTransition('committed', 'removable'), true)
  assert.equal(canTransition('planned', 'verified'), false)
  assert.equal(canTransition('done', 'failed'), false)
  assert.equal(canTransition('failed', 'copied'), false)
  for (const state of states) if (state !== 'done' && state !== 'failed') assert.equal(canTransition(state, 'failed'), true)
  assert.throws(() => assertTransition('verified', 'copied'), /Illegal/)
})

test('an illegal transition is refused before anything is written, and a retry restores the earlier state', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-journal-'))
  try {
    const journal = await Journal.open(dir)
    await assert.rejects(journal.append({k: 'file', id: 'a', state: 'verified'}), /Illegal/)
    assert.equal(journal.writes, 0)
    await journal.append({k: 'file', id: 'a', state: 'copied', mode: 'copy'})
    await journal.append({k: 'file', id: 'a', state: 'failed', reason: 'locked', code: 'EBUSY'})
    assert.equal(journal.fold.files.get('a')?.state, 'failed')
    await assert.rejects(journal.append({k: 'file', id: 'a', state: 'failed', reason: 'again'}), /Illegal/)
    await journal.append({k: 'retry', id: 'a'})
    assert.equal(journal.fold.files.get('a')?.state, 'copied')
    assert.equal((await readJournal(dir)).files.get('a')?.state, 'copied')
  } finally {
    await fs.rm(dir, {recursive: true, force: true})
  }
})

test('opening a journal with a torn last line never joins the next record to it', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-journal-'))
  try {
    await fs.writeFile(journalFile(dir), `${line({k: 'file', id: 'a', state: 'copied'})}\n{"k":"file","id":"a","state":"ver`)
    const journal = await Journal.open(dir)
    assert.equal(journal.fold.torn, true)
    await journal.append({k: 'file', id: 'a', state: 'verified'})
    const fold = await readJournal(dir)
    assert.equal(fold.files.get('a')?.state, 'verified')
    assert.equal(fold.corrupt, 1, 'the torn fragment stays as one unreadable line')
  } finally {
    await fs.rm(dir, {recursive: true, force: true})
  }
})
