import {test} from 'node:test'
import assert from 'node:assert/strict'
import {startOverMessage} from './startOver.js'

test('a clean reset says the folder stayed', () => {
  const result = startOverMessage({deleteFolder: false, folderDeleted: false, dataDeleted: true, problems: []})
  assert.equal(result.tone, 'success')
  assert.match(result.message, /folder stayed/)
})

test('delete everything that worked says both were deleted', () => {
  const result = startOverMessage({deleteFolder: true, folderDeleted: true, dataDeleted: true, problems: []})
  assert.equal(result.tone, 'success')
  assert.match(result.message, /folder and what this PC kept were deleted/)
})

test('a folder that could not be deleted is reported as an error with the reason', () => {
  const result = startOverMessage({deleteFolder: true, folderDeleted: false, dataDeleted: true, problems: ['Some of the Keepfin folder could not be deleted (EBUSY).']})
  assert.equal(result.tone, 'error')
  assert.match(result.message, /folder stayed|could not be deleted/)
  assert.doesNotMatch(result.message, /folder and what this PC kept were deleted/)
})

test('a folder that was already gone is a note, not an error', () => {
  const result = startOverMessage({deleteFolder: true, folderDeleted: true, dataDeleted: true, notes: ['The Keepfin folder was already gone.'], problems: []})
  assert.equal(result.tone, 'success')
  assert.match(result.message, /already gone/)
})

test('nothing erased is an error', () => {
  const result = startOverMessage({deleteFolder: false, folderDeleted: false, dataDeleted: false, problems: ['Keepfin did not close in time, so nothing was erased.']})
  assert.equal(result.tone, 'error')
  assert.match(result.message, /did not finish/)
})
