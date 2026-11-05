import {useCallback, useEffect, useState, type ReactNode} from 'react'
import {formatBytes} from '../../shared/contracts'
import type {StorageOverview} from '../../shared/storage'
import {messageOf, onPush} from '../bridge'
import {plural} from '../format'
import {setLibView} from '../library/libNav'
import {useLibrarySettings} from '../library/libStore'
import {MoveFolderDialog} from '../setup/MoveWindow'
import {BringInDialog, outsideSpaces} from './BringIn'
import {refreshTrash, useTrash} from '../library/trashStore'
import {navigate} from '../nav'
import {CloudTip} from '../setup/FolderStep'
import {checkFiles, getOverview, openFolder, setMusicShortcut} from '../setup/storageApi'
import {Badge, Button, InlineAlert, ProgressBar, Spinner, Switch} from '../ui/controls'
import {Group, SettingCard} from '../ui/SettingCard'
import {StartOver} from './StartOver'
import {CloudAccountsGroup, Places} from './storage/Copies'
import {useCopies} from './storage/useCopies'

function healthText(health: StorageOverview['health']) {
  if (health.checking) return 'Checking…'
  if (health.missing > 0) return `${plural(health.missing, 'file')} missing`
  return 'All files found'
}

/** What was deleted from the Keepfin folder and still waits for 30 days; opens the Trash page. */
function TrashCard() {
  const {entries} = useTrash()
  useEffect(() => {
    void refreshTrash()
  }, [])
  const size = entries.reduce((sum, entry) => sum + entry.size, 0)
  return (
    <SettingCard
      anchor="trash"
      icon="trash"
      title="Trash"
      description={entries.length ? `${plural(entries.length, 'file')} · ${formatBytes(size)}` : 'Empty'}
      control={
        <Button
          onClick={() => {
            setLibView({type: 'trash'})
            navigate('Library')
          }}
        >
          Open trash
        </Button>
      }
    />
  )
}

/**
 * Settings → Storage, in the order people look: is the library safe (Your library: the main storage and its copies), then the main
 * storage itself (where it is, what is in it, whether every file is there, the trash), cloud accounts, Windows, and Start over.
 */
export function StorageSettings() {
  const copies = useCopies()
  return (
    <>
      <Places copies={copies} />
      <MainStorage cloud={<CloudAccountsGroup copies={copies} />} />
      <StartOver anchor="start-over-storage" />
    </>
  )
}

