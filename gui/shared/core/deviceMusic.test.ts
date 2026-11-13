import {test} from 'node:test'
import assert from 'node:assert/strict'
import {deviceMusicIdOf, deviceMusicIncludes} from './deviceMusic.js'

const song = {relativePath: 'Music/Rock/', displayName: 'a.mp3', durationMs: 180000, mime: 'audio/mpeg'}

test('device music adapter: length, flags and left-out folders', () => {
  assert.equal(deviceMusicIncludes(song), true)
  assert.equal(deviceMusicIncludes({...song, durationMs: 29999}), false)
  assert.equal(deviceMusicIncludes({...song, isRingtone: true, isMusic: true}), false)
  assert.equal(deviceMusicIncludes({...song, relativePath: 'Sounds/'}), false)
  assert.equal(deviceMusicIncludes({...song, relativePath: 'Sounds/', isMusic: true}), true)
  assert.equal(deviceMusicIncludes(song, ['Music/Rock']), false)
  assert.equal(deviceMusicIncludes({...song, relativePath: 'Music/Rock2/'}, ['Music/Rock/']), true)
  assert.equal(deviceMusicIncludes({...song, displayName: 'a.flac', mime: ''}), true)
  assert.equal(deviceMusicIncludes({...song, displayName: 'a.txt', mime: ''}), false)
})

test('device music adapter: ids only use safe characters', () => {
  assert.equal(deviceMusicIdOf('external_primary', '1234'), 'dm-external_primary-1234')
  assert.match(deviceMusicIdOf('a:b', 'x/ص'), /^[A-Za-z0-9_-]{1,100}$/)
  assert.equal(deviceMusicIdOf('v', 'k'.repeat(300)).length, 100)
})
