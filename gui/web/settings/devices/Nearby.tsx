import {useMemo, useState} from 'react'
import type {DiscoveredDevice, DevicesState} from '../../../shared/devices'
import {Button, Segmented, Spinner} from '../../ui/controls'
import {Dialog} from '../../ui/Dialog'
import {Icon} from '../../ui/Icon'
import {Group} from '../../ui/SettingCard'
import {CIRCLE_MAX, circleSlots, connectLabel, effectiveView, readViewChoice, shortId, slotPosition, writeViewChoice, type ViewChoice} from './devicesLogic'

/** Where a device was found, as a few words. */
export const nearbyRoute = (device: DiscoveredDevice) => (device.route === 'tailscale' ? 'via Tailscale' : 'Home network')

const platformIcon = (platform?: string) => (platform === 'android' ? 'pip' : 'sidebar')
const deviceKey = (device: DiscoveredDevice) => `${device.hint}-${device.host}`

type Props = {
  state: DevicesState
  /** The devices to show: what the search found (kept after it stops). */
  devices: DiscoveredDevice[]
  searching: boolean
  /** Why the search is not running: the person pressed Stop, or the minute was up. */
  ended: 'stopped' | 'finished' | null
  onStop: () => void
  onSearchAgain: () => void
  /** Connect pressed on a found device: the asking flow starts. */
  onConnect: (device: DiscoveredDevice) => void
  /** "Add by address" (inside "Not seeing it?"). */
  onAddByAddress: () => void
  onAllowFirewall: () => void
  /** Opens the guide, at its Tailscale part. */
  onTailscaleGuide: () => void
  busy: string
  disabled?: boolean
  /** The interface size in percent (80-140). */
  scale: number
}

/**
 * Available devices: devices with Keepfin open that are not paired yet, found on this network or through Tailscale. A circle with
 * this PC in the middle when there are few and the text is not large, otherwise a searchable list; the person can switch, and the
 * choice is remembered. One status line directly under it says what Keepfin is doing (with Stop while it looks, Search again after)
 * and "Not seeing it?" opens the troubleshooting, which is also where Add by address lives.
 */
export function AvailableDevices({state, devices, searching, ended, onStop, onSearchAgain, onConnect, onAddByAddress, onAllowFirewall, onTailscaleGuide, busy, disabled, scale}: Props) {
  const [chosen, setChosen] = useState<ViewChoice | null>(readViewChoice)
  const [query, setQuery] = useState('')
  const [help, setHelp] = useState(false)
  const view = effectiveView(chosen, devices.length, scale)
  const choose = (next: ViewChoice) => {
    setChosen(next)
    writeViewChoice(next)
  }
  return (
    <Group id="nearby" cards title="Available devices" description="Keepfin is open there, not connected yet.">
      <div className="devices-available devices-panel">
        <div className="devices-available-bar">
          <Segmented
            label="Show available devices as"
            size="sm"
            value={view}
            onChange={choose}
            options={[
              {value: 'circle', label: 'Circle'},
              {value: 'list', label: 'List'},
            ]}
          />
        </div>
        {view === 'circle' ? (
          <Circle devices={devices} searching={searching} onConnect={onConnect} busy={busy} disabled={disabled} onShowList={() => choose('list')} />
        ) : (
          <List devices={devices} query={query} onQuery={setQuery} onConnect={onConnect} busy={busy} disabled={disabled} />
        )}
        <p className="small muted devices-available-status" role="status">
          <span className="devices-available-text">
          {searching ? (
            devices.length > 0 || state.scanning ? (
              <>
                {state.scanning && <Spinner />} {state.tailscale.state === 'on' ? 'Looking on this network and Tailscale…' : 'Looking on this network…'}
              </>
            ) : (
              <>Nothing found yet.</>
            )
          ) : ended === 'stopped' ? (
            <>Search stopped</>
          ) : (
            <>Search finished · {devices.length} found</>
          )}
          </span>
          {searching ? (
            <button type="button" className="link-btn devices-available-action" onClick={onStop}>
              Stop
            </button>
          ) : (
            <button type="button" className="link-btn devices-available-action" onClick={onSearchAgain}>
              Search again
            </button>
          )}
          <button type="button" className="link-btn" onClick={() => setHelp(true)}>
            Not seeing it?
          </button>
        </p>
      </div>
      {help && (
        <Troubleshoot
          state={state}
          disabled={disabled}
          onClose={() => setHelp(false)}
          onAllowFirewall={onAllowFirewall}
          onAddByAddress={() => {
            setHelp(false)
            onAddByAddress()
          }}
          onTailscaleGuide={() => {
            setHelp(false)
            onTailscaleGuide()
          }}
        />
      )}
    </Group>
  )
}

