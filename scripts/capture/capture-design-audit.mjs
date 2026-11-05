import {_electron as electron, expect} from '@playwright/test'
import {build} from 'esbuild'
import {execFileSync} from 'node:child_process'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {pathToFileURL} from 'node:url'
import http from 'node:http'

const captures = await fs.mkdtemp(
  path.join(os.tmpdir(), 'keepfin-design-audit-'),
)
const baseline = path.join(captures, 'baseline')
await fs.mkdir(baseline)
const oldSource = execFileSync('git', ['show', 'HEAD:gui/web/main.tsx'], {
  encoding: 'utf8',
})
const oldStyles = execFileSync('git', ['show', 'HEAD:gui/web/style.css'], {
  encoding: 'utf8',
})
const oldFonts = execFileSync('git', ['show', 'HEAD:gui/web/fonts.css'], {
  encoding: 'utf8',
})
const revision = execFileSync('git', ['rev-parse', 'HEAD'], {
  encoding: 'utf8',
}).trim()
await build({
  stdin: {
    contents: oldSource,
    sourcefile: 'main.tsx',
    loader: 'tsx',
    resolveDir: path.resolve('gui/web'),
  },
  bundle: true,
  format: 'esm',
  outfile: path.join(baseline, 'main.js'),
  plugins: [
    {
      name: 'original-style',
      setup(builder) {
        builder.onResolve({filter: /^\.\/fonts\.css$/}, () => ({
          path: 'original-fonts.css',
          namespace: 'baseline',
        }))
        builder.onLoad({filter: /.*/, namespace: 'baseline'}, () => ({
          contents: oldFonts,
          loader: 'css',
        }))
        builder.onLoad({filter: /gui[\\/]web[\\/]style\.css$/}, () => ({
          contents: oldStyles,
          loader: 'css',
          resolveDir: path.resolve('gui/web'),
        }))
      },
    },
  ],
})
await fs.writeFile(
  path.join(baseline, 'index.html'),
  '<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="./main.css"></head><body><div id="root"></div><script type="module" src="./main.js"></script></body></html>',
)
const server = http.createServer((req, res) => {
  const colors = req.url.includes('2')
    ? ['#364550', '#a2c3d1']
    : ['#353e58', '#b4c5f3']
  res.writeHead(200, {'Content-Type': 'image/svg+xml'})
  res.end(
    `<svg xmlns="http://www.w3.org/2000/svg" width="480" height="270"><rect width="480" height="270" fill="${colors[0]}"/><path d="M0 190 160 90 290 190 410 70 480 130V270H0" fill="${colors[1]}" opacity=".55"/><circle cx="90" cy="62" r="22" fill="${colors[1]}"/><text x="24" y="248" fill="white" font-family="sans-serif" font-size="18">UI fixture · synthetic media</text></svg>`,
  )
})
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
const profile = path.join(captures, 'profile')
const app = await electron.launch({args: ['.', `--user-data-dir=${profile}`], env: {...process.env, KEEPFIN_FOLDER: '0'}})
try {
  const page = await app.firstWindow()
  await page.getByRole('heading', {name: 'Add links'}).waitFor()
  await app.evaluate(({ipcMain}, base) => {
    const video = {kind: 'video', label: '1080p · mp4', args: []}
    const audio = {kind: 'audio', label: 'MP3 audio', args: []}
    const jobs = [
      {
        id: '1',
        title: 'A quiet day in the mountains',
        status: 'downloading',
        percent: 64,
        speed: 2048000,
        eta: 37,
        uploader: 'Studio field notes',
        choice: video,
      },
      {
        id: '2',
        title: 'How design changes everyday life',
        status: 'queued',
        percent: 0,
        uploader: 'Design conversations',
        choice: video,
      },
      {
        id: '3',
        title: 'Late-night radio — episode 24',
        status: 'completed',
        percent: 100,
        uploader: 'Night frequency',
        choice: audio,
      },
      {
        id: '4',
        title: 'The small details that matter',
        status: 'completed',
        percent: 100,
        uploader: 'Studio field notes',
        choice: video,
      },
      {
        id: '5',
        title: 'A journey through the northern coast',
        status: 'failed',
        percent: 0,
        uploader: 'Outside journal',
        choice: video,
        error: 'Synthetic fixture: connection interrupted',
      },
    ].map((job, i) => ({
      ...job,
      url: `https://example.com/${job.id}`,
      thumbnail: `${base}/${job.id}.svg`,
      label: job.choice.label,
      createdAt: Date.now() - i * 86400000,
      file:
        job.status === 'completed'
          ? `C:/Downloads/Keepfin/Fixture/${job.title}.mp4`
          : undefined,
    }))
    ipcMain.removeHandler('keepfin:request')
    ipcMain.handle('keepfin:request', (_, body) => {
      if (body.op === 'updateState')
        return {mode: 'development', phase: 'idle', current: '0.3.10'}
      if (body.op === 'inspect')
        return {
          url: body.url,
          title: 'The art of noticing',
          thumbnail: `${base}/1.svg`,
          uploader: 'Studio field notes',
          duration: 542,
          choices: [video, audio],
        }
      return {
        jobs,
        platform: 'desktop',
        outDir: 'C:/Users/You/Downloads/Keepfin',
        organization: 'platform',
      }
    })
  }, `http://127.0.0.1:${server.address().port}`)
  const size = (width, height) =>
    app.evaluate(
      ({BrowserWindow}, [w, h]) =>
        BrowserWindow.getAllWindows()[0].setContentSize(w, h),
      [width, height],
    )
  const capture = async (name) => {
    await page.locator('.job img').first().waitFor()
    for (const thumbnail of await page.locator('.job img').all()) {
      await thumbnail.scrollIntoViewIfNeeded()
      await expect(thumbnail).toHaveJSProperty('complete', true)
    }
    await page.evaluate(() => window.scrollTo(0, 0))
    await page.screenshot({
      path: path.join(captures, name),
      fullPage: true,
      scale: 'css',
    })
  }
  await size(1240, 860)
  await page.goto(pathToFileURL(path.join(baseline, 'index.html')).href)
  await page.getByRole('heading', {name: 'Add links'}).waitFor()
  await page.getByRole('button', {name: 'Settings', exact: true}).click()
  await page.getByLabel('Dark appearance').uncheck()
  await page.getByRole('button', {name: 'Downloads 2', exact: true}).click()
  await capture('01-before-downloads-light.png')
  await page.getByRole('button', {name: 'Settings', exact: true}).click()
  await page.getByRole('heading', {name: 'Authenticated sites'}).waitFor()
  await page.screenshot({
    path: path.join(captures, '02-before-settings-light.png'),
    fullPage: true,
    scale: 'css',
  })

  await page.goto(pathToFileURL(path.resolve('build/web/index.html')).href)
  const nav = page.getByRole('navigation', {name: 'Main navigation'})
  await page.getByRole('heading', {name: 'Add links'}).waitFor()
  await nav.getByRole('button', {name: 'Settings', exact: true}).click()
  await page.getByLabel('Dark appearance').check()
  await nav.getByRole('button', {name: 'Downloads 2', exact: true}).click()
  await capture('03-after-downloads-dark.png')
  await nav.getByRole('button', {name: 'Library', exact: true}).click()
  await expect(page.locator('.collection .job')).toHaveCount(3)
  await capture('04-after-library-dark.png')
  await nav.getByRole('button', {name: 'Settings', exact: true}).click()
  await page.getByLabel('Dark appearance').uncheck()
  await nav.getByRole('button', {name: 'Downloads 2', exact: true}).click()
  await capture('05-after-downloads-light.png')
  await page.getByLabel('Video links').fill('https://example.com/preview')
  await page.getByLabel('Format for The art of noticing').waitFor()
  await capture('06-after-media-review-light.png')
  await size(390, 850)
  await capture('07-after-phone-web-light.png')
  await nav.getByRole('button', {name: 'Settings', exact: true}).click()
  await page.getByLabel('Interface size').press('End')
  await page.getByLabel('Dark appearance').check()
  await nav.getByRole('button', {name: 'Downloads 2', exact: true}).click()
  await capture('08-after-phone-web-dark-140.png')
  const overflow = await page.evaluate(() => ({
    width: document.documentElement.clientWidth,
    scroll: document.documentElement.scrollWidth,
  }))
  assertNoOverflow(overflow)
  await fs.writeFile(
    path.join(captures, 'README.md'),
    `# Current-run design audit\n\nBaseline source: ${revision}. Original main.tsx and style.css compiled from Git into a temporary bundle. Native Electron screenshot surface for all captures. Media, titles, progress, thumbnails and paths are controlled synthetic IPC fixtures; they are never seeded into production. Final 390px browser-interface capture is a responsive Electron renderer, not an Android device. Current baseline and final screenshots captured during the same run.\n`,
  )
  console.log(
    JSON.stringify({captures, baselineRevision: revision, overflow}, null, 2),
  )
} finally {
  await app.close()
  await new Promise((resolve) => server.close(resolve))
  await fs.rm(profile, {recursive: true, force: true})
}
function assertNoOverflow(bounds) {
  if (bounds.scroll > bounds.width + 1)
    throw new Error(`Capture layout overflows: ${JSON.stringify(bounds)}`)
}
