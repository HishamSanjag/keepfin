// Undo of "Remove from playlist": the songs go back where they were and what changed since stays (the same cases as the phone's RestoreOrderTest).
import assert from 'node:assert/strict'
import {test} from 'node:test'
import {restoreOrder} from './library.js'

const restore = (before: string, now: string, removed: string) => restoreOrder([...before], [...now], new Set(removed)).join('')

test('one song goes back to its place', () => assert.equal(restore('abcde', 'abde', 'c'), 'abcde'))
test('the first and the last go back', () => assert.equal(restore('abcde', 'bcd', 'ae'), 'abcde'))
test('neighbours removed together keep their order', () => assert.equal(restore('abcde', 'ae', 'bcd'), 'abcde'))
test('everything removed comes back in order', () => assert.equal(restore('abc', '', 'abc'), 'abc'))
test('a reorder made since stays', () => assert.equal(restore('abcde', 'eabd', 'c'), 'eabcd'))
test('a song added since stays', () => assert.equal(restore('abcd', 'abdx', 'c'), 'abcdx'))
test('a song put back already is not doubled', () => assert.equal(restore('abc', 'abc', 'b'), 'abc'))
