import {test} from 'node:test'
import assert from 'node:assert/strict'
import {EventEmitter} from 'node:events'
import type {AppUpdater} from 'electron-updater'
import {DesktopUpdates, updateMode} from './updates.js'

function fixture(mode: 'installed' | 'portable' | 'development' = 'installed') {
  const events = new EventEmitter()
  let checks = 0,
    installs = 0,
    active = false
  let installArgs: unknown[] = []
  const fake = Object.assign(events, {
    checkForUpdates: async () => {
      checks++
      events.emit('update-available', {
        version: '0.3.10',
        releaseNotes: 'Fixed downloads',
      })
      return null
    },
    downloadUpdate: async () => {
      events.emit('download-progress', {percent: 65})
      events.emit('update-downloaded', {})
      return []
    },
    quitAndInstall: (...args: unknown[]) => {
      installs++
      installArgs = args
    },
  })
  const controller = new DesktopUpdates({
    updater: fake as unknown as AppUpdater,
    mode,
    current: '0.3.9',
    hasDownloads: () => active,
    openReleases: async () => {},
  })
  return {
    controller,
    events,
    fake,
    stats: () => ({checks, installs}),
    installArgs: () => installArgs,
    setActive: (v: boolean) => {
      active = v
    },
  }
}
test('updates are explicit and cannot interrupt active downloads or discard a ready update', async () => {
  const f = fixture()
  assert.equal((f.fake as any).autoDownload, false)
  assert.equal((f.fake as any).autoInstallOnAppQuit, false)
  await assert.rejects(f.controller.action('updateInstall'), /Download/)
  assert.equal((await f.controller.action('updateCheck')).phase, 'available')
  assert.equal((await f.controller.action('updateDownload')).phase, 'ready')
  await f.controller.action('updateCheck')
  assert.equal(f.stats().checks, 1)
  f.setActive(true)
  await assert.rejects(f.controller.action('updateInstall'), /Finish or cancel/)
  assert.equal(f.stats().installs, 0)
  f.setActive(false)
  await f.controller.action('updateInstall')
  assert.deepEqual(f.stats(), {checks: 1, installs: 1})
  // An update installs without the installer's pages (silent) and opens Keepfin again afterwards.
  assert.deepEqual(f.installArgs(), [true, true])
})
test('installer launch failure leaves the update controls retryable', async () => {
  const f = fixture()
  await f.controller.action('updateCheck')
  await f.controller.action('updateDownload')
  f.fake.quitAndInstall = () => {
    f.events.emit('error', new Error('Cannot launch installer'))
  }
  await f.controller.action('updateInstall')
  assert.equal(f.controller.snapshot().phase, 'error')
  await f.controller.action('updateCheck')
  assert.equal(f.controller.snapshot().phase, 'available')
})
test('portable and development builds cannot invoke the installer', async () => {
  for (const mode of ['portable', 'development'] as const) {
    const f = fixture(mode)
    await assert.rejects(f.controller.action('updateCheck'), /Setup edition/)
    await assert.rejects(f.controller.action('updateInstall'), /Setup edition/)
    assert.equal(f.stats().installs, 0)
  }
})
test('overlapping checks are serialized, cancellation remains retryable, and feed errors are visible', async () => {
  const f = fixture()
  let finish!: () => void
  f.fake.checkForUpdates = () =>
    new Promise((resolve) => {
      finish = () => {
        f.events.emit('update-available', {version: '0.3.10'})
        resolve(null)
      }
    })
  const pending = f.controller.action('updateCheck')
  assert.equal((await f.controller.action('updateCheck')).phase, 'checking')
  finish()
  await pending
  f.fake.downloadUpdate = ((token: any) =>
    new Promise((_resolve, reject) => {
      token.onCancel(() => reject(new Error('cancelled')))
    })) as any
  const download = f.controller.action('updateDownload')
  await f.controller.action('updateCancel')
  await download
  assert.equal(f.controller.snapshot().phase, 'available')
  f.fake.checkForUpdates = async () => {
    throw new Error('404')
  }
  await f.controller.action('updateCheck')
  assert.equal(f.controller.snapshot().phase, 'error')
  assert.match(f.controller.snapshot().message!, /public downloads/)
  assert.equal(f.controller.snapshot().version, undefined)
})
test('private feed errors explain public access without leaking HTTP headers', async () => {
  const f = fixture()
  f.fake.checkForUpdates = async () => {
    throw new Error(
      'HttpError: 404 method: GET url: https://github.com/HishamSanjag/keepfin-source/releases.atom Headers: { authorization: sensitive-header }',
    )
  }
  await f.controller.action('updateCheck')
  assert.match(f.controller.snapshot().message!, /private|public/i)
  assert.doesNotMatch(
    f.controller.snapshot().message!,
    /Headers|authorization|sensitive-header|HttpError/,
  )
})

