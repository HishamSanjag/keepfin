import {useEffect, useState} from 'react'
import '../../styles/devices.css'
import {useNav} from '../../nav'
import type {DiscoveredDevice, DevicesState} from '../../../shared/devices'
import {useAppearance} from '../../appearance'
import {messageOf} from '../../bridge'
import {plural} from '../../format'
import {Button, EmptyState, IconButton, InlineAlert, Spinner, Switch} from '../../ui/controls'
import {Group, SettingCard, SettingExpander} from '../../ui/SettingCard'
import {WhereFiles} from '../where/WhereFiles'
import {AddDevice} from './AddDevice'
import {YourDevices} from './DeviceCards'
import {DevicesGuide, type GuidePart} from './HowDevicesWork'
import {LibraryOffers} from './LibraryOffers'
import {AvailableDevices} from './Nearby'
import {QrCard} from './QrCard'
import {TypeCode} from './TypeCode'
import {allowFirewall, connectTo, openPairing, renameThisDevice, turnDevicesOff, turnDevicesOn} from './devicesApi'
import {useDeviceCopies} from './useDeviceCopies'
import {useDevices} from './useDevices'
import {useSearch} from './useSearch'

/** Where other devices can find this one, in a few words: "Home network", or "Tailscale" when that is the only way in. */
export function visibleOn(state: Pick<DevicesState, 'lan' | 'tailscale'>): string {
  return state.lan ? 'Home network' : state.tailscale.state === 'on' ? 'Tailscale' : ''
}

/** The one Tailscale line under the switch, only when it matters: "Also on Tailscale", or "Tailscale: not running · How?" while a paired device is away. */
export function tailscaleLine(state: Pick<DevicesState, 'lan' | 'tailscale' | 'paired'>): 'on' | 'off' | null {
  if (state.tailscale.state === 'on') return state.lan ? 'on' : null
  return state.paired.some((device) => !device.online) ? 'off' : null
}

/**
 * Settings → Devices, in this order: the header (this device, the On/Off switch, the "?" guide, a problem card only when something
 * blocks other devices), Available devices (a circle or a list) beside the QR code, Your devices (one card each), libraries on other
 * devices, and Advanced. Every section has the same width. Devices sync the library directly over the local network or Tailscale;
 * nothing goes through a server of ours, and nothing listens until the person turns it on. While the page is open Keepfin looks for
 * devices for a minute at a time. A pairing in progress is shown by PairingHost, at the app level.
 */
