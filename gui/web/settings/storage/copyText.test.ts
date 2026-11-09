import assert from 'node:assert/strict'
import {test} from 'node:test'
import {copyFiles, dataProgressPercent, dataProgressWords} from './copyText'

test('the data step says what it is doing and how far', () => {
  assert.equal(dataProgressWords({dataStep: 'tidying', dataDone: 100, dataTotal: 1021}, 'Google Drive'), 'Cleaning up old Keepfin records on Google Drive · 100 of 1,021')
  assert.equal(dataProgressWords({dataStep: 'tidying', dataDone: 0, dataTotal: 0}), 'Cleaning up old Keepfin records')
  assert.equal(dataProgressWords({dataStep: 'saving', dataDone: 3, dataTotal: 12}, 'Google Drive'), 'Saving Keepfin’s own data · 3 of 12')
  assert.equal(dataProgressWords({dataStep: 'saving'}), 'Saving Keepfin’s own data…')
  assert.equal(dataProgressWords({}), 'Saving Keepfin’s own data…')
})

test('the bar of the data step is a real fraction, and has none while the size is unknown', () => {
  assert.equal(dataProgressPercent({dataDone: 100, dataTotal: 1021}) !== undefined && Math.round(dataProgressPercent({dataDone: 100, dataTotal: 1021})!), 10)
  assert.equal(dataProgressPercent({dataDone: 12, dataTotal: 12}), 100)
  assert.equal(dataProgressPercent({}), undefined)
  assert.equal(dataProgressPercent({dataDone: 0, dataTotal: 0}), undefined)
})

test('a copy counts the person’s files, not the entries it holds beside them', () => {
  assert.deepEqual(copyFiles({kept: 113, waiting: 0, items: 84, itemsKept: 84}), {kept: 84, waiting: 0})
  assert.deepEqual(copyFiles({kept: 101, waiting: 11, items: 80, itemsKept: 71}), {kept: 71, waiting: 9})
  assert.deepEqual(copyFiles({kept: 5, waiting: 2}), {kept: 5, waiting: 2})
})