function Circle({devices, searching, onConnect, busy, disabled, onShowList}: {devices: DiscoveredDevice[]; searching: boolean; onConnect: Props['onConnect']; busy: string; disabled?: boolean; onShowList: () => void}) {
  const placed = useMemo(() => {
    const slots = circleSlots(devices.map(deviceKey))
    return devices
      .filter((device) => slots.has(deviceKey(device)))
      .map((device) => ({device, slot: slots.get(deviceKey(device))!}))
      .sort((a, b) => a.slot - b.slot)
  }, [devices])
  return (
    <>
      <div className="devices-circle" role="group" aria-label="Available devices">
        <span className="devices-ring devices-ring-outer" aria-hidden="true" />
        <span className="devices-ring devices-ring-inner" aria-hidden="true" />
        {/* the sweep turns only while a search runs: after Stop or when the minute is up the circle stands still */}
        {searching && <span className="devices-sweep" aria-hidden="true" />}
        <span className="devices-circle-me">
          <Icon name="sidebar" size={20} />
          <strong>This PC</strong>
        </span>
        {placed.map(({device, slot}) => {
          const {x, y} = slotPosition(slot)
          return (
            <button
              key={deviceKey(device)}
              type="button"
              className="devices-node"
              style={{left: `${x}%`, top: `${y}%`}}
              data-route={device.route}
              disabled={disabled || busy === `pair-${device.hint}`}
              aria-label={`${connectLabel(device.name)}, ${nearbyRoute(device)}`}
              onClick={() => onConnect(device)}
            >
              <span className="devices-node-icon" aria-hidden="true">
                {busy === `pair-${device.hint}` ? <Spinner /> : <Icon name={platformIcon(device.platform)} size={20} />}
              </span>
              <span className="devices-node-name">{device.name}</span>
              <span className="devices-node-route">{nearbyRoute(device)}</span>
              <span className="devices-node-go" aria-hidden="true">
                Connect
              </span>
            </button>
          )
        })}
      </div>
      {devices.length > CIRCLE_MAX && (
        <p className="small muted">
          Showing {CIRCLE_MAX} of {devices.length}.{' '}
          <button type="button" className="link-btn" onClick={onShowList}>
            Show all in a list
          </button>
        </p>
      )}
    </>
  )
}

function List({devices, query, onQuery, onConnect, busy, disabled}: {devices: DiscoveredDevice[]; query: string; onQuery: (value: string) => void; onConnect: Props['onConnect']; busy: string; disabled?: boolean}) {
  const needle = query.trim().toLowerCase()
  const shown = needle ? devices.filter((device) => `${device.name} ${device.host} ${nearbyRoute(device)}`.toLowerCase().includes(needle)) : devices
  return (
    <div className="devices-list">
      <input className="input" type="search" value={query} placeholder="Find a device" aria-label="Find a device" onChange={(event) => onQuery(event.target.value)} spellCheck={false} />
      {shown.length > 0 ? (
        <ul className="devices-rows devices-nearby" aria-label="Available devices">
          {shown.map((device) => (
            <li key={deviceKey(device)} className="devices-row devices-nearby-row">
              <span className="devices-row-icon" aria-hidden="true">
                <Icon name={platformIcon(device.platform)} size={18} />
              </span>
              <span className="devices-row-text">
                <strong>{device.name}</strong>
                <span className="small muted">{nearbyRoute(device)}</span>
              </span>
              <span className="small muted tabular devices-short-id" title="Short device id">
                {shortId(device.hint)}
              </span>
              <Button size="sm" variant="primary" disabled={disabled} loading={busy === `pair-${device.hint}`} onClick={() => onConnect(device)} aria-label={connectLabel(device.name)}>
                Connect
              </Button>
            </li>
          ))}
        </ul>
      ) : (
        <p className="small muted">{devices.length ? 'No device matches that.' : 'Nothing to list yet.'}</p>
      )}
    </div>
  )
}

/** What to check when a device does not show up. Nothing here guesses the cause. */
function Troubleshoot({state, disabled, onClose, onAllowFirewall, onAddByAddress, onTailscaleGuide}: {state: DevicesState; disabled?: boolean; onClose: () => void; onAllowFirewall: () => void; onAddByAddress: () => void; onTailscaleGuide: () => void}) {
  return (
    <Dialog
      title="Not seeing your device?"
      onClose={onClose}
      labelledBy="devices-help-title"
      footer={
        <Button variant="primary" onClick={onClose}>
          Close
        </Button>
      }
    >
      <ul className="devices-help-list">
        <li>Open Keepfin, then Devices, on the other device, and make sure Devices is on there too.</li>
        <li>Check that both are on the same Wi-Fi.</li>
        <li>
          {state.tailscale.state === 'on' ? (
            'Tailscale is on here. Make sure it is running on the other device too.'
          ) : (
            <>
              Away from home, run Tailscale on both devices.{' '}
              <button type="button" className="link-btn" onClick={onTailscaleGuide}>
                How?
              </button>
            </>
          )}
        </li>
        <li>
          {state.firewall.blocked ? (
            <>
              Windows Firewall is blocking other devices.{' '}
              <Button size="sm" variant="primary" onClick={onAllowFirewall}>
                Allow Keepfin through Windows Firewall on private networks
              </Button>
            </>
          ) : (
            'Windows Firewall is not blocking Keepfin on this network.'
          )}
        </li>
        <li>
          Still nothing? Connect by a name, a number or a code from the other device.{' '}
          <Button size="sm" icon="plus" disabled={disabled} onClick={onAddByAddress}>
            Add by address
          </Button>
        </li>
      </ul>
    </Dialog>
  )
}
