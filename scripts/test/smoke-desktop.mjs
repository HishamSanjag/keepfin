// Isolated Electron smoke check for a development build or a packaged executable:
//   node scripts/test/smoke-desktop.mjs                      (after `npm run gui:build`)
//   node scripts/test/smoke-desktop.mjs "releases/desktop/win-unpacked/Keepfin.exe"
// Verifies sandboxing, the v2 bridge, navigation, settings persistence, updater mode and,
// for packaged builds, that the bundled yt-dlp seeds the managed tools directory.
import {_electron as electron} from '@playwright/test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'

const userData = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-desktop-smoke-'))
const executable = process.argv[2]
const {version} = JSON.parse(
  await fs.readFile(new URL('../../package.json', import.meta.url), 'utf8'),
)
// A shell that exports ELECTRON_RUN_AS_NODE would start Electron as plain Node.
const env = {...process.env}
delete env.ELECTRON_RUN_AS_NODE
// The first part checks the app without a Keepfin folder (KEEPFIN_FOLDER=0); the second part below runs the default, with it.
const app = await electron.launch({
  ...(executable
    ? {executablePath: executable, args: [`--user-data-dir=${userData}`]}
    : {args: ['.', `--user-data-dir=${userData}`]}),
  env: {...env, KEEPFIN_FOLDER: '0'},
  timeout: 60_000,
})
try {
  const page = await app.firstWindow()
  const errors = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.getByRole('region', {name: 'Add a link'}).waitFor()

  // Renderer isolation: no Node globals, only the narrow bridge.
  assert.equal(await page.evaluate(() => typeof window.require), 'undefined')
  assert.equal(await page.evaluate(() => typeof window.process), 'undefined')
  assert.deepEqual(
    await page.evaluate(() => Object.keys(window.keepfin).sort()),
    ['onPush', 'pathForFile', 'request'],
  )

  const state = await page.evaluate(() => window.keepfin.request({op: 'state'}))
  assert.equal(state.platform, 'desktop')
  assert.deepEqual(state.jobs, [])
  assert.ok(state.settings.concurrency >= 1)

  // Invalid input is rejected by the main process, not just the renderer.
  await assert.rejects(
    page.evaluate(() => window.keepfin.request({op: 'inspect', url: 'file:///C:/x'})),
  )

  // Settings round-trip through IPC and are pushed back as a snapshot.
  const pushed = page.evaluate(
    () =>
      new Promise((resolve) => {
        const off = window.keepfin.onPush((event) => {
          if (event.type === 'snapshot' && event.snapshot.settings.concurrency === 4) {
            off()
            resolve(true)
          }
        })
      }),
  )
  const updated = await page.evaluate(() =>
    window.keepfin.request({op: 'updateSettings', settings: {concurrency: 4}}),
  )
  assert.equal(updated.settings.concurrency, 4)
  assert.equal(await pushed, true)

  // App icon: the picker is gone (Windows keeps old icons in taskbar pins and Start), so the window and Keepfin's own shortcuts
  // always carry the main icon, copied at start to <userData>/app-icon.ico.
  if (process.platform === 'win32') {
    const manifest = JSON.parse(await fs.readFile(new URL('../../resources/app-icons/index.json', import.meta.url), 'utf8'))
    const kept = path.join(userData, 'app-icon.ico')
    const expected = await fs.readFile(new URL(`../../resources/app-icons/${manifest.main}-${manifest.accent.slice(1).toUpperCase()}.ico`, import.meta.url))
    for (let tries = 0; tries < 50 && !(await fs.readFile(kept).then((b) => b.equals(expected), () => false)); tries++) await new Promise((r) => setTimeout(r, 100))
    assert.ok((await fs.readFile(kept)).equals(expected), 'app-icon.ico is not the main Keepfin icon')
  }

  // Every surface renders: the main navigation, and Settings from its button in the page header.
  const nav = page.getByRole('navigation', {name: 'Main navigation'})
  const open = {
    Library: () => nav.getByRole('button', {name: 'Library', exact: true}),
    Settings: () => page.getByRole('navigation', {name: 'Main navigation'}).getByRole('button', {name: 'Settings', exact: true}),
    Downloads: () => nav.getByRole('button', {name: 'Downloads', exact: true}),
  }
  for (const [tab, button] of Object.entries(open)) {
    await button().click()
    await page.getByRole('heading', {level: 1, name: tab}).waitFor()
  }

  const update = await page.evaluate(() => window.keepfin.request({op: 'updateState'}))
  assert.equal(update.current, version)
  assert.equal(update.mode, executable ? 'installed' : 'development')

  if (executable) {
    // Packaged builds ship a verified yt-dlp that seeds <userData>/tools without a download.
    const engine = await page.evaluate(() => window.keepfin.request({op: 'engineState'}))
    const ytdlp = engine.tools.find((tool) => tool.name === 'yt-dlp')
    assert.equal(ytdlp?.state, 'ready', `yt-dlp not ready: ${JSON.stringify(ytdlp)}`)
  }
  assert.deepEqual(errors, [])
  console.log(
    'PASS: sandboxed v2 bridge, IPC validation, settings push, navigation and updater mode' +
      (executable ? ', bundled yt-dlp in packaged app' : ' in development app'),
  )
} finally {
  await app.close()
  await fs.rm(userData, {recursive: true, force: true})
}

