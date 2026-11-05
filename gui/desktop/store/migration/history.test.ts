import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {markHistoryMoved, pendingMoves, rehomeHistory, type HistoryJob, type Move} from './history.js'

// The owner's real case: the history saved the name with every Arabic letter and emoji dropped, and the move took the real file.
const FULL = 'كرت 5090 يحتاج هوية! وجوجل تصنع ألعابا من الكلام 😳 _ أخبارَ الباشا 📰'
const DAMAGED = ' 5090  !       _   '

const oldDir = path.join(path.sep, 'old', 'YouTube')
const newDir = path.join(path.sep, 'kf', 'Downloads', 'YouTube')
const there = (files: string[]) => {
  const set = new Set(files.map((file) => path.normalize(file).toLowerCase()))
  return async (file: string) => set.has(path.normalize(file).toLowerCase())
}

test('a damaged history path follows the move through the name the job asked for, and the template follows the folder', async () => {
  const moves: Move[] = [{source: path.join(oldDir, `${FULL}.mp4`), dest: path.join(newDir, `${FULL}.mp4`)}]
  const job: HistoryJob = {id: 'a', file: path.join(oldDir, `${DAMAGED}.mp4`), outputTemplate: path.join(oldDir, `${FULL}.%(ext)s`)}
  const changed = await rehomeHistory([job], moves, there([path.join(newDir, `${FULL}.mp4`)]))
  assert.equal(changed, 1)
  assert.equal(job.file, path.join(newDir, `${FULL}.mp4`))
  assert.equal(job.outputTemplate, path.join(newDir, `${FULL}.%(ext)s`))
})

test('exact paths and sidecars move; a damaged name without a template is matched when it is unique', async () => {
  const moves: Move[] = [
    {source: path.join(oldDir, 'Song.webm'), dest: path.join(newDir, 'Song.webm')},
    {source: path.join(oldDir, 'Song.webp'), dest: path.join(newDir, 'Song.webp')},
    {source: path.join(oldDir, `${FULL}.m4a`), dest: path.join(newDir, `${FULL}.m4a`)},
  ]
  const plain: HistoryJob = {id: 'p', file: path.join(oldDir, 'Song.webm'), files: [path.join(oldDir, 'Song.webp')]}
  const damaged: HistoryJob = {id: 'd', file: path.join(oldDir, `${DAMAGED}.m4a`)}
  await rehomeHistory([plain, damaged], moves, there(moves.map((move) => move.dest)))
  assert.equal(plain.file, path.join(newDir, 'Song.webm'))
  assert.deepEqual(plain.files, [path.join(newDir, 'Song.webp')])
  assert.equal(damaged.file, path.join(newDir, `${FULL}.m4a`))
})

test('a guess never takes a file another job really had, and paths that still exist stay', async () => {
  const moves: Move[] = [{source: path.join(oldDir, `${FULL}.mp4`), dest: path.join(newDir, `${FULL}.mp4`)}]
  // listed first, so a single pass would let the damaged job take it
  const guessing: HistoryJob = {id: 'g', file: path.join(oldDir, `${DAMAGED}.mp4`)}
  const real: HistoryJob = {id: 'r', file: path.join(oldDir, `${FULL}.mp4`)}
  const elsewhere: HistoryJob = {id: 'e', file: path.join(path.sep, 'kept', 'Clip.mp4')}
  await rehomeHistory([guessing, real, elsewhere], moves, there([path.join(newDir, `${FULL}.mp4`), path.join(path.sep, 'kept', 'Clip.mp4')]))
  assert.equal(real.file, path.join(newDir, `${FULL}.mp4`))
  assert.equal(guessing.file, path.join(oldDir, `${DAMAGED}.mp4`))
  assert.equal(elsewhere.file, path.join(path.sep, 'kept', 'Clip.mp4'))
})

test('two jobs that had the same file both follow it', async () => {
  const moves: Move[] = [{source: path.join(oldDir, 'Song.mp3'), dest: path.join(newDir, 'Song.mp3')}]
  const first: HistoryJob = {id: '1', file: path.join(oldDir, 'Song.mp3')}
  const again: HistoryJob = {id: '2', file: path.join(oldDir, 'Song.mp3')}
  assert.equal(await rehomeHistory([first, again], moves, there([path.join(newDir, 'Song.mp3')])), 2)
  assert.equal(again.file, path.join(newDir, 'Song.mp3'))
})

test('a destination that is not there is not used', async () => {
  const moves: Move[] = [{source: path.join(oldDir, 'Song.webm'), dest: path.join(newDir, 'Song.webm')}]
  const job: HistoryJob = {id: 'p', file: path.join(oldDir, 'Song.webm')}
  assert.equal(await rehomeHistory([job], moves, there([])), 0)
  assert.equal(job.file, path.join(oldDir, 'Song.webm'))
})

test('only committed runs count, and each run is brought along once', async () => {
  const deviceDir = await fs.mkdtemp(path.join(os.tmpdir(), 'kf-history-'))
  try {
    const write = async (run: string, committed: boolean) => {
      const dir = path.join(deviceDir, 'migration', run)
      await fs.mkdir(dir, {recursive: true})
      await fs.writeFile(path.join(dir, 'plan.json'), JSON.stringify({version: 1, files: [{kind: 'media', source: `/old/${run}.mp4`, dest: `/kf/${run}.mp4`}, {kind: 'art', source: '/old/a.jpg', dest: '/kf/a.jpg'}]}))
      const lines = [{k: 'run', event: 'started', at: 1}, ...(committed ? [{k: 'run', event: 'committed', at: 2}] : [])]
      await fs.writeFile(path.join(dir, 'journal.jsonl'), lines.map((line) => JSON.stringify(line)).join('\n') + '\n')
    }
    await write('done', true)
    await write('open', false)
    const first = await pendingMoves(deviceDir)
    assert.deepEqual(first.moves, [{source: '/old/done.mp4', dest: '/kf/done.mp4'}])
    assert.equal(first.runs.length, 1)
    await markHistoryMoved(first.runs, 5)
    assert.deepEqual(await pendingMoves(deviceDir), {moves: [], runs: []})
  } finally {
    await fs.rm(deviceDir, {recursive: true, force: true})
  }
})
