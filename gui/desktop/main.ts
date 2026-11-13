import {
  app,
  BrowserWindow,
  dialog,
  ipcMain,
  protocol,
  session,
  shell,
  Notification,
  nativeImage,
  safeStorage,
  screen,
} from 'electron'
import fs from 'node:fs/promises'
import {existsSync, copyFileSync, readFileSync} from 'node:fs'
import {applyAppIcon, shortcutCandidates, type ShortcutFields} from './appIcon.js'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import ffmpegStatic from 'ffmpeg-static'
import electronUpdater from 'electron-updater'
import {DownloadManager, REMOTE_CONSENT_BYTES} from './manager.js'
import {QueueJobPort, windowsMetered} from './remoteJobs.js'
import {guardUrl} from './net/guard.js'
import {StudioEngine} from './engine/index.js'
import {ToolManager, unpackedPath} from './engine/tools.js'
import {InfoCache} from './engine/ytdlp.js'
import {DesktopUpdates, updateMode} from './updates.js'
import {ActivityRecorder, ActivityStore} from './activity.js'
import {DesktopNotifications} from './notifications.js'
import {ThumbnailStore} from './thumbnails.js'
import {thumbnailFrame} from './thumbnail-frame.js'
import {boundedThumbnail} from './thumbnail-image.js'
import {emptyCombineState} from '../shared/combine.js'
import {isActive} from '../shared/contracts.js'
import {audioExtensions, imageExtensions, MEDIA_SCHEME, videoExtensions} from '../shared/library.js'
import {LibraryIndex} from './library/index.js'
import {PlayCache} from './library/prepare.js'
import {handleLibraryRequest, libraryOperations, type LibraryDeps} from './library/ops.js'
import {purgeKeepfinTrash} from './library/files.js'
import {FollowEngine} from './library/follow.js'
import {ArtistFollowing, artistHost} from './library/artists.js'
import {fetchArtistReleases, findArtistChannel} from './engine/music.js'
import {prefersAudio} from '../shared/providers.js'
import {UndoShelf} from './library/undo.js'
import {respondToMedia} from './library/protocol.js'
import {supportLink} from '../shared/support.js'
import {loadProfiles} from './store/profiles.js'
import {launchHelper, planStartOver, takeResult as takeStartOverResult} from './startOver.js'
import {bootKeepfinFolder, flagOffState, type KeepfinBoot} from './keepfinBoot.js'
import {safeStorageBox} from './cloud/accounts.js'
import {findSignIns} from './signin/auto.js'
import {SignInStore} from './signin/store.js'
import {handleSignIn} from './signin/ops.js'
import {signInWindows} from './signin/window.js'
import {windowsFirewall} from './net/firewall.js'
import {unavailableState} from './net/ops.js'
import type {PushEvent, Request, Snapshot} from '../shared/contracts.js'
import type {StorageArgs} from '../shared/storage.js'