// Flag on (KEEPFIN_FOLDER=1): first start through the bridge, one library change, restart, reopen ready with it.
// The app relaunches itself after setup; that is skipped here by never asking for storageState after creating.
{
  const flagData = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-folder-smoke-'))
  let folder = path.join(flagData, 'KeepfinFolder')
  const launch = () =>
    electron.launch({
      ...(executable ? {executablePath: executable, args: [`--user-data-dir=${flagData}`]} : {args: ['.', `--user-data-dir=${flagData}`]}),
      env: {...env, KEEPFIN_FOLDER: '1'},
      timeout: 60_000,
    })
  const ask = (page, request) => page.evaluate((r) => window.keepfin.request(r), request)
  let flagApp
  try {
    flagApp = await launch()
    let page = await flagApp.firstWindow()
    assert.equal((await ask(page, {op: 'storageState'})).phase, 'firstStart')
    // First start through the real screens (the bridge alone once hid a bug in how the screens send the folder):
    // the system folder picker is replaced in the main process by one that returns the test folder.
    await fs.mkdir(folder, {recursive: true})
    await flagApp.evaluate(({dialog}, picked) => {
      dialog.showOpenDialog = async () => ({canceled: false, filePaths: [picked]})
      dialog.showOpenDialogSync = () => [picked]
    }, folder)
    await page.getByRole('button', {name: 'Skip intro'}).click()
    await page.getByRole('button', {name: 'Next', exact: true}).click()
    await page.getByRole('button', {name: 'Change…'}).click()
    // never write a shortcut into the real Music folder
    const shortcut = page.locator('.setup-host input[type="checkbox"]')
    if (await shortcut.count()) await shortcut.first().uncheck()
    await page.getByRole('button', {name: 'Start using Keepfin'}).click()
    const findFolderJson = async (dir, depth = 0) => {
      for (const entry of await fs.readdir(dir, {withFileTypes: true}).catch(() => [])) {
        const full = path.join(dir, entry.name)
        if (entry.isFile() && entry.name === 'folder.json' && path.basename(dir) === '.keepfin') return path.dirname(dir)
        if (entry.isDirectory() && depth < 3) {
          const found = await findFolderJson(full, depth + 1)
          if (found) return found
        }
      }
      return undefined
    }
    let created
    for (let wait = 0; wait < 100 && !created; wait++) {
      created = await findFolderJson(folder)
      if (!created) await new Promise((resolve) => setTimeout(resolve, 200))
    }
    assert.ok(created, 'the first-start screens created a Keepfin folder')
    folder = created
    // profiles.json is written last (after the folder), so the next start opens it: wait for it before closing
    let profiled = false
    for (let wait = 0; wait < 100 && !profiled; wait++) {
      profiled = await fs.readFile(path.join(flagData, 'profiles.json'), 'utf8').then((text) => Boolean(JSON.parse(text).current), () => false)
      if (!profiled) await new Promise((resolve) => setTimeout(resolve, 200))
    }
    assert.ok(profiled, 'the first start saved its profile')
    await flagApp.close()

    flagApp = await launch()
    page = await flagApp.firstWindow()
    const reopened = await ask(page, {op: 'storageState'})
    assert.equal(reopened.phase, 'ready')
    assert.equal(reopened.folder.path, folder)
    const overview = await ask(page, {op: 'storageOverview'})
    assert.equal(overview.path, folder)
    await ask(page, {op: 'playlistSave', playlist: {name: 'Smoke playlist', itemIds: []}})
    await flagApp.close()

    flagApp = await launch()
    page = await flagApp.firstWindow()
    assert.equal((await ask(page, {op: 'storageState'})).phase, 'ready')
    const library = await ask(page, {op: 'libraryState'})
    assert.ok(library.collections.some((c) => c.name === 'Smoke playlist'), 'the playlist survives a restart')
    // Copies through the real bridge, main process and worker threads: a backup copy on another folder, check, preview, add, first sync.
    const none = await ask(page, {op: 'copiesState'})
    assert.equal(none.available, true)
    assert.deepEqual(none.places, [])
    const target = path.join(flagData, 'BackupDrive')
    await fs.mkdir(target)
    assert.equal((await ask(page, {op: 'copiesCheckFolder', copies: {path: target}})).ok, true)
    assert.equal((await ask(page, {op: 'copiesCheckFolder', copies: {path: path.join(folder, 'Music')}})).ok, false, 'a folder inside the Keepfin folder is refused')
    const draft = {path: target, name: 'Smoke backup', mode: 'backup', holds: {all: true, spaces: [], playlists: [], favourites: false}, schedule: {everyHours: null, auto: false}}
    const preview = await ask(page, {op: 'copiesPreview', copies: {draft}})
    assert.equal(preview.status, 'ready')
    await ask(page, {op: 'copiesAdd', copies: {placeId: preview.placeId}})
    let settled
    for (let i = 0; i < 100 && !settled; i++) {
      const state = await ask(page, {op: 'copiesState'})
      const place = state.places.find((p) => p.name === 'Smoke backup')
      if (place && place.status === 'upToDate') settled = place
      else await new Promise((r) => setTimeout(r, 300))
    }
    assert.ok(settled, 'the first sync of the new copy finishes in the built app')
    assert.equal(JSON.parse(await fs.readFile(path.join(target, '.keepfin', 'folder.json'), 'utf8')).generation >= 0, true)
    await flagApp.close()
    console.log('PASS: flag on: first start, folder created, reopened ready, library change kept after restart, a backup copy added and synced')
  } finally {
    await flagApp?.close().catch(() => {})
    await fs.rm(flagData, {recursive: true, force: true, maxRetries: 10, retryDelay: 300}).catch(() => {})
  }
}