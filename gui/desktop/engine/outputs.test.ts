import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {damageKey, fromTemplate, locateFile, matchDamaged, OutputMissingError, settleOutputs} from './outputs.js'

// The owner's real case: the engine reported the name with every Arabic letter and emoji dropped.
const FULL = 'كرت 5090 يحتاج هوية! وجوجل تصنع ألعابا من الكلام 😳 _ أخبارَ الباشا 📰'
const DAMAGED = ' 5090  !       _   '

test('damageKey keeps only lowercase printable ASCII', () => {
  assert.equal(damageKey(FULL), '5090!_')
  assert.equal(damageKey(DAMAGED), '5090!_')
  assert.equal(damageKey('Song Title (Live)'), 'songtitle(live)')
  assert.equal(damageKey('\t\n  '), '')
})

test('the matcher picks a unique file with the same extension and skeleton', () => {
  const names = [`${FULL}.mp4`, 'Other.mp4', `${FULL}.srt`]
  assert.equal(matchDamaged({stems: [DAMAGED], ext: '.mp4'}, names), `${FULL}.mp4`)
  assert.equal(matchDamaged({stems: [DAMAGED], ext: '.MP4'}, names), `${FULL}.mp4`, 'extensions compare case-insensitively')
})

test('an ambiguous match is refused', () => {
  const names = [`${FULL}.mp4`, `${FULL} 2.mp4`.replace(' 2', '😳'), 'x.mp4']
  assert.equal(names.filter((name) => damageKey(name.slice(0, -4)) === '5090!_').length, 2)
  assert.equal(matchDamaged({stems: [DAMAGED], ext: '.mp4'}, names), undefined)
})

test('a file owned by something else is never picked, and a different extension never matches', () => {
  const names = [`${FULL}.mp4`, `${FULL}.webm`]
  assert.equal(matchDamaged({stems: [DAMAGED], ext: '.mp4'}, names, (name) => name.endsWith('.mp4')), undefined)
  assert.equal(matchDamaged({stems: [DAMAGED], ext: '.mkv'}, names), undefined)
  // The owned twin does not make the other one ambiguous.
  assert.equal(matchDamaged({stems: [DAMAGED], ext: '.mp4'}, [`${FULL}.mp4`, `${FULL}😳.mp4`], (name) => name === `${FULL}😳.mp4`), `${FULL}.mp4`)
})

test('stems are tried in order: the first that matches decides', () => {
  const names = ['Real Name.mp4']
  assert.equal(matchDamaged({stems: ['nothing', 'real  name'], ext: '.mp4'}, names), 'Real Name.mp4')
  assert.equal(matchDamaged({stems: ['real name', 'nothing'], ext: '.mp4'}, ['Real Name.mp4', 'REAL NAME.mp4']), undefined)
})

test('templates give back a real extension', () => {
  assert.equal(fromTemplate('C:\\d\\a.%(ext)s', '.mp4'), 'C:\\d\\a.mp4')
  assert.equal(fromTemplate('C:\\d\\a.%(ext)s', 'mp3'), 'C:\\d\\a.mp3')
  assert.equal(fromTemplate('C:\\d\\a.mp4', '.mp4'), undefined)
  assert.equal(fromTemplate(undefined, '.mp4'), undefined)
})

async function folder() {
  return fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-outputs-'))
}

test('the job-finish net: a reported file that exists is kept as it is', async () => {
  const dir = await folder()
  const file = path.join(dir, `${FULL}.mp4`)
  await fs.writeFile(file, 'video')
  const settled = await settleOutputs({file, files: []}, {template: path.join(dir, `${FULL}.%(ext)s`)})
  assert.deepEqual(settled, {file, files: [], repaired: false})
})

test('the job-finish net: a damaged report is resolved from the output template with the real extension', async () => {
  const dir = await folder()
  const real = path.join(dir, `${FULL}.mp4`)
  await fs.writeFile(real, 'video')
  const settled = await settleOutputs({file: path.join(dir, `${DAMAGED}.mp4`), files: []}, {template: path.join(dir, `${FULL}.%(ext)s`)})
  assert.equal(settled.file, real)
  assert.equal(settled.repaired, true)
})

test('the job-finish net: with no usable template the damage-tolerant match finds the file; sidecars follow', async () => {
  const dir = await folder()
  const real = path.join(dir, `${FULL}.mp4`)
  const subtitle = path.join(dir, `${FULL}.en.srt`)
  await fs.writeFile(real, 'video')
  await fs.writeFile(subtitle, 'subs')
  const logs: string[] = []
  const settled = await settleOutputs(
    {file: path.join(dir, `${DAMAGED}.mp4`), files: [path.join(dir, `${DAMAGED}.en.srt`), path.join(dir, 'gone.jpg')]},
    {log: (message) => logs.push(message)},
  )
  assert.equal(settled.file, real)
  assert.deepEqual(settled.files, [subtitle], 'a sidecar that cannot be found is left out')
  assert.equal(logs.length, 2)
})

test('the job-finish net: nothing found fails clearly instead of saving a wrong path', async () => {
  const dir = await folder()
  await fs.writeFile(path.join(dir, 'Unrelated.mp4'), 'x')
  await assert.rejects(() => settleOutputs({file: path.join(dir, `${DAMAGED}.mp4`), files: []}, {template: path.join(dir, `${FULL}.%(ext)s`)}), OutputMissingError)
  assert.equal(await locateFile(path.join(dir, `${DAMAGED}.mp4`)), undefined)
})

test('the job-finish net: two possible files, or a file already owned by another job, are not guessed', async () => {
  const dir = await folder()
  await fs.writeFile(path.join(dir, `${FULL}.mp4`), 'a')
  await fs.writeFile(path.join(dir, `${FULL}😳.mp4`), 'b')
  const reported = path.join(dir, `${DAMAGED}.mp4`)
  await assert.rejects(() => settleOutputs({file: reported, files: []}), OutputMissingError)
  const owned = (file: string) => path.basename(file) === `${FULL}.mp4`
  assert.equal(await locateFile(reported, {owned}), path.join(dir, `${FULL}😳.mp4`))
})

test('a name with almost no ASCII left is never matched by its skeleton alone', () => {
  // "أخبار الباشا.mp4" reported as "  .mp4": an empty key would claim any all-Arabic file in the folder.
  assert.equal(matchDamaged({stems: ['  '], ext: '.mp4'}, ['أغنية أخرى.mp4']), undefined)
  assert.equal(matchDamaged({stems: [' 5 '], ext: '.mp4'}, ['حلقة 5.mp4']), undefined)
})
