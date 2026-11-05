import assert from 'node:assert/strict'
import {test} from 'node:test'
import {folderName, isDriveRoot, keepfinInside, placeFor} from './folderPlace'

const filled = {exists: true, empty: false, isKeepfinFolder: false}
const empty = {exists: true, empty: true, isKeepfinFolder: false}
const keepfin = {exists: true, empty: false, isKeepfinFolder: true}

test('a drive root gets a Keepfin folder inside it, empty or not', () => {
  assert.equal(placeFor('F:\\', filled), 'F:\\Keepfin')
  assert.equal(placeFor('F:\\', empty), 'F:\\Keepfin')
  assert.equal(placeFor('F:', empty), 'F:\\Keepfin')
  assert.equal(placeFor('\\\\nas\\media', filled), '\\\\nas\\media\\Keepfin')
  assert.equal(placeFor('/', filled), '/Keepfin')
})

test('a folder with other files gets a Keepfin folder inside it', () => {
  assert.equal(placeFor('D:\\Media', filled), 'D:\\Media\\Keepfin')
  assert.equal(placeFor('D:\\Media\\', filled), 'D:\\Media\\Keepfin')
  assert.equal(placeFor('/home/me/Music', filled), '/home/me/Music/Keepfin')
})

test('a folder named Keepfin and an existing Keepfin folder are used as they are; an empty folder gets one inside it too', () => {
  assert.equal(placeFor('F:\\Keepfin', filled), 'F:\\Keepfin')
  assert.equal(placeFor('F:\\keepfin', empty), 'F:\\keepfin')
  assert.equal(placeFor('E:\\Backup\\Library', keepfin), 'E:\\Backup\\Library')
  assert.equal(placeFor('D:\\New folder', empty), 'D:\\New folder\\Keepfin')
  assert.equal(placeFor('F:\\test', empty), 'F:\\test\\Keepfin')
})

test('path helpers', () => {
  assert.equal(folderName('C:\\Users\\you\\Keepfin\\'), 'Keepfin')
  assert.equal(folderName('C:\\'), '')
  assert.equal(isDriveRoot('C:\\'), true)
  assert.equal(isDriveRoot('C:\\Music'), false)
  assert.equal(isDriveRoot('\\\\nas\\media\\'), true)
  assert.equal(isDriveRoot('\\\\nas\\media\\music'), false)
  assert.equal(keepfinInside('C:\\'), 'C:\\Keepfin')
})