const here = path.dirname(fileURLToPath(import.meta.url))
// Development runs use their own id so Windows never caches Electron's icon for the installed app.
if (process.platform === 'win32') app.setAppUserModelId(app.isPackaged ? 'app.keepfin.desktop' : 'app.keepfin.desktop.dev')
// Preserve the previous desktop profile after the product/package rename.
if (!process.argv.some((argument) => argument.startsWith('--user-data-dir='))) {
  app.setPath('userData', path.join(app.getPath('appData'), 'Keepfin'))
}
let manager: DownloadManager | undefined
let library: LibraryIndex | undefined
let playCache: PlayCache | undefined
let followEngine: FollowEngine | undefined
/** A system notification that opens Settings › Storage; set once the notification preferences are read. */
let notifyCopies: ((note: {title: string; body: string}) => void) | undefined
let activityStore: ActivityStore | undefined
let folderBoot: KeepfinBoot | undefined
let window: BrowserWindow
let startingOver = false
/** The layout is designed down to 560 px of app width (window ÷ UI scale; dev-docs/plans/DESKTOP-WIDTHS.md), so the window never gets narrower. */
const MIN_LAYOUT_WIDTH = 560
const MIN_WINDOW_HEIGHT = 640
function keepLayoutWidth(scale: number) {
  if (!window || window.isDestroyed()) return
  const frame = window.getSize()[0] - window.getContentSize()[0]
  const area = screen.getDisplayMatching(window.getBounds()).workAreaSize.width
  const width = Math.min(area, Math.ceil((MIN_LAYOUT_WIDTH * scale) / 100) + frame)
  window.setMinimumSize(width, MIN_WINDOW_HEIGHT)
  // A larger scale in a narrow window: widen it to the new minimum (Windows applies the minimum only on the next resize).
  if (!window.isMaximized() && !window.isFullScreen() && window.getSize()[0] < width) window.setSize(width, window.getSize()[1])
}
// Windows: in a window that is not maximized, Chromium's DirectComposition presenter stopped showing page updates over a playing
// video (the player's controls froze hidden or visible until something else repainted). The owner's A/B test (2026-10-24): without
// DirectComposition the bug is gone; without the video overlay or without vsync it stays. GPU compositing remains on; frames go
// to an ordinary swap chain instead.
if (process.platform === 'win32') app.commandLine.appendSwitch('disable-direct-composition')
// Must run before the app is ready: media elements need a streaming, CORS-enabled, secure scheme.
protocol.registerSchemesAsPrivileged([
  {
    scheme: MEDIA_SCHEME,
    privileges: {standard: true, secure: true, stream: true, supportFetchAPI: true, corsEnabled: true},
  },
])
if (!app.requestSingleInstanceLock()) app.quit()
else {
  app.on('second-instance', () => {
    window?.show()
    window?.focus()
  })
  app
    .whenReady()
    .then(async () => {
      const userData = app.getPath('userData')
      const tools = new ToolManager({
        dir: path.join(userData, 'tools'),
        resources: app.isPackaged
          ? path.join(process.resourcesPath, 'tools')
          : undefined,
        execPath: process.execPath,
        electron: true,
        nodeVersion: process.versions.node,
        ffmpegStatic,
        channel: () => manager?.getSettings().engine.channel ?? 'stable',
      })
      const engine = new StudioEngine(
        tools,
        new InfoCache(path.join(userData, 'cache', 'info')),
        {ytDlpCacheDir: path.join(userData, 'cache', 'yt-dlp'), choicesFile: path.join(userData, 'music-choices.json'), userAgent: `Keepfin/${app.getVersion()} (https://github.com/HishamSanjag/keepfin)`},
      )
      const ffmpegPath = () => tools.ffmpeg() ?? (ffmpegStatic ? unpackedPath(ffmpegStatic) : undefined)
      const thumbnails = new ThumbnailStore(
        path.join(userData, 'thumbnails-v1'),
        {
          frame: thumbnailFrame,
          resize: (image) => {
            if (!boundedThumbnail(image.bytes)) return
            const decoded = nativeImage.createFromBuffer(image.bytes)
            // nativeImage cannot read WebP (YouTube's playlist thumbnails are WebP); the window shows it as it is.
            if (decoded.isEmpty()) return image.mime === 'image/webp' ? image : undefined
            const size = decoded.getSize()
            if (
              !size.width ||
              !size.height ||
              size.width * size.height > 16_000_000
            )
              return
            const factor = Math.min(1, 480 / size.width, 480 / size.height)
            const scaled = decoded.resize({
              width: Math.max(1, Math.round(size.width * factor)),
              height: Math.max(1, Math.round(size.height * factor)),
              quality: 'good',
            })
            return {mime: 'image/jpeg', bytes: scaled.toJPEG(82)}
          },
        },
      )
      const push = (event: PushEvent) => {
        if (
          window &&
          !window.isDestroyed() &&
          !window.webContents.isDestroyed()
        )
          window.webContents.send('keepfin:push', event)
      }
      // Downloads other devices ask for ("Download on…") reach the queue through this port; it is attached once the queue exists.
      const jobPort = new QueueJobPort()
      // The Keepfin folder is on from 1.0.0; KEEPFIN_FOLDER=0 starts the app without it (the earlier, folder-less behaviour, kept for tests).
      if (process.env.KEEPFIN_FOLDER !== '0') {
        folderBoot = await bootKeepfinFolder({
          deviceDir: userData,
          userMusicDir: app.getPath('music'),
          ffmpeg: ffmpegPath,
          appVersion: app.getVersion(),
          pickFolder: async (defaultPath) => {
            const result = await dialog.showOpenDialog(window, {defaultPath, properties: ['openDirectory', 'createDirectory']})
            return result.canceled ? undefined : result.filePaths[0]
          },
          openPath: async (target) => {
            const failure = await shell.openPath(target)
            if (failure) throw new Error(failure)
          },
          shortcuts: shell,
          // cloud sign-in: the system browser, and tokens encrypted with the platform's secure storage
          cloud: {box: safeStorageBox(safeStorage), openExternal: (url) => shell.openExternal(url)},
          devices: {box: safeStorageBox(safeStorage), jobs: jobPort, firewall: windowsFirewall()},
          trash: (file) => shell.trashItem(file),
          notify: (note) => notifyCopies?.(note),
          saveReportDialog: async (text) => {
            const result = await dialog.showSaveDialog(window, {title: 'Save setup report', defaultPath: 'Keepfin setup report.txt', filters: [{name: 'Text', extensions: ['txt']}]})
            if (result.canceled || !result.filePath) return false
            await fs.writeFile(result.filePath, text, 'utf8')
            return true
          },
          push,
          // Writers are fenced while the migration runs: follow checks stop, active downloads pause.
          pause: async () => {
            followEngine?.stop()
            for (const job of manager?.snapshot().jobs ?? []) if (isActive(job.status)) await manager?.pause(job.id).catch(() => {})
          },
          resume: () => {
            if (folderBoot?.live) followEngine?.start()
          },
          relaunch: () => {
            app.relaunch()
            app.quit()
          },
          // Change location: downloads and the index let go of the folder; the window then shows the move and Keepfin restarts once, at the end
          closeLibrary: async () => {
            followEngine?.stop()
            await manager?.close()
            await library?.close()
            manager = undefined
            library = undefined
          },
        })
      }
      const index = new LibraryIndex({
        // Behind the first-start and setup screens the old library.json is only read (by the setup window), never written.
        file: folderBoot && !folderBoot.live ? path.join(userData, 'cache', 'library-gate.json') : path.join(userData, 'library.json'),
        persistence: folderBoot?.persistence,
        // Music on this PC: the person's Music folder to begin with; files in the Keepfin folder are never listed there.
        device: {file: path.join(userData, 'device-music.json'), folders: () => [app.getPath('music')], keepfinRoot: () => folderBoot?.root},
        artDir: path.join(userData, 'library-art'),
        ffmpeg: ffmpegPath,
        jobs: () => manager?.completedJobs() ?? [],
        songPending: (id) => manager?.isPending(id) ?? false,
        // A finished job's saved thumbnail becomes durable library artwork.
        jobArt: async (job) => {
          if (!job.thumbnail || !manager) return
          const url = await thumbnails.get({url: job.thumbnail}, job.id)
          const match = url && /^data:(image\/[a-z]+);base64,(.+)$/.exec(url)
          return match ? {bytes: Buffer.from(match[2], 'base64'), mime: match[1]} : undefined
        },
      })
      library = index
      playCache = new PlayCache(path.join(userData, 'play-cache'), ffmpegPath)
      await index.init()
      // Keepfin's own sign-ins: encrypted with the OS, decrypted into a private temp file only for the length of one engine run.
      const signInStore = new SignInStore({dir: path.join(userData, 'signin'), vault: {available: () => safeStorage.isEncryptionAvailable(), encrypt: (text) => safeStorage.encryptString(text), decrypt: (data) => safeStorage.decryptString(data)}})
      void signInStore.sweepTemp()
      const signInWindow = signInWindows({BrowserWindow, session, parent: () => window}, signInStore)
      const queue = new DownloadManager({
        signIns: signInStore.finder(findSignIns),
        store: path.join(userData, 'queue.json'),
        defaultOutDir: path.join(app.getPath('downloads'), 'Keepfin'),
        engine,
        library: index,
        libraryFolders: {
          music: path.join(app.getPath('music'), 'Keepfin'),
          videos: path.join(app.getPath('videos'), 'Keepfin'),
        },
        // A link another device sends is checked (every redirect hop, every address) before anything is opened for it, and the
        // person here is asked first when the download is large or the connection is metered.
        remote: folderBoot?.live
          ? {
              guard: async (url, signal) => void (await guardUrl(url, {}, signal)),
              consentBytes: REMOTE_CONSENT_BYTES,
              metered: () => windowsMetered(),
              originOf: (id) => folderBoot?.jobOrigin(id),
            }
          : undefined,
      })
      manager = queue
      jobPort.attach(queue)
      await queue.init()
      await queue.followMovedFiles(userData).catch((error) => console.error('Download history could not follow the moved files:', error))
      // Seeds from the bundled copy (or downloads on first run) in the background.
      void tools.ytDlp().catch(() => {})
      const retain = (ids: string[]) =>
        void thumbnails.retain(ids).catch(() => {})
      let retainedIds = queue
        .snapshot()
        .jobs.map((job) => job.id)
        .join('|')
      retain(queue.snapshot().jobs.map((job) => job.id))
      // Activity centre: recorded here from real events, independent of system notification settings.
      const activity = new ActivityStore({
        file: path.join(userData, 'activity.json'),
        onChange: (list) => push({type: 'activity', activity: list}),
      })
      activityStore = activity
      await activity.init()
      const recorder = new ActivityRecorder({
        store: activity,
        focused: () => !!window && !window.isDestroyed() && window.isFocused() && !window.isMinimized(),
      })
      recorder.seed(queue.snapshot())
      recorder.appStarted(app.getVersion())
      const delivered = new Set<Notification>()
      const notifications = new DesktopNotifications({
        store: path.join(userData, 'notifications.json'),
        initial: queue.snapshot(),
        supported: Notification.isSupported(),
        batchMs: 5000,
        show: (payload, fail) => {
          const alert = new Notification({
            id: payload.id,
            groupId: 'keepfin-downloads',
            title: payload.title,
            body: payload.body,
            silent: payload.silent,
            icon: path.join(here, '../web/icon.png'),
          })
          delivered.add(alert)
          alert.on('close', () => delivered.delete(alert))
          alert.on('failed', () => {
            delivered.delete(alert)
            fail(
              'The operating system could not display this alert. Check system notification settings and Do not disturb.',
            )
          })
          alert.on('click', () => {
            window?.show()
            window?.focus()
            push({
              type: 'reveal',
              tab:
                payload.tab ??
                (payload.job?.status === 'completed' ? 'Library' : 'Downloads'),
              group: payload.group?.id,
            })
            if (payload.job?.status === 'completed' && payload.job.file) {
              const file = payload.job.file
              void fs
                .access(file)
                .then(() => shell.showItemInFolder(file))
                .catch(() => {})
            }
          })
          alert.show()
        },
        progress: (value, indeterminate) => {
          if (window && !window.isDestroyed())
            window.setProgressBar(value, {
              mode:
                value < 0 ? 'none' : indeterminate ? 'indeterminate' : 'normal',
            })
        },
      })
      await notifications.init()
      index.subscribePush((event) => push(event))
      session.defaultSession.protocol.handle(MEDIA_SCHEME, (request) =>
        respondToMedia(request, {
          item: (id) => index.lookup(id)?.path,
          art: (id) => (index.lookup(id) ? index.artPath(id) : undefined),
          sub: (id, n) => index.lookup(id)?.subPaths?.[n],
          job: (id) => {
            const job = queue.snapshot().jobs.find((item) => item.id === id)
            return job?.status === 'completed' && job.file ? job.file : undefined
          },
          prepared: (id) => (index.lookup(id) ? playCache!.path(id) : undefined),
          video: (id) => index.videoFile(id),
          peer: (id) => folderBoot?.peerSource(id),
        }),
      )
      const follow = new FollowEngine({
        index,
        host: queue,
        onFound: (found) => push({type: 'follow', found}),
        onQueued: (queued) => recorder.syncFound(queued, queue.snapshot()),
        onHeld: (held) => recorder.syncHeld(held, queue.snapshot()),
        notify: ({title, body}) => {
          const preferences = notifications.snapshot()
          if (!Notification.isSupported() || !preferences.preferences.enabled) return
          const alert = new Notification({
            groupId: 'keepfin-collections',
            title,
            body,
            silent: !preferences.preferences.sound,
            icon: path.join(here, '../web/icon.png'),
          })
          delivered.add(alert)
          alert.on('close', () => delivered.delete(alert))
          alert.on('failed', () => delivered.delete(alert))
          alert.on('click', () => {
            window?.show()
            window?.focus()
            push({type: 'reveal', tab: 'Library'})
          })
          alert.show()
        },
      })
      followEngine = follow
      const artists = new ArtistFollowing(
        path.join(userData, 'following.json'),
        artistHost(queue, (name, key) => findArtistChannel(name, key), (channelId) => fetchArtistReleases(channelId), (url) => prefersAudio(url)),
        (state) => push({type: 'following', state}),
      )
      // followed artists are looked at a little after launch and then every 15 minutes; each artist is read at most twice a day
      setTimeout(() => void artists.check(undefined, false).catch(() => {}), 45_000).unref()
      setInterval(() => void artists.check(undefined, false).catch(() => {}), 15 * 60_000).unref()
      notifyCopies = ({title, body}) => {
        const preferences = notifications.snapshot()
        if (!Notification.isSupported() || !preferences.preferences.enabled) return
        const alert = new Notification({groupId: 'keepfin-copies', title, body, silent: !preferences.preferences.sound, icon: path.join(here, '../web/icon.png')})
        delivered.add(alert)
        alert.on('close', () => delivered.delete(alert))
        alert.on('failed', () => delivered.delete(alert))
        alert.on('click', () => {
          window?.show()
          window?.focus()
        })
        alert.show()
      }
      // how Undo is remembered, for the library and for download history
      const undoShelf = new UndoShelf()
      // Deleted files wait in the Keepfin trash for Undo and for a month; once, a little after the library opens, the old ones go
      if (folderBoot?.root) {
        const root = folderBoot.root
        setTimeout(() => void purgeKeepfinTrash(root, Date.now(), undoShelf.held()).catch((error) => console.error('Keepfin trash purge failed:', error)), 30_000).unref()
      }
      const libraryDeps: LibraryDeps = {
        index,
        follow,
        artists,
        manager: queue,
        cache: playCache,
        ffmpeg: ffmpegPath,
        trash: (file) => shell.trashItem(file),
        keepfinRoot: () => folderBoot?.root,
        undo: undoShelf,
        playFrom: (itemId, fileName) => folderBoot?.playFromItem(itemId, fileName) ?? Promise.resolve(undefined),
        pickFiles: async (kind) => {
          const result = await dialog.showOpenDialog(window, {
            title: kind === 'folder' ? 'Add a folder' : 'Add files',
            properties: kind === 'folder' ? ['openDirectory'] : ['openFile', 'multiSelections'],
            filters:
              kind === 'folder'
                ? undefined
                : [{name: 'Media', extensions: [...videoExtensions, ...audioExtensions, ...imageExtensions]}],
          })
          return result.canceled ? undefined : result.filePaths
        },
        savePlaylist: async (name) => {
          const result = await dialog.showSaveDialog(window, {
            title: 'Export playlist',
            defaultPath: name + '.m3u8',
            filters: [{name: 'Playlist', extensions: ['m3u8']}],
          })
          return result.canceled ? undefined : result.filePath
        },
        openPath: (file) => shell.openPath(file),
        showInFolder: (file) => shell.showItemInFolder(file),
        startDrag: (file, art) => {
          let icon = art ? nativeImage.createFromPath(art) : nativeImage.createEmpty()
          if (icon.isEmpty()) icon = nativeImage.createFromPath(path.join(here, '../web/icon.png'))
          window.webContents.startDrag({file, icon: icon.resize({width: 64, height: 64})})
        },
      }
      queue.subscribe((event) => {
        push(event)
        if (event.type === 'snapshot') {
          notifications.consume(event.snapshot)
          recorder.consume(event.snapshot)
          const ids = event.snapshot.jobs.map((job) => job.id)
          const signature = ids.join('|')
          if (signature !== retainedIds) {
            retainedIds = signature
            retain(ids)
          }
        } else if (event.type === 'progress')
          notifications.consumeProgress(event.progress)
      })
      const updates = new DesktopUpdates({
        updater: electronUpdater.autoUpdater,
        current: app.getVersion(),
        channel: queue.snapshot().settings.updateChannel,
        mode: updateMode({
          packaged: app.isPackaged,
          platform: process.platform,
          portableFile: process.env.PORTABLE_EXECUTABLE_FILE,
          setupUninstaller: () => existsSync(path.join(path.dirname(app.getPath('exe')), 'Uninstall Keepfin.exe')),
        }),
        hasDownloads: () =>
          queue.snapshot().jobs.some((job) => isActive(job.status)),
        openReleases: (url) => shell.openExternal(url),
        bundledNotes: () => {
          // Packaged next to the program by scripts/build/stage-release-notes.mjs; a development run reads the docs.
          const file = app.isPackaged
            ? path.join(process.resourcesPath, 'release-notes.md')
            : path.join(app.getAppPath(), 'docs', 'release-notes', `${app.getVersion()}.md`)
          try {
            return readFileSync(file, 'utf8')
          } catch {
            return undefined
          }
        },
        onReady: (version) => recorder.updateReady(version),
      })
      const ids = () => queue.snapshot().jobs.map((job) => job.id)
      const dropThumbnails = async (
        before: string[],
        work: Promise<Snapshot>,
        /** "Remove from history" sent with an undoId: how long the artwork waits, and how the entries come back. */
        undoId?: unknown,
      ) => {
        // the entries as they are now, so Undo can bring them back whole (only finished ones: a paused or waiting download is not brought back)
        const saved = undoId ? queue.storedJobs() : []
        const snapshot = await work
        const kept = new Set(snapshot.jobs.map((job) => job.id))
        const gone = before.filter((id) => !kept.has(id))
        const back = saved.filter((job) => !kept.has(job.id))
        if (undoId && back.length && back.every((job) => job.status === 'completed' || job.status === 'failed' || job.status === 'cancelled')) {
          // the artwork stays for a minute, the time the notice is on screen
          thumbnails.defer(gone, 60_000)
          undoShelf.record(undoId, async () => {
            thumbnails.keep(gone)
            const count = await queue.restoreJobs(back, before)
            return {done: count, failed: back.length - count}
          })
          return snapshot
        }
        await Promise.all(gone.map((id) => thumbnails.remove(id).catch(() => {})))
        return snapshot
      }
      ipcMain.handle('keepfin:request', async (event, request: Request) => {
        if (
          !window ||
          event.sender !== window.webContents ||
          event.senderFrame !== window.webContents.mainFrame
        )
          throw new Error('Untrusted caller.')
        if (
          !request ||
          typeof request !== 'object' ||
          typeof request.op !== 'string'
        )
          throw new Error('Invalid request.')
        if (request.op.startsWith('copies')) {
          if (folderBoot) return folderBoot.handleCopies(request.op, request.copies ?? {})
          if (request.op === 'copiesState') return {available: false, places: [], rows: [], review: [], reviewMore: 0, syncing: false}
          throw new Error('Unknown operation.')
        }
        if (request.op.startsWith('devices')) {
          if (folderBoot) return folderBoot.handleDevices(request.op, request.devices ?? {})
          return unavailableState('Devices need the Keepfin folder.')
        }
        if (request.op === 'whereState' || request.op === 'whereItem') {
          if (folderBoot) return folderBoot.handleWhere(request.op, request.id)
          return request.op === 'whereItem' ? {places: []} : {available: false, places: [], singles: []}
        }
        if (request.op.startsWith('combine')) {
          if (folderBoot) return folderBoot.handleCombine(request.op, request.combine ?? {})
          return emptyCombineState('Combining libraries needs the Keepfin folder.')
        }
        if (request.op.startsWith('storage') || request.op.startsWith('setup')) {
          // The renderer nests the arguments in `storage`; the desktop smoke sends them flat. Both reach the service.
          if (folderBoot) return folderBoot.handle(request.op, {...(request as unknown as StorageArgs), ...(request.storage ?? {})})
          if (request.op === 'storageState') return flagOffState
          throw new Error('Unknown operation.')
        }
        switch (request.op) {
          case 'thumbnail':
            return thumbnails.get(
              queue.thumbnailSource(request.id, request.url),
              request.id,
            )
          case 'activityList':
            return activity.list()
          case 'activityRead': {
            const read = request.read
            if (read !== 'all' && !(Array.isArray(read) && read.length <= 500 && read.every((id) => typeof id === 'string' && id.length <= 200)))
              throw new Error('Invalid request.')
            activity.markRead(read)
            return activity.snapshot()
          }
          case 'activityRemove':
            if (typeof request.id !== 'string' || !request.id || request.id.length > 200) throw new Error('Invalid request.')
            activity.remove(request.id)
            return activity.snapshot()
          case 'notificationState':
            return notifications.snapshot()
          case 'notificationConfigure':
            return notifications.configure(request.notifications)
          case 'notificationTest':
            return notifications.test()
          case 'notificationSettings':
            if (process.platform !== 'win32')
              throw new Error(
                'Open notification settings in your operating system.',
              )
            await shell.openExternal('ms-settings:notifications')
            return notifications.snapshot()
          case 'openReleaseLink': {
            const url = new URL(request.url!)
            if (
              url.protocol !== 'https:' ||
              url.username ||
              url.password ||
              url.href.length > 4096
            )
              throw new Error(
                'Only public HTTPS links are allowed in release notes.',
              )
            await shell.openExternal(url.href)
            return null
          }
          case 'openSupportLink': {
            const target = supportLink(request.id)
            if (!target) throw new Error('Unknown support link.')
            await shell.openExternal(target)
            return null
          }
          case 'windowScale': {
            const scale = Number(request.scale)
            if (!Number.isFinite(scale) || scale < 80 || scale > 140) throw new Error('Unknown scale.')
            keepLayoutWidth(scale)
            return null
          }
          case 'signInState':
          case 'signInOpen':
          case 'signInRemove':
            return handleSignIn(request.op, request, {
              store: signInStore,
              open: signInWindow.open,
              clear: signInWindow.clear,
            })
          case 'startOverResult':
            return takeStartOverResult()
          case 'startOver': {
            if (startingOver) throw new Error('Keepfin is already starting over.')
            const profiles = await loadProfiles(userData).catch(() => undefined)
            const profileFolder = profiles?.profiles.find((profile) => profile.id === profiles.current)?.folder
            // Refuses (nothing touched) when a folder deletion is asked for and the folder is not safely a Keepfin folder.
            const plan = await planStartOver({userData, deleteFolder: request.deleteFolder === true, profileFolder, home: app.getPath('home')})
            startingOver = true
            try {
              followEngine?.stop()
              for (const job of queue.snapshot().jobs) if (isActive(job.status)) await queue.pause(job.id).catch(() => {})
              await launchHelper({
                plan,
                pid: process.pid,
                execPath: process.execPath,
                relaunch: app.isPackaged ? (process.env.PORTABLE_EXECUTABLE_FILE ?? app.getPath('exe')) : undefined,
              })
            } catch (error) {
              startingOver = false
              if (folderBoot?.live) followEngine?.start()
              throw error
            }
            // Let the answer reach the window, then close; before-quit flushes and closes everything, the helper waits for the exit.
            setTimeout(() => app.quit(), 300)
            return null
          }
          case 'updateState':
          case 'updateCheck':
          case 'updateDownload':
          case 'updateCancel':
          case 'updateInstall':
          case 'updateReleases':
          case 'updateNotes':
            return updates.action(request.op)
          case 'state':
            return queue.snapshot()
          case 'inspect':
            return queue.inspect(request.url, request.inspectionId)
          case 'cancelInspection':
            return queue.cancelInspection(String(request.inspectionId))
          case 'enqueue':
            return queue.enqueue(request.url, request.format, request.options, request.space, request.videoId, request.joinPlaylist)
          case 'findMusicVideo':
            return queue.findMusicVideoOf(request.url)
          case 'cancel':
            return queue.cancel(request.id)
          case 'pause':
            return queue.pause(request.id)
          case 'resume':
            return queue.resume(request.id)
          case 'retry':
            return queue.retry(request.id)
          case 'saveAgain':
            return queue.saveAgain(request.id)
          case 'moveUp':
            return queue.moveUp(request.id)
          case 'remoteJobAllow':
            return queue.allowRemote(request.id)
          case 'remoteJobDecline':
            return queue.declineRemote(request.id)
          case 'startDiscovery':
            return queue.startDiscovery(request.url, request.inspectionId)
          case 'discoveryState':
            return queue.discoveryState(request.inspectionId)
          case 'discoveryMore':
            return queue.discoveryMore(request.inspectionId)
          case 'cancelDiscovery':
            return queue.cancelDiscovery(request.inspectionId)
          case 'enqueueDiscovered':
            return queue.enqueueDiscovered(
              request.inspectionId,
              request.urls,
              request.preset,
              request.options,
              request.grouped,
              request.space,
              request.keepInSync,
              request.findVideo,
              request.joinPlaylist,
            )
          case 'pauseGroup':
            return queue.pauseGroup(request.id)
          case 'resumeGroup':
            return queue.resumeGroup(request.id)
          case 'cancelGroup':
            return queue.cancelGroup(request.id)
          case 'retryGroup':
            return queue.retryGroup(request.id)
          case 'openGroupLink': {
            const url = new URL(queue.groupLink(request.id))
            if (url.protocol !== 'https:' && url.protocol !== 'http:')
              throw new Error('Only web links can be opened.')
            await shell.openExternal(url.href)
            return null
          }
          case 'setMusicChoice':
            return queue.setMusicChoice(request.url, request.musicChoice)
          case 'musicAlternates':
            return queue.musicAlternates(request.url, request.urls)
          case 'removeFailed':
            return dropThumbnails(ids(), queue.removeFailed(request.id), request.undoId)
          case 'removeGroup':
            return dropThumbnails(ids(), queue.removeGroup(request.id), request.undoId)
          case 'clear':
            return dropThumbnails(ids(), queue.clear(), request.undoId)
          case 'remove':
            return dropThumbnails(ids(), queue.remove(request.id), request.undoId)
          case 'deleteFile':
            return dropThumbnails(ids(), queue.deleteFile(request.id))
          case 'updateSettings': {
            const snapshot = await queue.updateSettings(request.settings)
            void updates.setChannel(snapshot.settings.updateChannel).catch(() => {})
            return snapshot
          }
          case 'importCookies': {
            const result = await dialog.showOpenDialog(window, {
              title: 'Import Netscape cookies.txt',
              properties: ['openFile'],
              filters: [{name: 'Cookie files', extensions: ['txt']}],
            })
            return result.canceled
              ? queue.snapshot()
              : queue.importCookies(result.filePaths[0])
          }
          case 'clearCookies':
            return queue.clearCookies()
          case 'chooseFolder': {
            if (request.space !== undefined) {
              // Space editor: only pick a folder; the space is saved through spaceSave.
              const current = typeof request.text === 'string' && path.isAbsolute(request.text) ? request.text : undefined
              const picked = await dialog.showOpenDialog(window, {
                defaultPath: current ?? queue.getSettings().outDir,
                properties: ['openDirectory', 'createDirectory'],
              })
              return picked.canceled ? null : {path: picked.filePaths[0]}
            }
            const result = await dialog.showOpenDialog(window, {
              defaultPath: queue.getSettings().outDir,
              properties: ['openDirectory', 'createDirectory'],
            })
            return result.canceled
              ? queue.snapshot()
              : queue.updateSettings({outDir: result.filePaths[0]})
          }
          case 'engineState':
            return queue.engineState()
          case 'engineUpdate':
            return queue.engineUpdate()
          case 'open':
          case 'reveal': {
            // A history entry saved before file names were read as UTF-8 may point at a damaged name; relink it first.
            if (request.id) await queue.repairJobFile(request.id)
            const job = queue.job(request.id)
            if (job.status !== 'completed' || !job.file)
              throw new Error('This file is not ready yet.')
            await fs.access(job.file).catch(() => {
              throw new Error('The file is no longer at its saved location.')
            })
            if (request.op === 'reveal') shell.showItemInFolder(job.file)
            else {
              const failure = await shell.openPath(job.file)
              if (failure) throw new Error(failure)
            }
            return null
          }
          default:
            if (libraryOperations.has(request.op)) return handleLibraryRequest(request, libraryDeps)
            throw new Error('Unknown operation.')
        }
      })
      const createWindow = () => {
        window = new BrowserWindow({
          width: 1240,
          height: 860,
          // The renderer reports its UI scale at start (windowScale), which adjusts the minimum width.
          minWidth: MIN_LAYOUT_WIDTH,
          minHeight: MIN_WINDOW_HEIGHT,
          backgroundColor: '#10151e',
          title: 'Keepfin',
          icon: path.join(here, '../web/icon.png'),
          autoHideMenuBar: true,
          webPreferences: {
            preload: path.join(here, 'preload.cjs'),
            contextIsolation: true,
            nodeIntegration: false,
            sandbox: true,
          },
        })
        window.webContents.setWindowOpenHandler(() => ({action: 'deny'}))
        window.webContents.on('will-navigate', (e) => e.preventDefault())
        void window.loadFile(path.join(here, '../web/index.html'))
      }
      createWindow()
      // Windows app icon (Settings -> Appearance): the saved variant on the window and Keepfin's own shortcuts.
      // Windows keeps the old icon in taskbar pins and Start, so choosing an icon is not offered there any more: the window and the
      // shortcuts always carry the Keepfin icon, and an icon picked in an earlier version is put back once.
      const applyIcon = (changed: boolean) => {
        if (process.platform !== 'win32') return
        try {
          const iconsDir = app.isPackaged ? path.join(process.resourcesPath, 'app-icons') : path.join(app.getAppPath(), 'resources', 'app-icons')
          const manifest = JSON.parse(readFileSync(path.join(iconsDir, 'index.json'), 'utf8'))
          const id = ''
          const result = applyAppIcon(
            {
              iconsDir,
              manifest,
              userData,
              exe: process.execPath,
              shortcuts: app.isPackaged && !process.env.PORTABLE_EXECUTABLE_FILE,
              candidates: shortcutCandidates({
                appData: app.getPath('appData'),
                desktop: app.getPath('desktop'),
                programData: process.env.ProgramData,
              }),
              shell: {
                readShortcutLink: (file) => shell.readShortcutLink(file) as unknown as ShortcutFields,
                writeShortcutLink: (file, operation, options) => shell.writeShortcutLink(file, operation, options as Electron.ShortcutDetails),
              },
              files: {exists: existsSync, copy: (from, to) => copyFileSync(from, to)},
              setWindowIcon: (file) => window?.setIcon(nativeImage.createFromPath(file)),
            },
            id,
            changed,
          )
          if (changed || result.shortcutsFailed) push({type: 'appIcon', shortcutsFailed: result.shortcutsFailed})
        } catch {}
      }
      const earlierIcon = queue.snapshot().settings.appIcon
      applyIcon(earlierIcon !== '')
      if (earlierIcon !== '') void queue.updateSettings({appIcon: ''}).catch(() => {})
      // Behind the first-start and setup screens nothing scans or writes the old library.
      if (!folderBoot || folderBoot.live) {
        // music video looks that never ran before Keepfin closed run once the library knows its songs
        void index.scan().then(() => queue.resumeVideoLooks(), () => queue.resumeVideoLooks())
        follow.start()
      }
      notifications.consume(queue.snapshot())
      app.on('activate', () => {
        if (!BrowserWindow.getAllWindows().length) createWindow()
      })
    })
    .catch((error) => {
      dialog.showErrorBox('Keepfin could not start', String(error))
      app.quit()
    })
  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit()
  })
  let exiting = false
  app.on('before-quit', (event) => {
    // after Change location the downloads and the index are already closed; the rest still flushes
    if (exiting || (!manager && !folderBoot && !activityStore)) return
    event.preventDefault()
    exiting = true
    followEngine?.stop()
    void Promise.all([manager?.close(), (library?.close() ?? Promise.resolve()).then(() => folderBoot?.close()), activityStore?.close()]).finally(() => {
      playCache?.close()
      app.quit()
    })
  })
}