/** The main storage: the Keepfin folder, how full its drive is, what is in it, whether every file is still there, and the trash. */
function MainStorage({cloud}: {cloud: ReactNode}) {
  const [overview, setOverview] = useState<StorageOverview | undefined>()
  const [error, setError] = useState('')
  const [busy, setBusy] = useState<'check' | 'open' | 'music' | ''>('')
  const [actionError, setActionError] = useState('')
  const [moving, setMoving] = useState(false)
  const [bringing, setBringing] = useState(false)
  const library = useLibrarySettings()

  const load = useCallback(() => {
    setError('')
    getOverview()
      .then(setOverview)
      .catch((caught) => setError(messageOf(caught)))
  }, [])
  useEffect(() => {
    load()
    // The folder state also changes behind our back (a check finishing, setup ending).
    return onPush((event) => {
      if (event.type === 'storage') load()
    })
  }, [load])

  async function act(kind: 'check' | 'open' | 'music', work: () => Promise<StorageOverview | null>) {
    setBusy(kind)
    setActionError('')
    try {
      const next = await work()
      if (next) setOverview(next)
    } catch (caught) {
      setActionError(messageOf(caught))
    } finally {
      setBusy('')
    }
  }

  if (error && !overview)
    return (
      <Group title="Main storage">
        <InlineAlert
          tone="error"
          title="Could not read the folder details"
          actions={
            <Button onClick={load} icon="refresh">
              Try again
            </Button>
          }
        >
          {error}
        </InlineAlert>
      </Group>
    )
  if (!overview)
    return (
      <Group title="Main storage">
        <p className="muted small" role="status">
          <Spinner /> Loading…
        </p>
      </Group>
    )

  const total = overview.totalBytes
  const free = overview.freeBytes
  const usedPercent = total && free !== undefined ? ((total - free) / total) * 100 : undefined
  const missing = overview.health.missing > 0
  const outside = outsideSpaces(library?.spaces ?? [], overview.path)
  return (
    <>
      <Group cards id="main-storage" title="Main storage: your Keepfin folder" description="Everything you keep lives here: your music, videos, photos, playlists and history. Copies and your other devices follow it.">
        <SettingCard
          anchor="folder"
          icon="folder"
          title={<span className="setup-path">{overview.path}</span>}
          description={
            <>
              {formatBytes(overview.bytes) || '0 B'} in Keepfin
              {overview.drive ? ` · on ${overview.drive}` : ''}
            </>
          }
          control={
            <>
              <Button icon="open" loading={busy === 'open'} disabled={!!busy} onClick={() => void act('open', async () => (await openFolder(), null))}>
                Open folder
              </Button>
              <Button icon="move" disabled={!!busy} onClick={() => setMoving(true)}>
                Change location…
              </Button>
            </>
          }
        >
          {usedPercent !== undefined && total !== undefined && free !== undefined && (
            <div className="storage-bar">
              <ProgressBar value={usedPercent} label="Drive space used" />
              <span className="small muted tabular">
                {formatBytes(free)} free of {formatBytes(total)}
              </span>
            </div>
          )}
          {overview.spaces.length > 0 && (
            <ul className="storage-spaces" aria-label="What is in it">
              {overview.spaces.map((space) => (
                <li key={space.id}>
                  <span>{space.name}</span>
                  <span className="small muted tabular">
                    {plural(space.files, 'file')} · {formatBytes(space.bytes) || '0 B'}
                  </span>
                </li>
              ))}
              {!!overview.musicVideoBytes && (
                <li>
                  <span>Music videos · {formatBytes(overview.musicVideoBytes)}</span>
                </li>
              )}
            </ul>
          )}
        </SettingCard>
        <SettingCard
          anchor="files"
          icon="check"
          title={
            // The status describes the files, so it sits with the title; the row's control holds only the action.
            <span className="storage-files-title">
              Files
              <Badge tone={overview.health.checking ? 'neutral' : missing ? 'warning' : 'success'}>{healthText(overview.health)}</Badge>
            </span>
          }
          description={
            overview.health.checking
              ? 'Looking for every file Keepfin knows about.'
              : missing
                ? 'Keepfin lists these files but cannot find them. They may have been moved or deleted outside Keepfin.'
                : overview.health.lastCheckedAt
                  ? `Last checked ${new Date(overview.health.lastCheckedAt).toLocaleString()}.`
                  : 'Every file Keepfin knows about is where it should be.'
          }
          control={
            <Button
              icon="refresh"
              loading={busy === 'check' || overview.health.checking}
              disabled={!!busy}
              onClick={() => void act('check', checkFiles)}
            >
              Check files
            </Button>
          }
        />
        {outside.length > 0 && (
          <InlineAlert
            tone="info"
            actions={
              <Button variant="primary" onClick={() => setBringing(true)}>
                Bring them in
              </Button>
            }
          >
            {outside.length === 1 ? `${outside[0].name} is outside your Keepfin folder.` : `${outside.length} spaces are outside your Keepfin folder.`}
          </InlineAlert>
        )}
        <TrashCard />
        {overview.insideCloudApp && <CloudTip app={overview.insideCloudApp} />}
        {actionError && <InlineAlert tone="error">{actionError}</InlineAlert>}
      </Group>
      {cloud}
      <Group cards title="Windows">
        <SettingCard
          anchor="windows-music"
          icon="music"
          title="Show in Windows Music"
          description="Adds a Keepfin shortcut to your Music folder. The songs stay in the Keepfin folder."
          control={<Switch label="Show in Windows Music" checked={overview.musicShortcut} disabled={!!busy} onChange={(enabled) => void act('music', () => setMusicShortcut(enabled))} />}
        />
      </Group>
      {moving && <MoveFolderDialog current={overview.path} onClose={() => setMoving(false)} />}
      {bringing && <BringInDialog spaces={outside} root={overview.path} onClose={() => setBringing(false)} />}
    </>
  )
}
