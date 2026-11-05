// Progress isolation proof: while the fake bridge pushes 4 Hz progress for the transferring jobs,
// rows that are not transferring must not re-render, and the progressing rows must keep the very
// same <img> element and src (no reload, no extra `thumbnail` requests).
import assert from 'node:assert/strict'
import {startServer, launch, openApp} from './ui-fixtures/harness.mjs'

const srv = await startServer()
const browser = await launch()
let failed = false
try {
  const page = await openApp(browser, srv.url, {})
  await page.locator('.job').first().waitFor()
  // Let initial artwork requests settle, then tag every thumbnail element.
  await page.waitForFunction(() => document.querySelectorAll('.job .thumb img').length >= 3)
  await page.waitForTimeout(800)
  const baseline = await page.evaluate(() => {
    const images = [...document.querySelectorAll('.job .thumb img')]
    images.forEach((img, i) => {
      img.dataset.tag = String(i)
      img.__node = img
    })
    window.__imgs = images.map((img) => ({img, src: img.src}))
    const rows = [...document.querySelectorAll('.job')].map((row) => ({title: row.getAttribute('aria-label'), cls: row.className}))
    window.__mutations = 0
    const observed = [...document.querySelectorAll('.job')].filter((row) => !row.classList.contains('job-downloading'))
    const observer = new MutationObserver((records) => {
      window.__mutations += records.length
    })
    observed.forEach((row) => observer.observe(row, {subtree: true, childList: true, attributes: true, characterData: true}))
    return {renders: {...window.__keepfinTest.rowRenders}, rows, observed: observed.length, thumbs: window.__fake.calls.filter((c) => c.op === 'thumbnail').length}
  })
  const transferring = baseline.rows.filter((row) => row.cls.includes('job-downloading')).length
  assert.ok(transferring >= 2, `expected several transferring rows, saw ${transferring}`)
  const pushesBefore = await page.evaluate(() => window.__fake.pushes)
  await page.waitForTimeout(3000)
  const after = await page.evaluate(() => {
    const downloading = [...document.querySelectorAll('.job.job-downloading')].map((row) => row.getAttribute('data-id') || row.getAttribute('aria-label'))
    return {
      renders: {...window.__keepfinTest.rowRenders},
      mutations: window.__mutations,
      pushes: window.__fake.pushes,
      thumbs: window.__fake.calls.filter((c) => c.op === 'thumbnail').length,
      sameImages: window.__imgs.every(({img, src}) => img.isConnected && img.src === src),
      imgCount: window.__imgs.length,
      downloading,
    }
  })
  const pushRate = (after.pushes - pushesBefore) / 3
  assert.ok(pushRate >= 3, `expected ~4 progress pushes/s, saw ${pushRate.toFixed(1)}`)

  // Rows that re-rendered are exactly the ones whose progress changed.
  const grown = Object.keys(after.renders).filter((id) => after.renders[id] > (baseline.renders[id] || 0))
  const growth = grown.map((id) => after.renders[id] - (baseline.renders[id] || 0))
  assert.ok(grown.length > 0 && grown.length <= transferring, `re-rendered rows (${grown.length}) must be limited to the ${transferring} transferring rows`)
  assert.equal(after.mutations, 0, 'non-transferring rows must have zero DOM mutations while others progress')
  assert.equal(after.thumbs, baseline.thumbs, 'progress must not trigger thumbnail requests')
  assert.ok(after.sameImages, 'thumbnail <img> elements and src must stay identical during progress')
  assert.equal(after.imgCount > 0, true)
  console.log(`ok   progress isolation: ${pushRate.toFixed(1)} pushes/s; ${grown.length}/${Object.keys(after.renders).length} rows re-rendered (${growth.join(',')} times); ${after.imgCount} thumbnails unchanged; 0 mutations on ${baseline.observed} idle rows; 0 new thumbnail requests`)
  assert.equal(page.errors.length, 0, page.errors.join(' | '))

  // A collection kept together: expanded children keep per-job subscriptions. Only the transferring
  // children and the group header re-render; idle siblings see zero renders and zero DOM mutations.
  const grouped = await openApp(browser, srv.url, {fake: {scenario: 'groups'}})
  const row = grouped.locator('.group-row[data-group="grp-road"]')
  await row.waitFor()
  await row.locator('.job').nth(5).waitFor()
  await grouped.waitForTimeout(600)
  const ids = await grouped.evaluate(() => {
    const jobs = window.__fake.jobs().filter((job) => job.group && job.group.id === 'grp-road')
    return Object.fromEntries(jobs.map((job) => [job.status === 'downloading' ? `down${job.title.slice(-1)}` : job.status, job.id]))
  })
  const idle = Object.entries(ids).filter(([key]) => !key.startsWith('down'))
  const busy = Object.entries(ids).filter(([key]) => key.startsWith('down'))
  assert.equal(busy.length, 2)
  const before = await grouped.evaluate(() => {
    window.__mutations = 0
    const observer = new MutationObserver((records) => (window.__mutations += records.length))
    document.querySelectorAll('.group-row[data-group="grp-road"] .job:not(.job-downloading)').forEach((el) => observer.observe(el, {subtree: true, childList: true, attributes: true, characterData: true}))
    return {...window.__keepfinTest.rowRenders}
  })
  await grouped.waitForTimeout(3000)
  const afterGroup = await grouped.evaluate(() => ({renders: {...window.__keepfinTest.rowRenders}, mutations: window.__mutations}))
  const delta = (id) => (afterGroup.renders[id] || 0) - (before[id] || 0)
  for (const [key, id] of idle) assert.equal(delta(id), 0, `idle ${key} child re-rendered ${delta(id)} times`)
  for (const [key, id] of busy) assert.ok(delta(id) >= 3, `transferring child ${key} should re-render with progress (saw ${delta(id)})`)
  assert.equal(afterGroup.mutations, 0, 'idle children must have zero DOM mutations while siblings progress')
  const header = delta('group:grp-road')
  assert.ok(header >= 1 && header <= 14, `group header renders at most once per push batch (saw ${header} in 3 s)`)
  console.log(`ok   group progress isolation: ${idle.length} idle children 0 renders, 0 mutations; transferring children ${busy.map(([, id]) => delta(id)).join('/')} renders; header ${header} renders in 3 s`)
  assert.equal(grouped.errors.length, 0, grouped.errors.join(' | '))
} catch (error) {
  failed = true
  console.error('FAIL', error.stack || error)
} finally {
  await browser.close()
  await srv.close()
}
process.exit(failed ? 1 : 0)