export function DevicesSettings() {
  const {state, error, set} = useDevices()
  const {state: copies} = useDeviceCopies()
  const {scale} = useAppearance()
  const [adding, setAdding] = useState(false)
  const [busy, setBusy] = useState('')
  const [actionError, setActionError] = useState('')
  const [name, setName] = useState<string | null>(null)
  const [open, setOpen] = useState<Set<string>>(() => new Set())
  const [guide, setGuide] = useState<GuidePart | null>(null)
  // a tap on a device in "Where your files are" asks for its card: it opens
  const nav = useNav()
  useEffect(() => {
    const id = nav.anchor?.startsWith('device-') ? nav.anchor.slice('device-'.length) : ''
    if (id) setOpen((current) => (current.has(id) ? current : new Set(current).add(id)))
  }, [nav.anchor, nav.focus])
  const enabled = !!state?.available && state.enabled
  const search = useSearch(enabled, state?.discovered ?? [], state?.paired ?? [], set)

  async function act(label: string, work: () => Promise<DevicesState>) {
    setBusy(label)
    setActionError('')
    try {
      set(await work())
    } catch (caught) {
      setActionError(messageOf(caught))
    } finally {
      setBusy('')
    }
  }

  /** Connect: this device opens its side of pairing too (so a device that is waiting for it can get in), then asks the other one. */
  function connect(device: DiscoveredDevice) {
    setAdding(false)
    void act(`pair-${device.hint}`, async () => {
      await openPairing().then(set)
      return connectTo(`${device.host}:${device.port}`)
    })
  }
  const toggleCard = (deviceId: string) =>
    setOpen((current) => {
      const next = new Set(current)
      if (!next.delete(deviceId)) next.add(deviceId)
      return next
    })

  if (error && !state) return <InlineAlert>{error}</InlineAlert>
  if (!state)
    return (
      <p className="muted">
        <Spinner /> Loading…
      </p>
    )
  if (!state.available) {
    return (
      <EmptyState icon="layers" title="Devices are not available">
        {state.unavailable ?? 'Devices need the Keepfin folder.'}
      </EmptyState>
    )
  }
  if (!state.enabled) {
    return (
      <section className="device-status" id="setting-status" data-state="off" aria-label="Devices is off">
        <div className="device-status-head">
          <span className="device-status-dot" aria-hidden="true" />
          <div className="device-status-text">
            <h3>Devices is off</h3>
            <p className="small muted">Turning it on lets this PC find your other computers and phones and keep your library in step, directly, without an account.</p>
          </div>
          <label className="devices-switch">
            <span className="small">Devices off</span>
            <Switch label="Devices on" checked={false} disabled={busy === 'on'} onChange={() => void act('on', turnDevicesOn)} />
          </label>
          <IconButton icon="info" label="How devices work" onClick={() => setGuide('how')} />
        </div>
        <p className="small muted">Windows may ask whether Keepfin can use the network. Allow it, so your phone and other computers can reach this PC on your home network.</p>
        {actionError && <InlineAlert>{actionError}</InlineAlert>}
        {guide && <DevicesGuide tailscale={state.tailscale} part={guide} onClose={() => setGuide(null)} />}
      </section>
    )
  }

  const visible = visibleOn(state)
  const tailscale = tailscaleLine(state)
  const blocked = state.firewall.blocked
  const healthy = !blocked && !state.portError && visible !== ''
  return (
    <>
      <section className="device-status" id="setting-status" data-state={healthy ? 'ok' : 'warn'} aria-label="This device">
        <div className="device-status-head">
          <span className="device-status-dot" aria-hidden="true" />
          <div className="device-status-text">
            <h3>Devices</h3>
            <p className="small muted">
              <strong>{state.thisDevice?.name ?? 'This PC'}</strong>{' '}
              {name === null && (
                <button type="button" className="link-btn" data-testid="devices-rename" onClick={() => setName(state.thisDevice?.name ?? '')}>
                  Rename
                </button>
              )}
              {' · '}
              {visible ? `Visible on ${visible}` : 'Not on a network yet. Connect to Wi-Fi or turn on Tailscale.'}
            </p>
          </div>
          <label className="devices-switch">
            <span className="small">Devices on</span>
            <Switch label="Devices on" checked={state.enabled} disabled={busy === 'off'} onChange={() => void act('off', turnDevicesOff)} />
          </label>
          <IconButton icon="info" label="How devices work" onClick={() => setGuide('how')} />
        </div>
        {name !== null && (
          <form
            className="devices-rename"
            onSubmit={(event) => {
              event.preventDefault()
              const next = name.trim()
              if (next && next !== state.thisDevice?.name) void act('name', () => renameThisDevice(next))
              setName(null)
            }}
          >
            <input
              className="input"
              autoFocus
              value={name}
              maxLength={40}
              onChange={(event) => setName(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Escape') setName(null)
              }}
              aria-label="Name of this PC"
              aria-describedby="devices-rename-hint"
            />
            <Button size="sm" variant="primary" type="submit" disabled={!name.trim() || name.trim() === state.thisDevice?.name}>
              Save
            </Button>
            <Button size="sm" onClick={() => setName(null)}>
              Cancel
            </Button>
            <p id="devices-rename-hint" className="small muted">
              Your other devices show this name.
            </p>
          </form>
        )}
        {tailscale === 'on' && <p className="small muted devices-tailscale-line">Also on Tailscale</p>}
        {tailscale === 'off' && (
          <p className="small muted devices-tailscale-line">
            Tailscale: not running ·{' '}
            <button type="button" className="link-btn" onClick={() => setGuide('tailscale')}>
              How?
            </button>
          </p>
        )}
        {blocked && (
          <InlineAlert
            tone="warning"
            title="Windows Firewall is blocking other devices"
            actions={
              <Button size="sm" variant="primary" loading={busy === 'allow'} onClick={() => void act('allow', allowFirewall)}>
                Allow Keepfin
              </Button>
            }
          >
            {`Windows is stopping other devices from reaching Keepfin on this ${state.firewall.category === 'unknown' ? '' : `${state.firewall.category.toLowerCase()} `}network. Allow Keepfin once and your phone and other computers can find this PC.`}
            {state.firewall.notice ? ` ${state.firewall.notice}` : ''}
          </InlineAlert>
        )}
        {state.portError && (
          <InlineAlert tone="warning" title="Devices could not start">
            {state.portError}
          </InlineAlert>
        )}
        {actionError && <InlineAlert>{actionError}</InlineAlert>}
        {state.notes.map((note) => (
          <InlineAlert key={note} tone="info">
            {note}
          </InlineAlert>
        ))}
      </section>
      {adding && !state.pairing && (
        <Group id="address" cards hideHeading title="Add by address">
          <AddDevice state={state} onState={set} onClose={() => setAdding(false)} />
        </Group>
      )}
      <div className="devices-top">
        <AvailableDevices
          state={state}
          devices={search.devices}
          searching={search.searching}
          ended={search.ended}
          onStop={search.stop}
          onSearchAgain={search.again}
          scale={scale}
          onConnect={connect}
          onAddByAddress={() => setAdding(true)}
          onAllowFirewall={() => void act('allow', allowFirewall)}
          onTailscaleGuide={() => setGuide('tailscale')}
          busy={busy}
          disabled={state.port === undefined}
        />
        <QrCard state={state} onState={set} />
      </div>
      {state.enabled && state.port !== undefined && (
        <Group id="type-code" cards hideHeading title="Type a code">
          <TypeCode onState={set} />
        </Group>
      )}
      <Group id="paired" cards title="Your devices" description={state.paired.length ? plural(state.paired.length, 'device') : undefined}>
        {state.paired.length === 0 ? (
          <EmptyState icon="layers" title="No other devices yet">
            Connect your phone or another computer from Available devices above. Both need Keepfin open.
          </EmptyState>
        ) : (
          <YourDevices state={state} onState={set} open={open} onToggle={toggleCard} copies={copies} />
        )}
      </Group>
      <WhereFiles />
      <LibraryOffers state={state} onState={set} />
      {guide && <DevicesGuide tailscale={state.tailscale} part={guide} onClose={() => setGuide(null)} />}
      <div className="settings-cards">
        <SettingExpander anchor="advanced" icon="settings" title="Advanced" description="This device’s fingerprint and port.">
          <SettingCard
            icon="lock"
            title="Fingerprint"
            description="The other device shows the same fingerprint when you pair."
            control={
              <p className="devices-fingerprint tabular" aria-label="Device fingerprint">
                {state.thisDevice?.fingerprint}
              </p>
            }
          />
          {state.port !== undefined && <SettingCard icon="link" title="Listening on port" control={<p className="tabular">{state.port}</p>} />}
          {state.libraries.length > 0 && (
            <SettingCard icon="library" title="Libraries">
              <ul className="devices-libraries" aria-label="Libraries">
                {state.libraries.map((lib) => (
                  <li key={lib.libraryId}>
                    <strong>{lib.name}</strong>
                    <span className="small muted">{lib.signed ? (lib.admin ? 'Signed. This device can add and remove devices.' : 'Signed. Another device adds and removes devices.') : 'Not signed yet. It is signed when you pair the first device.'}</span>
                  </li>
                ))}
              </ul>
            </SettingCard>
          )}
        </SettingExpander>
      </div>
    </>
  )
}
