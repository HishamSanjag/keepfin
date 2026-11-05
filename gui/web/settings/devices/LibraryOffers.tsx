import {useState} from 'react'
import type {DevicesState, LibraryOffer} from '../../../shared/devices'
import {messageOf} from '../../bridge'
import {plural} from '../../format'
import {Button, InlineAlert} from '../../ui/controls'
import {Group} from '../../ui/SettingCard'
import {cancelJoin, joinLibrary, openLibrary, pickLibraryFolder} from './devicesApi'

type Props = {state: DevicesState; onState: (state: DevicesState) => void}

const stageWords: Record<string, string> = {
  folder: 'Preparing the folder…',
  asking: 'Asking the other device to let this one in…',
  pulling: 'Taking in the library…',
  done: 'Done.',
  failed: 'Stopped.',
}

/**
 * Libraries that paired devices share and this device does not hold yet ("Get this library"), the join in progress, and the libraries
 * this device holds (the app opens another one by restarting). Getting a library makes a Keepfin folder of its own on this device.
 * Songs and videos are not copied by it: they come with a copy, or play from the other device.
 */
export function LibraryOffers({state, onState}: Props) {
  const [error, setError] = useState('')
  const [busy, setBusy] = useState('')
  const join = state.join
  const running = !!join && join.stage !== 'done' && join.stage !== 'failed'
  const profiles = state.profiles ?? []
  const others = profiles.filter((p) => !p.current)

  async function get(offer: LibraryOffer, existing?: string) {
    setError('')
    try {
      const picked = existing ?? (await pickLibraryFolder())
      if (!picked) return
      setBusy(`${offer.deviceId}:${offer.libraryId}`)
      onState(await joinLibrary(offer.deviceId, offer.libraryId, picked))
    } catch (caught) {
      setError(messageOf(caught))
    } finally {
      setBusy('')
    }
  }

  if (!state.offers.length && !join && others.length === 0) return null
  const joined = join?.stage === 'done' ? profiles.find((p) => p.libraryId === join.libraryId) : undefined
  return (
    <>
      {(state.offers.length > 0 || join) && (
        <Group id="offers" cards title="Libraries on other devices" description="A paired device shares a library that this device does not have yet.">
          {error && <InlineAlert>{error}</InlineAlert>}
          {join && (
            <div className="devices-join" role="status" aria-label="Taking in a library">
              <p>
                <strong>{join.name}</strong> from {join.deviceName}
              </p>
              <p className="small muted">
                {stageWords[join.stage]}
                {join.stage === 'pulling' && join.segments > 0 ? ` ${plural(join.segments, 'file')} so far.` : ''}
              </p>
              {join.stage === 'failed' && <InlineAlert tone="error">{join.error}</InlineAlert>}
              <div className="devices-join-actions">
                {running && (
                  <Button size="sm" variant="ghost" onClick={() => void cancelJoin().then(onState).catch((caught) => setError(messageOf(caught)))}>
                    Stop
                  </Button>
                )}
                {join.stage === 'failed' && join.resumable && (
                  <Button size="sm" onClick={() => void get({deviceId: join.deviceId, deviceName: join.deviceName, libraryId: join.libraryId, name: join.name, online: true}, join.folder)}>
                    Continue
                  </Button>
                )}
                {join.stage === 'done' && joined && (
                  <Button size="sm" variant="primary" onClick={() => void openLibrary(joined.id).then(onState).catch((caught) => setError(messageOf(caught)))}>
                    Open it (restarts Keepfin)
                  </Button>
                )}
              </div>
              {join.stage === 'done' && <p className="small muted">The library is in its own folder at {join.folder}. Its songs and videos arrive with a copy, or play from the other device.</p>}
            </div>
          )}
          {state.offers.length > 0 && (
            <ul className="devices-offers" aria-label="Libraries to get">
              {state.offers.map((offer) => (
                <li key={`${offer.deviceId}:${offer.libraryId}`} className="devices-offer">
                  <div>
                    <strong>{offer.name}</strong>
                    <span className="small muted">
                      from {offer.deviceName}
                      {offer.online ? '' : ' (not connected right now)'}
                    </span>
                  </div>
                  <Button size="sm" variant="primary" disabled={!offer.online || running} loading={busy === `${offer.deviceId}:${offer.libraryId}`} onClick={() => void get(offer)}>
                    Get this library…
                  </Button>
                </li>
              ))}
            </ul>
          )}
        </Group>
      )}
      {others.length > 0 && (
        <Group cards title="Libraries on this device" description="Keepfin opens one at a time. Opening another restarts the app.">
          <ul className="devices-offers" aria-label="Libraries on this device">
            {profiles.map((p) => (
              <li key={p.id} className="devices-offer">
                <div>
                  <strong>{p.name}</strong>
                  <span className="small muted">{p.current ? 'Open now' : 'Not open'}</span>
                </div>
                {!p.current && (
                  <Button size="sm" onClick={() => void openLibrary(p.id).then(onState).catch((caught) => setError(messageOf(caught)))}>
                    Open (restarts Keepfin)
                  </Button>
                )}
              </li>
            ))}
          </ul>
        </Group>
      )}
    </>
  )
}
