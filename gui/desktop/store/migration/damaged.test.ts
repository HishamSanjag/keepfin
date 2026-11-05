// The owner's case: a library item saved under a damaged name (every Arabic letter and emoji dropped), a real file with the
// full name beside it, and a job that remembers the name it asked for. The migration must carry the item to the real file.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import {takeInventory} from './inventory.js'
import {buildPlan} from './plan.js'
import {modelFromPlan} from '../persistence.js'
import {makeWorld, put} from './fixtures.js'

const FULL = 'كرت 5090 يحتاج هوية! وجوجل تصنع ألعابا من الكلام 😳 _ أخبارَ الباشا 📰'
const DAMAGED = ' 5090  !       _   '

async function damagedWorld(options: {template?: boolean; twin?: boolean} = {}) {
  const world = await makeWorld({loose: false})
  const folder = path.join(world.downloads, 'YouTube')
  const real = path.join(folder, `${FULL}.mp4`)
  await put(real, 'real-video')
  if (options.twin) await put(path.join(folder, `${FULL}😳.mp4`), 'twin-video')
  const library = JSON.parse(await fs.readFile(path.join(world.deviceDir, 'library.json'), 'utf8'))
  library.items.push({
    id: 'damaged', space: 'downloads', path: path.join(folder, `${DAMAGED}.mp4`), folder: 'YouTube', kind: 'video', title: FULL, size: 10, modifiedAt: 1, addedAt: 99, hasArt: false,
    jobId: 'jobD', missing: true, favorite: true,
  })
  await fs.writeFile(path.join(world.deviceDir, 'library.json'), JSON.stringify(library))
  const queue = JSON.parse(await fs.readFile(path.join(world.deviceDir, 'queue.json'), 'utf8'))
  queue.jobs.push({
    id: 'jobD', status: 'completed', file: path.join(folder, `${DAMAGED}.mp4`), files: [path.join(folder, `${DAMAGED}.mp4`)],
    ...(options.template === false ? {} : {outputTemplate: path.join(folder, `${FULL}.%(ext)s`)}),
  })
  await fs.writeFile(path.join(world.deviceDir, 'queue.json'), JSON.stringify(queue))
  return {world, real, folder}
}

const counter = () => {
  let n = 0
  return () => `uuid-${++n}`
}

test('the inventory relinks a damaged item to the file the job asked for, and the job record follows', async () => {
  const {world, real} = await damagedWorld()
  try {
    const inventory = await takeInventory(world.deviceDir)
    const item = inventory.library.items.find((candidate) => candidate.id === 'damaged')!
    assert.equal(item.path, real)
    assert.deepEqual(inventory.missing.map((entry) => entry.itemId), ['gone'], 'the damaged item is no longer missing')
    const file = inventory.media.find((candidate) => candidate.path === real)!
    assert.equal(file.indexed, true)
    assert.equal(file.itemId, 'damaged')
    assert.equal(inventory.unindexed.some((candidate) => candidate === real), false, 'the real file is not an unlisted extra')
    assert.deepEqual(inventory.jobs.find((job) => job.id === 'jobD'), {id: 'jobD', file: real, exists: true})
  } finally {
    await world.cleanup()
  }
})

test('the plan and the imported model put the item at the real file in the Keepfin folder', async () => {
  const {world} = await damagedWorld()
  try {
    const inventory = await takeInventory(world.deviceDir)
    const plan = await buildPlan(inventory, {target: world.target, choices: {}, newId: counter(), runId: 'run1', now: () => 1000})
    const {model} = modelFromPlan(plan)
    const item = model.items.find((candidate) => candidate.title === FULL)!
    assert.equal(item.path, path.join(world.target, 'Downloads', 'YouTube', `${FULL}.mp4`))
    assert.equal(item.favorite, true, 'the item keeps its own state')
    assert.equal(model.items.filter((candidate) => candidate.path === item.path).length, 1)
  } finally {
    await world.cleanup()
  }
})

test('without a job the damage-tolerant match still finds a unique file in the same folder', async () => {
  const {world, real} = await damagedWorld({template: false})
  try {
    const inventory = await takeInventory(world.deviceDir)
    assert.equal(inventory.library.items.find((candidate) => candidate.id === 'damaged')!.path, real)
  } finally {
    await world.cleanup()
  }
})

test('with a job, the name it asked for settles what the damage-tolerant match would find ambiguous', async () => {
  const {world, real} = await damagedWorld({twin: true})
  try {
    const inventory = await takeInventory(world.deviceDir)
    assert.equal(inventory.library.items.find((candidate) => candidate.id === 'damaged')!.path, real)
  } finally {
    await world.cleanup()
  }
})

test('two files that could both be the item, and no job to say which, leave it missing', async () => {
  const {world, folder} = await damagedWorld({template: false, twin: true})
  try {
    const inventory = await takeInventory(world.deviceDir)
    assert.equal(inventory.library.items.find((candidate) => candidate.id === 'damaged')!.path, path.join(folder, `${DAMAGED}.mp4`))
    assert.ok(inventory.missing.some((entry) => entry.itemId === 'damaged'))
  } finally {
    await world.cleanup()
  }
})