test('installed-version notes use the fixed public feed, preserve updater phase, and cache successful fetches', async () => {
  let calls = 0
  const owner = new DesktopUpdates({
    updater: Object.assign(new EventEmitter(), {}) as unknown as AppUpdater,
    mode: 'portable',
    current: '0.3.11',
    hasDownloads: () => false,
    openReleases: async () => {},
    fetch: (async (url) => {
      calls++
      assert.equal(
        url,
        'https://api.github.com/repos/HishamSanjag/keepfin/releases/tags/v0.3.11',
      )
      return new Response(
        JSON.stringify({body: '**Better downloads**\n- Fixed previews'}),
        {status: 200},
      )
    }) as typeof fetch,
  })
  const state = await owner.action('updateNotes')
  assert.equal(state.notesVersion, '0.3.11')
  assert.equal(state.notes, '**Better downloads**\n- Fixed previews')
  assert.equal(state.phase, 'idle')
  assert.equal(state.version, undefined)
  await owner.action('updateNotes')
  assert.equal(calls, 1)
})

test('release notes network failure does not disable downloading or fabricate an update', async () => {
  const owner = new DesktopUpdates({
    updater: Object.assign(new EventEmitter(), {}) as unknown as AppUpdater,
    mode: 'installed',
    current: '0.3.11',
    hasDownloads: () => false,
    openReleases: async () => {},
    fetch: (async () => new Response('missing', {status: 404})) as typeof fetch,
  })
  await assert.rejects(owner.action('updateNotes'), /notes could not be loaded/)
  assert.equal(owner.snapshot().phase, 'idle')
  assert.equal(owner.snapshot().version, undefined)
})

test('channels: Beta allows pre-releases, nothing ever allows downgrades, and switching starts a fresh check', async () => {
  const f = fixture()
  const updater = f.fake as any
  assert.equal(updater.allowPrerelease, false)
  assert.equal(updater.allowDowngrade, false)
  assert.equal(updater.channel, undefined, 'the updater channel is never set, so beta.yml is not requested')
  await f.controller.setChannel('beta')
  assert.equal(updater.allowPrerelease, true)
  assert.equal(updater.allowDowngrade, false)
  assert.equal(f.controller.snapshot().channel, 'beta')
  assert.equal(f.stats().checks, 1, 'switching checks right away')
  // The updater flips allowDowngrade when a channel is assigned; switching back must restore it.
  updater.allowDowngrade = true
  await f.controller.setChannel('stable')
  assert.equal(updater.allowPrerelease, false)
  assert.equal(updater.allowDowngrade, false)
  assert.equal(f.stats().checks, 2)
})

test('an offered version that is not newer than the running build is ignored', async () => {
  const f = fixture()
  f.fake.checkForUpdates = async () => {
    f.events.emit('update-available', {version: '0.3.8'})
    return null
  }
  assert.equal((await f.controller.action('updateCheck')).phase, 'current')
})
test('only the Setup edition updates itself; an MSI install is told to use Releases, like the portable one', () => {
  const setup = () => true
  const none = () => false
  assert.equal(updateMode({packaged: false, platform: 'win32', setupUninstaller: setup}), 'development')
  assert.equal(updateMode({packaged: true, platform: 'win32', portableFile: 'D:\Keepfin.exe', setupUninstaller: none}), 'portable')
  assert.equal(updateMode({packaged: true, platform: 'win32', setupUninstaller: setup}), 'installed')
  assert.equal(updateMode({packaged: true, platform: 'win32', setupUninstaller: none}), 'msi')
  assert.equal(updateMode({packaged: true, platform: 'darwin', setupUninstaller: none}), 'unsupported')
})
